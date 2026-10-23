package com.dripps.voxyserver.server;

import com.dripps.voxyserver.Voxyserver;
import com.dripps.voxyserver.sync.DirtyRegionQueue;

import me.cortex.voxy.common.voxelization.*;
import me.cortex.voxy.common.world.*;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.minecraft.core.SectionPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Executed only by the dedicated maintenance worker. Main-thread capture is a bounded lookup of
 * references/light.
 */
final class SourceMaintenance {
    final DirtyRegionQueue<ServerLevel> dirty;
    private final ServerLodEngine engine;
    private final Path journal;
    private DirtyRegionQueue.Work<ServerLevel> active;
    private CompletableFuture<List<Piece>> snapshot;
    private List<Piece> pieces;
    private int cursor;
    private long snapshotStarted;
    volatile long updates, retries;
    long captureCount, captureNanos;

    private record Piece(
            LevelChunkSection source,
            CompoundTag stored,
            DataLayer block,
            DataLayer sky,
            int x,
            int y,
            int z) {}

    SourceMaintenance(ServerLodEngine engine, Path journal, long quietMillis, long cooldownMillis) {
        this.engine = engine;
        this.journal = journal;
        dirty =
                new DirtyRegionQueue<>(
                        quietMillis * 1_000_000L, 30_000_000_000L, cooldownMillis * 1_000_000L);
    }

    void mark(ServerLevel level, int x, int y, int z) {
        dirty.mark(level, x, y, z, System.nanoTime());
    }

    boolean step(MinecraftServer server) {
        if (active == null) {
            active = dirty.poll(System.nanoTime());
            if (active == null) return false;
            snapshotStarted = System.nanoTime();
            snapshot = new CompletableFuture<>();
            var work = active;
            var result = snapshot;
            server.execute(
                    () -> {
                        long began = System.nanoTime();
                        try {
                            capture(work, result);
                        } finally {
                            captureCount++;
                            captureNanos += System.nanoTime() - began;
                        }
                    });
            return true;
        }
        if (!snapshot.isDone()) {
            if (System.nanoTime() - snapshotStarted < 10_000_000_000L) return false;
            retries++;
            dirty.retry(active, System.nanoTime());
            snapshot.cancel(false);
            clearActive();
            return true;
        }
        try {
            if (pieces == null) {
                pieces = snapshot.join();
                cursor = 0;
            }
            if (cursor < pieces.size()) {
                Piece p = pieces.get(cursor++);
                ServerLevel level = active.dimension();
                WorldEngine world =
                        engine.getOrCreate(
                                WorldIdentifier.of(level), level.dimension().identifier());
                if (world == null) throw new IllegalStateException("LOD world unavailable");
                world.acquireRef();
                try {
                    PalettedContainerFactory palettes =
                            PalettedContainerFactory.create(level.registryAccess());
                    LevelChunkSection copy = p.source == null ? null : snapshotSection(p.source);
                    var blocks =
                            copy != null
                                    ? copy.getStates()
                                    : p.stored.getCompound("block_states").isPresent()
                                            ? palettes.blockStatesContainerCodec()
                                                    .parse(
                                                            NbtOps.INSTANCE,
                                                            p.stored
                                                                    .getCompound("block_states")
                                                                    .orElseThrow())
                                                    .getOrThrow()
                                            : palettes.createForBlockStates();
                    // The block and biome palettes are copied together when source is live.
                    var biomes =
                            copy != null
                                    ? copy.getBiomes()
                                    : p.stored.getCompound("biomes").isPresent()
                                            ? palettes.biomeContainerCodec()
                                                    .parse(
                                                            NbtOps.INSTANCE,
                                                            p.stored
                                                                    .getCompound("biomes")
                                                                    .orElseThrow())
                                                    .getOrThrow()
                                            : palettes.createForBiomes();
                    DataLayer bl = p.block, sl = p.sky;
                    if (p.stored != null) {
                        bl = light(p.stored, "BlockLight");
                        sl = light(p.stored, "SkyLight");
                    }
                    final DataLayer block = bl, sky = sl;
                    var vox =
                            WorldConversionFactory.convert(
                                    VoxelizedSection.createEmpty().setPosition(p.x, p.y, p.z),
                                    world.getMapper(),
                                    blocks,
                                    biomes,
                                    (x, y, z) ->
                                            (byte)
                                                    ((sky == null ? 0 : sky.get(x, y, z))
                                                            | ((block == null
                                                                            ? 0
                                                                            : block.get(x, y, z))
                                                                    << 4)));
                    WorldVoxilizedSectionMipper.mipSection(vox, world.getMapper());
                    WorldUpdater.insertUpdate(world, vox);
                    updates++;
                } finally {
                    world.releaseRef();
                }
            }
            if (cursor >= pieces.size()) clearActive();
        } catch (Exception e) {
            retries++;
            dirty.retry(active, System.nanoTime());
            clearActive();
            if (retries == 1 || retries % 100 == 0)
                Voxyserver.LOGGER.warn(
                        "LOD maintenance deferred a source snapshot: {}", e.toString());
        }
        return true;
    }

    private static LevelChunkSection snapshotSection(LevelChunkSection source) {
        synchronized (source) {
            return source.copy();
        }
    }

    private static DataLayer light(CompoundTag tag, String key) {
        byte[] bytes = tag.getByteArray(key).orElse(new byte[0]);
        return bytes.length == 0 ? null : new DataLayer(bytes);
    }

    private static void capture(
            DirtyRegionQueue.Work<ServerLevel> work, CompletableFuture<List<Piece>> result) {
        try {
            ServerLevel level = work.dimension();
            long key = work.node();
            int startX = WorldEngine.getX(key) * 2,
                    startY = WorldEngine.getY(key) * 2,
                    startZ = WorldEngine.getZ(key) * 2;
            var futures = new ArrayList<CompletableFuture<List<Piece>>>();
            var bl = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
            var sl = level.getLightEngine().getLayerListener(LightLayer.SKY);
            for (int oz = 0; oz < 2; oz++)
                for (int ox = 0; ox < 2; ox++) {
                    int x = startX + ox, z = startZ + oz;
                    int column = (1 << (ox | (oz << 1))) | (1 << (ox | (oz << 1) | 4));
                    if ((work.sourceMask() & column) == 0) continue;
                    var chunk = level.getChunkSource().getChunkNow(x, z);
                    if (chunk != null) {
                        if (!chunk.isLightCorrect())
                            throw new IllegalStateException("lighting not settled");
                        var list = new ArrayList<Piece>();
                        for (int y = startY; y < startY + 2; y++) {
                            if ((work.sourceMask() & (1 << (ox | (oz << 1) | ((y - startY) << 2))))
                                    == 0) continue;
                            int index = y - chunk.getMinSectionY();
                            if (index < 0 || index >= chunk.getSections().length) continue;
                            var pos = SectionPos.of(x, y, z);
                            var block = bl.getDataLayerData(pos);
                            var sky = sl.getDataLayerData(pos);
                            list.add(
                                    new Piece(
                                            chunk.getSections()[index],
                                            null,
                                            block == null ? null : block.copy(),
                                            sky == null ? null : sky.copy(),
                                            x,
                                            y,
                                            z));
                        }
                        futures.add(CompletableFuture.completedFuture(list));
                    } else {
                        final int needed = work.sourceMask() & column;
                        final int sourceColumn = ox | (oz << 1);
                        futures.add(
                                level.getChunkSource()
                                        .chunkMap
                                        .read(new ChunkPos(x, z))
                                        .thenApply(
                                                optional -> {
                                                    if (optional.isEmpty())
                                                        throw new CompletionException(
                                                                new IOException(
                                                                        "dirty source chunk not"
                                                                            + " saved yet: "
                                                                                + x
                                                                                + ","
                                                                                + z));
                                                    var tag = optional.get();
                                                    var sections =
                                                            tag.getList("sections").orElseThrow();
                                                    var list = new ArrayList<Piece>();
                                                    for (var value : sections)
                                                        if (value instanceof CompoundTag section) {
                                                            int y =
                                                                    section.getIntOr(
                                                                            "Y", Integer.MIN_VALUE);
                                                            if (y >= startY
                                                                    && y < startY + 2
                                                                    && (needed
                                                                                    & (1
                                                                                            << (sourceColumn
                                                                                                    | ((y
                                                                                                                    - startY)
                                                                                                            << 2))))
                                                                            != 0)
                                                                list.add(
                                                                        new Piece(
                                                                                null, section, null,
                                                                                null, x, y, z));
                                                        }
                                                    if (list.size() != Integer.bitCount(needed))
                                                        throw new CompletionException(
                                                                new IOException(
                                                                        "dirty source sections not"
                                                                            + " saved yet"));
                                                    return list;
                                                }));
                    }
                }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .whenComplete(
                            (unused, error) -> {
                                if (error != null) result.completeExceptionally(error);
                                else {
                                    var all = new ArrayList<Piece>();
                                    for (var future : futures) all.addAll(future.join());
                                    result.complete(all);
                                }
                            });
        } catch (Throwable e) {
            result.completeExceptionally(e);
        }
    }

    private void clearActive() {
        active = null;
        snapshot = null;
        pieces = null;
        cursor = 0;
    }

    void resume(Map<String, ServerLevel> levels) {
        if (!Files.exists(journal)) return;
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(journal)))) {
            if (in.readInt() != 0x56534436) throw new IOException("unknown maintenance journal");
            int n = in.readInt();
            if (n < 0 || n > 65_536) throw new IOException("invalid journal count");
            for (int i = 0; i < n; i++) {
                var level = levels.get(in.readUTF());
                long root = in.readLong();
                int count = in.readUnsignedShort();
                if (count > 512) throw new IOException("invalid journal bitmap");
                long[] bits = new long[count];
                for (int j = 0; j < count; j++) bits[j] = in.readLong();
                if (level != null) dirty.restore(level, root, bits, System.nanoTime());
            }
        } catch (Exception e) {
            Voxyserver.LOGGER.error("cannot resume LOD maintenance journal", e);
        }
    }

    void checkpoint() {

        try {
            Files.createDirectories(journal.getParent());
            Path temp = journal.resolveSibling(journal.getFileName() + ".tmp");
            var roots = dirty.snapshotIncluding(active);
            try (var out =
                    new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(0x56534436);
                out.writeInt(roots.size());
                for (var root : roots) {
                    out.writeUTF(root.dimension().dimension().identifier().toString());
                    out.writeLong(root.key());
                    out.writeShort(root.bits().length);
                    for (long bits : root.bits()) out.writeLong(bits);
                }
            }
            try {
                Files.move(
                        temp,
                        journal,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, journal, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Voxyserver.LOGGER.error("cannot checkpoint LOD maintenance work", e);
        }
    }
}
