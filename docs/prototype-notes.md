# PaperYSM

PaperYSM is a Paper-side prototype for talking to the Yes Steve Model client
protocol without running the original YSM server mod.

Current scope:

- Registers the observed YSM plugin message channel: `yes_steve_model:2_6_0`.
- Sends the YSM `51` handshake packet with protocol version `2.6.0`.
- Accepts the YSM `52` client handshake response.
- Tracks which players have a compatible YSM client.
- Exposes `/ysm status`, `/ysm status <player>`, `/ysm handshake <player>`, and `/ysm debug`.
- Exposes `/ysm native` and `/ysm native selftest` for the no-worker reimplementation path.
- Scans local encrypted `.ysm` files from `plugins/PaperYSM/models` with `/ysm models`.
- Supports in-game model repository reload with `/ysm models reload`, so admins can add `.ysm` files without stopping the Paper server.
- Prepares decrypted V3 payloads as internal chunked distribution packages with `/ysm dist`.
- Captures client `id=2` raw/native packets under `plugins/PaperYSM/captures/raw` for finishing the no-worker protocol.
- Can send the observed `id=4` entity model-state packet with `/ysm apply <player> <modelId> [textureId]`.

What this does not do yet:

- It does not render models for vanilla clients. Players still need a compatible
  YSM client mod.
- It does not yet send model cache/data packets. Those packet bodies appear to
  be produced or consumed by the original native `ysm-core` layer. The next
  step is to reproduce those packet payloads with the algorithms already present
  in the parent YSMParser project.
- `/ysm apply` only changes the model state on clients that already know
  the referenced model id. Full no-worker model distribution still requires
  reproducing the raw/native cache synchronization packets.

Build with the local Gradle distribution:

Requires JDK 21+. The checked-in Gradle config points at `D:/Programs/JDK22`.

```powershell
& "C:\Users\minec\.gradle\wrapper\dists\gradle-8.12.1-bin\eumc4uhoysa37zql93vfjkxy0\gradle-8.12.1\bin\gradle.bat" build
```

Maven fallback:

Requires Maven.

```bash
mvn package
```

The plugin jar is written to `target/paper-ysm-0.1.0-SNAPSHOT.jar`.
Gradle writes the plugin jar to `build/libs/paper-ysm-0.1.0-SNAPSHOT.jar`.
For the included Paper test server, close the server and run `install-direct-paperysm.bat`
after building to copy the jar into `test-server/plugins` and clear Paper's remap
cache.

Runtime model repository:

- By default `models-dir: "models"` resolves under the plugin data folder, so the server scans `plugins/PaperYSM/models`.
- Nested folders are allowed. A file such as `plugins/PaperYSM/models/author/foo.ysm` becomes model id `author/foo`.
- Startup scans run when `scan-models-on-enable: true`.
- Admins can run `/ysm models reload` in game or from console after adding models. This rebuilds the repository without restarting the server.
- `/ysm models` prints the active root, loaded count, failed count, a few loaded model ids, and early failures.

Local test servers:

- `test-server` is the direct PaperYSM test server. Join it directly on `127.0.0.1:30001`; Paper Velocity support is disabled and the Freesia Backend jar is renamed with a `.disabled-for-backend-only` suffix.
- `test-server-backend` is the Paper backend for the FreesiaII capture stack. It keeps Paper Velocity support enabled and has PaperYSM renamed with a `.disabled-for-freesiaii-current` suffix.
- `start-paperysm-direct.bat` starts only the direct PaperYSM test server.
- `start-freesiaii-stack.bat` starts `test-server-backend`, `test-server-velocity`, and `test-server-worker`. Join that stack through Velocity on `127.0.0.1:30000`.
- Both Paper servers currently use `server-port=30001`, so run only one of them at a time.

Distribution staging:

- `distribution.prepare-on-reload: true` prepares every successfully scanned V3 `.ysm` into an in-memory transfer package.
- Transfer chunks currently use the washed-zstd payload, while decompressed payload SHA-256 and size are kept for validation. This keeps memory and network cost much closer to YSM's native cache path than chunking the fully decompressed model.
- `/ysm dist status` shows the prepared package count, chunk count, transfer bytes, decompressed bytes, and early failures.
- `/ysm dist prepare` rebuilds all prepared packages from the current model repository.
- `/ysm dist prepare <modelId>` prepares one loaded model on demand.
- `/ysm dist auth [player]` sends the Java-layer `id=6` authorized model list. The original YSM server sends this after the `51/52` handshake.
- `/ysm dist diagnose [player]` prints the prepared package state plus per-player sync counters, including whether `id=6` auth was sent and whether any S2C `id=1` raw/native packets have actually gone out.
- `/ysm dist replay <player> <captureNameOrFile> [fast|freesia]` can replay captured S2C `id=1` raw packet bodies from `sync.raw-replay-dir` when `sync.enable-raw-replay: true`. It accepts raw `.bin` bodies and Freesia debug `.log/.txt` lines such as `S2C Packet Data (... bytes): HEX...`, automatically extracting only full `id=1` packets with a valid native hash. `fast` streams every packet at `sync.raw-replay-interval-ticks`; `freesia` sends the first two packets only after the client answers with C2S `id=2`, matching the observed Worker bootstrap cadence. If the second C2S reply does not arrive before `sync.raw-replay-handshake-timeout-ticks`, the remaining packets are not streamed. This is a controlled reverse-engineering aid, not the normal distribution path.
- `/ysm dist clear` clears in-memory prepared packages.
- `distribution.write-cache-files: false` keeps decrypted payloads memory-only. Set it to `true` only on a controlled test server when byte-for-byte native comparisons are needed; files are written under `distribution.cache-dir`.
- This is the staging layer for ordinary-player distribution. The remaining protocol work is to wrap these prepared chunks in the exact YSM native/raw packet sequence expected by the client.

Raw/native capture:

- `capture.client-raw-packets: true` stores client `id=2` raw packet bodies in `plugins/PaperYSM/captures/raw`.
- These captures are important because the decompiled YSM Java layer passes raw packet bodies directly into native code; finishing no-worker distribution depends on reproducing that native packet state machine.
- The current blind bootstrap/manifest probes can prove whether the client accepts our generated native stream. If `dist probe` and `dist streamprobe` leave `c2sRaw=0/0b`, switch to a real Freesia/original capture instead of adding more guesses.
- For a Freesia fixture, enable Freesia debug, trigger a successful worker model sync, copy the relevant log to `plugins/PaperYSM/captures/replay/<name>.log`, set `sync.enable-raw-replay: true`, then run `/ysm dist replay <player> <name>.log freesia`. PaperYSM will parse Freesia `S2C Packet Data` hex dumps and replay only full YSM `id=1` raw/native payloads.
- A pre-extracted Freesia replay directory can also be generated with `gradle -p paper-ysm analyzeFreesiaCapture -PfreesiaLog="<velocity latest.log>" -PfreesiaDumpDir="<PaperYSM data>/captures/replay/<name>"`. The directory form keeps packet ordering stable and avoids parsing large wrapped logs during in-game tests.
- The current local fixture is `plugins/PaperYSM/captures/replay/freesia-latest`; after installing the plugin on the Paper test server and joining with a YSM client, run `/ysm dist replay <player> freesia-latest freesia`.
- If `/ysm dist prepare` succeeds but the model does not appear in the YSM UI, that is expected until the native/raw cache sync is implemented. `dist auth` only advertises model ids; it does not deliver model bytes.
- When a compatible player joins and prepared packages exist, PaperYSM logs a sync diagnostic warning if auth has been sent but the native `id=1` stream is still missing. In that state the client will not show a download progress bar or new roulette entry.

Operational logging:

- Model scans log the root path, whether it exists, startup vs command trigger, loaded count, failure count, and duration.
- With `logging.model-scan-details: true`, each loaded `.ysm` logs its model id, source file, YSM version, format, encrypted size, decompressed size, trailing payload bytes, and scanner summary.
- Failed `.ysm` probes are always logged with the file path and exception message.
- `/ysm apply` logs the command sender, target player, entity id, model id, texture id, compatible viewer count, payload size, and a configurable hex preview.
- Session status now includes `auth`, `s2cRaw`, and `c2sRaw` counters so you can see whether the client-visible cache sync layer has actually started.
- Client `id=2` raw/native packets log player name, byte length, capture file, and a configurable hex preview.
- Replayed server `id=1` raw/native packets log player name, byte length, replay source, and a configurable hex preview.
- Packet detail logs can be reduced with `logging.packet-details: false` or shortened with `logging.packet-hex-preview-bytes`.

Offline encrypted model probe:

This verifies real V3 `.ysm` files through the Java jar path: V3 header
parsing, file hash verification, resource-layer modified XChaCha decrypt,
MT19937 XOR, nonce stripping, YSM-modified Zstd frame washing, Zstd
decompression, and decompressed payload format validation.

```powershell
& "C:\Users\minec\.gradle\wrapper\dists\gradle-8.12.1-bin\eumc4uhoysa37zql93vfjkxy0\gradle-8.12.1\bin\gradle.bat" probeYsmModels -PysmProbeInput="<file-or-directory>"
```

The same probe can run directly from the built jar:

```powershell
& "D:\Programs\JDK22\bin\java.exe" -cp .\build\libs\paper-ysm-0.1.0-SNAPSHOT.jar com.ysm.paper.nativebridge.crypto.YsmArchiveProbeMain "<file-or-directory>"
```

Offline distribution preparation probe:

This verifies the next staging layer: decompressed payload SHA-256, zstd payload
SHA-256, and chunk planning.

```powershell
& "C:\Users\minec\.gradle\wrapper\dists\gradle-8.12.1-bin\eumc4uhoysa37zql93vfjkxy0\gradle-8.12.1\bin\gradle.bat" prepareYsmModels -PysmPrepareInput="<file-or-directory>" -PysmPrepareLimit=3
```

Optional dump flags write debug artifacts for byte-for-byte comparisons:

```powershell
& "D:\Programs\JDK22\bin\java.exe" -cp .\build\libs\paper-ysm-0.1.0-SNAPSHOT.jar com.ysm.paper.nativebridge.crypto.YsmArchiveProbeMain "<file-or-directory>" --limit 1 --dump-zstd .\build\probe-dumps\zstd --dump-decompressed .\build\probe-dumps\decompressed
```

Crypto self-test:

```powershell
& "C:\Users\minec\.gradle\wrapper\dists\gradle-8.12.1-bin\eumc4uhoysa37zql93vfjkxy0\gradle-8.12.1\bin\gradle.bat" cryptoSelfTest
```

Raw/native packet codec self-test:

This verifies the reconstructed native packet plaintext envelope used by
`VerifyAndDecryptPacket`: two-byte padding header, packet type VarInt, key
fields for types `1` and `2`, type `4` marker parsing, and packet encryption
roundtrip for body-only native frames.

```powershell
& "C:\Users\minec\.gradle\wrapper\dists\gradle-8.12.1-bin\eumc4uhoysa37zql93vfjkxy0\gradle-8.12.1\bin\gradle.bat" rawPacketSelfTest
```

C++ vector verification:

The Gradle task compiles the small C++ vector generator, writes a fresh vector
under `build/generated-test-resources/`, then checks the Java port against it.

```powershell
& "C:\Users\minec\.gradle\wrapper\dists\gradle-8.12.1-bin\eumc4uhoysa37zql93vfjkxy0\gradle-8.12.1\bin\gradle.bat" verifyCppVectors
```

To refresh the checked-in fixture manually:

```powershell
New-Item -ItemType Directory -Force -Path .\paper-ysm\build\crypto-vectors
New-Item -ItemType Directory -Force -Path .\paper-ysm\src\test\resources
g++ -std=c++20 -O2 `
  -I .\external\cityhash\src `
  -I .\external\xchacha20\src `
  .\YSMParser\tools\YSMCryptoVectors.cpp `
  .\external\cityhash\src\city.cc `
  .\external\xchacha20\src\xchacha20.cc `
  -o .\paper-ysm\build\crypto-vectors\YSMCryptoVectors.exe
.\paper-ysm\build\crypto-vectors\YSMCryptoVectors.exe .\paper-ysm\src\test\resources\ysm-crypto-vectors.json
```

Protocol notes:

- Channel: `yes_steve_model:2_6_0`
- Packet envelope: first byte is the YSM subpacket id.
- Server handshake: subpacket `51`, followed by Minecraft/FriendlyByteBuf UTF string `2.6.0`.
- Client handshake response: subpacket `52`, followed by the same UTF string.
- Server raw/native packet wrapper: subpacket `1`, followed by the native packet body.
- Client raw/native packet wrapper: subpacket `2`, followed by the native packet body.
- Entity model state update: subpacket `4`, followed by target entity id as VarInt, model id UTF, texture id UTF, a boolean flag, and a compact entity-state body. FreesiaII treats this body as binary for YSM `2.6.x` and forwards it without decoding.
- Authorized model list: subpacket `6`, followed by a VarInt count and UTF model ids. This is a Java-layer permission/list packet, not the model cache payload.

## FreesiaII Takeaways

FreesiaII does not make a Paper backend generate every YSM native/cache packet by itself. Its proxy creates a hidden Minecraft session for each real player and connects that session to a Fabric Worker running the original YSM mod. The Worker emits normal YSM packets, and the proxy forwards or rewrites them.

The useful part for this project is the split:

- `id=51/52` handshake can be done directly by Paper for client detection.
- `id=4` entity-state packets can be replayed by Paper if we already have the opaque YSM entity-state body.
- The harder `id=1/2` raw/native synchronization path can be delegated to a temporary Worker, or later replaced with YSMParser-derived packet generation.

## No-Worker Reimplementation Track

The project now has a `YsmNativeBridge` boundary for replacing Freesia's Worker
with our own implementation. The first target is not client rendering; it is the
server-side subset of `ysm-core`:

- packet encrypt/decrypt and rolling key update,
- cached model decrypt/verify,
- model entity-state body generation,
- optional JNI bridge to the parent YSMParser C++ implementation while the Java
  port is being written.

Current Java implementation status:

- YSM-modified CityHash64 with seed support.
- MT19937-64 XOR layer.
- XChaCha20 with variable round counts.
- Packet encrypt/verify/decrypt roundtrip.
- Cached model verify/decrypt path up to the decrypted model payload.
- C++-generated vector verification for CityHash, MT19937 XOR, XChaCha30,
  modified ChaCha, packet encrypt/decrypt, and filename hash derivation.
- Real encrypted V3 `.ysm` archive probe through the built jar. The
  `星屑海螺` sample directory was checked with 49/49 successful files,
  including actual Zstd decompression.
- Internal Paper-side distribution staging for decrypted V3 archives, including
  decompressed SHA-256 identities and washed-zstd fixed-size transfer chunks.
- Client `id=2` raw/native packet capture for the next protocol-replay step.
- Per-player sync diagnostics now distinguish Java-layer auth (`id=6`) from
  native cache sync (`id=1/2`), which is why prepared/authenticated models still
  do not appear in the roulette until the raw stream exists.
- A guarded raw replay hook can send captured S2C `id=1` bodies from
  `plugins/PaperYSM/captures/replay` for controlled comparison against original
  YSM/Freesia traffic.
- `YsmRawPacketCodec` can build and decode the native packet plaintext envelope
  for packet types `1`, `2`, `3`, and `4`, and has a Gradle self-test for the
  body-only packet crypto path.
- Decompressed V3 payload structure scanning now counts models, bones,
  animations, controllers, textures, sounds, sub-entities, and trailing bytes.
- Paper plugin-side encrypted model repository scan is wired to
  `/ysm models`, so server testing can validate local `.ysm` files without
  leaving the plugin jar.
- The Paper protocol layer can now produce the observed `id=4` model-state
  packet directly for `/ysm apply`.
- The Java-decompressed `星屑海螺..ysm` payload was SHA256-matched against
  C++ `YSMParser.exe -d` output:
  `F5E9CB97765B8CBDC74301E943AF8FFF25424E5BB12206C2FD21161D834F0040`.
