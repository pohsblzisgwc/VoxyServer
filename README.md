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

### distance-based pacing

when `enableDistancePacing` is enabled, candidate sections are paced by distance:
- **Near (<= 16 sections)**: 100% transmission rate for immediate close-range visual fidelity.
- **Mid-Distance (17 ~ 48 sections)**: 50% trickle rate.
- **Far-Distance (> 48 sections)**: 25% trickle rate.

Paced candidates are deferred into a recovery queue rather than dropped, guaranteeing complete eventual delivery without candidate starvation.

### semantic cache synchronization and server load

Protocol **3** requires matching VoxyServer versions on both sides. Section fingerprints describe voxel meaning: namespaced block names and sorted block-state properties, namespaced biome names, light, and voxel positions. Air variants and their discarded biome slots are normalized to match Voxy storage; air light is preserved. Mapper IDs, registry numbering, LUT order, duplicate/unused LUT entries, compression, and packet encoding do not define content identity. Both sides use the same hash implementation. The client verifies received payloads before ingest and computes inventory fingerprints from its actual stored voxels on a background worker; a sidecar hash alone is not proof that the LOD database still contains that content.

Hashing and inventory/storage work run off the Minecraft tick and render threads. The server shares a bounded fingerprint cache and a 128-section snapshot cache across players. Dirty callbacks invalidate these immediately. New work is limited globally by both the section count and elapsed worker time above; player order rotates between budget windows. The client inventory worker limits itself to 32 section hashes and 20 ms of hashing per second, allowing an already-started section to finish. Increase server budgets only when the CPU and desired initial download speed permit it.

Cache inventories use acknowledged pages of at most 128 entries, with one page in flight per client. The server processes at most one page every five ticks across all players. A late page remains valid after the initial streaming grace period, and retries do not require rehashing that page. This bounds queued work without requiring a large inventory to finish in three seconds. Inventory refreshes are triggered by movement or radius changes and respect `manifestRefreshIntervalTicks`.

A world UUID namespaces sidecars, and every new stream session has a fresh unpredictable epoch. Epochs are compared for equality, not by age, so restoring an old backup cannot make old counters appear current. New sessions discard prior verification state while retaining local LOD content for comparison. Matching content is reused; differing content can replace newer client terrain with older server terrain.

Client storage is **overwrite-only**: a valid server section can replace local content, including with an older version after rollback. A missing, ungenerated or unreadable server section never deletes or blanks client terrain. Native Voxy ingest remains enabled, so it can continue acquiring terrain independently. These locally acquired sections are preserved whenever the server has no replacement data. Legacy clear signals only invalidate verification and queued work; they do not clear the local LOD database or its hash sidecar. If a rollback removes a server section entirely, the client's copy intentionally remains until actual replacement data is available. An explicitly transmitted all-air section is still real replacement content.

### restoring a world backup

Stop the server before restoring files. Restore the Minecraft world and its `<world>/voxyserver/` LOD data from the **same consistent backup**. The world UUID may be restored too; fresh session epochs still force content comparison. A rollback does not delete client caches or require redownloading unchanged terrain. Sections absent from the restored server are retained on the client under the overwrite-only contract.

If only Minecraft region files were restored, the old server LOD database may still describe the future. No client hash protocol can fix that source inconsistency. While the server is stopped, move the old `<world>/voxyserver/` directory aside, then rebuild with `/voxyserver import existing all` before admitting clients. The existing `force` import alone does not remove LODs for region files that disappeared. Replacing region files underneath a running server is unsupported.

### regression checks

`./gradlew check` runs the standalone network, synchronization, pacing, region-state and bandwidth suites with assertions enabled. `./gradlew runRegressionTests` runs them explicitly. The dependency-free semantic hashing, rollback identity, and cache-budget tests can also run with JDK 25 using `./scripts/test-semantic-sync.sh`.

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
