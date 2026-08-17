# Paper-YSM

Paper-YSM 是一个 Paper 侧的重实现原型，用于复现 Yes Steve Model 的服务端分发流程。它可以与 YSM 2.6.x 客户端完成握手，公开已授权的模型列表，重放捕获的 FreesiaII 风格原生缓存流，并将 YSM 模型状态应用到兼容的查看器。

当前的重点是提供一个可用的 Paper 侧缓存分发器：将真实的 Fabric/YSM/Freesia 工作端输出作为缓存基准，而 PaperYSM 负责握手、原生缓存重放、已保存的模型状态以及诊断功能。

## 当前状态

- YSM `51/52` Java 层握手已在 `yes_steve_model:2_6_0` 通道上正常工作。
- 授权模型列表数据包 `id=6` 会根据本地 `.ysm` 文件生成。
- V3 `.ysm` 解析与缓存准备使用了 YSMParser 项目中的逻辑。
- 原生缓存同步默认重放从工作端/Freesia 派生的缓存材料。
- 完成缓存同步后，YSM 客户端可以选择服务端模型，并在两个客户端之间看到模型状态和普通动画状态。
- 滚轮/自定义动画转发会从扫描得到的 `.ysm` 配置中解析动作，其中包括嵌套的滚轮/分类条目。请确保对应的 `.ysm` 文件位于 PaperYSM 的 `models` 目录下。

## 仓库布局

```text
src/                         Paper 插件源代码
docs/                        协议说明、进度记录和旧版原型说明
ysm-sniffer/                 Velocity 数据包捕获辅助工具
scripts/                     一次性本地启动脚本
test-server/                 未上传 需要自己整理使用
  direct-paper/              独立运行的 PaperYSM 测试服务器
  paper-backend/             用于 Velocity/FreesiaII 对比的 Paper 后端
  velocity-proxy/            带 FreesiaII 和数据包工具的 Velocity 代理
  freesia-worker/            Fabric YSM/Freesia 工作端服务器
references/                  未上传 需要自己整理使用
  YSMParser/                 用于格式处理的解析器项目快照
  FreesiaII/                 用作参考的 FreesiaII JAR 文件和发行版元数据
  decompiled/                反编译或提取的客户端/原生参考材料
```

## 构建

从仓库根目录使用 Pixi。Pixi 提供本地任务所需的 Gradle/JDK 工具链。

```powershell
pixi run build
pixi run check
```

插件 JAR 文件会写入：

```text
build/libs/paper-ysm-0.1.0-SNAPSHOT.jar
```

启动本地直连 Paper 测试服务器：

```powershell
pixi run deploy-direct
pixi run run-direct
```

`run-direct` 会执行构建、部署 JAR，并在当前终端中启动直连 Paper 服务器。`server-direct` 只启动服务器。

## 测试服务器

从仓库根目录使用以下入口：

```powershell
pixi run run-direct
scripts\start-freesiaii-stack.bat
```

直连 PaperYSM 测试：

- 加入 `127.0.0.1:30001`。
- 使用 `test-server\direct-paper`。
- 这是 PaperYSM 开发的主要运行环境。使用下面的通道切换脚本，可以让它默认采用工作端/Freesia 缓存重放路径。

FreesiaII 对比栈：

- 通过 `127.0.0.1:30000` 连接 Velocity。
- 启动 `test-server\paper-backend`、`test-server\velocity-proxy` 和 `test-server\freesia-worker`。
- 用于重新捕获数据，并与 FreesiaII 的行为进行对比。它不是常规的 PaperYSM 开发运行环境。

同一时间只能有一个 Paper 服务器绑定 `30001`。

直连 Paper 测试服务器通常应使用 Freesia/工作端缓存配置：

```bat
scripts\switch-direct-paper-channel.bat status
scripts\switch-direct-paper-channel.bat freesia
scripts\switch-direct-paper-channel.bat auth-only
```

- `freesia`：直连 Paper 在握手后重放捕获的 `freesia-from-velocity` 原生缓存固定数据。
- `generated`：仍作为研究配置保留，但不再是常规路径，因为客户端目前尚未接受 Java 生成的缓存字节。
- `auth-only`：仅执行 Java 握手和授权模型列表流程，不发送原生缓存流。

## 实用命令

```text
/ysm sync
/ysm models
/ysm models reload
/ysm status [player]
/ysm diagnose [player]
/ysm source <default|all|player> <cacheSource|clear>
/ysm config speed <intervalTicks> <chunkBytes>
/ysm config source <cacheSource>
/ysm debug <on|off>
```

普通玩家可以运行 `/ysm sync` 为自己执行同步。拥有 OP 权限或 `paperysm.admin` 权限的用户，可以指定其他玩家、为所有人执行同步、切换缓存来源，并在线修改发送速度/调试设置。`/paperysm` 仍作为旧脚本的别名保留，但推荐使用 `/ysm`。

从提取出的捕获数据中分析 Freesia 动画请求/广播对：

```powershell
gradle analyzeAnimationCapture
gradle analyzeAnimationCapture -PysmAnimationCaptureDir="references/analysis-artifacts/freesia-latest-extracted"
```

分析原生缓存固定数据，并将其解密后的服务端缓存负载与本地 `.ysm` 文件进行比较：

```powershell
gradle analyzeNativeCacheFixture -PysmNativeCacheFilter="拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm" -PysmNativeCacheLocalModel="test-server/direct-paper/plugins/PaperYSM/models/拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm"
```

当前可用的 FreesiaII 重放来源是 `cache/freesia-from-velocity`。它由捕获的 FreesiaII 缓存材料构建，包含客户端进度条和服务端模型选择所需的清单及缓存条目。Paper 生成的路径会将测试产物写入 `cache/openysm`。

## 动画模型目录

PaperYSM 将模型文件保存在 `plugins/PaperYSM/models` 下。原生缓存固定数据以及生成的 type3/type5 产物位于 `plugins/PaperYSM/cache/<channel>` 下。滚轮和嵌套滚轮的动画映射仍来自模型目录中已解码的 `.ysm` 配置：

```text
plugins\PaperYSM\models
```

不要通过 junction/symlink 暴露大型外部模型库。YSM 客户端浏览器会显示真实的相对路径，使用起来会很不方便。请将模型组复制到工作端的 `custom` 目录中，然后执行一次真实的 FreesiaII/Velocity 捕获，再将该捕获导出到 Paper 固定数据中。

将配置迁移到另一台服务器时，请同时复制 Paper 原生缓存固定数据，以及用于动画映射的匹配 `.ysm` 模型树。仅缓存固定数据就足以完成模型同步；缺失 `.ysm` 引用只意味着部分自定义滚轮动画无法解析。OP 可以在线修改引用目录：

```text
/ysm config modeldir <path>
```

正常的导入流程以捕获为先。启动完整的 FreesiaII 对比栈，通过 Velocity 使用 YSM 客户端加入，让 Freesia 完成原生同步，然后导出真实的 type3/type5 数据包：

```powershell
scripts\start-freesiaii-stack.bat
scripts\paperysm.bat export-capture
```

`scripts\sync-velocity-cache-to-paper.bat` 是同一捕获导出流程的简化批处理文件。若要生成完整的可移植固定数据，请在通过 Velocity 加入之前清除客户端的 YSM 缓存；否则在本次运行中，Freesia 可能只会为客户端缺失的模型发送 type5 数据块。

旧版工作端缓存批处理工具目前仅供研究使用。它可以对工作端的 `cache/server` 做快照并复制新生成的 bin 文件，但无法安全地自行生成匹配的 type3 令牌映射。只有在显式传入不安全标志时，才允许直接导出：

```powershell
scripts\export-worker-cache-batch.bat snapshot -SnapshotName default-clean
scripts\export-worker-cache-batch.bat export -Group "R18模型整合" -SnapshotName default-clean --unsafe-order-pair
```

仅将其用于协议实验。要让 Paper 客户端显示新模型，目前仍应使用 Velocity/Freesia 捕获导出器，这是受支持的方式。

日常本地工作可以使用 `scripts\paperysm.bat`，它通过一个 Python 菜单封装常用流程：

```powershell
scripts\paperysm.bat
```

该工具也支持 `status`、`copy-models`、`start-stack`、`export-capture`、`type3-inspect`、`start-worker` 和 `start-paper` 等直接命令。

## 文档

- `docs/ysm-sync-progress.md`：当前协议进度和流程图。
- `docs/paperysm-production-deploy.md`：将 PaperYSM 复制到实际 Paper 服务器，并从 Velocity 栈中移除 FreesiaII。
- `docs/native-reimplementation.md`：原生缓存重实现说明。
- `docs/prototype-notes.md`：保留历史细节的旧版工作 README。

## 参考资料

参考材料有意保存在 `references/` 中，因此无需在多个独立目录之间跳转即可研究项目：

- `references/YSMParser` 包含解析器实现，其中记录了 `.ysm` V3 格式、哈希路径映射、压缩方式和导出布局。
- `references/FreesiaII` 包含用于对比数据包行为的 FreesiaII JAR 文件和发行版元数据。
- `references/decompiled` 包含用于检查协议和原生缓存行为的反编译/提取 YSM 客户端材料。
