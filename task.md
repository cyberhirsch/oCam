# Tasks

## Recommended order

| # | Status | Step | Why first | Rough effort |
|---|--------|------|-----------|--------------|
| 1 | Done, not yet tested on a phone (`5b9a1b1`) | Shooting aids | Fast results, no new engine needed | 1 week |
| 2 | Done, not yet tested on a phone (`7ebf549`) | Engine + light painting, long exposure, star trails | No alignment needed on a tripod, and the most impressive result | 2–3 weeks |
| 3 | Done, not yet tested on a phone (`f5ee5bd`) | Bracketing → HDR fusion → Ultra HDR | Reuses the engine | 1–2 weeks |
| 4 | Done, not yet tested on a phone (`5b309bd`) | Focus stacking | Manual focus is already native | 1 week |
| 5 | Done, not yet tested on a phone (`5b309bd`) | Panorama | Biggest single feature; motion-sensor seeding cuts the work | 3–4 weeks |
| 6 | Open | Align and merge: night mode, super-resolution | The hardest maths; benefits from everything above | 4+ weeks |
| 7 | Open | Own RAW development | The end goal | Open-ended |

## Step 2: still open

- Test on a phone: frame rate at full merge size, HOLD, the turned picture on screen in all four
  rotations, the front camera, the build-up video's orientation, a run ended by leaving the app.
- Not built: glowing tail that fades, full size final frame, handheld alignment (step 6).
- Star trails have no gap filling. Frames run back to back, so a gap should be far under a pixel;
  check this on a real night sky.

## Step 3: still open

- Test on a phone: whether fused pictures look right, memory at 13 megapixels with five frames,
  whether the file still shows as Ultra HDR once its EXIF is written, how a gallery shows the gain,
  and how long the merge takes.
- Not built: RAW brackets, a full size merge, alignment of the frames (step 6), ghost removal, a
  gain map on a film simulation's own colours, Ultra HDR below Android 14.

## Step 4: still open

- Test on a phone: whether the lens reports arriving at each distance or every frame waits the full
  0.7 seconds, whether alignment follows this lens's breathing, memory at 13 megapixels (about 240 MB
  at the peak, by calculation), seconds per frame, and halos at the edges of near subjects.
- Not built: picking the near and far ends by tapping, keeping the frames to merge elsewhere, RAW or
  full size stacks.
- Alignment is tested on made up pictures with a known change of size. Real frames also differ in
  blur, which is what the fit has to see through.

## Step 5: still open

- Test on a phone, first of all: that a sweep in each direction lands the right way round, in portrait
  and in landscape. The signs of the sensor's seed are worked out, not measured, and if one is wrong
  every join falls back on a wrong guess or fails.
- Then: whether the focal length the lens reports is right (a wrong one shows as bent lines and joins
  that drift), seam quality with vignetting and with moving people, stitching time and memory for a
  full turn, and how well a gallery copes with a very wide JPEG.
- Not built: closing a full turn into a loop, vertical sweeps, panorama metadata for 360 viewers, a live
  guide on screen (only a slow down note), multi-band blending, per-frame brightness matching (an
  exposure is fixed for a sweep, but a camera's own tone curve may still vary).
- A film simulation on a very large panorama may not fit in memory, in which case it is saved ungraded.

## Outside the roadmap: timelapse with motion blur

Not built. A new mode, LAPSE: one picture every interval, written as a video at the end. Each picture
is a LONG exposure in its own right, so it carries the blur a real long shutter would give.

- Blur target: half of each interval (2 s interval, 1 s of light), the timelapse equivalent of a 180°
  shutter. Interval and blur length are settings; the interval is a good use for the volume keys.
- Chosen by the meter, per picture:
  - Dark: one exposure, up to the longest the lens reports (often only 0.5–1 s for an app like this).
  - Dim: several frames each close to 360° (exposure near the frame duration), averaged.
  - Bright: many short frames averaged over the blur length. Slow things (clouds, crowds, shadows) blur
    smoothly; fast ones (cars, birds) come out as a row of faint copies, because a phone cannot get a
    long exposure in sun without a real ND.
- Average as the frames arrive, as LONG does already; never record and keep a video to average later.
- Expose each frame so nothing clips: averaging cannot bring back a highlight clipped in every frame.
- Change between the three ways smoothly, or the video steps in brightness and blur at dusk.
- To check on a phone: battery and heat over an hour with the camera running all the time, whether
  exposure can reach the frame duration on this sensor, flicker under mains lights.

### Later: optical flow to fill the gaps in bright light

Between two short frames, find where each pixel moved (dense optical flow) and smear it along that
path, or warp in-between frames, before averaging. Fills the gaps so fast movers blur like a true
360° shutter.

- Fails at the edges of things passing in front of others (halos, tearing), on sky, glass, smoke and
  reflections, on anything moving more than about a tenth of the frame between samples, and on
  flickering lights.
- Cost: flow at about 480 px (DIS flow, a few ms a pair on a phone CPU), scaled up, blur applied at full
  size. Or fewer samples a second (4–8) with flow filling wider gaps, at more risk of artefacts.
- [ImageAligner.kt](app/src/main/java/com/ocam/sequence/ImageAligner.kt) finds one shift per frame
  only; per-pixel flow is new code, and on the GPU it needs OpenGL or Vulkan compute.
- Ship plain averaging first, then add this as a "smooth motion" option if the copies show on real
  footage.

### Related: LONG as a fake ND

- Set the shutter close to the frame duration by itself, so frames join up without gaps.
- Warn when frames clip.

## Outside the roadmap: review and QR codes

Written, unit tested, not yet tested on a phone.

- Test review on a phone: that the thumbnail updates after each kind of shot, swiping against pinching,
  double tap, a DNG with no JPEG, a very wide panorama, sharing, deleting RAW+JPEG, memory with neighbours
  loaded, and how Ultra HDR looks (it is shown as plain SDR here).
- Test QR on a phone: reading distance, how much the read-back slows the preview, with a film simulation
  on, in landscape where the sample may be stretched, and the front camera.
- Not built: 100% zoom on stills past 4096 pixels, a bin for deleted shots, shots from before a reinstall,
  joining a WiFi network, barcodes other than QR, marking the code on the preview, making a QR code for a
  photo.
