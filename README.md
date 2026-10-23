# voxyserver

a fabric server side mod that voxelizes chunks into LODs using [voxy](https://github.com/MCRcortex/voxy) and streams them to connected clients. players with voxy installed will receive LOD data from the server automatically, no client side world scanning/loading needed.

**Latest: minecraft 26.3 | fabric**

## how it works

when chunks load on the server, they get voxelized into voxy's LOD format and stored in a per world database. when a player with voxy connects, the server streams LOD sections to them in a spiral outward from their position. as the player moves, new sections are sent automatically.

block changes (building, explosions, etc) are detected and the affected LOD sections are revoxelized and pushed to clients automatically.

existing worlds can also be backfilled from their region files with an admin command, so already explored terrain can be voxelized without needing players to revisit it chunk by chunk.

players without voxy are unaffected.

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
| `lodStreamRadius` | `1024` | radius in chunks to stream LODs around each player |
| `maxSectionsPerTickPerPlayer` | `100` | max LOD sections sent per player per tick cycle |
| `sectionsPerPacket` | `50` | max LOD sections bundled into a single network packet |
| `maxBandwidthBytesPerSec` | `250000` | token-bucket egress rate limit in bytes/sec per player (`0` = unlimited) |
| `enableDistancePacing` | `true` | paces candidate selection by distance to prevent bandwidth spikes |
| `enableRegionHashSync` | `false` | reserved region summaries; partial coverage never suppresses section delivery; leave disabled |
| `hashSyncEnabled` | `true` | reuse client content after semantic hash comparison; bounded inventory reconciliation remains active when disabled |
| `hashWorkIntervalTicks` | `20` | interval for the shared server hash budget, in server ticks |
| `maxHashComputationsPerInterval` | `64` | maximum new section hashes per interval across all players |
| `maxHashMillisPerInterval` | `10` | worker time budget for new section hashing per interval; a section already started finishes |
| `hashRecheckIntervalTicks` | `1200` | recheck unchanged cached fingerprints after this many ticks; edits invalidate immediately |
| `manifestRefreshIntervalTicks` | `600` | minimum interval between movement/radius-triggered cache inventory refreshes |
| `tickInterval` | `5` | server ticks between each streaming cycle |
| `workerThreads` | `3` | number of worker threads for voxy to use |
| `generateOnChunkLoad` | `true` | voxelize chunks as they load on the server |
| `dirtyTrackingEnabled` | `true` | revoxelize and push LODs when blocks change |
| `dirtyTrackingInterval` | `40` | ticks between dirty chunk flushes (40 = 2 seconds) |
| `debugTrackingEnabled` | `false` | output internal server stats to the console periodically |
| `debugTrackingInterval` | `200` | ticks between dumping tracking stats (200 = 10 seconds) |

### example config

```json
{
  "lodStreamRadius": 1024,
  "maxSectionsPerTickPerPlayer": 100,
  "sectionsPerPacket": 50,
  "maxBandwidthBytesPerSec": 250000,
  "enableDistancePacing": true,
  "enableRegionHashSync": false,
  "hashSyncEnabled": true,
  "hashWorkIntervalTicks": 20,
  "maxHashComputationsPerInterval": 64,
  "maxHashMillisPerInterval": 10,
  "hashRecheckIntervalTicks": 1200,
  "manifestRefreshIntervalTicks": 600,
  "generateOnChunkLoad": true,
  "tickInterval": 5,
  "workerThreads": 3,
  "dirtyTrackingEnabled": true,
  "dirtyTrackingInterval": 40,
  "debugTrackingEnabled": false,
  "debugTrackingInterval": 200
}
```

higher `lodStreamRadius` means more LOD coverage but more storage and bandwidth. `maxSectionsPerTickPerPlayer` controls how fast LODs are sent to new players or when moving into unexplored areas. `maxBandwidthBytesPerSec` strictly shapes network egress using a token bucket filter. `sectionsPerPacket` controls how many sections are packed into each network packet.

Configuration files are validated before application or saving. Invalid startup files produce field-specific diagnostics and use validated defaults without overwriting the file. Invalid reloads retain the current validated configuration; commands and saves use the same validation guard. Legacy radius aliases are validated before normalization.

### distance-based pacing

when `enableDistancePacing` is enabled, candidate sections are paced by distance:
- **Near (<= 16 sections)**: 100% transmission rate for immediate close-range visual fidelity.
- **Mid-Distance (17 ~ 48 sections)**: 50% trickle rate.
- **Far-Distance (> 48 sections)**: 25% trickle rate.

Paced candidates are deferred into a recovery queue rather than dropped, guaranteeing complete eventual delivery without candidate starvation.

### semantic cache synchronization and server load

Protocol **4** requires matching VoxyServer versions on both sides. Section fingerprints describe voxel meaning: namespaced block names and sorted block-state properties, namespaced biome names, light, and voxel positions. Air variants and their discarded biome slots are normalized to match Voxy storage; air light is preserved. Mapper IDs, registry numbering, LUT order, duplicate/unused LUT entries, compression, and packet encoding do not define content identity. Both sides use the same hash implementation. The client verifies received payloads before ingest and computes inventory fingerprints from its actual stored voxels on a background worker; a sidecar hash alone is not proof that the LOD database still contains that content.

Hashing and inventory/storage work run off the Minecraft tick and render threads. The server shares a bounded fingerprint cache and a 128-section snapshot cache across players. Dirty callbacks invalidate these immediately. New work is limited globally by both the section count and elapsed worker time above; player order rotates between budget windows. The client inventory worker limits itself to 32 section hashes and 20 ms of hashing per second, allowing an already-started section to finish. Increase server budgets only when the CPU and desired initial download speed permit it.

Cache inventories use acknowledged pages of at most 128 entries, with one page in flight per client. Protocol 4 adds the server-captured center and radius to the session and an explicit abort status to page acknowledgements. Pages are numbered from zero within each request. Client scans and server validation use that same frozen dimension/range, even if the player moves. This wire change requires updating both sides.

`PART` pages stage claims without changing confirmed hashes. A contiguous, valid `COMPLETE` atomically replaces confirmations inside that range, updates changed hashes, and removes confirmations for absent client sections. Confirmations outside the range and in other dimensions remain valid. This only changes server verification state; it never deletes client terrain. Identical duplicate pages are idempotent while staging; after commit only the final COMPLETE receipt is retained for ACK-loss retries, releasing the staged page arrays. Conflicting duplicates abort, and gaps require retry. Superseded requests cannot commit.

Sections touched by deliveries overlapping a snapshot are conservatively re-sent rather than trusted from that snapshot. At commit, old overlapping batch confirmations are fenced; their late ACKs release resources and may still confirm sections outside the snapshot range. Queued superseded batches are rejected before transmission. Queue selection, token charging and sending are serialized with snapshot publication so an old queued payload cannot overtake its replacement.

The server processes at most one inventory page every five ticks globally. The initial streaming gate opens after 60 ticks while the transaction may continue. No-progress expiry is 2,400 ticks, absolute expiry is 12,000 ticks, staging is capped at 65,536 unique entries and 1,024 pages, and the client retries a page every 200 ticks and stops after eight failed retry opportunities. Partial pages are flushed after about two seconds of hash work so a slow inventory need not fill 128 entries before reporting progress. Failure, cancellation or expiry discards staging, invalidates uncertain confirmations only within the frozen scope, and resumes scanning. Movement/radius refreshes respect `manifestRefreshIntervalTicks` and wait for the current transaction to finish or expire. These caps may prevent very large inventories from completing; correctness then falls back to scoped re-transmission.

Outbound queues enforce both 128 packets and 8 MiB for every priority. Oversized packets are rejected before eviction. High-priority admissions may evict the newest normal packets but protect the oldest normal packet; admission is rejected if the remaining queue still exceeds either cap. When no normal packet is queued, one normal admission may evict newest high-priority packets to ensure background progress. After eight high-priority dequeues, a waiting normal packet gets a turn. Callers fail all evicted/rejected batches and retain their sections for bounded retry.

An exhausted base scanner still admits released paced work and dirty work; dirty work is polled first. Unreleased paced work does not wake an exhausted scan until its release tick. Candidate, acquire, hash-budget and bandwidth deferrals remain recoverable without waiting for the full idle rescan interval.

A world UUID namespaces sidecars, and every new stream session has a fresh unpredictable epoch. Epochs are compared for equality, not by age, so restoring an old backup cannot make old counters appear current. New sessions discard prior verification state while retaining local LOD content for comparison. Matching content is reused; differing content can replace newer client terrain with older server terrain.

Client storage is **overwrite-only**: a valid server section can replace local content, including with an older version after rollback. A missing, ungenerated or unreadable server section never deletes or blanks client terrain. Native Voxy ingest remains enabled, so it can continue acquiring terrain independently. These locally acquired sections are preserved whenever the server has no replacement data. Legacy clear signals only invalidate verification and queued work; they do not clear the local LOD database or its hash sidecar. If a rollback removes a server section entirely, the client's copy intentionally remains until actual replacement data is available. An explicitly transmitted all-air section is still real replacement content.

### restoring a world backup

Stop the server before restoring files. Restore the Minecraft world and its `<world>/voxyserver/` LOD data from the **same consistent backup**. The world UUID may be restored too; fresh session epochs still force content comparison. A rollback does not delete client caches or require redownloading unchanged terrain. Sections absent from the restored server are retained on the client under the overwrite-only contract.

If only Minecraft region files were restored, the old server LOD database may still describe the future. No client hash protocol can fix that source inconsistency. While the server is stopped, move the old `<world>/voxyserver/` directory aside, then rebuild with `/voxyserver import existing all` before admitting clients. The existing `force` import alone does not remove LODs for region files that disappeared. Replacing region files underneath a running server is unsupported.

### regression checks

`./gradlew check` runs the standalone network, synchronization, pacing, region-state and bandwidth suites with assertions enabled. `./gradlew runRegressionTests` runs them explicitly. The dependency-free semantic hashing, rollback identity, and cache-budget tests can also run with JDK 25 using `./scripts/test-semantic-sync.sh`.

### v1.2.5 correctness follow-up (development branch)

The fixes are based on published tag `v1.2.5` (`4deea7a`), which remains unchanged. Development artifacts are `1.2.6-dev`, protocol 4; no replacement release is published by these fixes.

| Area | Previous behavior | Corrected behavior / files |
|------|-------------------|----------------------------|
| Inventory | Incremental insert-if-absent claims; moving validation range; old ACK overwrite | Staged scoped transactions and scoped ACK fencing in `PlayerLodTracker`, `LodStreamingService`, `ClientLodSettings`, `ClientLodReceiver` and the session/page ACK payloads |
| Queue | High-priority bytes could exceed the cap | Final admission checks, bounded eviction and normal progress in `PlayerOutboundQueue`; failed batch recovery in the tracker and streaming service |
| Configuration | Startup applied/saved unchecked values | Shared validation in `VoxyServerConfig` and the command application boundary |
| Deferred work | Exhausted scans could wait 1,200 ticks despite ready paced work | `prepareScanWork` admits eligible deferred work; a full paced queue retains the next scan key |

Validation passed all **10/10** suites with `./gradlew build check runRegressionTests` and the standalone semantic script, including full JAR/source packaging, real Minecraft registry semantics, 100 concurrent old-ACK/snapshot races, concurrent queue accounting, a 256-player/512-page inventory simulation at the global five-tick page cadence, and a 64-player component simulation with 400 ticks and 76,800 admissions at 20,000 bytes/sec/player. The load simulation uses a deliberately smaller 24 KiB queue limit to force saturation; it checks every admission/dequeue/cleanup against both caps and verifies all outstanding resources return to zero. The recorded saturated run sent/ACKed 6,208 batches, failed 70,208 admissions/evictions through recovery, and drained the remaining 384 batches during cleanup. These counts describe the synthetic workload, not network or TPS measurements. Regression coverage includes H1-to-H2 refresh, scoped absence, duplicate/conflicting/gapped pages, stale requests/ACKs, failure and both expiry deadlines, configuration boundaries, and tick-2 paced recovery. These are component tests: no real Minecraft client/server session, render correctness, native-ingest race under live play, sustained TPS or production bandwidth savings has been validated.

Enable `debugTrackingEnabled` for server hash/cache/skip/send totals and per-player transaction duration/completion tick, staged entries, ACK timeouts, delivery failures/retries, dirty enqueue-to-ACK recovery ticks, and queue occupancy/peak/evictions/rejections. Delivery failure rate includes local rejection/eviction, not only negative client ACKs. Client manifest logs record scanned sections, hashes, hash milliseconds, total build time and completion status. Metrics are process-local diagnostics, not persisted analytics.

### client config

if the client has [ModMenu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config) installed, players can open the voxyserver config screen from the mods list to adjust their personal streaming preferences:

| option | description |
|--------|-------------|
| **Enable LOD Streaming** | toggle whether the server sends LOD data to you |
| **LOD Stream Radius** | personal LOD radius in blocks, 0 = use server default |
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

LOD data is stored per world at `<world>/voxyserver/`. this can be safely deleted to regenerate all LOD data.

## license

This mod is licensed under GNU GPLv3.
