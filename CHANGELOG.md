<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MCU Live Watch changelog

## [Unreleased]

## [3.1.0]

### Changed

- **Renamed to MCU Live Watch.** The plugin outgrew "STM32 Live Expressions": the ELF side parses
  any ELF and the sampling side talks to the probe through a bridge, so other probes and other MCU
  families can be added without touching the UI. Display name, root project and tool window follow.
- **New plugin id `dev.st33ng.mculivewatch`.** The old id still carried the `com.jfrog` group of the
  Conan template the project started from, which is also a namespace the plugin has no claim to.
  Settings are now stored under `mcu-live-watch.xml`; the tool window id is `McuLiveWatch`.
- Package root moved from `com.jfrog.MyPlugin.clion` to `dev.st33ng.mculivewatch`, with
  `toolWindow` renamed to `ui` and `StateServices` to `state`. `HelloWorldToolWindowFactory` is now
  `McuLiveWatchToolWindowFactory` and `LiveExperssionPanel` (sic) is `LiveExpressionPanel`.
- Bundled scripts are extracted to `$TMPDIR/mcu-live-watch/` instead of `$TMPDIR/stm32-live-expressions/`.
- **Toolchain raised to Java 25.** `verifyPluginProjectConfiguration` reported that CLion 2026.2.4.1
  requires Java 25 while the build still targeted 21. The plugin now compiles against the same JBR
  its platform runs on.
- **Plugin verification checks the build the plugin ships for.** `pluginVerification` uses
  `current()` instead of `recommended()`, which would resolve CLion 2026.3 EAP plus 2026.2.5 on
  every verification run. The verifier's cache is now kept in `build/pluginVerifierHome` instead of
  the hardcoded `~/.pluginVerifier`, which is not writable in restricted environments.
  `./gradlew verifyPlugin` reports the plugin as **compatible** with CL-262.11879.106.

### Removed

- **Everything left over from the Conan CLion plugin template**: the `.ci/` crawler that generated a
  Conan package database, its `gen-packages-info.yml` workflow, and the Conan screenshots in
  `.github/readme/`. None of it was reachable from the plugin code.
- Dead IDE run configurations: `Run IDE for UI Tests` (no `runIdeForUiTests` task exists) and
  `Run Qodana` (called `cleanInspections runInspections`, neither of which the Gradle plugin
  provides any more). `Run Verifications` now calls `verifyPlugin` instead of the 1.x name
  `runPluginVerifier`.
- `DEFAULT_DEVICE = "STM32F030C8"` is no longer used as a fallback: the bridge already refuses to
  start without a selected device, and a default that silently picked an unrelated part contradicted
  that.

### Added

- **GitHub Actions pipeline for a public plugin repository**: wrapper validation, `check`,
  Qodana, `buildPlugin`, `verifyPlugin` (cross-IDE compatibility), and a draft release that
  `release.yml` publishes to JetBrains Marketplace.
- CI builds without a local CLion: when `localPlatformPath` is empty, the platform is resolved from
  the JetBrains repository (`platformType`/`platformVersion`) instead of the local installation.
- `signing {}` block wired to the `CERTIFICATE_CHAIN` / `PRIVATE_KEY` / `PRIVATE_KEY_PASSWORD`
  secrets; builds without them stay unsigned and the Marketplace accepts them.
- README rewritten for the Marketplace listing: supported/unsupported scope, release steps and the
  required secrets.

## [3.0.8]

### Fixed

- **The Graph tab stayed empty while the Value column kept updating.** The chart only ever drew
  variables that had been passed to `PlotManager.addVariable`. A variable typed into the table's
  "+" row was inserted straight into the table model, so it never got an `XYSeries` — its samples
  were buffered and then silently dropped by `PlotUpdaterService`, which skipped any variable
  without a series. The Graph tab showed nothing, with no error to explain why.
  - `PlotUpdaterService` now creates a missing series on demand instead of discarding its points.
  - The "+" row goes through `addVariable()` like every other path, so the column layout, series
    creation and duplicate check live in one place.
  - `PlotManager.addVariable()` returns the series it created (or the existing one).

## [3.0.7]

### Fixed

- **A sample could be dropped while the EDT was busy painting.** `flushPendingValues()` took a
  snapshot of the pending queue and then called `clear()` on it. A value written between the
  snapshot and the `clear()` was discarded — and its producer had already seen the "flush already
  scheduled" guard as free, so nothing scheduled another flush. At 1 kHz this loses the newest
  value of a burst. The entries are now removed one by one, conditionally on the value that was
  snapshotted, and a new flush is scheduled if anything is still queued.

## [3.0.6]

### Fixed

- **Switching to another tool-window tab silently stopped the capture.** The session lived in
  `LiveExperssionPanel` and was torn down in `removeNotify()`, but IntelliJ calls `removeNotify()`
  when a tab is merely *deselected* — the component is removed from the tab container, not
  destroyed. Sampling therefore died the moment the Graph tab was opened, and only pressing Start
  again revived it. The session now lives in a project-level `LiveSessionService`, so its lifetime
  is tied to the project instead of to whichever tab happens to be visible.
- **The graph only refreshed while its tab was visible.** `GraphPanel` registered itself with
  `PlotUpdaterService` in its constructor and only ever *unregistered* in `removeNotify()`, so after
  the first visit to another tab it never re-attached and the chart stayed frozen. Registration now
  happens in `addNotify()`, and the panel repaints the data captured while it was hidden.
- **A variable was not charted until "Add to Graph" was used from the context menu.** Adding an
  expression now also creates its series, so the Graph tab shows data without a hidden extra step.
- Each Start begins a fresh capture: `SampleStore` is emptied and the chart's series are cleared
  (keeping their variables, so the legend and colours survive). The plot cursors are reset too —
  otherwise they pointed past the end of the new buffers and the first samples were skipped.

### Added

- **Start / Stop buttons and a status line on the Graph tab as well.** Both tabs drive the same
  project-level session, so either one can start or stop the capture.

## [3.0.5]

### Fixed

- **No samples reached the table even with a real probe: the bridge never connected to the MCU.**
  `_ensure_connected()` gated on `pylink.connected()`, which already returns `True` as soon as a
  *probe* is attached over USB — it says nothing about the target. So `connect()` was skipped and
  every read failed with `Target is not connected`, printed only to the bridge's stderr, which the
  plugin logged at `debug`. The gate is now `target_connected()`, and `set_tif(SWD)` runs **before**
  `connect()` (pylink's `connect()` issues `Device=` and connects implicitly, so a later `set_tif`
  is too late). Verified on a J-Link PLUS + STM32L100RC: 1492 samples in 3 s.
- **Bridge errors were silently discarded.** A line starting with `ERROR:` was ignored as "not a
  sample", so `ERROR: Failed to start reading (J-Link not connected)` was invisible. Errors are now
  logged and shown in the panel's status line.
- **`Value` displayed `IntValue(value=489583)` instead of `489583`.** A Kotlin `data class`
  generates its own `toString()` even when the sealed base class overrides it, so the inherited
  implementation was never used. Each variant now overrides `toString()` and there is an explicit
  `displayText()`.
- **Start/Stop felt unresponsive ("Stop doesn't stop").** Every sample posted its own
  `invokeLater`; at 1 kHz that flooded the EDT, so button clicks queued behind thousands of
  repaints. Values are now coalesced per variable and a single flush is scheduled at a time.
- **Stop left the session looking alive.** `On server` stayed `true` for every row, because the
  flag was never cleared. Stop now clears it, and pending values are dropped so stale text cannot
  appear after the session ends.
- The panel has a status line (`Idle` / `Starting...` / `Running - receiving samples` / `Stopped` /
  `Bridge error: ...`), so a silent Start no longer looks like a broken one.

## [3.0.4]

### Fixed

- **The Value column never updated for the first expression.** The "+" placeholder row is created
  once at the bottom of the table, and every later variable is inserted *above* it — but the
  registration loop skipped row 0 by index, assuming the placeholder was still there. The first
  real variable therefore lived at row 0, was never sent to the bridge, and "+" was registered as
  a variable name instead: the log showed `Variables not registered: +` and no samples arrived.
  The placeholder is now identified by its content, so all real rows are considered.
- Editable-state and context-menu checks that keyed off "row 0 is the `+` row" now also match the
  placeholder by content, so a real variable in row 0 is editable and gets its own menu.
- A session restarted after Stop stayed paused (the client-side pause flag was never cleared),
  so the reader polled an empty queue and no sample reached the table. `start()` now resumes.

## [3.0.3]

### Fixed

- Starting a live session failed with *"Cannot connect to the J-Link bridge on port 12345"* while
  the bridge was in fact running: the plugin dialled the socket 700 ms after spawning the bridge,
  but the bridge imports `pylink` and only then binds its TCP socket, so the port was still closed.
  Start now waits for the child's own "waiting for connection" line (up to 20 s) instead of sleeping
  a fixed time, and a bridge that never announces readiness is replaced rather than reused.
- Failures now quote the last lines of the bridge's own output, so the dialog says *why* the bridge
  did not come up instead of only that it could not be reached.

## [3.0.2]

### Fixed

- The *Select J-Link Device* dialog could stay on "Reading the device list..." with an empty table
  even though the list had already been read (the log shows `Using the device list ... : 14957
  devices` immediately before it). The dialog now fills the table synchronously while it is being
  built — no background fill and no `invokeLater` on the opening path — so there is no window in
  which the list exists but is not shown. Only *Refresh from J-Link*, which really does run a
  process, is asynchronous.

## [3.0.1]

### Fixed

- The *Select J-Link Device* dialog opened empty and stayed on "Reading the device list from SEGGER
  J-Link Commander...". The list no longer has to be exported before the dialog can be used: a copy
  of the `ExpDevList` table is shipped inside the plugin (`device/jlink-devices.csv.gz`, 74 KB for
  14 959 devices) and is shown immediately, offline, without spawning a process.
- *Refresh from J-Link* re-exports the table from the installed SEGGER software on demand. A failed
  or slow export now reports the reason in the status line and keeps the previous list, instead of
  leaving the dialog blank with no way forward.

## [3.0.0]

### Added

- Device chooser modelled on SEGGER's *Target Device Settings*: manufacturer list, name filter and
  a device table populated from the ~15 000-entry list the J-Link DLL reports. Selecting from that
  list removes the CubeMX (`STM32L100RCTx`) versus SEGGER (`STM32L100RC`) spelling mismatch, and
  the bridge now refuses to start when no device is selected instead of guessing one.
- **Test J-Link** button in the settings dialog: connects once through SEGGER's own `JLinkExe`,
  so a mismatch with the official toolchain is visible before plotting.
- The Python bridge and the ELF parser now ship inside the plugin (`python/jlink_bridge.py`,
  `python/elf_symbols.py`) and are extracted to a temp directory on first use. No external script
  paths are required any more.
- Automatic Python interpreter discovery: the plugin probes common locations and picks the first
  interpreter that can import both `pylink` and `pyelftools`.
- `--json` output mode for the ELF parser, keeping symbol parsing independent of locale.
- J-Link device name and interface speed are configurable per project.

### Changed

- Migrated the build to CLion 2026.2 (build 262): Gradle 9.8.1, Kotlin 2.3.21, IntelliJ Platform
  Gradle Plugin 2.20.1, JFreeChart 1.5.5. The platform is resolved from the local CLion
  installation instead of a downloaded distribution.
- Toolchain raised to Java 21; `sinceBuild` set to 262.

### Fixed

- The settings dialog no longer hangs on "detecting...": child processes are now drained
  concurrently and killed on timeout, instead of reading stdout before `waitFor` (which made the
  timeout unreachable — the 322 KB device list deadlocked on a 64 KB pipe buffer).
- The ELF panel no longer keeps showing "Loading..." after symbols have loaded.
- Devices without a manufacturer were dropped from the device list because `trim()` removed the
  leading tab.

- Chart no longer leaks memory: samples go to fixed-capacity ring buffers (`SampleBuffer`) instead
  of an unbounded `MutableList`. Retention is enforced by `XYSeries.maximumItemCount` rather than a
  `remove(0)` loop.
- Chart no longer degrades over time: one shared `PlotUpdaterService` timer drains buffers
  incrementally from a per-variable cursor and decimates bursts, instead of rebuilding every series
  from the full history on each tick.
- Closing the Graph tab no longer kills the updater permanently.
- Socket client uses independent read and write locks plus a background reader thread, so `stop`
  is delivered immediately instead of waiting for the socket timeout.
- Live expression loop no longer polls with `Thread.sleep`; it waits on a queue and honours debug
  session end.
- The J-Link bridge process is owned by a project service: one process per project, terminated on
  project close, instead of one leaked process per tool window.
- ELF parsing no longer runs on the EDT, has a timeout, drains stderr concurrently, and reports a
  non-zero exit instead of failing silently.
- Removed hardcoded absolute paths; Python interpreter, scripts, ELF file and port are configurable
  per project and persisted.
- ELF symbols are typed (`ElfSymbol`) instead of `Array<String>`, removing locale-dependent parsing
  of Russian prose and `toInt(16)` overflow on addresses.
- Removed unused `kotlinx-serialization` dependency and the dead
  `com.sun.java.accessibility` import.
- Batched J-Link reads: variables with nearby addresses are fetched in one `memory_read()` call.
- One scheduler thread in the bridge instead of a `threading.Timer` per variable per sample.

## [2.0.9]

### Changed

- Fix deprecated API usages
