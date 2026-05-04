# AGENTS.md

## Repository Expectations

- Prefer CRLF line endings on Windows.
- Keep changes focused on PaperYSM protocol behavior, local test harnesses, or
  documentation unless the user asks for broader cleanup.
- Do not delete captured protocol material, local cache fixtures, or reference
  jars without explicit approval.
- Treat `references/` as read-mostly source material.

## Project Overview

Paper-YSM is a Java Paper plugin prototype for reproducing the Yes Steve Model
server-side distribution flow without requiring the original FreesiaII worker.

Main runtime paths:

- Direct Paper test server with PaperYSM only.
- Velocity/FreesiaII comparison stack for observing the reference behavior.
- Paper backend connected through Velocity.
- Fabric worker server used by FreesiaII.

## Key Code Areas

- `src/main/java/com/ysm/paper/PaperYsmPlugin.java`: command handling, player
  state, handshake, model state, raw/native replay orchestration.
- `src/main/java/com/ysm/paper/nativebridge/`: native-cache and raw packet
  codec helpers.
- `src/main/java/com/ysm/paper/model/`: local `.ysm` model repository and
  distribution package preparation.
- `ysm-sniffer/`: Velocity-side packet capture helper.

## Test Server Layout

Formal repository layout:

- `test-server/direct-paper`: standalone PaperYSM test server.
- `test-server/paper-backend`: Paper backend used by the proxy stack.
- `test-server/velocity-proxy`: Velocity + FreesiaII proxy.
- `test-server/freesia-worker`: Fabric YSM/Freesia worker.

Only one Paper server should bind `127.0.0.1:30001` at a time.

## Current Protocol Notes

- Java-layer YSM handshake and `id=6` authorized model list work.
- FreesiaII-style native cache replay can trigger the client sync progress bar
  and enable server model selection.
- Two-client model visibility works after each client has completed native-cache
  sync and selected a server model.
- Wheel/custom animations are not stable yet. Recent testing suggests the
  client-visible wheel entry may not map directly to the emitted animation id.
  Do not "fix" this by guessing ids; compare against FreesiaII captures first.

## Recommended Workflow

- Read `docs/ysm-sync-progress.md` before touching sync or packet code.
- For parser/export questions, inspect `references/YSMParser` first.
- For reference behavior, compare against `references/FreesiaII` and captured
  logs/cache material.
- Build with `gradle build` when changing Java code.
- Prefer running protocol self-tests before server testing when codec logic
  changes.
