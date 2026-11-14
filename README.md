# MCU Live Watch

CLion plugin for watching live variable values on a running MCU over a J-Link probe, without
halting the target.

<!-- Plugin description -->
Watch MCU global variables live over J-Link without stopping the target.

The plugin parses the ELF file of your project, lists its symbols, and streams the values of the
ones you pick into a table and a live chart. Sampling rate is configurable per variable, so a
fast-changing value and a slow counter can be watched at the same time.

Currently implemented for Arm Cortex-M targets debugged through SEGGER J-Link in CLion. The design is
not tied to that combination: the ELF side parses any ELF, and the sampling side talks to the probe
through a small bridge, so other probes and other MCU families can be added behind the same
interface.
<!-- Plugin description end -->

## Status

| Area | State |
|---|---|
| ELF symbol browser | works |
| Live expressions (table + chart) | works on STM32 (J-Link, `pylink`) |
| Device picker | works — list taken from SEGGER's own table |
| Non-J-Link probes | not implemented |
| Non-Cortex-M targets | not implemented |

## What it does

- **ELF symbol browser** — lists objects with address, size and DWARF type, sortable.
- **Live expressions** — per-variable sampling rate, values streamed while the target runs.
- **Live chart** — bounded ring buffers, min/max decimation, flat memory and CPU over hours.

## Requirements

- CLion 2026.2 (build 262) or newer.
- A SEGGER J-Link probe with its software pack installed.
- Python 3 with `pylink-square` and `pyelftools`:

  ```bash
  pip install pylink-square pyelftools
  ```

  The plugin probes common locations (`/usr/bin/python3`, Homebrew, the project's `.venv`, and
  `~/.local/pipx/venvs/**`) and uses the first interpreter that can import both packages.

## Configuration

Nothing is mandatory. Press **Settings** in the ELF Parser tab only to override defaults:

| Field | Default |
|---|---|
| ELF file | auto-detected from the project tree |
| J-Link device | **selected, never guessed** — see below |
| Python interpreter | autodetected (must import `pylink` and `pyelftools`) |
| ELF parser script | bundled `elf_symbols.py` |
| J-Link bridge script | bundled `jlink_bridge.py` |
| Demangle C++ names | off |

### Device selection

Press **Select...** to choose from the device list SEGGER's own software reports (~15 000 entries,
read from the J-Link DLL through `pylink`). The chooser mirrors SEGGER's *Target Device Settings*:
pick a manufacturer, filter by name, select a device.

This replaces guessing the part from the project: CubeMX writes `STM32L100RCTx` while J-Link only
accepts `STM32L100RC`, and a wrong pick means reading registers from the wrong MCU. Because the list
comes from the vendor DLL, whatever is selected here is a name `JLink.connect()` accepts.

**Test J-Link** connects once through SEGGER's `JLinkExe`, so a probe or wiring problem is visible
before plotting starts. If no device is selected, the bridge refuses to start rather than connecting
to a guessed part.

## Architecture

```
bundled python/elf_symbols.py   ──stdout(JSON)──>  ElfParsePanel ──> ElfTable (typed symbols)
bundled python/jlink_bridge.py  ──TCP───────────>  SocketClient ──> LiveExpressionTable
                                                                └─> SampleStore (ring buffers)
                                                                         └─> PlotUpdaterService
                                                                                  └─> GraphPanel
```

The capture itself lives in a project-level `LiveSessionService`, so it keeps running when the tool
window tab is switched — see `CHANGELOG.md` 3.0.6 for why that was not free.

The two bundled scripts are extracted from the plugin jar to
`$TMPDIR/mcu-live-watch/python/` on first use.

### Bridge protocol

```
put;NAME;SIZE;0xADDR;HZ;TYPE     register a variable
deleteVariable;NAME              unregister
setVariableReadFrq;NAME;HZ       change rate
setSpeed;KHZ                     change J-Link speed
start | stop                     start/stop streaming
   -> NAME;Data:VALUE            sample
   -> OK: ... | ERROR: ...       reply
```

## Performance notes

- **Ring buffers, not lists.** Samples live in fixed-capacity `SampleBuffer`s; the chart keeps at
  most 2000 points per series via `XYSeries.maximumItemCount`.
- **Incremental drain.** One shared `PlotUpdaterService` timer appends only new samples using a
  per-variable cursor, and decimates bursts by keeping the min/max of each bucket.
- **Batched reads.** The bridge merges variables whose addresses are close and reads them in one
  `memory_read()` call — measured at 2 variables per read for adjacent globals.
- **One scheduler thread.** A single scheduling loop instead of a `threading.Timer` per variable per
  sample.
- **Locked writes.** Socket writes are serialised, so interleaved samples cannot corrupt a line.

## Building

```bash
./gradlew buildPlugin test
```

The platform is resolved from the local CLion installation (`localPlatformPath` in
`gradle.properties`), so the build always targets the exact build the plugin runs on.

The artifact lands in `build/distributions/mcu-live-watch-<version>.zip`.

## GitHub Actions

| Workflow | Trigger | What it does |
|---|---|---|
| `build.yml` | push to `main`, PR, manual | wrapper validation, `check`, Qodana, `buildPlugin`, `verifyPlugin`, draft release |
| `release.yml` | release published | `publishPlugin` to JetBrains Marketplace + attach the zip to the release |
| `dependabot.yml` | monthly | Gradle dependencies and Actions versions |

CI has no CLion, so every Gradle invocation there passes `-PlocalPlatformPath=`, which makes the
build download the platform (`platformType`/`platformVersion`) instead of using a local install.

## Releasing

1. Bump `pluginVersion` in `gradle.properties` and add a `CHANGELOG.md` section under that version.
2. Push; CI builds and creates a **draft** release.
3. Publish the draft. `release.yml` then uploads the plugin to JetBrains Marketplace.

Publishing needs these repository secrets:

| Secret | Used by |
|---|---|
| `PUBLISH_TOKEN` | `publishPlugin` — token from https://plugins.jetbrains.com/author/me/tokens |
| `QODANA_TOKEN` | optional Qodana code inspection; without it the job skips and stays green |
| `CERTIFICATE_CHAIN` | optional jar signing |
| `PRIVATE_KEY` | optional jar signing |
| `PRIVATE_KEY_PASSWORD` | optional jar signing |

Without the signing secrets the jar is published unsigned, which the Marketplace accepts.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md) and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
