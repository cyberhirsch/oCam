package com.ocam.sequence

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ocam.io.PhotoStore
import kotlin.math.max

/** The long side of the build-up video, before anything this phone's encoder may refuse. */
private const val LONG_SIDE = 2160
private const val MAX_FACTOR = 8
private const val FRAME_RATE = 30
private const val INPUT_WAIT_US = 5_000L
private const val END_WAIT_US = 10_000L
private const val END_WAIT_TRIES = 300

/**
 * The picture being built, as an H.264 video, one frame of video for each step of the build-up.
 *
 * It runs at the merge's pace and never holds it up: if the encoder has no room for a frame the
 * frame is left out, which a build-up survives and a light painting would not.
 */
class VideoWriter(
    context: Context,
    baseName: String,
    sourceWidth: Int,
    sourceHeight: Int,
    private val orientation: Int,
) {
    private val factor: Int
    private val width: Int
    private val height: Int
    private val codec: MediaCodec
    private val file = PhotoStore.newVideo(context, "$baseName.mp4")
    private val muxer: MediaMuxer
    private val pixels: ByteArray
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var written = 0

    init {
        try {
            val found = chooseEncoder(sourceWidth, sourceHeight)
                ?: throw IllegalStateException("No encoder takes ${sourceWidth}x$sourceHeight")
            factor = found.factor
            width = found.width
            height = found.height
            pixels = ByteArray(width * height * 3 / 2)
            codec = MediaCodec.createByCodecName(found.name)
            codec.configure(found.format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(file.descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
        } catch (t: Throwable) {
            file.discard()
            throw t
        }
    }

    fun add(merger: FrameMerger) {
        val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
        if (index >= 0) {
            merger.writeScaledNv21(factor, width, height, pixels)
            val image = codec.getInputImage(index)
                ?: throw IllegalStateException("The encoder gave no image to write into")
            val planes = image.planes
            // Whatever layout this encoder wants: its planes say, and every sample goes by them.
            val luma = planes[0]
            for (row in 0 until height) {
                for (column in 0 until width) {
                    luma.buffer.put(
                        row * luma.rowStride + column * luma.pixelStride,
                        pixels[row * width + column],
                    )
                }
            }
            val start = width * height
            val u = planes[1]
            val v = planes[2]
            for (row in 0 until height / 2) {
                for (column in 0 until width / 2) {
                    val from = start + row * width + column * 2
                    v.buffer.put(row * v.rowStride + column * v.pixelStride, pixels[from])
                    u.buffer.put(row * u.rowStride + column * u.pixelStride, pixels[from + 1])
                }
            }
            codec.queueInputBuffer(index, 0, pixels.size, written * 1_000_000L / FRAME_RATE, 0)
            written++
        }
        drain(ending = false)
    }

    /** @return the name the video was saved under, or null if there was nothing in it. */
    fun finish(): String? {
        return try {
            if (written == 0) {
                file.discard()
                return null
            }
            val index = codec.dequeueInputBuffer(1_000_000L)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index, 0, 0, written * 1_000_000L / FRAME_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            drain(ending = true)
            muxer.stop()
            file.commit()
            file.name
        } catch (t: Throwable) {
            file.discard()
            throw t
        } finally {
            release()
        }
    }

    /** Throw away what has been written, after a failure. */
    fun abandon() {
        file.discard()
        release()
    }

    private fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }

    private fun drain(ending: Boolean) {
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (ending) END_WAIT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!ending || ++waits > END_WAIT_TRIES) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.setOrientationHint(orientation)
                    muxer.start()
                }
                index >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) muxer.writeSampleData(track, output, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private class Encoder(
        val name: String,
        val format: MediaFormat,
        val factor: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * The largest size some encoder here takes, a whole division of the frame so the picture is
     * only ever averaged down. Sizes are kept to multiples of sixteen, the one size every
     * encoder's buffers line up with; what that leaves over is cropped from the edge.
     */
    private fun chooseEncoder(sourceWidth: Int, sourceHeight: Int): Encoder? {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val longest = max(sourceWidth, sourceHeight)
        val smallest = max(1, (longest + LONG_SIDE - 1) / LONG_SIDE)
        for (factor in smallest..MAX_FACTOR) {
            val width = sourceWidth / factor and 15.inv()
            val height = sourceHeight / factor and 15.inv()
            if (width <= 0 || height <= 0) return null
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                .apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 8).coerceAtMost(40_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
            val name = codecs.findEncoderForFormat(format)
            if (name != null) return Encoder(name, format, factor, width, height)
        }
        return null
    }
}
