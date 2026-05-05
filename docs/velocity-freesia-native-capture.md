# Velocity Freesia Native Capture

This is the fallback path when Paper-generated type3 manifests do not make the
YSM client answer with type4.

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
Use the longer script when you want to pass explicit capture paths.

Explicit form:

```bat
scripts\export-freesia-native-fixture.bat ^
  test-server\velocity-proxy\logs\latest.log ^
  test-server\direct-paper\plugins\PaperYSM\captures\native-cache\freesia-from-velocity ^
  test-server\velocity-proxy\plugins\ysm-sniffer-captures
```

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
