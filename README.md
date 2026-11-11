# Dashphonic

Bluetooth music panel and clock for FYT Android head units.

See what's playing on your phone, change tracks and adjust volume in a narrow side panel, with room for navigation beside it. Dashphonic reads media data directly from FYT firmware, including music that ordinary Android widgets cannot see.

**[Download APK](https://github.com/vsevalid/dashphonic/releases/latest)** · [Compatibility](#compatibility) · [Installation](#installation) · [Русский](README.ru.md)

![Dashphonic in four modes: album artwork, light theme without artwork, synced lyrics and FM radio](docs/media/dashphonic-screens.png)

<details>
<summary>Watch the demo: track changes, synced lyrics and the trip screen</summary>

<p align="center"><img src="docs/media/dashphonic-demo.gif" width="368" height="710" alt="Dashphonic changing tracks, showing synced lyrics and opening the trip voltage chart"></p>

</details>

## Features

- Track title, artist and progress from your phone, with playback buttons and touch gestures.
- Album artwork and synced lyrics, each with its own off switch. Zen mode hides both and stops their network requests.
- A Bluetooth device list to connect paired phones, find nearby devices and give them names within the app.
- A clock, date, outside temperature, phone battery level and a voltage chart for the current trip. Battery and voltage readings depend on the data your phone and firmware provide.
- FM station information and a button to switch back to Bluetooth.
- Light and dark themes, automatic switching by headlights or time, a choice of fonts, and English and Russian interfaces.
- Updates from GitHub Releases, checked and installed from the app.
- Hold a trip voltage peak to inspect it, scroll through long trips and pinch to change the horizontal scale. Optional reverse-gear pause waits for 1 second in R and resumes only playback it paused.

## Compatibility

| Head unit | Status |
| --- | --- |
| Ekiy T7, FYT UIS7862, Android 10 | Built and tested on this device |
| Other FYT units on UIS7862 or UIS8581 | Expected to work with the `com.syu.ms` service; not confirmed on every model |
| Phones, tablets and non-FYT head units | Outside the intended device range; media integration requires FYT firmware services |

The APK requires Android 9 (API 28) or later. The Android version alone does not establish FYT compatibility. If you test another head unit, [report your model, firmware and what works](https://github.com/vsevalid/dashphonic/issues).

**Package conflict:** Dashphonic uses the application ID `com.ava.car`, which FYT firmware whitelists for media broadcasts. It cannot be installed alongside another app with that ID.

## Installation

1. Open the [latest release](https://github.com/vsevalid/dashphonic/releases/latest) and download `dashphonic-X.Y.Z.apk` from **Assets**. The source archives are not installable apps.
2. Open the APK on the head unit, either from a USB stick or after downloading it in the head unit's browser. Allow installation from that source when Android asks.
3. Launch Dashphonic on its own or place it in your launcher's split-screen panel.
4. Connect your phone over Bluetooth and start playback. The first-launch tutorial explains the controls; open **Settings → How to use** to replay it.

For later updates, tap the clock and open **Settings → App**. Automatic release checks run at most once every 12 hours and can be disabled.

If an installed build uses a different signing key or a higher Android version code, Android may require you to uninstall it before installing the release. **Uninstalling removes local settings and history.**

## Controls

| Action | Gesture or control |
| --- | --- |
| Next / previous track | Swipe left / right, or use the playback buttons |
| Play / pause | Double-tap the player area, or use the play/pause button |
| Adjust volume | Drag the bottom volume bar, or move two fingers up / down |
| Show synced lyrics | Tap the album cover when lyrics are available; tap the small cover to return |
| Open Bluetooth devices | Tap the phone row at the bottom |
| Open the trip voltage chart | Tap the voltage reading, when available |
| Inspect a voltage peak | Hold the chart: the large voltage and its status switch to the selected value and time. Release to restore live readings. |
| Scroll or zoom the trip chart | One hour fits the screen by default; longer trips scroll horizontally. Pinch to zoom in or out. Keyboard: hold arrows or Home / End to inspect, + / − to zoom, 0 to restore the hourly scale. |
| Open settings | Tap the clock |

Enable **Settings → Music → Pause in reverse** to pause Bluetooth music after R has stayed active for 1 second. Passing through R more quickly does nothing. After leaving R for 250 ms, Dashphonic resumes only if it sent the pause and received confirmation. Manual playback controls, a different audio source, calls, phone or service disconnection, and ignition off cancel automatic resumption. This option is off by default and requires the FYT reverse signal (`MAIN / U_BACKCAR`, code 12) and the firmware's idle Bluetooth phone state. Hardware verification is pending.

The trip chart shows voltage, not battery charge percentage. New history retains timestamps for minimum and maximum readings when samples are grouped; older history may show a time interval for a peak whose exact timestamp was not recorded.

## Offline use and missing data

The clock, Bluetooth controls, track data from the head unit and trip voltage history work without internet. Saved artwork and lyrics load from the Android disk cache before any network lookup, including when the phone initially reports an unknown track duration. Positive cached results do not expire by age; bounded cache storage and Android cache cleanup can still remove them. Lyrics for a known different track duration are not reused. Weather shows the last cached reading with a stale-data indicator. New artwork, lyrics, weather readings and update checks need a connection.

| Symptom | What to check |
| --- | --- |
| Android will not install the APK | Check for another `com.ava.car` app, a different signing key or a higher installed version code. See [Installation](#installation) before uninstalling. |
| Music plays but track details are missing | Check that the phone is connected to the head unit's Bluetooth system and that the unit has compatible FYT firmware. Include the model and firmware in an issue if the problem persists. |
| Artwork or lyrics do not appear | Check the connection, the individual feature switches and Zen mode. The online services may have no match for the track, even when connected. |
| Battery or voltage is absent | These readings depend on what the phone and head unit firmware report. |

## Privacy and network access

Dashphonic has no accounts, analytics or ads. Settings, trip voltage history and cached artwork and lyrics stay on the head unit. Android backup is disabled.

| Feature | External services | Request data |
| --- | --- | --- |
| Artwork search | iTunes Search, then MusicBrainz | Artist and track title |
| Artwork downloads | Apple image hosts, Cover Art Archive / Internet Archive | Image URLs returned by the artwork services |
| Synced lyrics | LRCLIB | Artist, track title and duration when available |
| Weather | Open-Meteo forecast and geocoding APIs | Coordinates for forecasts; the city you enter for city search |
| Updates | GitHub API and release downloads | Release checks and APK downloads; the updater sends the app version in its User-Agent |

Artwork and lyric lookup requests also include the app version and repository URL in their User-Agent. External services receive normal connection metadata, including the requesting IP address.

Turn off artwork or lyrics to stop requests for that feature. Zen mode stops both. Location permission is requested only when you choose your current location for weather; you can enter a city instead.

## How FYT integration works

On the target FYT firmware, the Bluetooth player runs inside `com.syu.ms` without publishing an Android `MediaSession`. This prevents ordinary Android media widgets from seeing music played from the phone. Dashphonic uses the service's AIDL interface, `com.syu.ipc.IRemoteToolkit`, and firmware broadcasts to read track information, playback position and volume directly.

The Android layer is written in Kotlin. The panel runs in a WebView using local HTML, CSS and JavaScript assets. The FYT bridge is in [`FytIpcBridge.kt`](app/src/main/java/app/sidewave/FytIpcBridge.kt), and the interface starts in [`panel.html`](app/src/main/assets/panel.html).

## Development

### Build a debug APK

Use JDK 17, Android SDK Platform 34, Build Tools 34.0.0 and Gradle 8.2.1, matching [CI](.github/workflows/build.yml). Gradle must be installed separately; the repository does not include a Gradle wrapper. Set `ANDROID_HOME` to your SDK installation, or set `sdk.dir` in a local `local.properties` file.

```bash
git clone https://github.com/vsevalid/dashphonic.git
cd dashphonic
gradle :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions also runs the panel tests and builds a debug APK on pushes and pull requests.

### Preview the panel in a browser

From the repository root, serve the assets with Python 3:

```bash
python3 -m http.server 8766 --directory app/src/main/assets
```

Open [localhost:8766/panel.html](http://localhost:8766/panel.html). The browser preview uses demo data; it does not test the head unit's Bluetooth or FYT integration.

### Run the panel tests

With Node.js and JDK 17 or later installed, run from the repository root (the reverse-pause tests compile the Java controller):

```bash
node --test tests/*.test.cjs
```

### Publish releases from a fork

<details>
<summary>Signing, tags and the in-app update source</summary>

The updater reads releases from the repository the APK was built from. GitHub Actions sets this from `GITHUB_REPOSITORY`; for local fork builds, pass `-PgithubRepo=OWNER/REPO` to Gradle. Without either setting, local builds use `vsevalid/dashphonic`.

1. Create a release signing key:

   ```bash
   scripts/create-release-key.sh
   ```

   With `gh` authenticated to your fork, run the `gh secret set` commands the script prints from the fork's repository directory. Keep a backup of the key and its password.

2. Choose an unused `vX.Y.Z` tag, for example:

   ```bash
   git tag v1.0.5
   git push origin v1.0.5
   ```

The [release workflow](.github/workflows/release.yml) runs the tests, builds and verifies the signed APK, and publishes it. It derives `versionCode` from the tag: `v1.2.3` becomes `10203`. Minor and patch numbers must each be below 100. A release signed with a different key cannot update an existing installation in place.

</details>

## Help and contributions

[Open an issue](https://github.com/vsevalid/dashphonic/issues) with your head unit model, Android and firmware versions, steps to reproduce, and expected and actual behavior. Reports from other FYT models help establish compatibility. Remove private information and device identifiers from logs and screenshots before posting.

For a code change, fork the repository, create a branch, run the panel tests and build a debug APK. In the pull request, describe the change and how you tested it. Include the head unit model and firmware if you tested on hardware. Keep signing keys and generated APKs out of the repository.

## Support

If Dashphonic is useful to you, you can [buy me a coffee](https://buymeacoffee.com/vsevalid) to support its development.

## License

[MIT](LICENSE). Bundled fonts use the SIL Open Font License; their license files are in [`app/src/main/assets/fonts`](app/src/main/assets/fonts).

The launcher icon is based on the supplied Dashphonic artwork.
