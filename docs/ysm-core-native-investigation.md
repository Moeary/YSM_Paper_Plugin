# ysm-core Native Investigation

## Latest Test Conclusion

The current PaperYSM generated-cache path reaches the native cache transport
states, but the client still does not register the model as a usable GUI/server
model.

The direct Paper log from `2026-05-05 11:13` shows:

- `id=51/52` Java-layer handshake succeeds.
- `id=6` authorized model list is sent with the Laffey model id.
- generated cache starts with `layout=legacy`, `payload=headerless-v3`.
- the client decodes the type3 manifest and replies with native type4
  `value=1`.
- the client requests one cache entry token for
  `拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm`.
- PaperYSM sends all 54 native type5 chunks for that requested token.
- PaperYSM then replays the saved `id=4` model state.

So the current failure is no longer "the sync flow does not start". The
remaining failure is that the bytes inside the requested type5 cache stream are
not the same server-cache model representation that `ysm-core` produces.

## What The Technical Report Adds

The YSM technical report at
`https://www.ysm.rip/blog/yes-steve-model-technical-report` describes the same
high-level network shape we have observed locally:

- YSM uses the CustomPayload channel for the native protocol.
- the first server raw packet is encrypted with a hardcoded 56-byte bootstrap
  key;
- type1/type2 establish S2C and C2S session keys;
- type3 carries cache metadata plus `ServerCacheKey` and `ClientCacheKey`;
- the client requests model cache entries with type4;
- the server streams requested cache chunks with type5;
- downloaded server-cache bytes are not the same as the final client cache file:
  the client decrypts with the server cache key and rewrites its own local cache
  form.

Our Freesia fixture confirms the important part in practice: decrypting the
captured `拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm` server-cache file with the type3
`ServerCacheKey` yields a non-zstd plaintext of `868095` bytes. The analyzer's
`headerlessV3` check also reports `hashOk=false` at both tested offsets, so this
fixture is not merely the local `.ysm` file with the `YSGP` header removed. The
local `.ysm` file variants we tried are much larger and different:

- local `.ysm` source: about `1316458` bytes;
- local headerless encrypted body: about `1314098` bytes;
- local washed zstd payload: about `1313797` bytes;
- generated Paper cache sent in the latest test: about `1314121` bytes.

That size and structure mismatch explains why the client can request the token
and receive type5 chunks but still does not show the model.

## FreesiaII Reality Check

FreesiaII is not a clean-room implementation of the YSM native cache generator.
The useful path is:

1. a real Fabric/Forge YSM runtime is kept alive on the Worker server;
2. the YSM mod loads `ysm-core.dll`;
3. the native core scans server model repositories and emits native raw
   `id=1` packets;
4. FreesiaII relays those opaque payloads through the proxy path to the real
   player.

This means FreesiaII proves the protocol shape and gives us captures, but it
does not contain the missing server-cache packing logic as normal Java code.
That logic lives in `ysm-core`.

## True Worker Runtime Check

The local Fabric worker at `test-server\freesia-worker` has already produced
real `ysm-core` cache files under
`config\yes_steve_model\cache\server`. Comparing those files against the
Freesia-derived PaperYSM fixture shows that the worker's Laffey cache body is
byte-identical to the captured native replay body:

- fixture file:
  `test-server\direct-paper\plugins\PaperYSM\captures\native-cache\freesia-latest\server-cache\b5ebabd1cfc7f2f956acf1fbccdea2f39aca01.bin`
- worker file:
  `test-server\freesia-worker\config\yes_steve_model\cache\server\7ba8305fb6883eb92fac6dba3815dd126eff2395`
- bytes: `868921`
- SHA256:
  `1F26F1E1E9BFDBFF9236B11353712212E0714D7EAEB14B28F95B9F35669615CE`

This proves the real runtime oracle route is viable: let the YSM/Fabric worker
generate the native server-cache body, then have PaperYSM replay/serve it. The
remaining bridge problem is exporting the matching native type3 manifest/token
metadata for arbitrary models.

Use `scripts\probe-worker-native-cache.bat` to rerun this comparison.

## Local DLL Findings

The 2.6.5 Fabric DLL inspected here is:

`references/decompiled/ysm-2.6.5-fabric+mc1.21.1-release.jar.src/META-INF/native/ysm-core.dll`

`dumpbin /exports` reports only three exports:

- `JNI_OnLoad`
- `JNI_OnUnload`
- `YSM_FABRIC_1210_PRODUCTION`

There are no exported `Java_...` native methods. The Java native methods are
registered dynamically from `JNI_OnLoad`.

`dumpbin /headers` shows large `YSMS0`, `YSMS1`, and `YSMS2` sections. Combined
with the technical report's VMProtect note and the almost empty `strings`
output, static string/table search is a poor route for this DLL.

Direct source-launcher loading also fails outside the mod runtime:

```text
scripts\probe-ysm-native-core.bat
YSM native DLL load failed: java.lang.RuntimeException: err: 56
```

The same `err: 56` happens for the local 2.6.2 and 2.6.5 DLLs. That strongly
suggests `JNI_OnLoad` performs runtime/environment checks before native methods
become callable.

## High-Value Java Native Entrypoints

The most relevant decompiled class is:

`references/decompiled/ysm-2.6.5-fabric+mc1.21.1-release.jar.src/com/elfmcys/yesstevemodel/o0oooOoOOoOo0ooOOOOoOoOO.java`

Its useful native declarations are:

```java
public static native void OO0ooo0OooOoO0OoO0ooOO0o(
        String modelId,
        @Nullable String textureId,
        @Nullable Consumer<oO0o0OoOOO0o0oo00oOO00oO> callback);

private static native boolean OO0ooo0OooOoO0OoO0ooOO0o(@Nullable Object callback);

private static native void OO0ooo0OooOoO0OoO0ooOO0o(
        UUID[] playerIds,
        String[] playerNames,
        String[] viewerNames,
        Object callback);

public static native void OO0ooo0OooOoO0OoO0ooOO0o(UUID playerId, ByteBuffer rawC2S);
```

The surrounding Java code shows the likely roles:

- the private boolean call reloads/scans the model repository and returns model
  metadata;
- the `UUID[]` call starts server-side model sync and emits S2C native raw
  packets through Java callback/helper methods;
- the `UUID, ByteBuffer` call ingests C2S native raw packets from a player;
- Java helper methods wrap a direct `ByteBuffer` into the YSM CustomPayload
  packet and send it to that UUID's connection.

If we can run this class in a controlled runtime, it is the shortest path to
real server-cache bytes.

## Recommended Next Route

Stop spending much time on new guessed payload shapes. The latest `headerless-v3`
test already got type4 and all type5 chunks out, which is enough evidence that
the missing piece is not a small envelope flag.

Preferred next steps:

1. Build a Worker-side capture/instrumentation run that copies the exact
   native-generated server-cache bytes for our local Laffey model and records
   the corresponding type3 body.
2. Use that capture to extend `YsmNativeCacheFixtureAnalyzerMain` so it compares
   local `.ysm` stages against a real native-generated server-cache for the same
   model.
3. In parallel, build a minimal native probe only if we can satisfy
   `JNI_OnLoad`'s runtime checks. A bare `System.load` currently fails with
   `err: 56`, so a pure stub JVM harness is unlikely to work without recreating
   more of the Fabric/YSM runtime.
4. If Worker capture is not enough, move to dynamic DLL analysis: run the real
   Worker/YSM server, attach after startup, and intercept Java callbacks or
   native file writes around the model reload/sync native calls.

For PaperYSM itself, the likely product shape remains the same: keep the Paper
plugin pure Java for deployment, but use captured/native-core output as the
oracle until the server-cache packer is understood well enough to port.

See also `docs\paperysm-native-worker-bridge.md` for the direct-DLL versus
real-worker bridge decision.
