# voxyserver

a fabric server side mod that voxelizes chunks into LODs using [voxy](https://github.com/MCRcortex/voxy) and streams them to connected clients. players with voxy installed will receive LOD data from the server automatically, the client reports its active render nodes; no world-wide scan is required.

**Latest: minecraft 26.3 | fabric**

## how it works

Release **v1.2.7** uses **protocol 5** on Fabric / Minecraft 26.3. Both sides must update together. Published `v1.2.5` (`4deea7a`) is unchanged.

The client derives demand from Voxy's actual render subscriptions at native LOD levels **0–4**. Each node contains 32³ voxels and spans `32 << level` blocks. Voxy's `sectionRenderDistance` uses **512-block top-level units**: values 16 and 32 mean 512 and 1024 Minecraft chunks. The remote range is capped by the native view, personal preference and server maximum. The server validates the requested coordinates, dimension, epoch and rate without enumerating the view grid or the database.

1. Client compares one semantic digest and coverage mask per fixed 4×4×4 group **at the same LOD**.
2. When that group differs, client supplies hashes only for its server-present leaves. The server sends only differing/missing native nodes. Native child topology participates in the proof; a coarse voxel hash is never treated as a digest of its finer descendants.
3. Stable groups audit again after a randomized **150–210 seconds**. Newly watched nodes and changed groups start earlier. One client proof worker reads actual local data, with round-robin groups and bounded hashing; a second worker ingests received data. Native Voxy ingestion remains enabled.
4. A single server maintenance worker coalesces source changes, updates native LODs and fills a shared on-demand hash index. Block/light/biome notifications only mark source bits. There is no startup world enumeration, player spiral scanner, automatic full import or per-block hash computation. Optional background world generation uses bounded point sampling as described below.

Clients are **overwrite-only**. A real server node, including an explicitly stored all-air node, may replace that same native node. Missing or unreadable server nodes never clear local terrain. Coarse updates preserve independently cached finer nodes and child references. The server's absence is not synthesized as an all-air replacement, including when Voxy returns a cached missing-node placeholder.

Players without the matching mod/protocol receive no LOD stream. Existing worlds can still be backfilled through the explicit admin import commands below.

## building

due to voxy's license, the source and binary can't be included in this repo. you'll need to clone and build it yourself.

`build.gradle` automatically matches any jar placed in the `libs/` folder (`libs/*.jar`), such as `libs/voxy-26.3.jar` or `libs/voxy.jar`.

### unix

1. clone voxy into the root of this project:
   ```bash
   git clone https://github.com/MCRcortex/voxy.git
   ```

2. build the voxy jar:
   ```bash
   cd voxy
   ./gradlew build
   ```

3. go back to the project root, make the `libs` directory, and copy the built jar:
   ```bash
   cd ..
   mkdir -p libs
   cp voxy/build/libs/voxy-*.jar libs/voxy-26.3.jar
   ```

4. build voxyserver:
   ```bash
   ./gradlew build
   ```

the output jar will be in `build/libs/`.

---

### windows

*note: please use command prompt (cmd) for these commands, not powershell.*

1. clone voxy into the root of this project:
   ```cmd
   git clone https://github.com/MCRcortex/voxy.git
   ```

2. build the voxy jar:
   ```cmd
   cd voxy
   gradlew build
   ```

3. go back to the project root, make the `libs` directory, and copy the built jar:
   ```cmd
   cd ..
   mkdir libs
   for %f in (voxy\build\libs\voxy-*.jar) do copy "%f" libs\voxy-26.3.jar
   ```

4. build voxyserver:
   ```cmd
   gradlew build
   ```

the output jar will be in `build\libs\`.

## installation

drop the voxyserver, voxy & sodium jar's into the server's `mods/` folder. no other server side dependencies are needed.

clients just need voxy installed as normal along with voxyserver.

## config

config file is generated at `config/voxyserver.json` on first run.

| option | default | description |
|--------|---------|-------------|
| `maxClientVoxyRadius` / `lodStreamRadius` | `1024` | maximum remote radius in Minecraft chunks; validated legacy aliases |
| `maxSectionsPerTickPerPlayer` | `100` | per-player send limit, further capped by client preference |
| `sectionsPerPacket` | `16` | native nodes per packet; the wire hard cap remains 16 |
| `maxBandwidthBytesPerSec` | `250000` | per-player token bucket, `0` = unlimited |
| `hashSyncEnabled` | `true` | reuse semantic matches; disabling it still preserves absence and protocol limits |
| `maintenanceMillisPerSecond` | `20` | global worker wall-time allowance per second, including proofs, source conversion, hashing and packet encoding |
| `maintenanceQuietMillis` | `2000` | coalesce changes until a source root is quiet; continuous changes release after max(30 seconds, configured quiet time), plus queue delay |
| `maintenanceCooldownMillis` | `10000` | minimum repeat-update interval for a 32³ L0 node |
| `maxDemandRequestsPerSecond` | `20` | accepted requests per player per nominal server second |
| `hashWorkIntervalTicks` | `20` | shared hash allowance window, in nominal 50 ms units |
| `maxHashComputationsPerInterval` | `64` | new node hash limit across all players |
| `maxHashMillisPerInterval` | `10` | additional hashing time allowance inside the overall maintenance allowance |
| `workerThreads` | `3` | Voxy storage/service workers; the maintenance scheduler itself uses one thread |
| `generateOnChunkLoad` | `true` | enqueue loaded source sections for background ingestion |
| `generateOnChunkUnload` | `true` | enqueue final source updates, using vanilla pending-save reads without loading chunks |
| `dirtyTrackingEnabled` | `true` | enqueue actual block, light and biome changes |
| `debugTrackingEnabled` | `false` | log maintenance/hash/cache/proof/delivery and queue diagnostics |
| `debugTrackingInterval` | `200` | server ticks between diagnostic reports |

Old `tickInterval`, `dirtyTrackingInterval`, `enableDistancePacing`, `enableRegionHashSync`, `hashRecheckIntervalTicks` and `manifestRefreshIntervalTicks` fields remain accepted and validated for configuration compatibility. Protocol 5 uses client render demand, native LOD selection and the maintenance settings above instead of those scanner/inventory schedules.

Configuration is validated before application or saving. Invalid startup files produce field-specific diagnostics and use validated defaults without overwriting the file. Invalid reloads retain the current validated configuration; commands and saves use the same validation guard. Changing chunk lifecycle generation flags or enabling/disabling dirty tracking still requires a restart.

In v1.2.7 all defaults stay unchanged. Workload/timing integer settings now accept positive 32-bit values (up to 2,147,483,647); bandwidth, quiet/cooldown times and background delay/hold/backlog/history/recheck accept zero as documented. Generation starts accept 1..600000 per minute. The view protocol still limits radius to 4096, packets to 16 nodes, and queues retain their byte/packet caps. Voxy service threads accept 1..10000 because the native pool's shutdown releases 10000 permits. These technical bounds remain enforced.

### maintenance and memory

Each 512³ source root allocates up to 4 KiB of bits for changed 16³ source sections. Repeated redstone changes set the same bit. The worker groups ready sections by their containing 32³ native node and converts only the marked source octants. Notifications hold a short per-root lock; scanning cooldowns, journal writes, palette copying, conversion and semantic hashes run outside that lock and outside the Minecraft tick thread.

For live sources, the main thread performs at most four direct chunk lookups and eight section-reference/light captures per work item. Palette copies run on the maintenance worker under short section-local writer monitors. Unloaded sources use vanilla targeted chunk I/O; missing saves retry instead of inventing replacement data. This is asynchronous maintenance, not a claim of zero main-thread work or zero lock contention.

The index holds at most **65,536 node fingerprints**, **128 encoded node snapshots**, and **8,192 pending probes**, shared between players. It starts empty and verifies only requested native nodes after restart; it is not a persistent full-world index. Cold edits do not fill the index. Native updates invalidate affected cached fingerprints and negative-presence entries; unchanged group requests reuse them. Missing-node lookups use the overall I/O/time allowance without consuming the semantic-hash count limit. Source processing and hashing rotate so one cannot permanently starve the other. Request admission is capped at 256 pending requests globally and four per player, with round-robin service. Each player can subscribe to at most 1,024 groups; the client tracks at most 65,536 active native nodes.

Dirty work, including the active item, is journaled about every 30 seconds and on a clean stop. Startup merges it with newly loaded source work. A crash can lose the latest uncheckpointed dirty window, and Voxy's asynchronous storage writes are not a world-wide atomic checkpoint. Normal chunk loads queue source verification again. Existing loaded chunks may therefore incur bounded repeated ingestion after reconnect/reload; this implementation does not persist per-source freshness stamps or scan/rebuild the whole map.

A nonpreemptible node/source operation may finish beyond the configured worker allowance; that allowance is not a hard CPU/TPS guarantee. Main-thread captures, Voxy's own compression/save workers, manual imports and periodic journal/lease metadata are additional costs. Debug logs expose worker CPU/wall time, maximum work quantum, main capture time, hashes/cache hits, matched groups, actual sends, retries/ACK timeouts and per-player queue peaks/rejections/evictions. Saturated budgets delay convergence rather than moving heavy work onto the main thread.

### optional background generation

This side path slowly generates full Minecraft chunks near players, then marks their source sections for the existing asynchronous LOD maintenance worker. It is **disabled by default**. It samples an annulus, rotates between players, and never enumerates the view grid or region database. Targets stay outside the player's effective vanilla view plus two chunks, inside the configured radius and dimension whitelist, and inside the world border. A zero-width effective annulus produces no work. Spectators are excluded by default.

Before generation, targeted vanilla pending-save I/O checks the candidate. Already stored FULL chunks are skipped without loading them; known partial chunks may be completed. Unknown/corrupt status data is a failure, not permission to replace a chunk. Duplicate in-flight coordinates are suppressed across players; a bounded recent-target cache avoids frequent rechecks.

| setting | default | meaning |
|---------|---------|---------|
| `backgroundGenerationEnabled` | `false` | enable the optional path; all settings here apply live |
| `backgroundGenerationMinRadiusChunks` | `16` | inner target radius in Minecraft chunks, raised to at least effective vanilla view + 2 |
| `backgroundGenerationMaxRadiusChunks` | `64` | outer target radius, greater than minimum; positive 32-bit integer, with world-border validation |
| `backgroundGenerationChunksPerMinute` | `6` | global generation-start rate, 1..600000; default remains 6; high rates use bounded tick batches |
| `backgroundGenerationMaxConcurrent` | `1` | total pending/ready/generating/held/draining jobs, any positive 32-bit integer across all players |
| `backgroundGenerationCheckIntervalTicks` | `100` | ticks between candidate passes; native cancellations and completions are still checked each tick |
| `backgroundGenerationCandidatesPerPass` | `4` | maximum constant-size candidate attempts per pass, any positive 32-bit integer |
| `backgroundGenerationMaxProbeMillis` | `1` | soft main-thread scheduler/probe allowance per pass, positive 32-bit milliseconds; does not cap native generation time |
| `backgroundGenerationStartMspt` | `25.0` | headroom required to admit generation; finite, positive |
| `backgroundGenerationStopMspt` | `35.0` | cancel owned work immediately when observed MSPT exceeds this; must exceed start MSPT |
| `backgroundGenerationResumeDelayTicks` | `600` | continuous below-start ticks required before initial admission or recovery; 600 = 30 seconds at 20 TPS |
| `backgroundGenerationTimeoutSeconds` | `20` | wall-clock deadline from initial probe to completed target, positive 32-bit seconds |
| `backgroundGenerationKeepLoadedTicks` | `60` | short hold for LOD capture after FULL completion, nonnegative 32-bit ticks; the LOD queue can also read saved chunks later |
| `backgroundGenerationMaxPendingLodSections` | `256` | suspend and release work above this source backlog; 0 disables this additional gate |
| `backgroundGenerationHistorySize` | `4096` | recent coordinate cache limit, nonnegative 32-bit integer; 0 disables history |
| `backgroundGenerationRecheckSeconds` | `600` | minimum repeat-probe delay for a recently selected coordinate |
| `backgroundGenerationDimensions` | `["minecraft:overworld"]` | explicit nonempty allowlist; unique namespaced identifiers; no arbitrary count cap |
| `backgroundGenerationIncludeSpectators` | `false` | allow spectator positions to seed candidates |

The global rate uses wall-clock credit plus a minute quota. It retains at most `max(1, ceil(rate / 1200))` starts of credit: 6/minute keeps one start per ten seconds, while 600000/minute permits batches of at most 500 at 20 TPS. Idle time does not accumulate a large catch-up burst. Raising only the rate does not raise concurrency, candidate frequency/count, probe budget or available MSPT headroom; those independently limit throughput. This is a configurable admission ceiling, not a claim that native Minecraft can generate 600000 chunks/minute. Long-running or held tickets are renewed every 600 ticks and remain loading-only.

Enable and tune through the validated configuration file, `/voxyserver reload`, or commands, for example:

```text
/voxyserver config set backgroundGenerationEnabled true
/voxyserver config set backgroundGenerationMaxRadiusChunks 64
/voxyserver config set backgroundGenerationChunksPerMinute 6
/voxyserver config set backgroundGenerationStartMspt 25.0
/voxyserver config set backgroundGenerationStopMspt 35.0
/voxyserver config set backgroundGenerationDimensions ["minecraft:overworld"]
```

Commands support fractional MSPT values and JSON dimension lists and use the same validation as startup/reload. Background-only changes cancel their owned work but **do not restart all client stream sessions**. No runtime downloads or new production dependencies are introduced.

The sampler observes the maximum of the current measured tick, vanilla average MSPT and smoothed MSPT. Between start and stop thresholds, existing work can finish but new generation is withheld. Overload, pause/backlog, hot disable, player movement/disconnect/dimension change, timeout or shutdown releases this mod's loading-only tickets. They do not request entity/block ticking or persistent forced loading. Completion only queues source bits; it never forces full-resolution data transmission.

**Cancellation boundary:** once vanilla has dispatched world-generation tasks, they may be shared with real players and cannot safely be force-interrupted. The scheduler removes only its own tickets immediately, discards later callbacks, and still counts unfinished native work against concurrency until it drains. It does not cancel shared futures, force-unload player chunks, or launch replacements on top of unfinished canceled work. This is immediate cancellation of this mod's demand, not a promise that all native CPU work stops in the same instant. A native future that never resolves blocks further admissions to that slot rather than allowing unbounded replacement work.

Minecraft generates dependency halos for a target; the configured radii/rate count **requested target chunks**, not every neighbor generated by the native pipeline. Native generation and synchronous ticket-graph setup can exceed the scheduler's soft allowance; the generator is not a hard CPU/MSPT guarantee. After ticket setup, load is sampled again and excessive tick time immediately closes admission. Large modded generators require correspondingly conservative settings. `/voxyserver stats` reports scheduler state, MSPT, completed targets, tickets, draining jobs and failures. Debug reports additionally include probes/skips, starts/completions, failures/last error, cancellations/timeouts, live tickets and canceled-but-draining jobs.

The scheduler regression simulates 1,200 ticks with concurrent jobs and a high-MSPT interval, verifies default-rate spacing and high-rate batches, immediate ticket release, finite timeouts, retained concurrency debt and recovery, and covers late I/O/native completions, hot reload, candidate bounds and coordinate arithmetic. A separate rate regression executes 600,000 admissions across 1,200 simulated ticks. Native pre-launch checks validate ticket flags, renewal and release isolation. Actual chunk generation under a running server and its sustained MSPT/storage effects have **not** been load-tested here.

### 中文命令速查

**v1.2.7 限额调整：默认仍为每分钟 6 个目标，允许范围扩大到 1–600000。** 并发、候选数、调度预算、半径和等待时间等策略上限已放宽；允许的最大整数为 2147483647。实际吞吐仍同时受并发、候选检查间隔、候选数、调度预算和 MSPT 限制。只提高每分钟额度不会自动提高其他设置。服务端不保证真实生成能达到配置上限。

所有 `/voxyserver` 命令（含 help、查询、stats、导入与修改）统一由服务端检查 `COMMANDS_ADMIN` 权限，对应原版权限等级 3 或更高。客户端提交命令不能绕过检查；普通玩家的远景需求协议仍可正常使用。

以下命令适用于 **v1.2.7 / 协议 5**。游戏内需要管理员权限；在服务器控制台执行时，去掉开头的 `/`。下列命令调整的是服务端行为，客户端与服务端仍需使用兼容的协议版本。

#### 查看、修改、保存与重载

| 命令 | 作用 |
|------|------|
| `/voxyserver config list` | 列出全部配置、当前值，以及是否需要重启 |
| `/voxyserver config get backgroundGenerationEnabled` | 查询某一项；把末尾选项名替换为下表任意选项即可 |
| `/voxyserver config set <选项名> <值>` | 修改配置，校验通过后立即保存到 `config/voxyserver.json`；可热更新的选项同时生效，不必再执行 reload |
| `/voxyserver reload` | 立即读取并校验手动修改的配置文件；与 `/voxyserver config reload` 等价 |
| `/voxyserver stats` | 查看一次同步及旁路生成状态 |
| `/voxyserver stats continuous` | 持续接收状态信息（游戏内玩家专用） |
| `/voxyserver stats stop` | 停止持续接收状态信息（游戏内玩家专用） |
| `/voxyserver help` | 查看命令帮助 |

配置文件也会被定期检查。无效热重载保留当前有效配置，无效命令不会保存；不会因为填错数值而静默采用它。所有旁路选项均支持热更新。**修改旁路选项会撤销当前旁路任务并重新检查恢复条件；仅修改旁路选项不会重置所有玩家的 LOD 同步会话。**

时间单位不要混用：`Ticks` 在 20 TPS 时是每秒 20 tick，卡顿或修改 tick rate 后换算会变化；`Seconds` / `Millis` 是秒 / 毫秒。半径使用 Minecraft 区块，**1 区块 = 16 方块**。生成速率使用实际时间，在服务器加速 tick 时也不会积累补发额度。

#### 旁路生成：每个选项对应的命令

表内命令使用默认值，复制后可替换末尾的值。功能默认关闭，确认参数后再开启。

| 用途 | 修改命令 | 默认值、范围与行为 |
|------|----------|------------------|
| 启用或关闭 | `/voxyserver config set backgroundGenerationEnabled true` | 默认 `false`；`true` 开启，`false` 立即撤销自有票据并停止新增任务 |
| 最近生成距离 | `/voxyserver config set backgroundGenerationMinRadiusChunks 16` | 默认 16；范围 0–2147483646，且小于最大半径。实际下限还会提高到有效原版视距 + 2 区块 |
| 最远生成距离 | `/voxyserver config set backgroundGenerationMaxRadiusChunks 64` | 默认 64；范围 1–2147483647，必须大于配置的最小半径；候选仍须在世界边界内 |
| 全服生成速率 | `/voxyserver config set backgroundGenerationChunksPerMinute 6` | 默认 6；范围 1–600000。全服共享，低速均匀间隔，高速按 tick 批量启动；不是每个玩家各 6 个，也不是原版全部依赖邻块的数量 |
| 总并发上限 | `/voxyserver config set backgroundGenerationMaxConcurrent 1` | 默认 1；范围 1–2147483647。包含探测、等待、生成、保留加载及取消后尚未收尾的任务 |
| 候选检查间隔 | `/voxyserver config set backgroundGenerationCheckIntervalTicks 100` | 默认 100 tick，20 TPS 时约 5 秒；范围 1–2147483647。超载取消与完成检查仍然每 tick 执行 |
| 每轮候选尝试数 | `/voxyserver config set backgroundGenerationCandidatesPerPass 4` | 默认 4；范围 1–2147483647。限制每轮抽样次数，不会遍历整个远景范围 |
| 调度软预算 | `/voxyserver config set backgroundGenerationMaxProbeMillis 1` | 默认 1 ms；范围 1–2147483647。限制候选/启动调度的软预算，不是原版区块生成的 CPU 时间上限 |
| 允许启动的 MSPT | `/voxyserver config set backgroundGenerationStartMspt 25.0` | 默认 25；必须为有限正数。观测 MSPT ≤此值才累计恢复时间、允许新增生成 |
| 立即停止的 MSPT | `/voxyserver config set backgroundGenerationStopMspt 35.0` | 默认 35；必须为有限数且大于启动阈值。观测 MSPT >此值立即撤票；介于两个阈值之间时不启动新生成，已有任务可完成 |
| 启动/恢复等待 | `/voxyserver config set backgroundGenerationResumeDelayTicks 600` | 默认 600 tick，20 TPS 时约 30 秒；范围 0–2147483647。需连续满足低负载条件，首次启动也要等待 |
| 单任务超时 | `/voxyserver config set backgroundGenerationTimeoutSeconds 20` | 默认 20 秒；范围 1–2147483647。从探测到目标完成的实际时间，不含完成后的保留加载阶段 |
| 完成后保留加载 | `/voxyserver config set backgroundGenerationKeepLoadedTicks 60` | 默认 60 tick，20 TPS 时约 3 秒；范围 0–2147483647。给异步 LOD 捕获留时间，0 表示完成后立即释放自有票据 |
| LOD 积压门槛 | `/voxyserver config set backgroundGenerationMaxPendingLodSections 256` | 默认 256；范围 0–2147483647。源区块段待办数大于此值时暂停旁路，0 表示关闭这项额外保护；计数单位不是 Minecraft 区块数 |
| 最近目标缓存容量 | `/voxyserver config set backgroundGenerationHistorySize 4096` | 默认 4096；范围 0–2147483647。限制记录多少个最近探测坐标，0 关闭该缓存 |
| 最近目标重查间隔 | `/voxyserver config set backgroundGenerationRecheckSeconds 600` | 默认 600 秒；范围 0–2147483647；0 允许立即重查。避免频繁重查同一坐标，过小会增加 I/O |
| 允许的维度 | `/voxyserver config set backgroundGenerationDimensions ["minecraft:overworld"]` | 默认仅主世界；必须是非空、不重复的命名空间维度 ID 的 JSON 数组，无固定数量上限。目标附近必须有该维度内的合适玩家 |
| 是否参考旁观玩家 | `/voxyserver config set backgroundGenerationIncludeSpectators false` | 默认 `false`；设为 `true` 后也会围绕旁观者选择候选 |

例如，同时允许主世界和下界：

```text
/voxyserver config set backgroundGenerationDimensions ["minecraft:overworld","minecraft:the_nether"]
```

旁路只检查当前选中的点：已完整保存的区块跳过，已存在的已知部分生成区块可补完，未知或损坏状态不当作新空区块覆盖。生成后进入现有异步 LOD 维护队列，不会因此向客户端全量推送 L0 数据。

#### 一套可直接使用的低速配置

先关闭、再修改，最后开启，避免每次调参都撤销一批正在进行的任务：

```text
/voxyserver config set backgroundGenerationEnabled false
/voxyserver config set backgroundGenerationMaxRadiusChunks 64
/voxyserver config set backgroundGenerationMinRadiusChunks 16
/voxyserver config set backgroundGenerationChunksPerMinute 6
/voxyserver config set backgroundGenerationMaxConcurrent 1
/voxyserver config set backgroundGenerationStartMspt 25.0
/voxyserver config set backgroundGenerationStopMspt 35.0
/voxyserver config set backgroundGenerationResumeDelayTicks 600
/voxyserver config set backgroundGenerationEnabled true
/voxyserver stats
```

这组数值从默认配置出发可直接执行。如果已有自定义数值，需要注意逐条校验：**扩大范围先调大最大半径，再调最小半径；缩小范围先降低最小半径，再降低最大半径。提高两项 MSPT 阈值时先调高停止阈值，降低时先调低启动阈值。** 也可以停用旁路后一次编辑配置文件，再执行 `/voxyserver reload`；半径和 MSPT 的大小关系仍必须有效。上面示例中的 `backgroundGenerationMaxRadiusChunks` 是旁路生成半径，与下面的客户端 LOD 发送半径是两个独立限制。

立即停用只需：

```text
/voxyserver config set backgroundGenerationEnabled false
```

撤票只撤销本模组的加载需求，不会强杀原版共享生成线程，也不会强制卸载玩家正在使用的区块。已经交给原版的任务可能继续收尾，并继续占并发额度，防止恢复后叠加负载。原版还可能生成目标依赖的邻块，所以每分钟目标数不等于实际生成的所有区块数。

#### 同步带宽与维护 CPU：相关命令

这些选项不是旁路生成速度。较大的客户端 LOD 半径不会自动触发全范围预生成；旁路最大半径也不会扩大客户端需求或绕过服务器的发送校验。

| 用途 | 修改命令 | 默认值与限制 |
|------|----------|------------|
| 客户端远景发送上限 | `/voxyserver radius 1024` | 默认 1024 区块；范围 1–4096。等价修改 `maxClientVoxyRadius`，同时同步旧别名 `lodStreamRadius`；实际还受客户端原生 Voxy 视距和个人设置约束 |
| 每玩家带宽 | `/voxyserver bandwidth 250000` | 默认 250000 字节/秒/玩家；0 表示不限速，全服总带宽不是这个值 |
| 开启语义去重 | `/voxyserver hashsync true` | 默认 `true`，等价修改 `hashSyncEnabled`；设为 `false` 会放弃复用匹配内容，可能显著增加传输 |
| 每玩家每 tick 发送上限 | `/voxyserver config set maxSectionsPerTickPerPlayer 100` | 默认 100，至少 1；客户端个人限制仍有效 |
| 每包节点数 | `/voxyserver config set sectionsPerPacket 16` | 默认 16，至少 1；实际协议每包最多 16 个节点，更大配置也不会绕过它 |
| 全服维护线程时间预算 | `/voxyserver config set maintenanceMillisPerSecond 20` | 默认 20 ms/秒；范围 1–2147483647。包括源转换、摘要处理、哈希与编码，是软预算，不包含原版旁路生成本身 |
| 等待变化安静的时间 | `/voxyserver config set maintenanceQuietMillis 2000` | 默认 2000 ms；范围 0–2147483647。合并频繁变化；持续变化另有最长等待，不是每次方块变化就算哈希 |
| 同一 L0 节点更新冷却 | `/voxyserver config set maintenanceCooldownMillis 10000` | 默认 10000 ms；范围 0–2147483647 |
| 哈希预算窗口 | `/voxyserver config set hashWorkIntervalTicks 20` | 默认 20；范围 1–2147483647，按标称 50 ms 单位计时；保留兼容字段 `hashRecheckIntervalTicks` 只需为正整数 |
| 每窗口新哈希数 | `/voxyserver config set maxHashComputationsPerInterval 64` | 默认 64；范围 1–2147483647，全服共享。缺失节点查询不占语义哈希次数 |
| 每窗口哈希时间 | `/voxyserver config set maxHashMillisPerInterval 10` | 默认 10 ms；范围 1–2147483647，同时仍受总维护预算约束 |
| 每玩家需求请求速率 | `/voxyserver config set maxDemandRequestsPerSecond 20` | 默认 20；范围 1–2147483647，按每 20 服务端 tick 的窗口限制 |
| Voxy 存储/服务线程数 | `/voxyserver threads 3` | 默认 3；范围 1–10000，等价修改 `workerThreads`；不会把单个维护调度器改为多个线程 |
| 诊断日志开关 | `/voxyserver config set debugTrackingEnabled true` | 默认 `false`；开启后输出维护和旁路统计，关闭使用 `false` |
| 诊断日志间隔 | `/voxyserver config set debugTrackingInterval 200` | 默认 200 tick，20 TPS 时约 10 秒；至少 1 |
| 普通区块加载时入队 LOD | `/voxyserver config set generateOnChunkLoad true` | 默认 `true`，需重启；旁路自身成功完成后仍会显式入队源数据 |
| 普通区块卸载时入队 LOD | `/voxyserver config set generateOnChunkUnload true` | 默认 `true`，需重启 |
| 日常方块/光照/生物群系变化跟踪 | `/voxyserver config set dirtyTrackingEnabled true` | 默认 `true`，需重启；关闭会影响日常 LOD 内容更新 |

上表使用简写命令的选项也都可用统一格式，例如 `/voxyserver config set maxBandwidthBytesPerSec 250000`、`/voxyserver config set workerThreads 3`。`/voxyserver radius`、`/voxyserver bandwidth`、`/voxyserver threads`、`/voxyserver hashsync` 不带末尾值时表示查询。

`tickInterval`、`dirtyTrackingInterval`、`enableDistancePacing`、`enableRegionHashSync`、`hashRecheckIntervalTicks`、`manifestRefreshIntervalTicks` 是保留兼容字段，仍可通过 `config get/set` 查看修改，但**不用于协议 5 的现行扫描/复核调度**。`/voxyserver pacing` 和 `/voxyserver regionhash` 修改的也是这些旧字段，不能把它们当作新两级摘要机制的开关。稳定分组的 150–210 秒随机复核目前是固定实现，没有对应的可调命令。

#### “开了为什么还没开始”：状态解释

执行 `/voxyserver stats` 看 `background` 一行：

| 状态/字段 | 含义与排查方式 |
|-----------|----------------|
| `disabled` | 开关关闭，执行启用命令后仍需满足恢复条件 |
| `cooldown` | 正累计连续低 MSPT 时间，首次启动也会进入这个阶段；默认需 600 tick |
| `headroom` | MSPT 高于启动阈值；虽未必超过停止阈值，也不会新增生成 |
| `mspt` | MSPT 超过停止阈值，或测量值异常，自有票据已撤销 |
| `suspended` | 服务器暂停，或 LOD 源区块段积压超过配置门槛；可查看 `pending`，等待实际同步先处理完 |
| `running` | 条件允许调度，不代表每 tick 都有生成；仍受全服速率、检查间隔、范围、已有区块与候选缓存约束 |
| `stopped` | 服务已停止或遇到调度异常；先查看日志、处理原因，必要时重启 |
| `MSPT` | 采用本次已测 tick 时间、原版平均值和平滑值中的最大值；不是只看一个很低的瞬时数字 |
| `targets` | 成功完成的旁路目标数，不包含原版隐式生成的所有依赖邻块 |
| `tickets` | 本模组仍持有的加载票据数 |
| `draining` | 已取消但原版尚未完成的任务数；继续占并发额度，不会用新任务把它们顶掉 |
| `failed` | 失败累计数；开启诊断日志可查看最近错误，以及探测、跳过、取消、超时等详细计数 |

如果 `running` 但没有新目标，还要检查：白名单维度内是否有非旁观玩家、范围是否仍在世界边界内、该区域是否已经完整生成、最近候选是否仍在重查冷却内，以及 `draining` 是否占满额度。实际最小半径会提高到原版视距 + 2；例如有效原版视距 32 时，配置最小 16 实际仍从 34 起选点，若最大半径 ≤34 就没有可用的环形范围。该方案不承诺按半径填满整张地图，也不等同于管理员的全量导入命令。

### proof and delivery state

Before: the server enumerated a full-resolution view grid and reconciled paged client inventories; moving or exhausted scans could create substantial repeated work. The retained v4 transaction/pacing regression fixtures protect those earlier correctness fixes, but the production protocol no longer starts those scans or inventory transactions.

Now: `render watch → bounded SUMMARY → DIFFERENT → bounded DETAIL → changed native nodes → guarded installation → batch ACK`. Each group is a complete snapshot of a fixed native LOD, wanted-node mask, dimension, epoch and request ID. An incomplete server index returns `PENDING`, which is never equality evidence. Server-present coverage and all node revisions must match the first proof before detail acceptance and again before batch enqueue. Duplicate detail does not resurrect already confirmed work. Keys outside the requested group remain independent.

Packets carry the group/request ID as well as epoch/batch ID. A client lease serializes proof replacement with actual native writes. Superseded or canceled data is rejected before installation; old ACKs release resources without confirming replaced content. Idle completed proofs still accept server invalidation notifications. Client-only data is hashed from storage, not trusted from a sidecar. Fingerprints describe namespaced block states and sorted properties, biomes, light, voxel positions and child topology; runtime mapper/registry IDs, palette order and binary compression are not identity.

SUMMARY and DETAIL retry at bounded intervals (2 and 5 seconds), at most 16 attempts per phase. Missing deliveries expire after 30 seconds; a later bounded proof can recover. Negative/overflow ACKs and rejected/evicted sends release credits and back off; the server invalidates the proof after eight delivery failures. Requests from prior epochs/IDs cannot advance a replacement proof. Cancellation and a ten-minute subscription lease release stale interests; canceled/expired request tombstones are bounded. A fresh epoch handles dimension switches and reconnects while retaining active client renderer interests and cached terrain.

Outbound queues retain the earlier strict **128-packet / 8 MiB** caps for both priorities, oversize rejection, eviction recovery and bounded high-priority bursts. Changed-group replacements receive priority; the oldest normal packet is protected and gets a turn after eight high-priority dequeues. Inbound reservations remain limited to **64 batches / 64 MiB**, including queued and processing batches.

| Change | Production files | Behavior |
|--------|------------------|----------|
| Client-driven two-stage demand | `ClientDemand`, `ClientDemandState`, `DemandGroup`, demand payloads, renderer mixins, `LodStreamingService` | Actual native LOD demand replaces the server grid scan; stable groups use low-frequency summaries |
| Shared async index/source maintenance | `DemandIndex`, `SourceMaintenance`, `DirtyRegionQueue`, block/light/biome mixins | Coalesced source work and shared semantic proofs run under bounded worker budgets |
| Stale proof/data/ACK isolation | `DemandProofState`, `DemandLease`, `PreSerializedLodPayload`, `PlayerLodTracker`, `RemoteIngestService` | Complete scoped proofs and per-request installation fencing; recoverable resource release |
| Overwrite-only native storage | `NativeNodeInstaller`, `SectionDataAccess`, native presence mixins | Same-LOD replacement retains local descendants; missing/unreadable placeholders are excluded |
| Queue/config/deferred correctness | `PlayerOutboundQueue`, `VoxyServerConfig`, retained tracker regressions | Earlier caps, validation and recovery fixes remain covered; new failures do not leak legacy scanner work |

### scale and measured component checks

These are square-area counts at a 384-block world height, before the renderer's circular culling/refinement. They do not imply allocating or transmitting every node:

| View radius | Old L0 grid candidates | L4 nodes covering the square | Same-LOD L4 proof groups |
|-------------|------------------------|-----------------------------|-------------------------|
| 512 chunks (16,384-block side) | 3,145,728 | 2,048 | 128 |
| 1024 chunks (32,768-block side) | 12,582,912 | 8,192 | 512 |

Fine nodes are added only when requested by native refinement; a coarse group hash does not suppress such requests. A fixed two-stage group fits a maximum 64-leaf proof and avoids balancing/persisting a separate B+ tree. Group metadata on the server scales with active demand, not the L0 world volume.

The automated synthetic 32,768-square test warms 8,192 fingerprints, then runs **256 × 512 = 131,072 group comparisons with zero additional hashes**. One local run took about **216 ms** for the cached comparisons, excluding initial hashing/storage, Minecraft ticks and networking. Actual codecs measured **132,992 bytes** for a flat 8,192-entry audit versus **69,378 bytes** for unchanged grouped request/reply audits (**47.8% less**); about 1% differing groups used **70,696 bytes** including second-stage metadata. Spread across 180 seconds, the unchanged audit is about **386 bytes/second/player** in both directions combined. This clears the 10% threshold for this modeled **audit metadata** workload. It is not a measured reduction in total production bandwidth: new terrain still needs delivery, changed terrain has extra proofs, and TCP framing/compression and native refinement alter the result.

The redstone component test coalesces 100,000 notifications into one source item. The retained 64-player test exercises 76,800 queue admissions under 20,000 bytes/second/player with deliberately smaller 24 KiB queues, checks every admission/dequeue/cleanup and releases all batch resources. Those are component stress tests, not live player/TPS benchmarks.

### verification and remaining limits

Run with JDK 25 and the compatible Voxy jar in `libs/`:

```bash
./gradlew build check runRegressionTests runNativeRegression runNativeClientRegression
./scripts/test-semantic-sync.sh
```

The **16 standalone suites** include proof changes/deletions, duplicate/out-of-order messages, late ACKs, timeout recovery, range isolation, fair bounded mailboxes, source quiet/cooldown/retry/journal behavior, invalidation races, configuration boundaries, semantic registry differences and queue saturation. Both Fabric pre-launch checks apply the real server/client mixins and exercise real Voxy worlds/mappers, native ingest, missing-node placeholders, coarse/fine preservation and source-update locking. They exit before opening a world, network listener, renderer or display. The client check validates that the renderer mixin targets apply; it does not run the live render loop. Test-only classes/resources and the Sodium test runtime do not enter the release jar. Bundled LWJGL core/native versions match Minecraft 26.3's 3.4.3 to avoid a native-library mismatch.

Still unverified: background generation under real server load, a real multi-client Minecraft session, live renderer refinement and missing-parent behavior, sustained TPS and storage throughput on a large explored map, latency while large ingest backlogs drain, and actual total bandwidth savings. User file replacement under a running server is unsupported. Fixed cache/subscription limits may cause extra revalidation in very large/dispersed views. The first connection and cold caches can converge slowly under deliberately low global budgets. Do not treat these results as production-safety or end-to-end certification.

### restoring a world backup

Stop before restoring. Restore Minecraft files and `<world>/voxyserver/` from the same consistent backup. New unpredictable session epochs force fresh comparison even if the world UUID was rolled back. Absent server nodes preserve the client's copy; real older nodes may overwrite it.

Restoring only Minecraft region files leaves the LOD database inconsistent. Move old LOD storage aside while stopped and explicitly rebuild/import before serving it. A normal `force` import does not remove LODs for vanished region files. A fresh map under normal daily edits does not need this full-world import path.

### client config

if the client has [ModMenu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config) installed, players can open the voxyserver config screen from the mods list to adjust their personal streaming preferences:

| option | description |
|--------|-------------|
| **Enable LOD Streaming** | toggle whether the server sends LOD data to you |
| **LOD Stream Radius** | personal remote cap in chunks, 0 = follow Voxy/server limits |
| **Max Sections Per Tick** | personal rate limit for sections sent per tick, 0 = use server default |

these values are capped at the server's configured maximums and sent to the server automatically when saved.

client preferences are stored in `config/voxyserver-client.json` with a default profile plus per server overrides keyed by their `host:port`. if a server has no saved override yet, the default profile is used. per server overrides can only be edited while connected to that server.

## commands

server admins can import existing generated chunks into voxy storage with the following commands:

- `/voxyserver import existing all`  
  imports all loaded dimensions that have a `region` folder
- `/voxyserver import existing current`  
  imports the current dimension of the executing player
- `/voxyserver import existing dimension <dimension>`  
  imports a specific loaded dimension, for example `minecraft:overworld`
- `/voxyserver import existing status`  
  shows the current import status
- `/voxyserver import existing cancel`  
  cancels the active import job

imports run sequentially and read existing `region/*.mca` files directly, which is much faster than waiting for chunks to be loaded normally. imported dimensions are refreshed for connected voxy clients automatically once the import completes.

imports can be resumed if eg server crashes

add `force` to rebuild the selected dimensions regardless of previous progress:

- `/voxyserver import existing all force`
- `/voxyserver import existing current force`
- `/voxyserver import existing dimension minecraft:overworld force`

checkpoints are inside each dimension's LOD `storage/import-checkpoints/` directory. deleting the lod storage also deletes its import progress.

## storage

LOD data is stored per world at `<world>/voxyserver/`. Stop the server and keep a consistent backup before replacing it. Rebuilding existing explored terrain is an explicit import operation, not an automatic startup scan.

## license

This mod is licensed under GNU GPLv3.
