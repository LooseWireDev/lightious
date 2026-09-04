# Lightious

Lightious is a small, text-first Invidious client for Light Phone III. It is a
clean-room Kotlin application built on the Light SDK—not a Clipious or
Materialious fork or wrapper.

The launch screen is a pairing-first Focused library and never loads a
recommendation feed on its own. Popular videos are available only when a paired
companion explicitly selects Explore mode and that page is enabled.

## Features

- Search for videos or open a YouTube URL.
- Open an optional signed-in Invidious account feed.
- Watch a low-bandwidth progressive stream while preserving its native aspect ratio.
- Listen through the Light SDK audio player, including detached playback.
- Keep optional local search and watch histories.
- Choose which pages appear on Home.
- Select an app-wide preferred audio language.
- Choose whether media is proxied through the configured Invidious instance.
- Pair with the companion website using a 12-character code—no token entry on the phone.
- Switch between unrestricted Explore and an explicitly curated Focused library.
- Browse Focused videos, allowed channels, and private Lightious playlists from
  LightOS-style bottom navigation.
- Search videos, audio, channels, playlist names and contents, and downloaded
  titles from one library search.
- Filter each Focused view by all, audio-only, or video-enabled items.
- Allow each selected video or whole channel to be listened to only or watched
  and listened to.
- Download approved media to app-private storage, with progress, cancel, retry,
  offline playback, and confirmed deletion from a Downloads tab.
- Preserve native video aspect ratios and provide an explicit screen-filling
  fullscreen view without stretching or cropping.
- Exclude YouTube Shorts from search, libraries, playlists, history, playback,
  and downloads.

There is no autoplay, comments, algorithmic Home feed, notifications, or
automatic public-instance rotation.

## Project structure

Lightious follows the same standalone composite-build pattern as
[`LooseWireDev/kelp`](https://github.com/LooseWireDev/kelp):

- `:app` is the Kotlin/Compose Light SDK tool.
- The repository owns its Gradle wrapper, settings, dependency catalog, tests,
  and GitHub Actions.
- An adjacent Light SDK checkout supplies the Light Gradle plugin and
  `com.thelightphone:client` through `includeBuild` dependency substitution.
- The companion control plane remains separate in
  [`LooseWireDev/lightious-invidious`](https://github.com/LooseWireDev/lightious-invidious).

The current SDK baseline is
[`LooseWireDev/light-sdk`](https://github.com/LooseWireDev/light-sdk) commit
`52fbc5a8aedbd3c4c88037580709e53540086229`. It is based on official Light SDK
v0.1.1 and includes the small composite-consumer allowlist patch also used by
Kelp. Lightious does not use Kelp's custom TIDAL RPC APIs.

## Build it

You need JDK 17, Android SDK platform 36, and the Light SDK checked out beside
this repository:

```text
parent/
├── light-sdk/
└── lightious/
```

Then run from the Lightious repository:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. If the SDK
lives elsewhere, pass `-Plightious.sdkPath=/absolute/path/to/light-sdk`.

The Light SDK development keystore signs local debug and release builds. The
GitHub release workflow uses a dedicated Lightious release key instead.

## Install

Tagged releases will appear on the
[`Releases`](https://github.com/LooseWireDev/lightious/releases) page. Install
or upgrade an APK with:

```sh
adb install -r lightious-vX.Y.Z-vcN.apk
```

The currently installed development build uses Light's shared development key.
A future APK signed with a new dedicated release key cannot replace that build
without either a one-time uninstall or an explicit Android signing-key rotation
plan. Do not publish the first tag until that choice is made.

## Continuous integration and releases

`.github/workflows/build.yml` runs unit tests and assembles a debug APK for
every push to `main` and every pull request. The APK is retained as a workflow
artifact.

`.github/workflows/release.yml` runs when a `v*` tag is pushed. It fails unless
the tag matches `versionName` in `app/lighttool.toml` and all four signing
secrets are configured:

- `LIGHTIOUS_RELEASE_KEYSTORE_B64`
- `LIGHTIOUS_RELEASE_STORE_PASSWORD`
- `LIGHTIOUS_RELEASE_KEY_ALIAS`
- `LIGHTIOUS_RELEASE_KEY_PASSWORD`

The workflow tests and builds the minified release, verifies its signature,
package ID (`com.loosewire.lightious`), version code and name, writes a SHA-256
checksum, and publishes the APK and checksum to GitHub Releases.

## Companion pairing and account sign-in

The companion-enabled Invidious fork exposes `/lightious`. Sign in there with a
normal Invidious account, select Explore or Focused mode, search for videos and
channels, add one result or a multi-selection, and arrange added videos into
private Lightious playlists. Search results never play on the companion site.
Each selected video or whole channel gets an audio-only or video-enabled policy.
Approve a phone using the 12-character code displayed by Lightious.

The phone creates its own random device credential and sends only its SHA-256
digest when pairing begins. The credential is stored with AES-GCM encryption
backed by the Android Keystore. Browser session cookies and account passwords
never enter the phone app. A paired phone refreshes companion policy before
showing a video and rechecks it with fresh media metadata before every audio or
video start, including resuming paused audio. Focused mode allows an exact
selected video or a video whose authoritatively reported channel is explicitly
allowed; an exact video policy overrides the channel default. Playlist
membership grants access only to that exact playlist item and never infers or
grants broader channel access.

Downloads follow the currently verified policy. Listen-only items save one
adaptive audio stream; video-enabled items save one muxed progressive stream
at 720p or below. Live/HLS media and separate adaptive video-plus-audio tracks
are not downloaded. If an app-wide language choice requires a separate adaptive
audio track, Lightious refuses to silently save a muxed video with different
audio and directs the user to save that item as listen-only instead. The
background job fetches fresh protected metadata and
re-resolves its short-lived signed media URL instead of persisting that URL.
Partial transfers can resume only when the stable stream identity still
matches; otherwise the partial file is discarded to avoid splicing formats.
Transient network and server failures retry through LightWork while retaining a
compatible partial transfer; user cancellation and deletion clean it up.
Downloaded files live under the app's private files directory and require no
broad storage permission.

Offline bytes cannot be recalled until the phone reconnects. After a successful
sync removes access, downgrades a video to listen-only, or confirms that the
pairing was revoked, Lightious deletes incompatible local copies. Explicitly
forgetting the companion on the phone also deletes that pairing's downloads.

The existing restricted Invidious bearer-token sign-in remains optional for
Explore-mode account feeds and watched-history sync. It is separate from the
companion device credential and never grants access to `/lightious` controls.

## History and privacy

Search and watch history are stored only in the app's local Room database.
Recording can be disabled independently, and each history screen provides a
separate confirmed clear action.

## Instance requirements

The configured HTTPS server must expose the Invidious v1 API, video metadata,
and a playable media URL. Companion pairing additionally requires the
[`lightious` branch of `LooseWireDev/lightious-invidious`](https://github.com/LooseWireDev/lightious-invidious/tree/lightious)
with its Lightious routes enabled and public HTTPS URL configured.

Paired playback always uses the server's short-lived, signed media gateway.
`Proxy media` applies only to unpaired or custom Explore-mode playback: enabling
it requests `local=true`, while disabling it normally uses the returned Google
video CDN URL directly. Protected live/HLS playback is not yet available.

## Compatibility status

The UI, navigation, input, theming, and audio path use Light SDK components.
Video rendering uses Media3 and an Android `TextureView`, so Lightious is an
experimental sideloaded tool rather than an officially supported Light tool.

Materialious and Clipious were used only as behavioral references for the
documented Invidious HTTP API; no source from either project is included.

## License

No project license has been selected yet. The source is public for inspection,
but redistribution and modification rights have not yet been granted.
