# Highlander Dash

A premium, minimal home screen (launcher) for Android car head units, built for a 2nd gen Toyota Highlander but usable in any car. Big speed readout, date and weather, heading and elevation, a Now playing card, Bluetooth tire pressure sensors, and a radio screen for FYT head units.

This is a shared test build. It is offered as-is, with no warranty. It does not change anything on your head unit: it is an app you can uninstall any time.

## Download

Get the newest APKs from the [Releases page](../../releases/latest):

- `HighlanderDash.apk`: the dashboard (install this on the head unit)
- `TireMonitor.apk`: optional phone app that reads the same tire sensors

On the head unit, open the release page in Chrome, tap the APK, and allow "Install unknown apps" for Chrome when asked.

## What is in this build

- Speed card with the HIGHLANDER wordmark (you can change the name), page dots and arrows to flip between speed, heading, elevation and tires
- Date and weather at the top (weather needs internet and a GPS fix), heading and elevation
- Now playing card for Spotify and any app that shows a media notification (allow "Song info access" in Settings)
- Settings: twelve accent colors, light or dark look, card fades, which cards show, which side the speed card is on, top bar items
- Tires: assign Bluetooth valve-cap sensors to wheels, low pressure warning, a timing table and a Compare sensors screen with a per-minute log
- Radio (FYT / Syu head units only): the launcher's own radio screen with saved stations, seek, step, FM and AM. On other units the Radio button opens the unit's own radio app
- Radio diagnostic: shows what the factory radio exposes, a command tester, and a button that copies the factory radio app files for study
- Apps drawer, Phone and Maps buttons (Maps opens Google Maps)

Not included (see the guide for how to add them): offline maps, place search, turn-by-turn navigation, 3D map, live traffic, saved places, and a fantasy football card.

## Build it yourself

Every push builds the APKs with GitHub Actions (`.github/workflows/build.yml`) and publishes them as a release.

1. Fork this repo (or use "Use this template")
2. Optional signing key, so your updates install over each other: create a keystore (`keytool -genkeypair -keystore release.jks -alias release -keyalg RSA -keysize 2048 -validity 10000`), then add repository secrets `SIGNING_KEYSTORE_B64` (the file in base64), `SIGNING_PASSWORD` and `SIGNING_ALIAS`. Without them, each build uses a throwaway key
3. Optional offline map for your area: add repository variables `MAP_REGION` (a Geofabrik path such as `north-america/us/texas`) and `MAP_BBOX` (`west,south,east,north`). Add `BUILDINGS_BBOX` for 3D buildings in a small area. The tools in `tools/` build the map data during the Action
4. Push, wait for the Action to finish, and download the APKs from your Releases page

## Layout of the code

- `app/src/main/assets/index.html`: the whole dashboard (HTML, CSS and JavaScript in one page)
- `app/src/main/java/.../MainActivity.java`: the Android side (GPS, media sessions, Bluetooth scan, the `Car` bridge the page calls)
- `FytRadio.java`, `RadioProbe.java`: FYT radio control and the radio diagnostic
- `TireDecoder.java`, `TireStats.java`: tire sensor decoding and the comparison statistics
- `tires/`: the Tire Monitor phone app
- `tools/`: scripts that build offline map data from OpenStreetMap

Map data © OpenStreetMap contributors (ODbL). Not affiliated with Toyota or any head unit maker.
