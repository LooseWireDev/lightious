# Lightious

Lightious is a small, text-first Invidious client for Light Phone III. It is a
clean-room Kotlin application built with Light SDK components—not a Clipious or
Materialious fork or wrapper.

The launch screen is an offline menu and never loads a recommendation feed on
its own. Popular videos are hidden by default and are requested only if that
page is explicitly enabled and opened.

## Features

- Search for videos or open a YouTube URL.
- Open an optional signed-in Invidious account feed.
- Watch a low-bandwidth progressive stream, with HLS support for live video.
- Listen through the Light SDK audio player, including detached playback.
- Keep optional local search and watch histories.
- Choose which pages appear on Home.
- Select an app-wide preferred audio language.
- Choose whether media is proxied through the configured Invidious instance.

There is no autoplay, comments, algorithmic Home feed, notifications, or
automatic public-instance rotation.

## Repository layout

This repository contains only the Light tool module under [`tool/`](tool/).
That keeps the application independent from the Light SDK source while matching
the tool path expected by Light's builder.

The module currently relies on the SDK root's Gradle catalog, Light plugin,
`:sdk:client` project, and development signing key, so this is not a truthful
standalone `./gradlew build` repository yet. To build the current app, place
this repository's `tool/` directory into a Light SDK checkout and run:

```sh
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
```

The last tested development context was
[`LooseWireDev/light-sdk`](https://github.com/LooseWireDev/light-sdk) commit
`52fbc5a8`. Compatibility with the official hosted builder has not been
independently verified.

## Account sign-in

The current client uses Invidious's restricted bearer-token flow and never asks
for an account password. The token is validated against the selected instance
and stored with AES-GCM encryption backed by the Android Keystore.

This manual flow is expected to be replaced by the Lightious companion pairing
experience. The companion service and focused, explicitly-curated library are
being developed separately in
[`LooseWireDev/lightious-invidious`](https://github.com/LooseWireDev/lightious-invidious).

## History and privacy

Search and watch history are stored only in the app's local Room database.
Recording can be disabled independently, and each history screen provides a
separate confirmed clear action.

## Instance requirements

The configured HTTPS server must expose the Invidious v1 API, video metadata,
and a playable media URL. Plain HTTP instances are rejected, except where a
future development build explicitly supports local testing.

`Proxy media` requests `local=true`. This keeps media traffic on the configured
Invidious host but consumes that server's bandwidth. Turning it off normally
uses the returned Google video CDN URL directly.

## Compatibility status

The UI, navigation, input, theming, and audio path use Light SDK components.
Video rendering uses Media3 and an Android `SurfaceView`, so Lightious is an
experimental sideloaded tool rather than an officially supported Light tool.

Materialious and Clipious were used only as behavioral references for the
documented Invidious HTTP API; no source from either project is included.

## License

No project license has been selected yet. The source is public for inspection,
but redistribution and modification rights have not yet been granted.
