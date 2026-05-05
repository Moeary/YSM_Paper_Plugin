# PaperYSM 命令与架构梳理

这份文档面向后续“给插件做减法”的判断：哪些命令应该成为管理员日常入口，哪些只是协议调研工具，哪些路径才是真正能让玩家用起来的产品主线。

当前结论先放前面：PaperYSM 已经收敛到“可用优先”的入口。日常命令改为 `/ysm`，普通玩家可以 `/ysm sync` 同步自己的默认 native cache；OP/管理员才可以指定玩家、`all`、cache source、发包速度和 debug。逆向阶段留下的 `/ysm dist ...`、`/ysm native selftest` 仍保留给研发排障，但不再是普通服主的主流程。

## 当前命令面

`plugin.yml` 注册的主命令是：

```text
/ysm <sync|status|models|diagnose>
```

`/paperysm` 仍是别名。普通玩家默认可以执行 `/ysm sync`、查看自己的
`status/diagnose`；`paperysm.admin`（默认 OP）才能指定目标玩家、`all`、
切换 cache source、在线改配置、手动 apply 和使用研发命令。

### 日常/准日常命令

| 命令 | 用途 | 参数 | 当前适用场景 |
| --- | --- | --- | --- |
| `/ysm sync` | 玩家给自己请求默认 native cache 同步 | 无 | 普通玩家可用。默认 source 来自 `sync.auto-native-cache-capture`。 |
| `/ysm sync <player|all> [source]` | 管理员给指定玩家或全部玩家同步 cache | 玩家/all、可选 source | OP 运维入口，替代日常 `/ysm dist nativecache ...`。 |
| `/ysm status` | 查看所有在线玩家的 YSM 会话状态 | 无 | 日常诊断。能看到是否兼容、最后包、auth、S2C/C2S raw 计数。 |
| `/ysm status <player>` | 查看单个玩家状态 | 玩家名 | 日常诊断。判断玩家有没有完成 51/52 握手。 |
| `/ysm models` | 查看模型仓库扫描结果 | 无 | 管理员确认 `plugins/PaperYSM/models` 是否有可用 `.ysm`。会列出前 8 个模型、失败数、准备好的分发包数。 |
| `/ysm models reload` | 重新扫描模型仓库，并按配置准备分发包 | 无 | 添加/替换 `.ysm` 后无需重启。产品化后应保留。 |
| `/ysm diagnose [player]` | 汇总玩家同步状态 | 可选玩家名 | 普通玩家看自己，管理员可看任意玩家。 |
| `/ysm source <default|all|player> <source|clear>` | 切换默认或在线玩家 cache source | source 名 | 管理员入口。 |
| `/ysm config speed <intervalTicks> <chunkBytes>` | 在线调整 type5 发包速度 | tick 间隔、chunk 大小 | 管理员入口，写回 config。 |
| `/ysm debug <on|off>` | 在线开关 raw packet debug | on/off | 管理员入口，写回 config。 |
| `/ysm apply <player> <modelId> [textureId] [disabled]` | 主动给目标玩家应用模型状态，并广播给兼容 viewer | 玩家名、模型 id、纹理 id、是否 disabled | 调试和手工运维都可用。当前要求命令发起时模型必须在本地仓库中，除非模型是 `default`。 |

README 里推荐的主路径基本也是这一组：

```text
/ysm status
/ysm sync
/ysm models
/ysm models reload
/ysm diagnose [player]
/ysm source default freesia-from-velocity
/ysm config speed 1 59926
/ysm apply <player> <modelId> [textureId]
```

### 运维/内部状态命令

| 命令 | 用途 | 参数 | 当前适用场景 |
| --- | --- | --- | --- |
| `/ysm handshake <player>` | 手动给玩家发送 YSM `id=51` 握手 | 玩家名 | 正常 join 后会自动握手。保留为排障入口即可，不应放在用户教程主流程。 |
| `/ysm debug` | 切换 `debug` 配置并保存 | 无 | 临时打开 raw subpacket 日志。产品化后更适合改成 `/ysm debug on/off` 或只保留配置项。 |
| `/ysm dist status` | 查看准备好的分发包数量、chunk 数、体积、失败项 | 无 | 对开发/运维有用，但多数时候 `dist diagnose` 更直接。 |
| `/ysm dist prepare [modelId]` | 重新准备全部或单个模型的内部分发包 | 可选模型 id | 当 `distribution.prepare-on-reload` 关闭，或单模型准备失败后手动补救。 |
| `/ysm dist auth [player]` | 手动发送 Java 层 `id=6` 授权模型列表 | 可选玩家名 | 自动握手后通常会发送。保留为诊断工具即可。 |
| `/ysm dist clear` | 清空内存中的分发包和 native/replay 状态 | 无 | 研发/压测方便。产品化时需要谨慎，避免管理员误清空运行态。 |
| `/ysm native` | 显示当前 native bridge 实现名 | 无 | 内部实现状态。普通服主意义不大。 |
| `/ysm native selftest` | 运行 Java crypto/native raw 相关自测 | 无 | 开发者命令。更适合测试任务或 debug 命名空间。 |

### 协议调研/实验命令

这些命令非常有研究价值，但不应该出现在普通管理员的主帮助里。

| 命令 | 用途 | 参数 | 当前定位 |
| --- | --- | --- | --- |
| `/ysm dist replay <player> <captureNameOrFile> [fast|freesia|freesia-prelude]` | 从 `sync.raw-replay-dir` 重放旧 raw `id=1` 捕获 | 玩家、文件/目录、节奏模式 | 受 `sync.enable-raw-replay` 保护。用于比较 Freesia/original 捕获，不是正常分发路径。 |
| `/ysm dist bootstrap <player> [mode] [variant] [paddingBytes]` | 发送生成的 native type1 bootstrap 包 | 玩家、key 模式、变体、padding | 用来观察客户端是否回 C2S `id=2`。实验命令。 |
| `/ysm dist probe <player> [quick|full] [intervalTicks] [paddingBytes]` | 批量尝试 bootstrap 组合 | 玩家、profile、间隔、padding | 逆向阶段探测工具。 |
| `/ysm dist stream <player> [mode] [next|selected|initial]` | 尝试生成/发送 manifest stream | 玩家、模式、模型选择策略 | 旧的本地生成 stream 探针。 |
| `/ysm dist streamprobe <player> [intervalTicks]` | manifest stream 探测序列 | 玩家、间隔 | 旧探针。 |
| `/ysm dist report <player> [keys|keys-models|legacy] [s2c|c2s|both] [paddingBytes]` | 发送 report-native type1/type3，观察 type2/type4 解码 | 玩家、type3 布局、key 方向、padding | 用于验证 type3 布局猜想。 |

## 配置入口

核心配置可以分成几组：

- 协议与握手：`protocol-version`、`channel`、`handshake-delay-ticks`、`handshake-retries`、`handshake-retry-interval-ticks`。
- 日志：`debug`、`logging.model-scan-details`、`logging.packet-details`、hex preview 字节数。
- 模型/动画参考：`models-dir`、`scan-models-on-enable`。`models-dir` 主要用于读取 `.ysm` profile/extra animation 元数据；native-cache fixture 仍可在没有本地 `.ysm` 文件时同步模型。
- 玩家状态：`state.remember-player-models`、`state.saved-models-file`。用于记住玩家上次选择的模型/纹理，并在下次兼容握手后自动恢复。
- 分发准备：`distribution.prepare-on-reload`、`distribution.chunk-bytes`、`distribution.cache-dir`、`distribution.write-cache-files`。
- 同步行为：`sync.send-authorized-models-on-handshake`、`sync.warn-missing-native-sync-on-handshake`。
- fixture/replay：`sync.enable-raw-replay`、`sync.raw-replay-dir`、`sync.auto-native-cache-on-handshake`、`sync.auto-native-cache-capture`、native-cache replay 的 delay/interval/chunk 设置。
- 调研探针：`sync.experimental-bootstrap-on-handshake`、`sync.experimental-bootstrap-mode`、`sync.experimental-probe-interval-ticks`。
- 捕获：`capture.client-raw-packets`、`capture.raw-packet-dir`。
- 未来 native bridge：`native.enabled`、`native.mode`。

产品化时，建议把配置文件也分层：默认配置只暴露模型目录、是否自动同步、日志级别；replay/probe/capture/native bridge 放到清楚标注的 `debug` 或 `research` 段落。

## 运行架构和数据流

### 1. 插件启动

启动时 `onEnable` 做这些事：

1. 保存默认配置，读取 `reloadSettings`。
2. 按 `native` 配置重载 `YsmNativeBridgeManager`。
3. 扫描模型仓库 `YsmModelRepository`。
4. 如果 `distribution.prepare-on-reload: true`，把扫描成功的 V3 `.ysm` 准备成 `YsmDistributionRepository.PreparedModel`。
5. 注册 YSM plugin message channel：默认 `yes_steve_model:2_6_0`。
6. 注册 join/quit 事件、`/ysm` 命令、tab complete。

### 2. 握手与会话

玩家加入后，插件创建 `YsmClientSession.pending`，按配置延迟发送 `id=51` server handshake。

客户端回 `id=52` 后：

- 如果版本等于 `protocol-version`，会话标记为 compatible。
- 如果 `sync.send-authorized-models-on-handshake: true`，立即发送 `id=6` 授权模型列表。
- 如果开启 `sync.auto-native-cache-on-handshake`，安排 native-cache fixture replay。
- 否则如果开启 `sync.experimental-bootstrap-on-handshake`，发送实验 bootstrap。
- 如果没有 native cache/raw 路径启动，并且已经有 prepared package，会记录“auth 已发但 native `id=1` 未发”的警告。
- 最后安排两次已知模型状态 replay，照顾晚完成握手的 viewer。

`YsmClientSession` 记录的是运维最需要的几类信号：兼容状态、协议版本、最后 packet、授权模型列表是否发送、S2C raw 发送数量、C2S raw 接收数量。

### 3. 模型仓库扫描

`models-dir` 在本地测试配置中指向 Freesia worker 的真实模型目录
`test-server/freesia-worker/config/yes_steve_model/custom`，不要用 junction
把外部大目录暴露给客户端模型浏览器。OP 可以在线修改并立即重扫：

```text
/ysm config modeldir <path>
```

扫描逻辑在 `YsmModelRepository`：

- 递归查找 `.ysm` 文件。
- 用 `YsmArchiveProbe.probe` 验证/解析 V3 加密包。
- 模型 id 是相对路径，统一使用 `/`，并去掉 `.ysm` 后缀。例如 `models/author/foo.ysm` 变成 `author/foo`。
- 成功项保存版本、format、源文件大小、解压大小、payload summary、extra animation profile。
- 失败项保存文件和异常信息。

这条路径适合产品化保留为“动画参考目录”：管理员只需要把模型放进目录，然后 `/ysm models reload`。如果目录为空，cache 同步仍然能跑，只是部分轮盘/多级菜单动作无法解析成模型内真实动画名。

### 4. 分发准备

`YsmDistributionRepository` 把模型仓库里的条目准备成内部传输包：

- 读取并提取 V3 `.ysm`。
- 保存 decompressed payload SHA-256 和 washed-zstd SHA-256。
- 当前真正用于 transfer 的是 washed-zstd payload，不是直接把完整 decompressed payload 切块。
- 按 `distribution.chunk-bytes` 计算 chunk。
- 默认只在内存保存；`distribution.write-cache-files: true` 时才写 `cache/distribution`，用于受控对比。

注意：prepared package 不是客户端能直接消费的 native cache。它是未来本地生成 native cache 的 staging layer。当前客户端要出现下载进度和 roulette entry，仍依赖 Freesia-derived native-cache fixture 或未来补全的本地生成器。

### 5. Native cache replay 当前工作路径

当前最接近“能用”的同步路径是普通入口 `/ysm sync`，它内部读取
`sync.auto-native-cache-capture` 配置的 fixture。手动指定 source 时用：

```text
/ysm sync <player> freesia-from-velocity
```

它从插件数据目录下读取：

```text
captures/native-cache/<captureName>/
  type3-body.bin
  cache-map.tsv
  server-cache.bin 或 cache-map.tsv 指向的 server-cache 文件
  type1-padding.txt / type3-padding.txt（可选）
```

流程是：

1. 发送 S2C `id=1` native type1 bootstrap。
2. 等客户端回 C2S `id=2` native type2 key。
3. 用客户端 key 发送 S2C type3 manifest/body。
4. 等客户端回 native type4 token request。
5. 用 `cache-map.tsv` 把 token 映射到 server-cache bytes。
6. 分片发送 S2C type5 chunks。
7. 发送完成后 replay 已知模型状态，让 viewer 看到之前选过的模型。

这条路径的价值很高：它证明客户端进度条、服务器模型选择、两客户端可见性是通的。它的产品化问题也很明确：依赖真实 Velocity/Freesia 捕获 fixture，不能由任意本地 `.ysm` 自动生成。旧的按 worker `cache/server` 目录顺序拼接 cache 的路径不可靠，不能作为管理员主流程。

### 6. 模型选择与状态广播

模型状态有两种来源：

- 管理员命令 `/ysm apply <player> <modelId> [textureId] [disabled]`。
- 客户端发来的 `id=5` model selection。

应用时插件会：

- 尝试在本地模型仓库中找到模型。命令路径比较严格，找不到会拒绝；客户端选择路径允许 client/cache-local 模型 id。
- 如果本地模型存在但分发包未准备，会按需准备。
- 写入 `appliedModelStates`。
- 用 `YsmEntityStateCodec.encodeModelSelectionBody` 生成实体状态 body。
- 包装成 `id=4` entity data update，发给所有 compatible viewer。

这条路径应当是产品主线之一：模型同步完成后，玩家选择模型，服务器广播给其他 YSM 客户端。

### 7. 动画转发

客户端 wheel/custom animation 请求是 `id=7`。当前插件会：

- 检查玩家已完成兼容握手。
- 解析 action/name/target entity。
- 如果客户端直接带 name，就使用客户端 name。
- 如果 name 为空，按当前选择模型的 profile 表尝试从 extra animation buttons 或 extra animations 里解析 action index。
- 找不到时 fallback 到 `extra<action>`。
- 广播 `id=21` animation，layer 当前固定为 `8`。

现有文档已经提醒：wheel/custom animation 的 action/id 映射还不稳定，不应靠猜 id “修好”。产品化建议是保留普通动画转发，但把映射失败日志做清楚，并继续用 Freesia 捕获验证。

### 8. Raw replay、bootstrap、report 等实验路径

`dist replay` 是旧的 raw `id=1` 捕获重放器，受 `sync.enable-raw-replay` 保护；`bootstrap/probe/stream/streamprobe/report` 是为了逆向 native packet state machine 写的探针。

它们共同服务于一个目标：最终替换 Freesia fixture，由 PaperYSM 自己从本地 `.ysm` 生成 type3 manifest、cache token、type5 chunk。它们不应该成为普通服主的心智负担。

## 产品化可保留的核心路径

建议把“真正可用插件”的主线定义为：

1. 启动扫描 `models/`。
2. 玩家加入后自动握手。
3. 自动发送授权模型列表。
4. 自动完成 native cache 同步。
5. 玩家在 YSM UI 中选择服务器模型。
6. PaperYSM 接收 `id=5` 并广播 `id=4`。
7. PaperYSM 接收 `id=7` 并广播 `id=21`。
8. 晚加入/晚同步玩家收到已有模型状态 replay。

围绕这条主线，建议保留或改名成更直观的命令：

- `/ysm status [player]`
- `/ysm models [reload]`
- `/ysm sync <player>`：包装当前 `dist nativecache`，以后替换成本地生成 native cache。
- `/ysm diagnose [player]`：包装当前 `dist diagnose`。
- `/ysm apply <player> <modelId> [textureId] [disabled]`

其中 `/ysm sync` 可以先内部调用 native-cache fixture，并在文案里明确 source；等本地生成器完成后，命令名无需改变。

## 建议隐藏或移入 debug 的命令

建议从普通 tab complete 和 README 主流程里移走：

- `dist replay`
- `dist bootstrap`
- `dist probe`
- `dist stream`
- `dist streamprobe`
- `dist report`
- `native selftest`
- `dist clear`

它们可以迁移为：

```text
/ysm debug replay ...
/ysm debug bootstrap ...
/ysm debug probe ...
/ysm debug report ...
/ysm debug native selftest
/ysm debug clear-runtime
```

或者保留现有命令但需要额外配置开关，例如 `debug.enable-research-commands: true` 才出现在 tab complete 中。这样不会误伤研发能力，也能让插件对普通管理员显得更像产品，而不是协议实验台。

## 后续优先级建议

### P0：把当前可用路径包成“一键同步”

目标不是立刻消灭 Freesia fixture，而是让当前已验证路径少敲命令、少踩坑：

- 新增或包装 `/ysm sync <player>`，默认使用 `sync.auto-native-cache-capture`。
- 允许配置 `sync.auto-native-cache-on-handshake: true` 时自动跑当前 native-cache replay。
- `diagnose` 明确告诉管理员下一步应该执行什么，例如“已握手但 native cache 未开始，请运行 /ysm sync <player>”。

### P1：收敛帮助和 tab complete

让默认命令面只显示产品主线：

- `status`
- `models`
- `sync`
- `diagnose`
- `apply`
- `debug`

`dist` 可以继续存在一段时间，但从 README 主流程和默认 tab complete 中淡出。

### P1：把配置分层并降低噪音

当前默认 `logging.model-scan-details: true`、`logging.packet-details: true` 对调研很友好，但生产服会很吵。

建议：

- 默认关闭 per-packet hex preview。
- 模型扫描只打印摘要，失败仍然打印。
- debug/research 配置集中到一个明确段落。

### P2：把 native cache fixture 变成可替换后端

产品 API 不应该绑定某个具体 fixture 名（例如
`freesia-from-velocity`）。建议抽象成：

- `NativeCacheSyncService`
- 当前实现：`FixtureNativeCacheSyncService`
- 实验实现：`GeneratedNativeCacheSyncService`，当前入口是 `/ysm dist ysmcache ...`

命令和配置只表达“给玩家同步模型缓存”，不要让管理员感知 type1/type3/type5。

### P2：同步完成状态要更显式

现在主要靠 raw 计数、type4/token、type5 scheduled 推断。后续最好有 per-player/per-model 状态：

- `not-started`
- `handshaking`
- `auth-sent`
- `cache-bootstrap-sent`
- `manifest-sent`
- `chunks-sent`
- `ready`
- `failed`

这样 `status` 和 `diagnose` 会更像管理员工具。

### P3：动画映射继续验证，不要猜

普通动画已经能转发，但 wheel/custom action 映射仍应对照 Freesia 捕获。建议产品化前：

- 保留 fallback，但把 fallback 标成 warning/debug。
- 收集更多模型 profile 与 action 的对应关系。
- 不把“自定义动画完全稳定”写进用户说明，直到捕获验证完成。

## 减法后的目标体验

理想的服主路径应该是：

1. 把 `.ysm` 放进 `plugins/PaperYSM/models`。
2. 执行 `/ysm models reload`。
3. 玩家进服，插件自动握手并同步。
4. 如果没生效，执行 `/ysm diagnose <player>`。
5. 管理员最多手动执行 `/ysm sync <player>` 或 `/ysm apply ...`。

其它命令都应该服务开发者，而不是成为普通使用说明的一部分。
