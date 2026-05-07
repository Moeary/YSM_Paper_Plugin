# PaperYSM 正式服部署清单

这份清单面向当前 Paper-only OpenYSM cache 路线：模型放 `models/`，插件自己生成
`cache/openysm/server-cache/`，玩家进服自动同步或手动 `/ysm sync`。

## 目录说明

`plugins/PaperYSM` 里建议保留这些：

```text
plugins/PaperYSM/
  config.yml                  # 必需
  models/                     # 必需，本地 .ysm 模型源
  cache/openysm/server-cache/ # 建议复制，已经生成好的 OpenYSM cache
  player-models.yml           # 可选，玩家上次模型、动画、roaming molang 状态记录
```

这些不建议带到正式服：

```text
plugins/PaperYSM/captures/    # 旧调试抓包目录
plugins/PaperYSM/debug/       # 新调试抓包/重放目录，生产服默认不会生成
config.yml.bak-*              # 本地测试备份
cache/openysm/DD12_.../       # 临时同步会话目录，正常会自动清理
cache/openysm/research-distribution/ # 旧研发 staging cache，生产服通常不需要
```

如果不复制 `cache/openysm/server-cache/`，正式服也能用
`/ysm admin cache all` 重新生成，但首次生成会吃 CPU 和磁盘 IO。

`cache/openysm/server-cache/index.tsv` 是机器读的索引；
`cache/openysm/server-cache/cache-map.tsv` 是人看的模型名到 cache 文件映射。
如果你从旧测试服带过来很多历史索引行，可以执行：

```text
/ysm admin cache compact
```

它会重写索引和 `cache-map.tsv`，不会重新生成模型 cache。

## 推荐配置

30 Mbps 上行建议先用保守传输速度：

```yaml
debug: false
scan-models-on-enable: true

logging:
  model-scan-details: false
  packet-details: false
  progress-interval-models: 32

sync:
  auto-generated-cache-on-handshake: true
  auto-generated-cache-model: all
  auto-generated-cache-interval-ticks: 1
  auto-generated-cache-chunk-bytes: 65536
  auto-generated-cache-max-models: 32
  auto-generated-cache-type5-packets-per-tick: 2
  auto-generated-cache-prewarm-on-startup: false
  auto-generated-cache-sync-online-after-prewarm: false

capture:
  client-raw-packets: false
```

`scan-models-on-enable` 现在是异步扫描，不会卡住开服主流程。模型库很大且你想完全手动控制时，可以改成 `false`，开服后由 OP 执行 `/ysm admin scan`。

默认配置里不再展示 `distribution.*`。那是旧的研发/对照分发 staging 入口，
不是当前 OpenYSM native cache 主路径；即使缺省，旧调试命令仍会用隐藏默认值工作。

## 管理员流程

首次部署或模型大规模更新：

```text
/ysm admin scan
/ysm admin cache all
/ysm admin cache compact
/ysm admin syncall all
```

日常新增或替换少量模型：

```text
/ysm admin scan
/ysm admin incremental all
```

强制让所有在线玩家重新拿最新目录：

```text
/ysm admin fullsync all
```

只发送已有 cache 目录，不重新生成：

```text
/ysm admin syncall all
```

30 Mbps 上行如果玩家同步时卡顿，可以继续降速：

```text
/ysm admin speed 65536 1 1
```

如果带宽很空，再尝试：

```text
/ysm admin speed 65536 3 1
```

## DriveBackupV2 排除

DriveBackupV2 的 `backup-list` 支持 `blacklist` glob。备份 `plugins` 时建议排除 PaperYSM 的大目录，只保留配置和玩家状态：

```yaml
backup-list:
  - path: "plugins"
    format: "'Backup-plugins-'yyyy-M-d--HH-mm'.zip'"
    create: true
    blacklist:
      - "PaperYSM/models/**"
      - "PaperYSM/cache/**"
      - "PaperYSM/debug/**"
      - "PaperYSM/captures/**"
```

改完 DriveBackupV2 配置后执行：

```text
/drivebackup reloadconfig
```

这样云备份不会上传几 GB 模型/cache；`PaperYSM/config.yml` 和
`PaperYSM/player-models.yml` 仍会进插件备份。

## Velocity

Velocity 会转发客户端到后端 Paper 的 custom payload/plugin message，所以
PaperYSM 可以放在后端 Paper 上跑。它不会帮你缓存或减少模型流量：数据仍然是
后端 Paper -> Velocity -> 玩家。如果 Velocity 和 Paper 不在同一台机器，还要计算
后端到 Velocity 的内网/公网带宽。

## Cache 大小和失效

OpenYSM 生成 cache 比 Freesia fixture 大是正常现象之一：PaperYSM 现在写的是
OpenYSM server-cache 形态，包含从 `.ysm` 反序列化后重新打包的完整 server-cache
payload；Freesia 导出的目录可能只覆盖某次 worker 同步里实际注册/请求过的模型，
而且文件名是模型名加 token。PaperYSM 现在通过 physical token 去重，多个目录别名可以
指向同一个 cache 文件，并在 `cache-map.tsv` 里保留可读模型名。

缓存是否失效主要取决于三件事：`cache/openysm/index.properties` 里的
`serverCacheKey`、模型文件大小/修改时间、以及模型 hash。复制到新服务器时建议连同
`cache/openysm/index.properties` 一起带走；不要随便改
`sync.auto-generated-cache-token-salt`，否则客户端会把它当成一批新 cache。

开服扫描期间进服的玩家会等扫描完成后再自动同步，避免先同步一个小目录，扫描完又全量
同步一次。

## 给玩家的说明

玩家只需要记住：

```text
/ysm sync
```

进服会自动同步；如果模型列表没刷新、刚换电脑、刚清过客户端缓存，就手动执行一次。
