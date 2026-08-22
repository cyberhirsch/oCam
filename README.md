# oCam

A small Android camera app for people who want the controls the phone usually hides:
everything on auto, everything on manual, RAW capture, and every lens individually
selectable.

Built directly on Camera2 (CameraX does not expose this much of the sensor) with a
Jetpack Compose overlay.

## The interface

Every control is on screen at once. There are no menus, no sheets and nothing to open:
each parameter is a thin slider with its button directly underneath.

```
  ────────────●──────────────
  [ AUTO ]  ISO           400
  ──────●────────────────────
  [ AUTO ]  SEC          1/60
```

**Two ways to set anything, and only two.** Press the button and the camera decides, or
move the slider and you decide. Touching a slider is itself the decision to go manual -
there is no separate switch to find first. `ALL AUTO` at the top hands everything back at
once.

While a control is on auto its slider keeps tracking what the camera is choosing, drawn
with a hollow thumb. So you can see the exposure the camera picked, and taking over never
makes the image jump.

## What it does

**Every lens, listed separately.** Phones expose their extra lenses as *physical* cameras
behind one *logical* camera, so a normal app only ever sees "back" and "front". This app
lists the top level cameras and the physical sub-cameras, and opens whichever one you pick.
Each is labelled with its zoom factor relative to the default lens on that side, its
35mm-equivalent focal length and its camera id, plus whether it can shoot RAW.

| Control | Auto | Manual |
| --- | --- | --- |
| ISO / SEC | AE | sensitivity and shutter over the sensor's full reported range |
| FOCUS | continuous AF, tap the preview for a spot, double tap to release it | distance from infinity to the lens minimum |
| WB | AWB | colour temperature, 2000K to 10000K |
| EV | - | bias while the camera meters; its button resets to zero |

ISO and SEC share one button on purpose: the hardware auto-exposure is all or nothing, so
taking over either one necessarily turns off the other. EV only exists while AE is on, and
the app greys it out rather than pretending otherwise.

**RAW.** The format button cycles JPEG → RAW → RAW+JPEG on lenses that support it. RAW is
written as DNG built from the exact capture result that produced the frame, so the black
level, colour matrices, noise profile and lens shading in the file match the shot. Files
land in `Pictures/oCam` through MediaStore, so no storage permission is needed and they
show up in the gallery immediately.

**A live readout** across the top shows what the camera actually did - the ISO, shutter,
aperture and focus distance from the capture results, not just what was requested.

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
  CaptureSettings   what the camera is being told to do, Kelvin to channel gains
io/PhotoStore       JPEG and DNG writing through MediaStore
ui/                 Compose preview, the slider rows, lens picker
```

`CameraController` keeps all camera calls on one background thread and does image encoding
on a second, so the UI thread only ever sees state updates. Still capture runs the standard
lock-focus → precapture → capture sequence when the relevant control is on auto, and skips
straight to the shot when it is manual; a timeout takes the picture anyway if AF or AE never
converge.

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
