# AGENTS.md

Single-module Android app (Gradle Kotlin DSL, AGP 9.4.1, Gradle 9.6). One module: `:app`, namespace/applicationId `com.eljuliodev.servidormc`. Jetpack Compose frontend (app propósito: servidor Minecraft corriendo en el propio teléfono). No CI, no README, not a git repo.

## Commands (verified)

```sh
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lint                   # lint — no separate typecheck step exists (warnings OK, 0 errors expected)
./gradlew :app:assembleDebug          # debug APK (verified building)
./gradlew :app:connectedAndroidTest   # instrumented tests; needs a running device/emulator
```

Order: `lint -> testDebugUnitTest`. Run the wrapper (`./gradlew`), not a system Gradle. `local.properties` points at `sdk.dir=/home/alexisg/Android/Sdk`.

## Gotchas

- **Kotlin is compiled by AGP 9's built-in Kotlin support** (no `kotlin-android` plugin — don't add one). Compose additionally requires the `org.jetbrains.kotlin.plugin.compose` plugin: `compose-compiler` in `gradle/libs.versions.toml`, and its `kotlin` version **must equal AGP's bundled Kotlin** (currently 2.2.10 = kotlin-stdlib version in AGP 9.4.1's pom). If you upgrade AGP, check its pom and bump `kotlin` or the build fails with "Compose Compiler Gradle plugin is required" / version mismatch.
- **SDK levels**: minSdk 29, targetSdk 36, compileSdk 37. Google Play requires targetSdk ≥ 36 for new apps since 2026-08-31 — don't lower it. `compileSdk 37` is what Compose 1.12+ (BOM `2026.08.00`) requires; don't downgrade. Lint's `OldTargetApi` warning ("not latest") is expected noise.
- **AGP 9 DSL differs from older templates.** `compileSdk { version = release(37) }` and release `optimization { enable = false }` are correct as written. Do not rewrite them to `compileSdk = 37` / `minifyEnabled`.
- **Test sources live under the old package**: `app/src/{test,androidTest}/java/com/example/servidormc/` while the namespace is `com.eljuliodev.servidormc`. New test files go under `com/eljuliodev/servidormc/`; don't move existing files without updating their `package` declarations. (The instrumented test's packageName assertion was already fixed to match applicationId — keep it in sync if applicationId ever changes.)
- **Entry point**: `MainActivity` (Compose, `ComponentActivity`) declared in the manifest as the LAUNCHER. If Android Studio says "default activity not found" again, the manifest intent-filter is what broke.
- **Configuration cache is on** (`org.gradle.configuration-cache=true`). If Gradle fails oddly after build-script edits, rerun once before debugging.
- `app/src/main/keepRules/rules.keep` is referenced by no build file — dead file, ignore it.
