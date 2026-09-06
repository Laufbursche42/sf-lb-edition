# Changelog

Notable changes to Laufbursche Edition (SoFlow), newest first.

The version series lives in `version.properties`, which both the gradle build and the release workflow read. `versionName` is `<major>.<minor>.<n>` where `n` counts the commits since the series began, so the number rises by one on every release with no manual editing. `versionCode` counts straight through a series change and never goes backwards.

Release notes are built automatically for each release: if this file has a section whose heading matches the released version it is used verbatim, otherwise the commit subjects since the previous release are listed. Either way a fixed Disclaimer and a "phoning home" note are appended (see `.github/release-footer.md`).

To hand-write the notes for a release, add a section headed with its version number at the top of the version list below, for example:

    ## 1.0.1
    - Fixed the light-mode toast readability
    - Corrected a help text

If no matching section exists the notes fall back to the commit messages, so keeping this file up to date is optional.

## 1.0.6

- Unlock and Lock (and the triple-tap on the speed tile) now only set the single speed value and no longer switch the ride mode. Comfort and Sport both reach the open speed, Eco stays firmware-limited, and you pick the ride mode yourself. This reverts the 1.0.5 behaviour that forced the sport gear: the scooter exposes a single speed value over Bluetooth, so forcing sport only cost range because Comfort reaches the same top speed with a gentler power delivery.

## 1.0.5

- Unlock, Lock and the triple-tap switched the scooter to the sport gear before setting the speed value, so the value always landed on sport. Superseded by 1.0.6, which no longer touches the ride mode.

## 1.0.4

- The GPS route of a ride is now recorded natively in a foreground location service, so tracking keeps running with the screen off. Finished tracks are imported into the routes list when the ride ends.

## 1.0.3

- A scooter that reports the generic name "SoFlow" now shows a neutral label instead of guessing a wrong model.
- The manual model override is kept across app restarts.

## 1.0.2

- Telemetry tiles are shown per model: current moved into the list instead of its own tile, power and energy only on SO3, trip removed where the model does not report it.
- The dashboard tiles use a uniform layout with the font capped at 32px and a capped fill scale, so nothing overflows and the screen stays filled.
- WebView text zoom is locked, so a large system font size no longer blows up the dashboard.

## 1.0.1

- Fixed telemetry on the Max: the BLE MTU is raised and multi-part frames are reassembled, so the battery percentage and the other values read correctly instead of showing 0% or a bogus fault.
- Only the real fault bits (0-5) are decoded, so a harmless status no longer appears as error 40000000.
- The speed is clamped at standstill, so the reading no longer jitters while the scooter is stopped.

## 1.0.0

First release of the SoFlow edition, built on the Laufbursche Edition WebView dashboard.

- Talks to SoFlow e-scooters over Bluetooth LE with no account and no cloud.
- Classifies the connected model from its Bluetooth name and GATT service, and speaks the D7, SO3 and SO6 protocol families over the Nordic UART, KingMeter and SO6 transports (AES where the model requires it).
- Live telemetry: speed, ride mode, pack voltage, current, power, energy, trip and total distance, battery percentage, fault state and the controller / display / CPU firmware versions the scooter reports.
- Scooter controls: top speed, ride mode (eco / normal / sport), lock / unlock, lights and dark mode, battery unlock and units, each offered only when the connected model's family supports it.
- Triple-tap the speed tile to unlock or re-lock the top speed over Bluetooth.
- Keeps the offline bicycle navigation, offline maps, POI overlays, SRT screen streaming, GPS/ride recording and in-app app-update features.
