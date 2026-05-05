# PaperYSM 第一版分发测试使用说明

这份说明面向两类人：

- 普通玩家：知道自己要装什么、进服后看什么、怎么判断同步成功。
- 腐竹/测试服管理员：知道模型放哪里、用哪些命令跑第一轮分发测试、出问题先看哪里。

当前 PaperYSM 仍是协议原型，不建议直接当生产服插件宣传。第一版测试的重点是验证：YSM 客户端能完成握手、看到服务器模型同步进度、选择服务器模型，并让其他已同步的 YSM 客户端看到模型状态。

## 当前能测什么

已验证或适合第一轮测试的内容：

- YSM 2.6 客户端加入 Paper 服务器后完成 `51/52` 握手。
- 服务器从本地 `.ysm` 模型仓库发送授权模型列表。
- 通过 `freesia-latest` native-cache fixture 触发客户端模型同步进度条。
- 玩家完成同步后，在 YSM 界面选择服务器模型。
- 两个都完成同步的 YSM 客户端之间可以互相看到模型状态。
- 普通动画广播已有基础转发能力。

暂时不要当成稳定功能宣传的内容：

- 任意本地 `.ysm` 自动生成客户端可消费的 native cache。
- 生产服无人工干预自动分发。
- 轮盘/自定义动画的完整映射。当前仍需要对照 FreesiaII 捕获验证，不要靠猜动画 id 修。

## 玩家说明

玩家只需要做这些事：

1. 使用和测试服匹配的 Minecraft 版本。
2. 安装 Yes Steve Model 客户端模组，建议使用 YSM 2.6.x 客户端。
3. 进入测试服后等待服务器同步。正常情况下，客户端会出现 YSM 模型同步进度。
4. 同步完成后，打开 YSM 的模型选择界面，选择服务器模型。
5. 如果看不到服务器模型，或者其他玩家看不到你的模型，把自己的游戏名告诉管理员，让管理员执行诊断命令。

可以发给玩家的简版通知：

```text
本服正在测试 PaperYSM 服务器模型分发。请安装 YSM 2.6.x 客户端进服，进服后等待模型同步进度条跑完，再到 YSM 模型界面选择服务器模型。看不到服务器模型时，把游戏名发给管理员排查。
```

## 腐竹准备

### 1. 安装插件

把 PaperYSM 插件 jar 放进 Paper 服务器的 `plugins` 目录，然后启动服务器。

本仓库本地构建输出路径是：

```text
build/libs/paper-ysm-0.1.0-SNAPSHOT.jar
```

本地直接测试服路径是：

```text
test-server/direct-paper
```

注意：只有 OP 或拥有 `paperysm.admin` 权限的管理员可以使用 `/paperysm` 命令。

### 2. 放置模型

把 `.ysm` 模型文件放到插件数据目录下：

```text
plugins/PaperYSM/models
```

可以用子目录整理模型。模型 id 等于相对 `models` 目录的路径，去掉 `.ysm` 后缀，并统一使用 `/`。

例如：

```text
plugins/PaperYSM/models/alice/robot.ysm
```

对应模型 id：

```text
alice/robot
```

本仓库的 `test-server/direct-paper` 已经带有一份测试模型：

```text
test-server/direct-paper/plugins/PaperYSM/models/拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm
```

对应模型 id：

```text
拉菲Ⅱ/拉菲Ⅱ_v1.2
```

添加或替换模型后执行：

```text
/paperysm models reload
```

再用下面命令确认扫描结果：

```text
/paperysm models
```

### 3. 确认 native-cache 测试素材存在

第一版可用的同步路径依赖 FreesiaII 捕获出来的 native-cache fixture。默认测试名是：

```text
freesia-latest
```

它需要放在插件数据目录：

```text
plugins/PaperYSM/captures/native-cache/freesia-latest
```

本仓库的本地测试服已经带有这份素材：

```text
test-server/direct-paper/plugins/PaperYSM/captures/native-cache/freesia-latest
```

如果你把插件拿到新的测试服，需要同时带上这份测试素材。否则 `/paperysm dist nativecache ...` 会提示找不到或加载失败。

## 推荐手动测试流程

第一轮建议手动跑，方便观察每一步是否成功。

### 1. 启动服务器并扫描模型

```text
/paperysm models reload
/paperysm models
```

期望结果：

- 能看到已加载模型数量。
- 失败数量为 `0`，或者失败项能明确指出是哪个 `.ysm` 文件有问题。
- 如果有 prepared packages，命令会提示这些包还不是客户端可直接消费的完整 native cache；这是当前原型阶段的正常提示。

### 2. 玩家进服后检查握手

玩家进入服务器后，执行：

```text
/paperysm status <player>
```

期望结果：

- 玩家状态显示 compatible。
- 能看到最后一次 YSM packet、授权模型列表发送状态、raw 包计数等信息。

如果显示还没有 YSM 活动，先让玩家等几秒或重新进服；仍然没有时，检查玩家是否安装了匹配版本的 YSM 客户端。

### 3. 给玩家启动 native-cache 同步

执行：

```text
/paperysm dist nativecache <player> freesia-latest 1 59926
```

参数含义：

- `<player>`：目标玩家名。
- `freesia-latest`：当前测试用 native-cache fixture 名。
- `1`：发送间隔，单位是 tick。
- `59926`：每片发送大小，当前手动测试推荐值。

期望结果：

- 管理员看到 `Started native-cache replay` 一类提示。
- 玩家客户端出现 YSM 同步进度条。
- 同步完成后，玩家能在 YSM 界面看到服务器/cache-local 模型入口。

### 4. 玩家选择模型

玩家在 YSM 界面选择服务器模型后，PaperYSM 会接收客户端选择并广播给其他兼容玩家。

如果想由管理员手动套用模型状态，可以执行：

```text
/paperysm apply <player> <modelId> [textureId]
```

例如：

```text
/paperysm apply Steve alice/robot
```

`textureId` 不填时默认使用 `default`。

### 5. 双人可见性测试

推荐至少用两个装了 YSM 的客户端测试：

1. 两个玩家都进服。
2. 分别对两人执行 native-cache 同步命令。
3. 两人都等同步完成。
4. 玩家 A 选择服务器模型。
5. 玩家 B 应该能看到玩家 A 的模型状态。
6. 反过来让玩家 B 选择模型，再看玩家 A 是否能看到。

注意：观察者也需要完成 YSM 握手和 native-cache 同步。只有被观察者同步完成是不够的。

## 可选：进服后自动同步

手动流程跑通后，可以把自动 native-cache replay 打开，减少管理员每次敲命令。

在 `plugins/PaperYSM/config.yml` 中设置：

```yaml
sync:
  auto-native-cache-on-handshake: true
  auto-native-cache-capture: "freesia-latest"
  auto-native-cache-delay-ticks: 20
  auto-native-cache-interval-ticks: 1
  auto-native-cache-chunk-bytes: 59926
```

修改配置后重启服务器。

第一轮分发测试仍建议先走手动命令。手动跑通后再打开自动同步，更容易定位问题。

## 常用管理员命令

```text
/paperysm status
/paperysm status <player>
/paperysm models
/paperysm models reload
/paperysm dist diagnose [player]
/paperysm dist ysmcache <player> [modelId|all] [intervalTicks] [chunkBytes] [legacy|keys] [washed-zstd|headerless-v3|encrypted-v3]
/paperysm dist nativecache <player> freesia-latest 1 59926
/paperysm apply <player> <modelId> [textureId]
```

命令说明：

| 命令 | 用途 |
| --- | --- |
| `/paperysm status` | 查看在线玩家的 YSM 会话状态。 |
| `/paperysm status <player>` | 查看单个玩家是否完成兼容握手。 |
| `/paperysm models` | 查看模型仓库扫描结果和准备好的分发包数量。 |
| `/paperysm models reload` | 添加或替换 `.ysm` 后重新扫描模型。 |
| `/paperysm dist diagnose [player]` | 汇总模型分发包、玩家握手、native-cache 同步状态。 |
| `/paperysm dist ysmcache <player> [modelId|all] [intervalTicks] [chunkBytes] [legacy|keys] [washed-zstd|headerless-v3|encrypted-v3]` | 实验性本地 `.ysm` 生成 native cache，不读取 Freesia cache 文件。`legacy` 是当前能到 type4/type5 的布局，`keys` 专门验证双 cache key 布局；payload 参数用于对比旧 `washed-zstd` 和去 YSGP 头的加密 `.ysm` 形态。 |
| `/paperysm dist nativecache <player> freesia-latest 1 59926` | 给玩家启动当前可用的 native-cache fixture 同步。 |
| `/paperysm apply <player> <modelId> [textureId]` | 管理员手动给玩家套用模型状态并广播。 |

其他 `dist replay/bootstrap/probe/stream/report` 相关命令是协议调研入口，不建议放进普通测试流程。

## 排障速查

### `/paperysm models` 没有模型

检查：

- `.ysm` 是否放在 `plugins/PaperYSM/models` 下。
- 文件后缀是否是 `.ysm`。
- 添加文件后是否执行了 `/paperysm models reload`。
- 控制台是否有模型解析失败信息。

### 玩家状态不是 compatible

检查：

- 玩家是否安装了 YSM 客户端。
- 客户端 YSM 版本是否匹配 2.6.x。
- 玩家是否刚进服，握手可能需要等几秒。
- 必要时让玩家重进服务器。

### 诊断提示只发送了授权模型列表，没有 native/raw 包

这通常表示 Java 层握手已经完成，但还没有启动 native-cache 同步。

执行：

```text
/paperysm dist nativecache <player> freesia-latest 1 59926
```

或者在手动流程确认可用后，打开 `sync.auto-native-cache-on-handshake`。

### native-cache 加载失败

检查测试素材是否存在：

```text
plugins/PaperYSM/captures/native-cache/freesia-latest
```

这份目录里至少需要包含 native-cache replay 所需的 `type3-body.bin`、`cache-map.tsv` 和对应 cache 数据。

### 玩家看不到服务器模型入口

检查：

- 玩家是否 compatible。
- 是否已经对该玩家执行过 native-cache 同步。
- 客户端同步进度是否跑完。
- `freesia-latest` 测试素材是否完整。

注意：当前本地 `.ysm` 扫描和 native-cache fixture 还没有完全合并成任意模型自动分发。第一轮测试请优先使用已配套的测试模型和 `freesia-latest` 同步素材。

### 其他玩家看不到某玩家的模型

检查：

- 观察者是否也安装了 YSM。
- 观察者是否完成 compatible 握手。
- 观察者是否也完成 native-cache 同步。
- 被观察者是否真的在 YSM 界面选择了服务器模型。
- 必要时执行 `/paperysm dist diagnose <player>` 分别查看两名玩家。

### 轮盘或自定义动画不对

先不要把它当成分发失败。当前 wheel/custom animation 映射仍在验证中。记录模型、动作、玩家名和复现步骤，再和 FreesiaII 捕获对照。

## 第一轮测试通过标准

一轮最小测试可以按下面标准判断通过：

- `/paperysm models` 能扫到测试模型。
- 玩家 `/paperysm status <player>` 显示 compatible。
- `/paperysm dist nativecache <player> freesia-latest 1 59926` 能启动。
- 玩家客户端出现并完成 YSM 同步进度。
- 玩家能在 YSM 界面选择服务器模型。
- 两个都完成同步的 YSM 玩家能互相看到模型状态。

如果以上都通过，这一版 PaperYSM 的“握手、授权列表、native-cache fixture 同步、模型选择广播”主链路就算首轮分发测试跑通。
