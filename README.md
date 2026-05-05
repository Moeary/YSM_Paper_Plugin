# Paper-YSM

Paper-YSM is a Paper-side reimplementation prototype for the Yes Steve Model
server distribution path. It can handshake with YSM 2.6.x clients, expose the
authorized model list, replay a captured FreesiaII-style native cache stream,
and apply YSM model state to compatible viewers.

The current priority is a usable Paper-side cache distributor: real
Fabric/YSM/Freesia worker output is treated as the cache oracle, while PaperYSM
handles handshake, native-cache replay, saved model state, and diagnostics.

## Current Status

- YSM `51/52` Java-layer handshake works on channel `yes_steve_model:2_6_0`.
- Authorized model list packet `id=6` is generated from local `.ysm` files.
- V3 `.ysm` parsing and cache preparation use logic from the YSMParser project.
- Native cache sync defaults to replaying worker/Freesia-derived cache material.
- After cache sync, YSM clients can select the server model and see model state
  and ordinary animation state across two clients.
- Wheel/custom animation forwarding is still under investigation. Recent tests
  show possible animation id/order mismatch, so this area is intentionally not
  treated as stable yet.

## Repository Layout

```text
src/                         Paper plugin source
docs/                        Protocol notes, progress notes, and old prototype notes
ysm-sniffer/                 Velocity packet capture helper
scripts/                     One-shot local launch scripts
test-server/
  direct-paper/              Standalone Paper server with PaperYSM
  paper-backend/             Paper backend for Velocity/FreesiaII comparison
  velocity-proxy/            Velocity proxy with FreesiaII and packet tools
  freesia-worker/            Fabric YSM/Freesia worker server
references/
  YSMParser/                 Snapshot of the parser project used for format work
  FreesiaII/                 FreesiaII jars and release metadata used as reference
  decompiled/                Decompiled or extracted client/native reference material
```

The formalized repository lives at `D:\Code_Project\Paper-YSM`. The original
prototype copy under `D:\Code_Project\YSMParser\paper-ysm` is kept as source
material until it is no longer needed.

## Build

Requires JDK 21+.

```powershell
gradle build
```

The plugin jar is written to:

```text
build/libs/paper-ysm-0.1.0-SNAPSHOT.jar
```

For the local direct Paper test server, copy the jar into
`test-server/direct-paper/plugins/` after building. The existing local test
servers already contain the current working jar snapshot.

## Test Servers

Use these entry points from the repository root:

```bat
scripts\start-direct-paper.bat
scripts\start-freesiaii-stack.bat
```

Direct PaperYSM test:

- Join `127.0.0.1:30001`.
- Uses `test-server\direct-paper`.
- Primary runtime for PaperYSM work. Use the channel switch script below to
  use the worker/Freesia cache replay path by default.

FreesiaII comparison stack:

- Join through Velocity at `127.0.0.1:30000`.
- Starts `test-server\paper-backend`, `test-server\velocity-proxy`, and
  `test-server\freesia-worker`.
- Useful for fresh captures and behavior comparison against FreesiaII. It is
  not the normal PaperYSM development runtime.

Only run one Paper server bound to `30001` at a time.

The direct Paper test server should normally run the Freesia/worker cache
profile:

```bat
scripts\switch-direct-paper-channel.bat status
scripts\switch-direct-paper-channel.bat freesia
scripts\switch-direct-paper-channel.bat auth-only
```

- `freesia`: direct Paper replays the captured `freesia-latest` native-cache
  fixture after handshake.
- `generated`: still exists as a research profile, but is no longer the normal
  path because Java-generated cache bytes are not accepted by the client yet.
- `auth-only`: Java handshake and authorized model list only; no native cache
  stream.

## Useful Commands

```text
/ysm sync
/ysm models
/ysm models reload
/ysm status [player]
/ysm diagnose [player]
/ysm source <default|all|player> <cacheSource|clear>
/ysm config speed <intervalTicks> <chunkBytes>
/ysm config source <cacheSource>
/ysm debug <on|off>
```

Normal players can run `/ysm sync` for themselves. OPs or users with
`paperysm.admin` can target other players, sync everyone, switch cache source,
and change send speed/debug settings online. `/paperysm` remains an alias for
older scripts, but `/ysm` is the intended command.

Analyze Freesia animation request/broadcast pairs from an extracted capture:

```powershell
gradle analyzeAnimationCapture
gradle analyzeAnimationCapture -PysmAnimationCaptureDir="references/analysis-artifacts/freesia-latest-extracted"
```

Analyze a native-cache fixture and compare its decrypted server-cache payload
against a local `.ysm`:

```powershell
gradle analyzeNativeCacheFixture -PysmNativeCacheFilter="拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm" -PysmNativeCacheLocalModel="test-server/direct-paper/plugins/PaperYSM/models/拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm"
```

The current working native-cache replay source is `freesia-latest`. It is built
from captured FreesiaII cache material and contains the manifest plus cache
entries needed by the client progress bar and server model selection.

To reorganize the direct Paper fixture into readable nested cache folders and
copy any matching latest Freesia worker cache files, run:

```powershell
scripts\sync-worker-native-cache.bat -ReorganizeExisting
```

Use `-DryRun` first to preview changes. The script updates `cache-map.tsv` and
creates a timestamped backup; it does not delete the original cache files.

## Documentation

- `docs/ysm-sync-progress.md`: current protocol progress and flowchart.
- `docs/native-reimplementation.md`: native cache reimplementation notes.
- `docs/prototype-notes.md`: older working README kept for historical detail.

## References

Reference material is intentionally kept inside `references/` so the project can
be studied without jumping between separate folders:

- `references/YSMParser` contains the parser implementation that taught us the
  `.ysm` V3 format, hashed path mapping, compression, and export layout.
- `references/FreesiaII` contains the FreesiaII jars and release metadata used
  to compare packet behavior.
- `references/decompiled` contains decompiled/extracted YSM client material used
  to inspect protocol and native-cache behavior.
