# oCam

A small Android camera app for people who want the controls the phone usually hides:
a fully manual camera, RAW capture, and every lens individually selectable.

Built directly on Camera2 (CameraX does not expose this much of the sensor) with a
Jetpack Compose overlay.

## The interface

Every control is on screen at once. There are no menus, no sheets and nothing to open:
each parameter is a thin slider, and the slider is the setting.

```
  ISO  ────────────●──────────────   400
  SEC  ──────●────────────────────  1/60
```

**Nothing is automatic.** There is no auto mode to leave or return to: exposure, focus and
white balance are set, and the camera changes none of them between frames.

Two things happen once, so that starting from nothing is not a hunt. When a lens opens, the
meter is read a single time and its reading lands in the ISO and shutter sliders, and the
lens looks once and keeps the distance it finds. After that only you move them - a tap on
the frame is another single look, not a mode.

## What it does

**Every lens, listed separately.** Phones expose their extra lenses as *physical* cameras
behind one *logical* camera, so a normal app only ever sees "back" and "front". This app
lists the top level cameras and the physical sub-cameras, and opens whichever one you pick.
Each is labelled with its zoom factor relative to the default lens on that side, its
35mm-equivalent focal length and its camera id, plus whether it can shoot RAW.

| Control | What it is |
| --- | --- |
| ISO / SEC | sensitivity and shutter over the sensor's full reported range |
| FOCUS | distance from infinity to the lens minimum; tap the frame to look once |
| WB | the camera's own lights - `TUN` `DAY` `SHD` - or `ADJ` to set it by hand |

There is no exposure compensation and no zebra: the first only biases a meter that is not
running, and the second measured the preview rather than the picture.

**White balance is the camera's, not this app's.** The named lights are handed to the
camera as its own fixed illuminants, because firmware knows what its sensor's channels do
under tungsten and an app converting a temperature into channel gains does not. A raw
sensor's green channel collects roughly twice what red and blue do, and by how much is a
property of that sensor: gains worked out from the illuminant alone come out near 1:1:1 and
leave every frame green. `ADJ` is the only one this app computes, and it computes it as a
shift away from the gains the camera itself reported for the light it was on.

**Straight lines.** A phone's wide lenses bend them, and the camera knows by how much: the
distortion correction block sits before the JPEG encoder and runs off the lens's own
coefficients. `UNDISTORT` in settings turns it on, which is the default, at the camera's
best quality for the shot and its fast setting for the preview. It never touches RAW - a DNG
is the sensor's own pixels, and the coefficients travel in the file for a converter to apply.
So a corrected JPEG and its DNG are the same photograph with different geometry, on purpose.

Focus and metering rectangles move with it: those are read in the corrected coordinate space
while correction is on and the pre-correction one while it is off, so a tap lands where it
was aimed either way.

**RAW.** The format button cycles JPEG → RAW → RAW+JPEG on lenses that support it. RAW is
written as DNG built from the exact capture result that produced the frame, so the black
level, colour matrices, noise profile and lens shading in the file match the shot. Files
land in `Pictures/oCam` through MediaStore, so no storage permission is needed and they
show up in the gallery immediately.

**A live readout** shows what the camera actually did - the ISO, shutter, aperture and
focus distance from the capture results, not just what was requested. Upright it sits above
the frame; on its side it moves onto the image, because there the frame is limited by height
and a strip above it would cost picture.

### Sensors that are not cameras

Probing every camera id also turns up things that are not photo cameras: depth and
infrared helpers, and sensors a manufacturer reserves for its own apps. On some
firmware, opening one of those does not fail cleanly - it takes the camera service
down until the phone is rebooted.

Two defences, because neither is enough alone:

- **Before opening.** How hard the test is depends on how the camera was found. One
  the system advertises is one the system means for apps, and is taken at its word
  unless it says outright that it is something else: `SYSTEM_CAMERA`, an infrared
  colour filter, no still output at all. One found only by probing ids has made no
  such claim, and that is where the depth and assist sensors live, so it also has to
  look like a photo camera before it is offered - a real focal length, no depth or
  motion-tracking role, and a largest image of at least 1.5 megapixels.
- **After opening.** The app writes down which lens it is about to open, and clears
  the note once the lens is running. A note still there at the next start means
  that lens is what went down. It is marked, never opened automatically, and takes
  a deliberate second tap from then on.

That second part is what makes this work on a phone nobody has tested: the device
teaches the app which of its cameras are real, one attempt at a time.

**And the last word is yours.** No rule read off metadata is right on every phone -
the one that hides a depth sensor here hides a macro lens there. Settings lists every
camera the phone answers for, with what is known about each: switch off the ones that
are not cameras and they leave the picker for good, and give the rest names that mean
something. A name replaces the zoom label on the button and in the readout.

### When a lens misbehaves

Long press the readout at the top of the screen. The app collects what the device
reports about every camera it can find - including the ids it probed and rejected,
each one's hardware level and capabilities, and which stream combination the open
lens actually granted - and offers it as text to copy. Paste that into a bug report;
it is the difference between a lens that is missing and a lens that refused.

## Requirements

- Android 10 (API 29) or newer
- Android Studio, or a JDK 17 and the Android SDK with platform 35

## Build

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Installing on a phone

CI builds the APK on every push and attaches it to a rolling `debug-latest`
prerelease, so the newest build is always at:

https://github.com/cyberhirsch/oCam/releases/download/debug-latest/app-debug.apk

Open that on the phone and it downloads the APK directly - the repository is
public, so no login is involved. Allow "install unknown apps" for the browser
once, then tap the download to install.

To serve it from your own machine instead - useful with no internet, or to avoid
the round trip through GitHub - run this on any machine the phone can reach:

```
./tools/serve-apk.sh
```

It uses a local build if there is one, otherwise downloads the release with `gh`,
prints a QR code pointing at that machine's LAN address, and serves the file
until you stop it.

## How it fits together

```
MainActivity        permission gate, orientation sensor, edge to edge shell
CameraViewModel     UI state, user actions, clamps settings to what a lens supports
camera/
  Lenses.kt         enumerates openable cameras, reads per lens capabilities
  CameraController  device + session + capture pipeline, on its own threads
  CaptureSettings   what the camera is being told to do, and the white balance shift
io/PhotoStore       JPEG and DNG writing through MediaStore
ui/                 Compose preview, the slider rows, lens picker
```

`CameraController` keeps all camera calls on one background thread and does image encoding
on a second, so the UI thread only ever sees state updates. Still capture goes straight to
the shot: with the exposure and the focus distance already set there is nothing to converge.
Only a lens that cannot be told one of them runs the lock-focus → precapture sequence first,
with a timeout that takes the picture anyway.

Preview, JPEG at full size and RAW at full size are configured as one session, which is a
stream combination the platform guarantees for RAW-capable devices as long as the preview
stays within 1080p - that bound is why the preview size is chosen the way it is.

## Deliberate limits

- Portrait phones. The UI is locked to portrait and the preview assumes a portrait-natural
  device; photo rotation still follows how you hold the phone, via the orientation sensor.
- No flash, no zoom slider, no video, no gallery. Lens selection replaces digital zoom.
- Physical sub-cameras that are not backward compatible (depth and IR helpers) are skipped -
  they cannot produce a preview.
- Some devices refuse to open a physical camera directly. The app retries briefly and then
  reports it rather than silently falling back to a different lens.
