# OpenYSM 逆向分析对比报告

## 1. OpenYSM 项目概述

OpenYSM (`references/OpenYSM`) 是一个**完整的 YSM Forge 模组逆向开源实现**，包含 892 个 Java 源文件，覆盖了 YSM 2.6.0 的全部功能：

- 完整的客户端渲染（模型、动画、Molang、GeckoLib3）
- 完整的服务端模型管理与缓存生成
- 完整的原生加密/解密协议（纯 Java 实现，不依赖 native DLL/SO）
- 音频系统（OGG Vorbis/Opus 解码）
- Capabilities 系统（玩家模型、载具、投射物、星标模型）

### 关键目录结构

```
OpenYSM/src/main/java/
├── com/elfmcys/yesstevemodel/
│   ├── model/ServerModelManager.java      # 服务端核心：模型加载、缓存生成、同步
│   ├── network/NetworkHandler.java         # 网络通道注册与消息分发
│   ├── network/message/                    # 所有网络消息类型
│   │   ├── S2CModelSyncPayload.java        # S2C 模型同步原始载荷
│   │   ├── C2SModelSyncPayload.java        # C2S 模型同步原始载荷
│   │   ├── S2CSyncPlayerStatePacket.java   # S2C 玩家状态同步
│   │   ├── S2CSyncAuthModelsPacket.java    # S2C 授权模型列表
│   │   └── ...
│   └── network/sync/PlayerStateSynchronizer.java  # 玩家状态同步器
└── rip/ysm/security/
    ├── YsmCrypt.java                       # 核心加密/解密（纯 Java）
    ├── YSMByteBuf.java                     # 自定义 ByteBuf 工具
    └── YSMClientCache.java                 # 客户端缓存管理
```

---

## 2. 加密体系对比

### 2.1 种子常量（完全一致）

| 用途 | OpenYSM (YsmCrypt) | PaperYSM (YsmSeeds) |
|------|--------------------|--------------------|
| MT19937 种子派生 | `0xD017CBBA7B5D3581` | `KEY_DERIVATION` |
| XChaCha20 状态初始化 | `0xA62B1A2C43842BC3` | `RES_VERIFICATION` |
| 服务端缓存解密 | `0xD1C3D1D13A99752B` | `CACHE_DECRYPTION` |
| 文件完整性校验 | `0x9E5599DB80C67C29` | `FILE_VERIFICATION` |
| 网络包校验 | `0xEE6FA63D570BD77B` | `PACKET_VERIFICATION` |
| 缓存完整性校验 | `0xF346451E53A22261` | `CACHE_VERIFICATION` |

### 2.2 引导密钥（Bootstrap Key）

OpenYSM 将硬编码引导密钥命名为 `publicKey`（56 字节），与我们在 native 二进制中找到的完全一致：

```
0FC77EF3 F4B8353A A2BA7FD3 1779468E
6542D098 8A9BB019 804F8156 366A1262
BE0EE5AD 4701D45E E4EBFB36 CB474298
F9E57A5C 3CDB2C76
```

### 2.3 加密算法实现

| 算法 | OpenYSM | PaperYSM |
|------|---------|----------|
| CityHash64（魔改） | `rip.ysm.algorithms.CityHash` | `CityHash64` (C++ JNI) |
| XChaCha20（魔改轮数） | `rip.ysm.algorithms.XChaCha20` | `XChaCha20` (C++ JNI) |
| MT19937_64 XOR | `rip.ysm.algorithms.MT19937` | `Mt19937_64` (Java) |
| Zstd（魔改 block header） | `rip.ysm.algorithms.YsmZstd` | `YsmZstd` (C++ JNI) |

**关键差异**：OpenYSM 的所有加密算法都是**纯 Java 实现**，不依赖任何 native 库。这意味着：
- 不需要加载 `libysm-core.so` / `ysm-core.dll`
- 不需要 VMProtect 脱壳
- 可以直接在 Paper 服务端运行

### 2.4 加解密流程

**网络包解密** (`YsmCrypt.decrypt`)：
```
packet → verify CityHash64(PACKET_VERIFICATION) → MT19937_XOR(key) → XChaCha20(key, 30轮) → plaintext
```

**网络包加密** (`YsmCrypt.encrypt`)：
```
plaintext → [append nextKey if needed] → XChaCha20(key, 30轮) → MT19937_XOR(key) → append CityHash64 → packet
```

**.ysm 文件解密** (`YsmCrypt.decryptYsmFile`)：
```
file → read key(32)+iv(24)+hash(8) from tail → verify CityHash64(FILE_VERIFICATION)
     → ModifiedChaChaDecrypt(key, iv, RES_VERIFICATION) → MT19937_XOR → skip padding → Zstd decompress
```

**服务器缓存加密** (`YsmCrypt.encryptServerCache`)：
```
plaintext → Zstd compress → add random padding → MT19937_XOR → ModifiedChaChaEncrypt(CACHE_DECRYPTION)
          → prepend VarInt headers → append CityHash64(CACHE_VERIFICATION) ^ hash1 ^ hash2
```

---

## 3. 网络协议对比

### 3.1 通道注册

| | OpenYSM | PaperYSM |
|--|---------|----------|
| Channel | `yes_steve_model:2_6_0` | `yes_steve_model:2_6_0` |
| 协议版本 | `2.6.0` | `2.6.0` |
| 传输层 | Forge SimpleChannel | Bukkit PluginMessage |

### 3.2 消息类型映射

OpenYSM 的 NetworkHandler 注册了以下消息类型：

| ID | 方向 | 类型 | PaperYSM 对应 |
|----|------|------|--------------|
| 1 | S2C | `S2CModelSyncPayload` | `SERVER_RAW_PACKET_ID` |
| 2 | C2S | `C2SModelSyncPayload` | `CLIENT_RAW_PACKET_ID` |
| 3 | S2C | `S2CExecuteMolangPacket` | `MOLANG_EXECUTE_ID` |
| 4 | S2C | `S2CSetModelAndTexturePacket` | `ENTITY_DATA_UPDATE_ID` |
| 5 | C2S | `C2SRequestSwitchModelPacket` | `CLIENT_MODEL_SELECTION_ID` |
| 6 | S2C | `S2CSyncAuthModelsPacket` | 授权模型列表 |
| 7 | C2S | `C2SPlayAnimationPacket` | `CLIENT_ANIMATION_REQUEST_ID` |
| 15 | C2S | `C2SCompleteFeedbackPacket` | 传输完成反馈 |
| 17 | C2S | `C2SRequestExecuteMolangPacket` | `CLIENT_MOLANG_EXECUTE_REQUEST_ID` |
| 19 | S2C | `S2CSyncAnimationExpressionPacket` | 动画表达式同步 |
| 21 | S2C | `S2CSyncPlayerStatePacket` | `ANIMATION_ID` |
| 22 | S2C | `S2CSyncVehicleModelPacket` | 载具模型同步 |
| 51 | S2C | `S2CVersionCheckPacket` | `SERVER_HANDSHAKE_ID` |
| 52 | C2S | `C2SVersionCheckPacket` | `CLIENT_HANDSHAKE_ID` |

### 3.3 原生同步握手流程（完整还原）

OpenYSM 的 `ServerModelManager` 实现了完整的原生同步流程：

#### Step 0: 服务端初始化
```
1. reloadPacks() → 扫描 built/custom/auth 目录
2. 对每个 .ysm 文件调用 YsmCrypt.decryptYsmFile() 解密
3. 用 YSMBinaryDeserializer 反序列化
4. 生成 server cache 文件 (encryptServerCache)
5. 生成 serverKey (56字节随机) 并持久化到 server_index JSON
```

#### Step 1: S2C Handshake Ping (type=1)
```java
// ServerModelManager.nativeSyncModels()
// 用 publicKey (硬编码引导密钥) 加密
byte[] garbage = randomBytes(16~63);
outBuf.writeGarbageHeader(garbageLen, garbage);
outBuf.writeByte(0x01);  // PacketId
EncryptedPacket result = YsmCrypt.encrypt(outBuf.toArray(), YsmCrypt.publicKey, true);
state.key1 = result.nextKey();  // 保存 S2C 会话密钥
sendModelData(uuid, result.data());
```

#### Step 2: C2S Pong (type=2)
```java
// 客户端用 publicKey 解密得到 S2C key，然后用 S2C key 加密回复
// 服务端在 nativeSendModelData() step==1 时处理
byte[] decrypted = YsmCrypt.decrypt(packetBytes, state.key1);
state.clientNextKey = decrypted[-56:];  // C2S 会话密钥
// 验证 type==2
```

#### Step 3: S2C Model Manifest (type=3)
```java
// ServerModelManager.sendPacket03()
outBuf.writeVarInt(3);           // type
outBuf.writeVarLong(0L);         // 决定 cache 文件夹名
outBuf.writeBytes(serverKey);    // ServerCacheKey (56字节)
outBuf.writeBytes(clientKey);    // ClientCacheKey (56字节)
outBuf.writeVarInt(modelCount);
for (model : models) {
    outBuf.writeVarLong(hash1);  // CityHash64(CACHE_VERIFICATION)
    outBuf.writeVarLong(hash2);  // CityHash64(CACHE_DECRYPTION)
    outBuf.writeString(modelId);
    outBuf.writeVarInt(flags);
    outBuf.writeVarInt(format);
}
// 还包含 pack 元数据（图标、名称、描述、本地化）
outBuf.writeVarInt(packCount);
for (pack : packs) { ... }
outBuf.writeVarInt(0);  // terminator
// 用 clientNextKey (C2S key) 加密
```

#### Step 4: C2S Model Request (type=4)
```java
// 客户端选择需要的模型，发送请求
// 服务端在 nativeSendModelData() step==2 时处理
byte[] decrypted = YsmCrypt.decrypt(packetBytes, state.key1);
// 解析 type==4, 读取 requestedHashes (hash1, hash2) 列表
```

#### Step 5: S2C Model Chunks (type=5)
```java
// ServerModelManager.sendPacket05()
for (hashes : requestedHashes) {
    byte[] fileData = Files.readAllBytes(CACHE_SERVER.resolve(fileName));
    int chunkSize = 32000;
    for (offset = 0; offset < totalSize; offset += chunkSize) {
        outBuf.writeVarInt(5);           // type
        outBuf.writeVarLong(hash1);
        outBuf.writeVarLong(hash2);
        outBuf.writeVarInt(totalSize);
        outBuf.writeVarInt(offset);
        outBuf.writeVarInt(length);
        outBuf.writeBytes(fileData, offset, length);
        // 用 key1 (S2C key) 加密
    }
}
```

#### Step 6: 客户端缓存落盘
客户端收到 type=5 数据后：
1. 用 ServerCacheKey 解密得到明文
2. 用 ClientCacheKey 重新加密
3. 保存为本地 cache 文件

---

## 4. 与 PaperYSM 的关键差异

### 4.1 架构差异

| 维度 | OpenYSM | PaperYSM |
|------|---------|----------|
| 平台 | Forge (客户端+服务端) | Paper (纯服务端) |
| 加密实现 | 纯 Java (rip.ysm.algorithms.*) | 依赖 native DLL/SO (JNI) |
| 模型加载 | 直接读 .ysm 文件 + 解密 | 需要 FreesiaII Worker |
| 缓存生成 | 纯 Java (encryptServerCache) | 依赖 native |
| 渲染 | 完整客户端渲染 | 无（纯服务端） |

### 4.2 PaperYSM 缺失但 OpenYSM 已实现的功能

1. **纯 Java 加密算法**：不需要 native DLL/SO
2. **服务端缓存生成**：`encryptServerCache()` 可直接从 .ysm 生成缓存
3. **完整的 type=3 manifest**：包含 ServerCacheKey + ClientCacheKey + 模型清单 + pack 元数据
4. **玩家状态同步**：`PlayerStateSynchronizer` 同步血量、饥饿、药水效果、飞行状态、输入方向
5. **授权模型系统**：`S2CSyncAuthModelsPacket` 控制玩家可用模型
6. **传输完成反馈**：`C2SCompleteFeedbackPacket` 确认传输完成
7. **可靠传输**：`sendPacketReliably()` 带背压控制的可靠包发送

---

## 5. 对 PaperYSM 插件的帮助

### 5.1 可以完全摆脱 FreesiaII Worker

OpenYSM 证明了**所有加密/解密都可以用纯 Java 完成**。PaperYSM 可以：

1. **直接移植 `rip.ysm.security.YsmCrypt`** 到 Paper 插件
2. **直接移植 `rip.ysm.algorithms.*`**（CityHash、XChaCha20、MT19937、YsmZstd）
3. **用 `YsmCrypt.decryptYsmFile()` 直接解密 .ysm 文件**
4. **用 `YsmCrypt.encryptServerCache()` 直接生成服务器缓存**
5. **用 `YsmCrypt.transcodeServerDataToClientCache()` 转码为客户端缓存**

这意味着：
- 不需要启动 FreesiaII Worker 进程
- 不需要 native DLL/SO
- 不需要 JNI 桥接
- 模型加载和缓存生成完全在 Paper JVM 内完成

### 5.2 实现非同步网络（玩家自治模型分发）

OpenYSM 的 `PlayerStateSynchronizer` 启示了一种**轻量级状态同步架构**：

#### 方案 A：纯状态分发（推荐）

```
服务端职责：
1. 加载 .ysm 文件 → 解密 → 生成缓存
2. 维护 玩家UUID → (模型ID, 纹理) 映射
3. 当玩家 A 进入玩家 B 的视野时，向 B 发送 A 的模型选择（type=4）
4. 如果 B 没有该模型的缓存，分发缓存（type=5 分片）
5. 分发玩家状态 delta（血量、药水、动画状态等）

客户端职责：
1. 本地缓存已下载的模型
2. 接收其他玩家的模型选择和状态
3. 自行渲染其他玩家的模型和动画
```

#### 方案 B：Chunk-based 分发（更精细）

参照 OpenYSM 的 `sendPacket05()`，按 32KB 分片发送：
- 支持断点续传（offset 字段）
- 支持背压控制（`PendingTransfer`）
- 支持多模型并行传输

#### 方案 C：视野距离内区块分发

```java
// 参照 OpenYSM 的 requestPlayerAuth() 实现
public void onPlayerMove(ServerPlayer player) {
    List<ServerPlayer> nearby = getPlayersInRenderDistance(player);
    for (ServerPlayer target : nearby) {
        if (!hasModelCache(target, player.getModelId())) {
            sendModelCache(target, player.getModelId());
        }
        sendPlayerState(target, player);  // 轻量级 delta 同步
    }
}
```

### 5.3 具体移植步骤

#### Phase 1: 移植加密算法
1. 复制 `rip/ysm/algorithms/` 目录（CityHash、XChaCha20、MT19937、YsmZstd）
2. 复制 `rip/ysm/security/YsmCrypt.java`
3. 复制 `rip/ysm/security/YSMByteBuf.java`
4. 验证：用已知 .ysm 文件测试 `decryptYsmFile()`

#### Phase 2: 实现服务端模型管理
1. 参照 `ServerModelManager.reloadPacks()` 实现模型扫描
2. 参照 `scanDirectoryModels()` 实现 .ysm 文件加载
3. 参照 `processAndCacheModel()` 实现缓存生成
4. 参照 `nativeSyncModels()` 实现握手流程

#### Phase 3: 实现网络协议
1. 参照 `NetworkHandler.init()` 注册所有消息类型
2. 参照 `sendPacket03()` 实现模型清单分发
3. 参照 `sendPacket05()` 实现模型分片传输
4. 参照 `PlayerStateSynchronizer` 实现状态同步

#### Phase 4: 实现非同步分发
1. 实现视野距离检测
2. 实现按需缓存分发
3. 实现 delta 状态同步
4. 实现传输完成反馈

---

## 6. 总结

OpenYSM 是一个**完整的、可直接参考的 YSM 逆向实现**。它证明了：

1. **所有 native 加密逻辑都可以用纯 Java 复现**，无需 DLL/SO
2. **服务端缓存生成完全可以在 JVM 内完成**，无需 FreesiaII Worker
3. **网络协议已经完全逆向**，握手流程清晰可循
4. **非同步网络完全可行**，只需分发模型选择 + 状态 delta + 按需缓存

PaperYSM 应该优先移植 OpenYSM 的加密算法层（Phase 1），这将彻底消除对 native 依赖的需求，为后续功能开发奠定基础。
