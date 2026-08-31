# Lightious repository guide

Lightious is a Kotlin and Compose client for Light Phone III built against the
Light SDK as an adjacent composite build.

## Repository map

- `app/`: the only Android/Light tool module.
- `app/lighttool.toml`: source of truth for app identity, version, permissions,
  capabilities, LightOS server package, and orientation.
- `app/src/main/kotlin/com/loosewire/lightious/`: production client code.
- `app/src/test/kotlin/com/loosewire/lightious/`: JVM unit tests.
- `settings.gradle.kts`: includes `:app` and the companion Light SDK checkout.
- `.github/workflows/`: CI and tag-driven release publishing.

The default SDK checkout is `../light-sdk`. Override it with
`-Plightious.sdkPath=/absolute/path/to/light-sdk`. Keep the CI SDK commit and
the documented local baseline aligned.

## Boundaries

- Use Light SDK screens, view models, UI primitives, input, theming, navigation,
  and audio APIs wherever the SDK provides them.
- The Media3 video path is an explicitly experimental sideload exception.
- Do not add the companion service or Invidious server code here; it belongs in
  `LooseWireDev/lightious-invidious`.
- Do not add a hand-written Android manifest. The Light SDK plugin generates it
  from `app/lighttool.toml`.
- Preserve the legacy history database filename and Android Keystore alias
  unless an explicit on-device migration is implemented first.

## Verification

Use the repository wrapper and actual app module:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Release tags must match `versionName` in `app/lighttool.toml`. GitHub release
signing must fail closed when any Lightious signing secret is absent.
