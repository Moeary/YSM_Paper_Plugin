# PaperYSM 模型入库流程

当前推荐流程已经改成“真实 Velocity/Freesia 捕获优先”。不要再用 worker
`cache/server` 文件按目录顺序去手配 type3；刚才的测试已经证明那样会得到
可读名字但错误 cache body，客户端可能不回 type4，或者请求到的 token 对不上。

```mermaid
flowchart TD
    A["外部 .ysm 模型目录"] --> B["复制到 freesia-worker/config/yes_steve_model/custom/<分组>"]
    B --> C["启动完整 Velocity/Freesia 栈"]
    C --> D["客户端从 127.0.0.1:30000 进 Velocity"]
    D --> E{"客户端 YSM cache 是否干净?"}
    E -- "干净" --> F["Freesia 发送完整 type3/type5"]
    E -- "已有缓存" --> G["Freesia 可能只发送缺失条目"]
    F --> H["ysm-sniffer + latest.log 捕获真实 S2C/C2S"]
    G --> H
    H --> I["scripts/paperysm.bat export-capture"]
    I --> J["生成 Paper fixture: type3-body.bin + cache-map.tsv + server-cache"]
    J --> K["direct-paper /ysm source default freesia-from-velocity"]
    K --> L["玩家 /ysm sync 或进服自动同步"]
    L --> M["测试模型列表、切换、可见性、轮盘动画"]
```

推荐命令：

```powershell
scripts\paperysm.bat copy-models "D:\BaiduNetdiskDownload\YSM模型（尽快保存下载） (2)\游戏IP分类" --group "游戏IP分类"
scripts\start-freesiaii-stack.bat
scripts\paperysm.bat export-capture
```

也可以使用短包装：

```powershell
scripts\sync-velocity-cache-to-paper.bat
```

导出后在 Paper 服里执行：

```text
/ysm source default freesia-from-velocity
/ysm sync
```

`export-report.tsv` 是验收重点：模型行需要有非空 file 且 `gaps=0`。
如果大量行没有 file，说明这次 Velocity/Freesia 捕获时客户端已经有缓存，
Freesia 没有重发对应 type5。要做可搬到其他客户端/服务器的完整 fixture，
需要清掉客户端 YSM cache 后重新进 Velocity 捕获。

旧图 `paperysm-ingest-flow.png` 是早期“worker 生成 cache 后再导出”的思路，
现在只作为历史记录。PaperYSM 的职责边界保持简单：Freesia/worker 负责产生
真实 native cache 和 type3/token map，PaperYSM 负责在 Paper 服分发 capture、
同步模型状态和桥接动画。`models-dir` 只作为动画映射和模型元数据参考，可以
指向 worker 的模型目录，也可以为空；为空时不影响已生成 cache 的分发，只是
部分模型动作可能无法映射播放。
