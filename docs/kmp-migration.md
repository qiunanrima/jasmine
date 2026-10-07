# Kotlin Multiplatform migration

## Category pilot

`:domain:category` shares `Category`, `CategoryRepository`, and `GetCategories`
between Android, JVM, and iOS. Packages and Android/JVM Java serialization are
preserved. `:domain` exports this module so existing consumers do not need to
change their imports or dependencies.

The JVM target provides a host-side verification path on Windows and Linux.
The iOS device and simulator targets require macOS and Xcode for compilation
and execution. The module exports a static `JasmineCategory` framework. No iOS
application is included yet.

## Independent build and CI

Use `./gradlew -p kmp` to build shared modules without the Android application,
external sibling Java builds, or machine-specific root Gradle properties.
In this isolated build, `:domain:category` is exposed as `:category`:

```sh
./gradlew -p kmp :category:jvmTest :category:spotlessCheck
```

The `KMP iOS Validation` workflow runs on macOS, tests shared code on JVM and
the iOS simulator, and uploads the ARM64 device framework. It can be dispatched
manually or triggered by shared-code changes on `feat/kmp-migration`.

This artifact is a framework, not an IPA or a complete iOS application.
The delivery target is a full iOS application packaged as an unsigned IPA.
Once that application exists, archive it with code signing disabled and package
the resulting device `.app` inside `Payload/` in a ZIP with the `.ipa` extension.
An unsigned IPA requires signing before installation on a normal iOS device.

Run the pilot checks:

```powershell
.\gradlew.bat :domain:category:jvmTest :domain:testDebugUnitTest :domain:category:spotlessCheck
```

On macOS, additionally compile and test the native shared code:

```sh
./gradlew :domain:category:compileKotlinIosArm64 :domain:category:iosSimulatorArm64Test
```

## Remaining boundaries

- Category mutation use cases still depend on Android preferences and logging.
- `:core:common`, `:source-api`, and the external Java API builds remain Android/JVM-specific.
- Database code and SQLDelight drivers have not moved to shared source sets.
- Existing Android presentation modules and reader behavior are unchanged.

## Next increments

1. Extract platform-neutral preference and logging contracts with Android adapters.
2. Move category mutation use cases and their behavioral tests into the pilot.
3. Share SQLDelight queries and repositories while retaining existing schema migrations.
4. Introduce shared source contracts and adapt existing Android sources.
5. Add an iOS application and validate a library/category/history workflow before UI migration.
