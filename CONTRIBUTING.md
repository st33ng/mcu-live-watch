# Contributing

Thanks for looking. This file covers the parts that are not obvious from the source.

## Build

```bash
./gradlew test check buildPlugin
```

The build normally resolves the IntelliJ Platform from a **local CLion installation**
(`localPlatformPath` in `gradle.properties`, `/Applications/CLion.app` by default). That is
deliberate: the plugin must link against the exact build it runs on, and CLion's platform bytecode is
Java 25 while a downloaded distribution may differ.

If you have no CLion at that path, point `localPlatformPath` at yours, or build like CI does:

```bash
./gradlew buildPlugin -PlocalPlatformPath=
```

A `localPlatformPath` that does not exist falls back to the download automatically (with a line in
the log), so a fresh clone builds on any OS without editing the file. The download is CLion from the
JetBrains repository, ~1.2 GB on a cold cache.

The Kotlin toolchain is Java 25. `kotlin.compiler.execution.strategy = in-process` is set because the
Kotlin compile daemon cannot start in every environment (it fails to write its daemon directory).

## Tests

There are 12 test classes / 40 tests, all JUnit 4. Most extend `BasePlatformTestCase` and therefore
start a platform fixture.

Two things that will bite you:

- Every `BasePlatformTestCase` test must call
  `setDefaultCodeInsightSettings(CodeInsightSettings.getInstance())` in `setUp()`, otherwise the
  fixture fails in `tearDown()` with `ComparisonFailure: Code insight settings damaged`.
- A Swing container that was never displayed does **not** fire `removeNotify()`. UI-lifecycle tests
  must set `frame.isVisible = true`, or they silently prove the opposite of what they claim.

`LiveSessionEndToEndTest` runs the real path (service → child process → socket → table → sample
store) against a fake bridge script, so the capture pipeline is covered without hardware.

## Code layout

```
src/main/kotlin/dev/st33ng/mculivewatch/
  state/     debug session flags
  storage/   ELF table, live expression table, project services
  bridge/    J-Link bridge process + bundled Python extraction
  device/    SEGGER device list, picker, JLinkExe wrapper
  listener/  debug start/stop hook
  plot/      ring buffers, chart series, updater timer, graph panel
  session/   LiveSessionService — owns the whole capture
  settings/  per-project persistent state
  ui/        tool window tabs
  util/      ProcessRunner
```

Rules that are not negotiable — they each came from a real defect:

1. **Every child process goes through `ProcessRunner`.** Reading `process.inputStream` before
   `waitFor(timeout)` makes the timeout unreachable and deadlocks on the 64 KB pipe buffer.
2. **Never gate target work on `pylink.connected()`.** It means "a probe is on USB". Use
   `target_connected()`, and call `set_tif(SWD)` *before* `connect()`.
3. **Never guess the J-Link device.** CubeMX writes `STM32L100RCTx`; SEGGER only knows
   `STM32L100RC`. A wrong name reads registers from the wrong MCU, so the bridge refuses to start
   without a device selected in the picker.
4. **Never identify the `"+"` placeholder row by index.** `insertRow(rowCount - 1, …)` pushes it
   down, so a real variable ends up at row 0. Compare row content.
5. **`removeNotify()` is not a lifetime signal.** IntelliJ calls it when a tab is merely deselected.
   Only listeners may be detached there; the capture lives in `LiveSessionService`.
6. **Coalesce samples before the EDT.** One `invokeLater` per sample at 1 kHz starves the queue and
   the Start/Stop buttons stop responding.

## Pull requests

- Bump `pluginVersion` in `gradle.properties` and add a `CHANGELOG.md` section under that version.
  The release workflow publishes whatever the changelog says.
- Run `./gradlew test check buildPlugin` before pushing; CI runs the same plus `verifyPlugin`.
- Say which part you actually exercised. "Tests pass" and "works on hardware" are different claims —
  the second one needs a probe.
