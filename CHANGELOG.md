# Changelog

Notable changes to Laufbursche Edition (SoFlow), newest first.

The version series lives in `version.properties`, which both the gradle build and the release workflow read. `versionName` is `<major>.<minor>.<n>` where `n` counts the commits since the series began, so the number rises by one on every release with no manual editing. `versionCode` counts straight through a series change and never goes backwards.

Release notes are built automatically for each release: if this file has a section whose heading matches the released version it is used verbatim, otherwise the commit subjects since the previous release are listed. Either way a fixed Disclaimer and a "phoning home" note are appended (see `.github/release-footer.md`).

To hand-write the notes for a release, add a section headed with its version number at the top of the version list below, for example:

    ## 1.0.1
    - Fixed the light-mode toast readability
    - Corrected a help text

If no matching section exists the notes fall back to the commit messages, so keeping this file up to date is optional.

## 1.0.0

First release of the SoFlow edition, built on the Laufbursche Edition WebView dashboard.

- Talks to SoFlow e-scooters over Bluetooth LE with no account and no cloud.
- Classifies the connected model from its Bluetooth name and GATT service, and speaks the D7, SO3 and SO6 protocol families over the Nordic UART, KingMeter and SO6 transports (AES where the model requires it).
- Live telemetry: speed, ride mode, pack voltage, current, power, energy, trip and total distance, battery percentage, fault state and the controller / display / CPU firmware versions the scooter reports.
- Scooter controls: top speed, ride mode (eco / normal / sport), lock / unlock, lights and dark mode, battery unlock and units, each offered only when the connected model's family supports it.
- Triple-tap the speed tile to unlock or re-lock the top speed over Bluetooth.
- Keeps the offline bicycle navigation, offline maps, POI overlays, SRT screen streaming, GPS/ride recording and in-app app-update features.
</content>
