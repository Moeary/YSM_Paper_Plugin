# Velocity Freesia Native Capture

This is the default import path for new PaperYSM cache fixtures. Paper-generated
or worker-order-paired type3 manifests are research-only until they match the
real native token map.

## What Gets Captured

- Freesia debug logging provides the real S2C native packets in
  `test-server/velocity-proxy/logs/latest.log`. Older extracted fixtures may
  also exist under `test-server/velocity-proxy/plugins/freesia-debug-capture`.
- `ysm-sniffer` captures raw Velocity plugin messages in both directions:
  `test-server/velocity-proxy/plugins/ysm-sniffer-captures`.
- Native sync type3 is still an outer `id=1` packet. The decoded inner packet
  type is `3`, so look for S2C `id01` files, not a raw file named `id03`.

## Clean Capture Recipe

1. Start the Velocity/Freesia worker stack with `ysm-sniffer-0.1.0.jar` enabled.
2. Join through Velocity with a YSM client.
3. Trigger Freesia worker cache sync while the client cache is clean enough to
   request the models you want.
4. Export a PaperYSM native-cache fixture. The default command reads
   `latest.log` for S2C packets and `ysm-sniffer-captures` for complete C2S
   packets:

```bat
scripts\sync-velocity-cache-to-paper.bat
```

This is a friendly wrapper around `scripts\export-freesia-native-fixture.bat`.
When `plugins\ysm-sniffer-captures\index.tsv` exists, the exporter now reads
that binary sniffer capture directly and does not need Freesia debug hex logs.
Use the longer script when you want to pass explicit capture paths.
The Python menu exposes the same path as `scripts\paperysm.bat export-capture`.

Explicit form:

```bat
scripts\export-freesia-native-fixture.bat ^
  test-server\velocity-proxy\plugins\ysm-sniffer-captures ^
  test-server\direct-paper\plugins\PaperYSM\captures\native-cache\freesia-from-velocity
```

## Sniffer Performance

The sniffer is meant to replace verbose Freesia hex logging during capture. The
current `ysm-sniffer-0.1.1` uses PacketEvents when available, so it can capture
Freesia Velocity plugin-originated S2C `id=1` packets as binary files instead of
requiring Freesia to print huge hex dumps. It writes YSM `id=1/id=2` packets
plus `index.tsv`, and does not log per-packet hex by default.

Velocity config file:

```text
plugins\ysm-sniffer.properties
```

Recommended capture settings:

```properties
capture-root=plugins/ysm-sniffer-captures
write-packets=true
reset-on-start=true
log-packets=false
log-preview-bytes=0
capture-only-native=true
packet-events=true
```

With those settings, keep `packetevents-velocity` installed and disable Freesia
debug hex logging if possible. Start the Velocity/Freesia stack, let one clean
client complete sync, then run `scripts\sync-velocity-cache-to-paper.bat`; it
will export `type3-body.bin`, `cache-map.tsv`, and `server-cache/**` from the
sniffer binary capture.

Build/install the local sniffer jar with:

```bat
scripts\install-velocity-sniffer.bat
```

Restart Velocity after installing it. If the log says PacketEvents listener is
unavailable, the sniffer will fall back to Velocity `PluginMessageEvent`, which
is not enough to see Freesia plugin-originated S2C type3/type5 packets.

## Paper Test

After export, switch the direct Paper test server to the captured fixture:

```text
/ysm source default freesia-from-velocity
/ysm sync
```

The exporter writes `export-report.tsv`. A model is only usable by Paper replay
when its row has a non-empty file path and `gaps=0`. If most rows are missing,
the client already had those cache entries and did not request their type5
payloads during the capture.

Do not repair missing rows by pairing worker `cache/server` files by order. The
type3 token, `cache-map.tsv` row, and cache body need to come from the same real
Freesia run. If the fixture is incomplete, clear the client YSM cache and
capture again.

## Animation Models

Do not store animation metadata beside cache `.bin` files. PaperYSM resolves
wheel and nested-wheel animations by scanning `.ysm` files from its configured
`models-dir`.

For local testing, keep `plugins\PaperYSM\config.yml` as:

```yaml
models-dir: ../../../freesia-worker/config/yes_steve_model/custom
```

This points PaperYSM at the worker's real model folder:

```text
test-server\freesia-worker\config\yes_steve_model\custom
```

Avoid linking external collections under that worker `custom` folder. The YSM
client browser exposes those relative link paths as `../../..`, so the current
workflow is to copy model groups into `custom\<group>` before starting the
worker.

```text
scripts\paperysm.bat copy-models "D:\BaiduNetdiskDownload\YSM模型（尽快保存下载） (2)\游戏IP分类" --group "游戏IP分类"
```

When copying PaperYSM to another server, copy the native-cache fixture and the
matching `.ysm` model tree together; cache files alone are enough for model
sync, but not enough for reliable custom animation mapping.
