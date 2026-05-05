# PaperYSM 正式服迁移清单

这份清单针对“Velocity 群组服里移除 FreesiaII，改成 Paper 后端直接跑
PaperYSM”的部署方式。

## 要复制的文件

目标 Paper 服务器根目录下需要这些文件：

```text
plugins/
  paper-ysm-0.1.0-SNAPSHOT.jar
  PaperYSM/
    config.yml
    captures/native-cache/<source>/
      type3-body.bin
      type1-padding.txt
      type3-padding.txt
      cache-map.tsv
      server-cache/**
    models/**              # 可选，用于轮盘/多级动画映射
    player-models.yml      # 可选，用于保留本地测试服的玩家选择记录
```

`captures/native-cache/<source>` 必须来自真实 Velocity/Freesia 捕获导出。
如果 `export-report.tsv` 里大量模型没有 file 或 `gaps` 不为 0，这份 fixture
只能服务已经有本地缓存的客户端，不适合搬到正式服。

可以用打包脚本生成一个可复制目录：

```powershell
gradle build
scripts\package-paper-deploy.bat -Fixture freesia-from-velocity -IncludeModels -Force
```

输出目录默认是：

```text
build\paperysm-deploy
```

把里面的 `plugins` 目录复制到正式 Paper 服务器根目录即可。

## 正式服 config.yml 建议

正式服建议把模型参考目录放在插件数据目录下：

```yaml
models-dir: models
scan-models-on-enable: true
debug: false

logging:
  model-scan-details: false
  packet-details: false

sync:
  send-authorized-models-on-handshake: true
  auto-native-cache-on-handshake: true
  auto-native-cache-capture: freesia-from-velocity
  auto-native-cache-delay-ticks: 20
  auto-native-cache-interval-ticks: 1
  auto-native-cache-chunk-bytes: 59926

capture:
  client-raw-packets: false
```

`models-dir` 只影响动画映射和模型元数据扫描。即使没有 `.ysm` 文件，只要
native-cache fixture 完整，模型同步仍然能跑；但部分轮盘/多级动作可能无法解析
成模型内真实动画名。

## Velocity/FreesiaII 该怎么删

正式运行时不需要 FreesiaII、Freesia worker，也不需要 ysm-sniffer。

1. 停止 Velocity 和 Paper 后端。
2. Velocity `plugins` 里禁用或移走 `Freesia-*.jar`。
3. 如果只为了捕获装过 `ysm-sniffer-*.jar`，正式服也移走。
4. `packetevents`、`multilogin` 等其他插件是否保留取决于你的群组服本身。
5. Paper 后端只保留 `paper-ysm-*.jar` 和 `plugins/PaperYSM` 数据目录。
6. Freesia worker 服务器不需要跟正式服一起启动。

Velocity 一般会透传现代 plugin message，PaperYSM 在后端 Paper 注册
`yes_steve_model:2_6_0` channel 后即可工作。

## 上线验证

玩家进入正式 Paper 后端后：

```text
/ysm status
/ysm sync
/ysm models
```

正常现象：

- `status` 里玩家是 compatible，协议是 `2.6.0`。
- 自动同步或 `/ysm sync` 会触发客户端 YSM 同步进度条。
- 同步后玩家能在 YSM UI 看到服务器/cache-local 模型。
- 选择模型后，其他已同步的 YSM 玩家能看到模型状态。

如果玩家看不到新增模型，先检查：

- `plugins/PaperYSM/captures/native-cache/<source>/cache-map.tsv` 是否有对应行。
- `server-cache` 文件是否存在且路径和 `cache-map.tsv` 一致。
- 捕获导出的 `export-report.tsv` 里该模型是否 `gaps=0` 且 file 非空。
- 玩家客户端是否完成 `/ysm sync`。

## 内存和流量

PaperYSM replay 当前是 disk-backed：发送 type5 时按 chunk 读取 cache 文件，
不会一次把几 GB server-cache 全塞进单个玩家会话内存。但首次同步会产生真实
下载流量，模型库越大，客户端首次同步越久。正式服可以把
`auto-native-cache-chunk-bytes` 调小一点换稳定性，或调大一点换速度。
