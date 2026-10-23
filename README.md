# voxyserver

a fabric server side mod that voxelizes chunks into LODs using [voxy](https://github.com/MCRcortex/voxy) and streams them to connected clients. players with voxy installed will receive LOD data from the server automatically, the client reports its active render nodes; no world-wide scan is required.

**Latest: minecraft 26.3 | fabric**

## how it works

The development branch uses **protocol 5** on Fabric / Minecraft 26.3. Both sides must update together. Published `v1.2.5` (`4deea7a`) is unchanged; development artifacts remain `1.2.6-dev`.

The client derives demand from Voxy's actual render subscriptions at native LOD levels **0–4**. Each node contains 32³ voxels and spans `32 << level` blocks. Voxy's `sectionRenderDistance` uses **512-block top-level units**: values 16 and 32 mean 512 and 1024 Minecraft chunks. The remote range is capped by the native view, personal preference and server maximum. The server validates the requested coordinates, dimension, epoch and rate without enumerating the view grid or the database.

1. Client compares one semantic digest and coverage mask per fixed 4×4×4 group **at the same LOD**.
2. When that group differs, client supplies hashes only for its server-present leaves. The server sends only differing/missing native nodes. Native child topology participates in the proof; a coarse voxel hash is never treated as a digest of its finer descendants.
3. Stable groups audit again after a randomized **150–210 seconds**. Newly watched nodes and changed groups start earlier. One client proof worker reads actual local data, with round-robin groups and bounded hashing; a second worker ingests received data. Native Voxy ingestion remains enabled.
4. A single server maintenance worker coalesces source changes, updates native LODs and fills a shared on-demand hash index. Block/light/biome notifications only mark source bits. There is no startup world enumeration, player spiral scanner, automatic full import or per-block hash computation.

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
| `maintenanceQuietMillis` | `2000` | coalesce changes until a source root is quiet; continuous changes also release after at most 30 seconds, plus queue delay |
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

### maintenance and memory

Each 512³ source root allocates up to 4 KiB of bits for changed 16³ source sections. Repeated redstone changes set the same bit. The worker groups ready sections by their containing 32³ native node and converts only the marked source octants. Notifications hold a short per-root lock; scanning cooldowns, journal writes, palette copying, conversion and semantic hashes run outside that lock and outside the Minecraft tick thread.

For live sources, the main thread performs at most four direct chunk lookups and eight section-reference/light captures per work item. Palette copies run on the maintenance worker under short section-local writer monitors. Unloaded sources use vanilla targeted chunk I/O; missing saves retry instead of inventing replacement data. This is asynchronous maintenance, not a claim of zero main-thread work or zero lock contention.

The index holds at most **65,536 node fingerprints**, **128 encoded node snapshots**, and **8,192 pending probes**, shared between players. It starts empty and verifies only requested native nodes after restart; it is not a persistent full-world index. Cold edits do not fill the index. Native updates invalidate affected cached fingerprints and negative-presence entries; unchanged group requests reuse them. Missing-node lookups use the overall I/O/time allowance without consuming the semantic-hash count limit. Source processing and hashing rotate so one cannot permanently starve the other. Request admission is capped at 256 pending requests globally and four per player, with round-robin service. Each player can subscribe to at most 1,024 groups; the client tracks at most 65,536 active native nodes.

Dirty work, including the active item, is journaled about every 30 seconds and on a clean stop. Startup merges it with newly loaded source work. A crash can lose the latest uncheckpointed dirty window, and Voxy's asynchronous storage writes are not a world-wide atomic checkpoint. Normal chunk loads queue source verification again. Existing loaded chunks may therefore incur bounded repeated ingestion after reconnect/reload; this implementation does not persist per-source freshness stamps or scan/rebuild the whole map.

A nonpreemptible node/source operation may finish beyond the configured worker allowance; that allowance is not a hard CPU/TPS guarantee. Main-thread captures, Voxy's own compression/save workers, manual imports and periodic journal/lease metadata are additional costs. Debug logs expose worker CPU/wall time, maximum work quantum, main capture time, hashes/cache hits, matched groups, actual sends, retries/ACK timeouts and per-player queue peaks/rejections/evictions. Saturated budgets delay convergence rather than moving heavy work onto the main thread.

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

The **15 standalone suites** include proof changes/deletions, duplicate/out-of-order messages, late ACKs, timeout recovery, range isolation, fair bounded mailboxes, source quiet/cooldown/retry/journal behavior, invalidation races, configuration boundaries, semantic registry differences and queue saturation. Both Fabric pre-launch checks apply the real server/client mixins and exercise real Voxy worlds/mappers, native ingest, missing-node placeholders, coarse/fine preservation and source-update locking. They exit before opening a world, network listener, renderer or display. The client check validates that the renderer mixin targets apply; it does not run the live render loop. Test-only classes/resources and the Sodium test runtime do not enter the release jar. Bundled LWJGL core/native versions match Minecraft 26.3's 3.4.3 to avoid a native-library mismatch.

Still unverified: a real multi-client Minecraft session, live renderer refinement and missing-parent behavior, sustained TPS and storage throughput on a large explored map, latency while large ingest backlogs drain, and actual total bandwidth savings. User file replacement under a running server is unsupported. Fixed cache/subscription limits may cause extra revalidation in very large/dispersed views. The first connection and cold caches can converge slowly under deliberately low global budgets. Do not treat these results as production-safety or end-to-end certification.

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
