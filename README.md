# Minimal Camera

A small Android camera app for people who want the controls the phone usually hides:
everything on auto, everything on manual, RAW capture, and every lens individually
selectable.

Built directly on Camera2 (CameraX does not expose this much of the sensor) with a
Jetpack Compose overlay.

## What it does

**Every lens, listed separately.** Phones expose their extra lenses as *physical* cameras
behind one *logical* camera, so a normal app only ever sees "back" and "front". This app
lists the top level cameras and the physical sub-cameras, and opens whichever one you pick.
Each is labelled with its zoom factor relative to the default lens on that side, its
35mm-equivalent focal length and its camera id, plus whether it can shoot RAW.

**Auto or manual, per control.**

| Control | Auto | Manual |
| --- | --- | --- |
| Exposure | AE with exposure compensation | ISO + shutter speed, over the sensor's full reported range |
| Focus | continuous AF, tap the preview to focus a spot, double tap to release it | focus distance from infinity to the lens minimum |
| White balance | AWB | colour temperature, 2000K to 10000K |

The `AUTO`/`MANUAL` button in the top right flips all three at once. Switching a control to
manual seeds it with the value the camera had just chosen, so nothing jumps.

ISO and shutter are one control on purpose: the hardware auto-exposure is all or nothing,
so turning one manual necessarily turns off the other. Exposure compensation only applies
while auto exposure is on, and the app says so rather than pretending otherwise.

**RAW.** The format button cycles JPEG → RAW → RAW+JPEG on lenses that support it. RAW is
written as DNG built from the exact capture result that produced the frame, so the black
level, colour matrices and noise profile in the file match the shot. Files land in
`Pictures/MinimalCamera` through MediaStore, so no storage permission is needed and they
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

## How it fits together

```
MainActivity        permission gate, orientation sensor, edge to edge shell
CameraViewModel     UI state, user actions, clamps settings to what a lens supports
camera/
  Lenses.kt         enumerates openable cameras, reads per lens capabilities
  CameraController  device + session + capture pipeline, on its own threads
  CaptureSettings   what the camera is being told to do, Kelvin to channel gains
io/PhotoStore       JPEG and DNG writing through MediaStore
ui/                 Compose preview, control panels, lens picker
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
