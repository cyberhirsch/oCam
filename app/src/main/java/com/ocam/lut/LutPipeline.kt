package com.ocam.lut

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLExt
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

private const val TAG = "LutPipeline"

/** Tiles for grading a still. Every GLES 3 device allows at least 2048, most far more. */
private const val TILE = 2048

/**
 * Puts a film simulation between the camera and the screen.
 *
 * Without one the camera writes its frames straight into the view and this app never touches a
 * pixel. A lookup table has to be applied to every frame, so when one is chosen the camera
 * writes into a texture here instead, a shader grades it, and the result goes to the view. The
 * same shader grades the JPEG afterwards, so what was framed is what is written.
 *
 * Everything happens on one thread with one EGL context: the camera's frames arrive on it, the
 * preview is drawn on it, and the still is graded on it.
 */
class LutPipeline(initial: CubeLut) {

    private val thread = HandlerThread("lut-gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputSurface: Surface? = null
    private var outputTexture: SurfaceTexture? = null

    private var cameraTextureId = 0
    private var cameraTexture: SurfaceTexture? = null
    private var lutTextureId = 0
    private var lutSize = initial.size
    private var pendingLut: CubeLut? = initial

    private var previewProgram = 0
    private var stillProgram = 0
    private var quad: FloatBuffer? = null
    private var quadFlipped: FloatBuffer? = null
    private val textureMatrix = FloatArray(16)

    private var frameWidth = 0
    private var frameHeight = 0
    @Volatile private var released = false

    /**
     * Hand the camera a texture to draw into, and send the graded result to [output].
     *
     * @return the SurfaceTexture the camera should be opened against, or null if the GL side
     *   could not be set up - in which case the caller should carry on without a simulation
     *   rather than show nothing.
     */
    fun start(output: SurfaceTexture, width: Int, height: Int): SurfaceTexture? {
        frameWidth = width
        frameHeight = height
        // The view's texture takes the camera's buffer size, exactly as it does when the camera
        // writes to it directly, so everything downstream - the fit, the rotation - is unchanged.
        output.setDefaultBufferSize(width, height)
        outputTexture = output

        var result: SurfaceTexture? = null
        runOnGlThread {
            try {
                setUpEgl(Surface(output))
                setUpProgramsAndTextures()
                val texture = SurfaceTexture(cameraTextureId).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ handler.post(::drawFrame) }, handler)
                }
                cameraTexture = texture
                result = texture
            } catch (t: Throwable) {
                Log.e(TAG, "Cannot set up the film simulation", t)
                tearDown()
            }
        }
        return result
    }

    /**
     * The lens chose a different preview size than the one this was started with. The camera's
     * own texture is resized by the controller; this is the other half - what is drawn, and the
     * buffer it is drawn into.
     */
    fun resize(width: Int, height: Int) {
        if (released || (width == frameWidth && height == frameHeight)) return
        frameWidth = width
        frameHeight = height
        runOnGlThread {
            cameraTexture?.setDefaultBufferSize(width, height)
            outputTexture?.setDefaultBufferSize(width, height)
        }
    }

    /** Swap in another table without rebuilding anything: same pipeline, different numbers. */
    fun setLut(lut: CubeLut) {
        pendingLut = lut
        runOnGlThread { uploadPendingLut() }
    }

    /**
     * Grade a still the same way the preview was graded.
     *
     * Tiled, because a full size photograph is bigger than a texture is allowed to be, and
     * because holding one more copy of a fifty megapixel image than necessary is how this runs
     * out of memory. Returns null if anything goes wrong, and the caller writes the original -
     * an ungraded photograph is a far better outcome than none.
     */
    fun grade(source: Bitmap): Bitmap? {
        if (released) return null
        var output: Bitmap? = null
        runOnGlThread {
            try {
                output = gradeOnGlThread(source)
            } catch (t: Throwable) {
                Log.e(TAG, "Cannot grade the still", t)
                output = null
            }
        }
        return output
    }

    fun release() {
        if (released) return
        released = true
        runOnGlThread { tearDown() }
        thread.quitSafely()
    }

    // region GL thread

    private fun drawFrame() {
        val texture = cameraTexture ?: return
        if (released || windowSurface == EGL14.EGL_NO_SURFACE) return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(textureMatrix)
            uploadPendingLut()

            GLES30.glViewport(0, 0, frameWidth, frameHeight)
            GLES30.glUseProgram(previewProgram)
            bindQuad(previewProgram, quad)
            GLES30.glUniformMatrix4fv(
                GLES30.glGetUniformLocation(previewProgram, "texMatrix"), 1, false, textureMatrix, 0
            )
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(previewProgram, "camera"), 0)
            bindLut(previewProgram)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            EGL14.eglSwapBuffers(display, windowSurface)
        } catch (t: Throwable) {
            Log.e(TAG, "Dropped a frame", t)
        }
    }

    private fun gradeOnGlThread(source: Bitmap): Bitmap {
        val maxTexture = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
        val tile = minOf(TILE, maxTexture[0].takeIf { it > 0 } ?: TILE)

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val texture = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        val frameBuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, frameBuffer, 0)
        val target = IntArray(1)
        GLES30.glGenTextures(1, target, 0)

        try {
            var y = 0
            while (y < source.height) {
                val height = minOf(tile, source.height - y)
                var x = 0
                while (x < source.width) {
                    val width = minOf(tile, source.width - x)
                    val piece = Bitmap.createBitmap(source, x, y, width, height)
                    gradeTile(piece, texture[0], target[0], frameBuffer[0])?.let {
                        canvas.drawBitmap(it, x.toFloat(), y.toFloat(), null)
                        it.recycle()
                    }
                    piece.recycle()
                    x += tile
                }
                y += tile
            }
        } finally {
            GLES30.glDeleteFramebuffers(1, frameBuffer, 0)
            GLES30.glDeleteTextures(1, texture, 0)
            GLES30.glDeleteTextures(1, target, 0)
        }
        return output
    }

    private fun gradeTile(
        piece: Bitmap,
        sourceTexture: Int,
        targetTexture: Int,
        frameBuffer: Int,
    ): Bitmap? {
        val width = piece.width
        val height = piece.height

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, piece, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, targetTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frameBuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, targetTexture, 0
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) !=
            GLES30.GL_FRAMEBUFFER_COMPLETE
        ) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return null
        }

        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(stillProgram)
        // GL reads a buffer back from the bottom up, so the quad is drawn upside down and comes
        // out of glReadPixels the right way round.
        bindQuad(stillProgram, quadFlipped)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(stillProgram, "source"), 0)
        bindLut(stillProgram)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(
            0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        pixels.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(pixels)
        }
    }

    private fun bindQuad(program: Int, buffer: FloatBuffer?) {
        val vertices = buffer ?: return
        val position = GLES30.glGetAttribLocation(program, "position")
        val texCoord = GLES30.glGetAttribLocation(program, "texCoord")
        vertices.position(0)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 16, vertices)
        GLES30.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES30.glVertexAttribPointer(texCoord, 2, GLES30.GL_FLOAT, false, 16, vertices)
        GLES30.glEnableVertexAttribArray(texCoord)
    }

    private fun bindLut(program: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "lut"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "lutSize"), lutSize.toFloat())
    }

    private fun uploadPendingLut() {
        val lut = pendingLut ?: return
        pendingLut = null
        lutSize = lut.size
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        // Linear filtering is what makes this a lookup *table* rather than a posteriser: the
        // hardware interpolates between the eight surrounding entries for nothing.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        val buffer = ByteBuffer
            .allocateDirect(lut.values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(lut.values)
        buffer.position(0)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buffer
        )
    }

    private fun setUpEgl(surface: Surface) {
        outputSurface = surface
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "cannot start EGL" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_NONE,
        )
        check(
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) && found[0] > 0
        ) { "no GLES 3 config" }

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "no GLES 3 context" }

        windowSurface = EGL14.eglCreateWindowSurface(
            display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(windowSurface != EGL14.EGL_NO_SURFACE) { "no window surface" }
        check(EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
            "cannot make the context current"
        }
    }

    private fun setUpProgramsAndTextures() {
        previewProgram = buildProgram(VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        stillProgram = buildProgram(VERTEX_SHADER, STILL_FRAGMENT_SHADER)
        quad = floatBuffer(QUAD)
        quadFlipped = floatBuffer(QUAD_FLIPPED)

        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        cameraTextureId = textures[0]
        lutTextureId = textures[1]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
        uploadPendingLut()
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, compile(GLES30.GL_VERTEX_SHADER, vertex))
        GLES30.glAttachShader(program, compile(GLES30.GL_FRAGMENT_SHADER, fragment))
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "cannot link: ${GLES30.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "cannot compile: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer
        .allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }

    private fun tearDown() {
        runCatching { cameraTexture?.setOnFrameAvailableListener(null) }
        runCatching { cameraTexture?.release() }
        cameraTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (windowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, windowSurface)
            }
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        windowSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        runCatching { outputSurface?.release() }
        outputSurface = null
        outputTexture = null
    }

    /** Everything GL happens on the one thread that holds the context, and callers wait for it. */
    private fun runOnGlThread(block: () -> Unit) {
        if (thread.looper.thread === Thread.currentThread()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await()
    }

    // endregion

    private companion object {
        /** Position and texture coordinate per corner, as a triangle strip. */
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        /** The same quad with the texture upside down, for reading a buffer back. */
        val QUAD_FLIPPED = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

        const val VERTEX_SHADER = """#version 300 es
in vec4 position;
in vec4 texCoord;
uniform mat4 texMatrix;
out vec2 uv;
void main() {
    gl_Position = position;
    uv = (texMatrix * texCoord).xy;
}
"""

        /**
         * An entry of an N cube sits at the centre of its texel, at (k + 0.5) / N - so the input
         * has to be squeezed into that range before it is looked up. Skipping this is the
         * classic half-texel error: it washes out the ends of the grade.
         */
        const val GRADE = """
vec3 graded(vec3 source) {
    vec3 scaled = source * ((lutSize - 1.0) / lutSize) + (0.5 / lutSize);
    return texture(lut, scaled).rgb;
}
"""

        const val CAMERA_FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES camera;
uniform highp sampler3D lut;
uniform float lutSize;
in vec2 uv;
out vec4 colour;
$GRADE
void main() {
    colour = vec4(graded(texture(camera, uv).rgb), 1.0);
}
"""

        const val STILL_FRAGMENT_SHADER = """#version 300 es
precision highp float;
uniform sampler2D source;
uniform highp sampler3D lut;
uniform float lutSize;
in vec2 uv;
out vec4 colour;
$GRADE
void main() {
    colour = vec4(graded(texture(source, uv).rgb), 1.0);
}
"""
    }
}
