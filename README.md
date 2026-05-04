# Paper-YSM

Paper-YSM is a Paper-side reimplementation prototype for the Yes Steve Model
server distribution path. It can handshake with YSM 2.6.x clients, expose the
authorized model list, replay a captured FreesiaII-style native cache stream,
and apply YSM model state to compatible viewers.

The current priority is protocol correctness and repeatable local testing, not
production deployment.

## Current Status

- YSM `51/52` Java-layer handshake works on channel `yes_steve_model:2_6_0`.
- Authorized model list packet `id=6` is generated from local `.ysm` files.
- V3 `.ysm` parsing and cache preparation use logic from the YSMParser project.
- Native cache sync can be replayed from captured FreesiaII cache material.
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
- Useful for testing PaperYSM without Velocity/FreesiaII.

FreesiaII comparison stack:

- Join through Velocity at `127.0.0.1:30000`.
- Starts `test-server\paper-backend`, `test-server\velocity-proxy`, and
  `test-server\freesia-worker`.
- Useful for fresh captures and behavior comparison against FreesiaII.

Only run one Paper server bound to `30001` at a time.

## Useful Commands

```text
/paperysm status
/paperysm models
/paperysm models reload
/paperysm dist diagnose [player]
/paperysm dist nativecache <player> freesia-latest 1 59926
/paperysm apply <player> <modelId> [textureId]
```

The current working native-cache replay source is `freesia-latest`. It is built
from captured FreesiaII cache material and contains the manifest plus cache
entries needed by the client progress bar and server model selection.

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
