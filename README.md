# VoxyServer

Fabric / Minecraft **26.3** · Java **25** · Voxy **0.2.20-beta**

**v1.3.0 使用协议 6，客户端和服务端必须一起升级。** 已发布的旧 tag 保持不变。服务端将已经拥有的地形转换成 Voxy LOD，客户端主动比较版本并请求所需的最高精度数据。

## 同步流程

1. 客户端首次连接后开始同步，此后每隔随机 **90–150 秒**校验一次。先发送很小的申请头；服务端确认并冻结本轮维度、中心、有效视距和高度范围，返回范围授权。玩家移动不会改变正在进行的这一轮范围。
2. 客户端枚举自己的 LOD0 存储键和已确认版本，在授权范围内为**所有大分块**提交版本号摘要，包括空分块。需求不依赖当前渲染器订阅。
3. 服务端只为摘要不同的大分块返回完整的内部 **LOD0 键及 32 位版本表**。双端按固定的 X、Z、Y 顺序处理版本，缺失位置为 0；不读取体素内容计算语义哈希。分页数据完成并校验前不作为完整快照使用。
4. 客户端比较版本表，明确选出缺失、变化或未确认的 LOD0 节点。最终选择清单按玩家高度与位置附近优先，分页交付，每页最多 256 个节点；上一页交付完成后提交下一页。版本摘要的固定坐标顺序不受发送优先级影响。
5. 服务端发送这些节点。**只交付 LOD0**；客户端按原版 Voxy 的 mip、子节点状态和邻接更新规则生成 LOD1–LOD4，并在安装成功后回复批次 ACK。

**每位玩家只允许一轮同步进行。** 包括元数据比较和数据交付在内，旧轮次未完成时，新轮次收到 BUSY，不替换旧任务、不累积视野任务。相同页可重放；旧 epoch、轮次、页或批次不能推进新任务。

带宽桶与每 tick 节点数量限制只用于最后的地形交付。摘要和版本表不使用旧的请求次数限流、距离抽样节流或语义哈希预算；控制报文仍有分页、队列和内存边界，并通过工作调度让玩家公平获得处理机会。

### 数据保护

- 服务端缺失、未能读取的节点不会被伪造成空气包。客户端保留自己缓存的地形。
- 元数据失效只取消版本确认，不删除 Voxy 地形。明确存在的服务端节点可以覆盖对应位置，包括实际保存的全空气节点。
- 客户端收到版本表不会立即记录为“已经拥有”；实际数据安装完成后才记录对应服务端版本。客户端原生摄取修改该节点时，取消这个确认。
- 版本回退时对应客户端确认归零，`0` 表示未确认，服务端有效版本从 `1` 开始。不会通过给本地缓存随意分配相同版本来声称同步成功。
- 版本相同表示更新记录一致，**不再证明体素内容一致**。Minecraft 数据、Voxy LOD 数据和版本索引需要配套备份、恢复。

## 低频维护与版本记录

方块、光照、Biome 回调只标记源区块段。一个维护线程负责复制调色板、转换、合并 LOD0 写入和发布版本，不在红石事件回调内更新索引、编码或发送数据。

同一 LOD0 节点每次更新后的冷却时间默认为 **30 秒，再随机增加 0–30 秒**。首次处理仍受静默合并时间和工作预算影响。一个 LOD0 节点的多个源区块段一批写入，发生实际变化后最多发布一个新版本；原版 Voxy 的逐值比较用于识别无变化写入，不计算内容哈希。持续变化的源在等待边界后仍可处理，不要求红石设备停止。

重复加载、卸载同一列的完整维护申请默认在 **180 秒**内合并；实际方块、光照和 Biome 脏事件绕过这项整列申请限制。已有待办仍会处理并持久化，不通过清空队列掩盖积压。

版本索引保存在 `<world>/voxyserver/lod0-versions-v6.bin` 及其增量日志。首次升级对已有 Voxy **LOD0 键**做一次后台迁移，赋予初始版本；不读取整张地图的体素，不加载原版区块。日常同步复用该索引和分块摘要缓存。索引正文读取、迁移和日志写入在工作线程进行。正常关闭原生存储后才写入完整关闭标记；异常退出后的版本记录重新建立信任范围，客户端保留地形并重新校验。

客户端版本侧文件位于游戏目录 `voxyserver/versions/`。文件与服务器世界、版本记录身份及原生世界区分；原生存储正常关闭后才确认持久记录可复用。元数据文件损坏或未完成关闭时按未确认处理。

首次接入、版本索引迁移或冷缓存需要传输真实 LOD0 数据。大视距不会自动生成不存在的 LOD，也不保证在一轮低带宽连接中立即补满。已经生成的 Minecraft 区块与已经转换的 Voxy LOD 是两回事；未转换的旧地图可显式导入。

### 已移除的运行机制

- 后台随机加载、生成远处 Minecraft 区块及其票据、速率控制和相关命令。
- 渲染订阅驱动的远端探测，以及等待远端数据时取消本地网格入队的拦截。
- 语义内容哈希链路、旧 Manifest 门控、距离抽样发送和旧 RegionHash 同步入口。
- 小型编码缓存导致的反复补哈希、分组变化撤销整轮请求的流程。

旧 `backgroundGeneration*`、语义哈希和距离节流配置不再生效，也不会重新启动这些机制。历史回归所需的旧实现保留在测试源集中，不进入运行 JAR 或 sources JAR。

## 安装与升级

服务端安装 Fabric API、兼容 Voxy 和本模组。客户端安装 Fabric API、Voxy、Sodium 和本模组。ModMenu 与 Cloth Config 可用于客户端设置界面。

停止两端，移除旧 VoxyServer JAR，放入同一个 v1.3.0 运行 JAR 后重启。**保留世界、`.voxy` 缓存和现有 LOD 存储。** 协议 5 客户端不会接收协议 6 地形流。

客户端个人设置位于 `config/voxyserver-client.json`，支持按服务器地址保存启用状态、远景半径和每 tick 数量偏好。有效范围取 Voxy 实际视距、个人设置和服务端上限。Voxy 的 `section_render_distance` 使用 512 方块顶层单位：16 对应 512 个 Minecraft 区块，32 对应 1024 个区块。

集成端／单人／局域网房间的服务端运行支持没有加入本版本。

## 服务端命令

所有 `/voxyserver` 命令，包括查询、帮助和 stats，均要求原版 `COMMANDS_ADMIN` 权限（等级 3 或更高）或控制台权限。

| 命令 | 作用 |
|---|---|
| `/voxyserver enabled false` | 停止并关闭服务端运行实例 |
| `/voxyserver enabled true` | 启动服务端运行实例 |
| `/voxyserver enabled` | 查询总开关 |
| `/voxyserver config list` | 列出当前配置及生效方式 |
| `/voxyserver config get <key>` | 查询一个配置项 |
| `/voxyserver config set <key> <value>` | 校验、保存并应用一个配置项 |
| `/voxyserver config reset` | 恢复全部服务端默认设置 |
| `/voxyserver config reset <key>` | 只恢复指定项；两个视距别名一起恢复 |
| `/voxyserver reload` | 从配置文件重新读取并校验 |
| `/voxyserver radius <chunks>` | 修改远景发送半径上限，1–4096 区块 |
| `/voxyserver bandwidth <bytes/s>` | 修改最终数据发送带宽，0 表示不限 |
| `/voxyserver threads <count>` | 修改原生 Voxy 工作线程数量 |
| `/voxyserver stats` | 显示版本节点、维护待办、活跃同步和发送数量 |
| `/voxyserver stats continuous` | 向管理员每秒显示 stats |
| `/voxyserver stats stop` | 停止连续显示 |

恢复默认值只改变设置，不删除地形、客户端缓存、版本索引或导入进度。配置首次读取、热重载和命令修改使用相同验证；无效文件保留用于修复，启动回退到验证过的默认值，热重载拒绝应用无效值。

### 配置项

文件：`config/voxyserver.json`。以下名称可直接用于 `config get/set/reset`。

| 选项 | 默认值 | 作用及命令示例 |
|---|---:|---|
| `enabled` | `true` | 总开关：`/voxyserver enabled false` |
| `maxClientVoxyRadius` / `lodStreamRadius` | `1024` | 远景上限；`/voxyserver radius 512`，范围 1–4096 |
| `maxSectionsPerTickPerPlayer` | `100` | 最终数据每玩家每 tick 的节点上限：`/voxyserver config set maxSectionsPerTickPerPlayer 100` |
| `sectionsPerPacket` | `16` | 每包节点数；线上的技术上限始终为 16 |
| `maxBandwidthBytesPerSec` | `250000` | 每玩家最终数据带宽：`/voxyserver bandwidth 1000000` |
| `workerThreads` | `3` | 原生存储／服务工作线程，1–10000；维护调度另用一个线程 |
| `maintenanceMillisPerSecond` | `20` | 源转换及最终编码的工作线程时间预算：`/voxyserver config set maintenanceMillisPerSecond 50` |
| `maintenanceQuietMillis` | `5000` | 源变化静默合并时间；连续变化也有最大等待边界，另受队列和冷却影响 |
| `maintenanceCooldownMillis` | `30000` | 同一 LOD0 更新的最短冷却时间，毫秒 |
| `maintenanceCooldownJitterMillis` | `30000` | 冷却随机增加量的上限；两项相加形成默认 30–60 秒 |
| `sourceColumnRecheckSeconds` | `180` | 重复整列加载／卸载维护申请的间隔；0 允许每次申请，实际脏事件不受此项阻挡 |
| `generateOnChunkLoad` | `true` | 记录自然加载的区块用于转换；不主动加载远处区块，修改需重启运行实例 |
| `generateOnChunkUnload` | `true` | 记录自然卸载区块；开启脏追踪时仍会记录卸载，修改需重启运行实例 |
| `dirtyTrackingEnabled` | `true` | 记录实际方块、光照和 Biome 变化，修改需重启运行实例 |
| `debugTrackingEnabled` | `false` | 开启周期诊断：`/voxyserver config set debugTrackingEnabled true` |
| `debugTrackingInterval` | `200` | 诊断日志间隔，单位 tick，20 TPS 时 200 为 10 秒 |

正整数工作量选项允许管理员设置正的 32 位整数；带宽、静默、冷却、随机增量和整列重查允许 0。队列字节上限、每包 16 节点及视距技术上限保持有效。实际 CPU、磁盘和网络吞吐不会因为提高数值而无限增加；时间预算也无法抢占已经开始的一次原生操作。

例如临时提高数据交付速度，同时保持低频源更新：

```text
/voxyserver bandwidth 1000000
/voxyserver config set maintenanceMillisPerSecond 50
/voxyserver config set debugTrackingEnabled true
/voxyserver stats continuous
```

第一条放宽网络带宽，第二条增加源转换和数据编码的工作时间，不改变 30–60 秒冷却。修改带宽不会撤销正在传输的轮次。用 `config reset <key>` 恢复指定默认值。

### 总开关

关闭时先停止新任务，再停止导入、持久化待办、排空并结束维护线程，最后关闭 Voxy 存储工作线程。服务器不再运行本模组的周期配置读取、更新检查、源转换或数据流。已加载的类、命令、网络类型和空回调仍有小量常驻成本；可随时重新开启的模组不能承诺字面上的零资源。

关闭期间不记录世界变化；重新开启不会全世界扫描补账。之后的加载／卸载、实际脏事件或显式导入用于更新旧地形。关闭期间修改文件后，需要命令重载或重启，关闭状态本身不轮询配置。

### 已有地图导入

这些命令显式读取已有 region 文件，不要求玩家访问，也不主动生成缺失区块：

```text
/voxyserver import existing all
/voxyserver import existing current
/voxyserver import existing dimension minecraft:overworld
/voxyserver import existing status
/voxyserver import existing cancel
```

在 `all`、`current` 或维度命令后加 `force` 可要求重新导入。进度保存在各 LOD 存储的 `import-checkpoints/` 中。世界回档时停止服务，配套恢复 Minecraft 世界、`<world>/voxyserver/` 及版本索引；只恢复原版 region 文件会留下过期 LOD，需要显式重新导入。正常日常维护不启动全世界导入。

## 队列、恢复与诊断

- 保留每玩家严格 **128 包／8 MiB** 的出站上限；本协议每玩家实际只保留一个待发／在途数据批次，每批最多 16 节点。
- 最终选择每页最多 256 节点，不提前把整个视野编码到发送队列。
- 客户端保持 **64 批／64 MiB** 入站限制及独立安装工作线程。本地 Voxy 摄取、网格构建持续工作。
- ACK 超时从实际发送时开始，排队等待带宽不计为 ACK 超时。失败、溢出、过期及超时释放批次，再做有上限的恢复尝试；重复或旧 ACK 不会重复释放。
- 维度切换、断线和新会话关闭旧租约。版本变化不撤销已接受的数据交付；每个包的版本与其数据快照一起捕获，更新留待后续校验。
- `version sync` 日志显示版本记录、源待办、实际 LOD0 发送量、完成／失败／BUSY、ACK 超时、重复整列申请合并量和工作耗时。每玩家记录含队列包数、字节数及失败重试量。
- 客户端日志显示 `version LOD session active`、`client version sync` 和 `remote LOD0 installed`。安装 ACK 不表示 GPU 已经完成绘制。

## 本次实现对应文件

| 文件 | 行为变化 |
|---|---|
| `client/ClientDemand.java`、`ClientVersionStore.java` | 客户端枚举版本、随机低频复查、分阶段选择及只修改确认元数据 |
| `server/LodStreamingService.java` | 冻结范围、完整版本表、单轮 BUSY、单批在途及有上限的 ACK 恢复 |
| `sync/version/VersionIndex.java`、`VersionDigest.java` | 持久化 32 位版本、固定位置顺序摘要及共享摘要缓存 |
| `server/SourceMaintenance.java`、`sync/DirtyRegionQueue.java` | 脏事件合并、30–60 秒冷却、重复整列申请抑制及原生写入后发布版本 |
| `network/Version*Payload.java`、`client/service/RemoteIngestService.java` | 协议 6、LOD0 编解码、实际安装成功后确认；不发送孤立 LOD4 |
| `server/throttle/PlayerOutboundQueue.java`、`BandwidthThrottler.java` | 复用严格字节队列、带宽桶和队列清理；不再承担距离抽样 |
| `config/VoxyServerConfig.java`、`server/VoxyServerCommands.java` | 保留一致校验、管理员权限和恢复默认命令，移除废弃配置入口 |

此前渲染订阅与分组修订可反复撤销尚未完成的交付；现在依次经过“申请范围 → 固定顺序版本摘要 → 完整差异版本表 → 客户端逐页选择 → LOD0 安装 ACK → 本轮完成”。维护期间再次修改节点不会撤销本轮，包携带与数据快照对应的实际版本，下一次低频校验处理后续变化。

## 构建与验证

Voxy 的源码和运行 JAR 不随本仓库分发。构建兼容 Voxy 后，将其 JAR 放在 `libs/`，例如 `libs/voxy-26.3.jar`。使用 Java 25：

```bash
./gradlew clean build check runRegressionTests runNativeRegression runNativeClientRegression
./scripts/test-semantic-sync.sh
```

Windows 使用 `gradlew.bat`。产物在 `build/libs/`。第二个脚本只验证已退出运行链路的历史兼容测试夹具；当前协议的核心测试是 `VersionSyncTest`、`VersionLoadTest` 和 Fabric 原生 `NativeVersionSyncTest`。

本版本 `check` 实际执行的独立回归为 **24/24 套通过**，两个 Fabric 原生回归启动任务及历史脚本均通过。自动化覆盖固定版本顺序、完整范围与边界、重复／乱序／截断页、版本持久化和异常关闭、回退归零、范围外记录保留、BUSY 拒绝、旧／重复／未发送 ACK、30–60 秒脏事件合并、严格出站字节限制、配置与管理员权限。原生测试使用真实 Voxy Mapper、WorldUpdater 和 mip，验证无变化写入、八个源段合并发布一次版本、LOD0 编码、父级及邻接事件；不启动 GPU。

32768×32768 方块、384 高度的完整几何包含 **12,582,912 个 LOD0 节点**。合成元数据测试建立这些 32 位版本，并让 256 个模拟玩家复用 8,450 个范围摘要；第二轮不重新计算已有分块摘要。单轮摘要数值共 **67,600 字节**，逐节点的键与版本数值为 **150,994,944 字节**。这只是元数据比较，不是实际地形或总网络流量节省率，也不代表真实 256 人 Minecraft 负载。

提供的地图副本另做了实际 Fabric 服务端与 4 个回环协议探针的测试，覆盖 512/1024 区块视距、带宽、分页和 BUSY。60 秒测试中，两位小范围玩家各完成 3 轮并出现无需重传的匹配轮次；512/1024 视距玩家各收到 2,128 个 LOD0 节点，约 37 MB 的编码数据，大范围冷同步尚未完成。一次主动失败成功重试，晚到的旧 ACK 未推进新批次；传输中修改带宽和每 tick 上限、重发个人偏好均未替换会话。四个玩家首包延迟约 1.6–10.3 秒（含一次失败场景），首批节点位于 Y=32 或 64，出站队列峰值低于 0.5 MB，未出现 ACK 超时。探针校验数据帧并在内存中记录版本，**不运行真实 Voxy 客户端或 GPU**。真实整合包画面、SSAO 边缘、海面接缝及长期生产负载仍需实际客户端验证；首次大范围冷同步可能持续多轮时间，未完成交付期间不会叠加新轮次。

可在**离线模式的一次性测试服务器副本**运行协议探针；探针作为玩家登录，会触发正常区块生命周期事件，不应直接用生产世界测试：

```bash
python3 scripts/test-live-version-sync.py 25565 60 512 V6Probe --inject-failure
```

此脚本固定 Minecraft 26.3（原版网络协议 777），依赖 Python 标准库。为验证去重而连续执行至多 3 轮，正式客户端仍遵守随机 90–150 秒间隔。
