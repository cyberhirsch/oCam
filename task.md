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
