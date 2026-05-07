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
- Wheel/custom animation forwarding resolves actions from scanned `.ysm`
  profiles, including nested wheel/classify entries. Keep the corresponding
  `.ysm` files available under the PaperYSM `models` directory.

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

Use Pixi from the repository root. Pixi provides the Gradle/JDK toolchain used
by the local tasks.

```powershell
pixi run build
pixi run check
```

The plugin jar is written to:

```text
build/libs/paper-ysm-0.1.0-SNAPSHOT.jar
```

For the local direct Paper test server:

```powershell
pixi run deploy-direct
pixi run run-direct
```

`run-direct` builds, deploys the jar, and starts the direct Paper server in the
current terminal. `server-direct` only starts the server.

## Test Servers

Use these entry points from the repository root:

```powershell
pixi run run-direct
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

- `freesia`: direct Paper replays the captured `freesia-from-velocity` native-cache
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

The current working FreesiaII replay source is `cache/freesia-from-velocity`.
It is built from captured FreesiaII cache material and contains the manifest
plus cache entries needed by the client progress bar and server model
selection. The Paper-generated route writes its test artifacts under
`cache/openysm`.

## Model Directory For Animations

PaperYSM keeps model files under `plugins/PaperYSM/models`. Native cache
fixtures and generated type3/type5 artifacts live under `plugins/PaperYSM/cache/<channel>`.
Wheel and nested-wheel animation mapping still comes from decoded `.ysm`
profiles in the model directory:

```text
plugins\PaperYSM\models
```

Do not expose large external model libraries through junctions/symlinks. The
YSM client browser shows the real relative path and becomes awkward to use.
Copy model groups into the worker `custom` directory instead, then make a real
FreesiaII/Velocity capture and export that capture into the Paper fixture.

When moving the setup to another server, copy both the Paper native-cache
fixture and any matching `.ysm` model tree used for animation mapping. The
cache fixture alone is enough for model sync; missing `.ysm` references only
mean some custom wheel animations cannot be resolved. OPs can change the
reference directory online:

```text
/ysm config modeldir <path>
```

The normal import path is capture-first. Start the full FreesiaII comparison
stack, join through Velocity with a YSM client, let Freesia complete native
sync, then export the real type3/type5 bundle:

```powershell
scripts\start-freesiaii-stack.bat
scripts\paperysm.bat export-capture
```

`scripts\sync-velocity-cache-to-paper.bat` is the same capture export wrapped in
a shorter batch file. For a full portable fixture, clear the client's YSM cache
before joining Velocity; otherwise Freesia may only send type5 chunks for models
the client was missing in that run.

The old worker-cache batch tool is now research-only. It can snapshot worker
`cache/server` and copy newly generated bin files, but it cannot safely invent
the matching type3 token map. Direct export is blocked unless you pass the
explicit unsafe flag:

```powershell
scripts\export-worker-cache-batch.bat snapshot -SnapshotName default-clean
scripts\export-worker-cache-batch.bat export -Group "R18模型整合" -SnapshotName default-clean --unsafe-order-pair
```

Use that only for protocol experiments. The supported way to make Paper clients
show new models is still the Velocity/Freesia capture exporter.

For day-to-day local work, `scripts\paperysm.bat` wraps the common workflow in a
single Python menu:

```powershell
scripts\paperysm.bat
```

The same tool also supports direct commands such as `status`, `copy-models`,
`start-stack`, `export-capture`, `type3-inspect`, `start-worker`, and
`start-paper`.

## Documentation

- `docs/ysm-sync-progress.md`: current protocol progress and flowchart.
- `docs/paperysm-production-deploy.md`: copying PaperYSM to a real Paper server
  and removing FreesiaII from a Velocity stack.
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
