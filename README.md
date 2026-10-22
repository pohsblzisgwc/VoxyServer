# voxyserver

a fabric server side mod that voxelizes chunks into LODs using [voxy](https://github.com/MCRcortex/voxy) and streams them to connected clients. players with voxy installed will receive LOD data from the server automatically, no client side world scanning/loading needed.

**Latest: Minecraft 26.3 | Fabric (LOD Bandwidth Optimization Demo)**

## how it works

when chunks load on the server, they get voxelized into voxy's LOD format and stored in a per world database. when a player with voxy connects, the server streams LOD sections to them in a spiral outward from their position. as the player moves, new sections are sent automatically.

block changes (building, explosions, etc) are detected and the affected LOD sections are revoxelized and pushed to clients automatically.

existing worlds can also be backfilled from their region files with an admin command, so already explored terrain can be voxelized without needing players to revisit it chunk by chunk.

players without voxy are unaffected.

## building

due to voxy's license, the source and binary can't be included in this repo. you'll need to clone and build it yourself.

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
   mkdir libs
   cp voxy/build/libs/voxy-*.jar libs/voxy.jar
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
   for %f in (voxy\build\libs\voxy-*.jar) do copy "%f" libs\voxy.jar
   ```

4. build voxyserver:
   ```cmd
   gradlew build
   ```

the output jar will be in `build\libs\`.

## installation

drop the voxyserver, voxy & sodium jar's into the server's `mods/` folder. no other server side dependencies are needed.

clients just need voxy installed as normal along with voxyserver.

## bandwidth optimizations (v1.2.0-beta.1+)

to alleviate network pressure on servers with high player counts or limited uplink:

- **64-bit Merkle-Style Region Hash Deduplication (`enableRegionHashSync`):**
  LOD sections are grouped into 16x16 Region units (512x512 blocks). On join, the client sends hashes of its local cached regions. The server compares region hashes and skips sending regions that are already up to date on the client, eliminating up to **90%~95%** of redundant data transfer upon player reconnection.
- **Token-Bucket Bandwidth Limiter (`maxBandwidthBytesPerSec`):**
  Replaces rigid section count caps with a byte-level token bucket to strictly enforce maximum outgoing bandwidth per player (default ~250 KB/s), completely smoothing out sudden network spikes.
- **Distance-Based Trickle Pacing (`enableDistancePacing`):**
  LODs near the player (within 32 chunks) are streamed with top priority, while distant LODs (32+ chunks) are trickled over time at reduced frequency to prevent network congestion while moving or exploring.

## config

config file is generated at `config/voxyserver.json` on first run.

| option | default | description |
|--------|---------|-------------|
| `lodStreamRadius` | `256` | radius in chunks to stream LODs around each player |
| `maxSectionsPerTickPerPlayer` | `100` | max LOD sections sent per player per tick cycle |
| `sectionsPerPacket` | `50` | max LOD sections bundled into a single network packet |
| `tickInterval` | `5` | server ticks between each streaming cycle |
| `workerThreads` | `3` | number of worker threads for voxy to use |
| `generateOnChunkLoad` | `true` | voxelize chunks as they load on the server |
| `dirtyTrackingEnabled` | `true` | revoxelize and push LODs when blocks change |
| `dirtyTrackingInterval` | `40` | ticks between dirty chunk flushes (40 = 2 seconds) |
| `debugTrackingEnabled` | `false` | output internal server stats to the console periodically |
| `debugTrackingInterval` | `200` | ticks between dumping tracking stats (200 = 10 seconds) |
| `maxBandwidthBytesPerSec` | `250000` | token-bucket egress bandwidth budget in bytes/s per player (~250 KB/s) |
| `enableDistancePacing` | `true` | pace distant LOD sections at reduced rates to smooth network usage |
| `enableRegionHashSync` | `true` | enable 64-bit Region Hash deduplication to skip already cached regions |

### example config

```json
{
  "lodStreamRadius": 256,
  "maxSectionsPerTickPerPlayer": 100,
  "sectionsPerPacket": 50,
  "generateOnChunkLoad": true,
  "tickInterval": 5,
  "workerThreads": 3,
  "dirtyTrackingEnabled": true,
  "dirtyTrackingInterval": 40,
  "debugTrackingEnabled": false,
  "debugTrackingInterval": 200,
  "maxBandwidthBytesPerSec": 250000,
  "enableDistancePacing": true,
  "enableRegionHashSync": true
}
```

higher `lodStreamRadius` means more LOD coverage but more storage and bandwidth. `maxSectionsPerTickPerPlayer` controls how fast LODs are sent to new players or when moving into unexplored areas. `sectionsPerPacket` controls how many sections are packed into each network packet (higher = fewer packets but larger each). lower `tickInterval` = more frequent streaming checks. `maxBandwidthBytesPerSec` strictly shapes outgoing bandwidth.

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

## storage

LOD data is stored per world at `<world>/voxyserver/`. this can be safely deleted to regenerate all LOD data.

## license

This mod is licensed under GNU GPLv3.
