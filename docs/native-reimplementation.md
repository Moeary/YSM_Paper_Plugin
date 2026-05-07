# Native Reimplementation Plan

Goal: replace the Freesia-style Worker relay with a local implementation of the
server-side subset of YSM's native `ysm-core` behavior.

## What We Need First

The Paper plugin does not need client rendering code. It needs the pieces that
let a server accept and distribute model state to an already-installed YSM
client:

- YSM channel handshake: implemented in `YsmProtocol`.
- Entity state packet `id=4`: implemented as an opaque body wrapper.
- Packet/cache crypto: already partially reproduced in the parent C++ project.
- Entity-state body generation from `.ysm` model packs: still missing.

## Decompiled 2.6.5 Protocol Observations

The Fabric/NeoForge Java layer registers one versioned custom payload channel:

- Channel id: `yes_steve_model:2_6_0`
- Packet `1`: server-to-client raw/native `ByteBuffer`
- Packet `2`: client-to-server raw/native `ByteBuffer`
- Packet `4`: server-to-client entity model-state update
- Packet `6`: server-to-client authorized model id set
- Packet `51`: server-to-client version handshake
- Packet `52`: client-to-server version handshake response

Packets `1` and `2` are intentionally opaque in Java. The handlers only copy the
remaining bytes into a direct `ByteBuffer` and pass them into the native sync
manager:

- S2C `1` calls the client native model sync path.
- C2S `2` calls the server native model sync path with the player's UUID.

This means the Paper plugin should not send raw decrypted model bytes directly
to clients. It needs to reproduce the native packet state machine around the
prepared model payloads.

The original server handles a client `52` by sending Java-layer player sync
packets and then entering native model sync:

- send the authorized model id set (`id=6`) when player auth metadata exists,
- update tracked entity model state,
- invoke native server sync for this player and nearby compatible players.

PaperYSM now implements the first Java-layer piece (`id=6`) and records it in
per-player diagnostics. The user's in-game test confirmed this layer: the server
log showed `YSM authorized model list sent`, but no progress bar or roulette
entry appeared. That is the expected failure mode while the native/raw S2C
`id=1` cache stream is still absent.

The Paper implementation now captures C2S `id=2` bodies under
`plugins/PaperYSM/captures/raw` and can optionally replay known-good S2C `id=1`
bodies from `plugins/PaperYSM/captures/replay` with
`sync.enable-raw-replay: true`. Replay accepts raw `.bin` bodies and Freesia
debug `.log/.txt` S2C hex dumps, stripping the outer YSM `id=1` byte when
present. Replay is only for controlled fixture comparison while the local sender
is rebuilt.

FreesiaII's proxy path is useful for fixture capture because its Worker session
receives `ClientboundCustomPayloadPacket` packets from the hidden Fabric server,
then forwards YSM channel payloads to the real player. Its protocol properties
only enumerate the Java-visible ids (`3`, `4`, `21`, `51`, `52`, `17`, `7`);
the native `id=1/2` payloads are passed through as opaque YSM channel data.
When Freesia debug is enabled, `MapperSessionProcessor` logs the full
`S2C Packet Data` hex dump before forwarding, which is enough for PaperYSM's
controlled replay importer.

## Native Methods Seen In The Decompiled Mod

The highest-value server-side native calls are in the original model sync
manager:

- scan/load model packs from server directories,
- handle C2S raw packet body from the client,
- generate S2C raw packet bodies for clients,
- synchronize selected model data for UUID/name/model triples.

Rendering native calls are intentionally out of scope for Paper.

## Reimplementation Order

1. Expose or port packet/cache algorithms from `YSMParser/algorithms/CryptoAlgorithms.cpp`. Done for the core packet/cache crypto subset in Java.
2. Generate deterministic test vectors for:
   - `EncryptPacket`,
   - packet verify/decrypt of generated encrypted packets,
   - `DeriveHashFromFileName`,
   - modified XChaCha,
   - MT19937 XOR,
   - CityHash64 with YSM constants.
   Done through `verifyCppVectors`; `DecryptCachedModel` still needs a real cached model fixture.
3. Add a JNI implementation of `YsmNativeBridge` as a quick correctness bridge if Java diverges from captured native behavior.
4. Reverse the entity-state body format enough to build `id=4` payloads without
   Worker.
5. Stage server-side decrypted model payloads in a transfer-friendly form.
   Done for V3 `.ysm` archives using washed-zstd chunks and decompressed
   SHA-256 validation.
6. Reconstruct the native raw packet envelope and key-carrying packet types.
   `YsmRawPacketCodec` handles plaintext padding, packet type VarInt,
   type `1`/`2` keys, OpenYSM-style type `3`, type `4` requests, and type `5`
   chunks. The generated route now derives model hashes from local `.ysm`
   metadata, builds ServerCacheKey-encrypted server-cache bodies, and sends
   them without a Freesia cache file. Runtime generation is scheduled off the
   Paper main thread and automatic join sync defaults to the player's saved
   model instead of preparing every repository entry.
7. Use captured C2S `id=2` packets to finish the native/raw model cache
   request/response sequence.
8. Replace cached Worker bodies with locally generated bodies. The first
   Paper-only implementation is in place via the `openysm/server-cache`
   generated-cache route; real-client validation should focus on older formats
   and models that require reserialization to format 32.

## Practical Milestones

- Milestone A: handshake confirms YSM clients on Paper.
- Milestone B: Paper can replay a captured `id=4` state body to a target entity.
- Milestone C: Paper can decrypt and inspect cached model packets.
- Milestone D: Paper can generate the state body for a known model path.
- Milestone E: Paper can distribute server-side model packs without a Worker.

## Current Test Coverage

- `cryptoSelfTest`: Java-only packet roundtrip and filename hash smoke test.
  It also verifies native cached-model encrypt/decrypt roundtrip.
- `rawPacketSelfTest`: Java native raw packet envelope and body-only crypto
  smoke test for packet types `1`, `2`, and `4`.
- `verifyCppVectors`: compiles `YSMParser/tools/YSMCryptoVectors.cpp`, generates
  C++ vectors from the vendored CityHash/XChaCha implementations, and verifies
  the Java port byte-for-byte against those vectors.
- `probeYsmModels`: runs the built Java implementation against real encrypted
  V3 `.ysm` archives. It currently verifies header format, file integrity hash,
  resource-layer decrypt, nonce removal, YSM-modified Zstd frame structure,
  actual Zstd decompression, and decompressed payload format. It does not yet
  deserialize the decompressed model tables.
- `prepareYsmModels`: runs the same V3 extraction path and prepares the
  washed-zstd payload as fixed-size transfer chunks while keeping decompressed
  payload hashes for validation.

## Native Core Probe Status

`scripts\probe-ysm-native-core.bat` records the current DLL evidence:

- `ysm-core.dll` only exports `JNI_OnLoad`, `JNI_OnUnload`, and a build marker.
- the Java native methods are therefore registered dynamically by `JNI_OnLoad`.
- the DLL contains large `YSMS0`/`YSMS1`/`YSMS2` sections, matching the
  VMProtect/virtualized-code expectation from the technical report.
- direct `System.load` outside the YSM mod runtime fails with
  `java.lang.RuntimeException: err: 56` for the local 2.6.2 and 2.6.5 DLLs.

That makes a pure standalone JNI harness unlikely to work until the runtime
check is understood or the real Fabric/Forge YSM environment is used as the
host. The next useful oracle should come from Worker-side capture or dynamic
instrumentation of the real YSM server runtime, not more guessed type5 payload
shapes.

`scripts\probe-worker-native-cache.bat` confirms the worker-oracle direction:
the local `test-server\freesia-worker` has a native-generated Laffey
server-cache file whose SHA256 matches the Freesia replay fixture exactly. See
`docs\paperysm-native-worker-bridge.md` for the proposed bridge shape.

## Current Real-Model Validation

Using `D:\BaiduNetdiskDownload\YSM模型（尽快保存下载） (2)\YSM各作者付费模型\星屑海螺`:

- 49 `.ysm` archives detected.
- 49/49 passed Java jar probe.
- 49/49 passed washed-zstd distribution preparation.
- Full sample transfer staging size: 175,550,436 bytes in 7,168 chunks at the
  default 24,576 byte chunk size.
- Full sample decompressed validation size: 916,608,585 bytes.
- Formats covered include 1, 4, 9, 15, 26, 28, and 31.
- `星屑海螺..ysm` Java dump matched C++ `_debug_m_decompressed.bin` by SHA256:
  `F5E9CB97765B8CBDC74301E943AF8FFF25424E5BB12206C2FD21161D834F0040`.

