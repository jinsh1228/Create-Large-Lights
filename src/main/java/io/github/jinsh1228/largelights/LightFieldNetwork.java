package io.github.jinsh1228.largelights;

import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Event-driven, bounded synchronization of sections that players actually use. */
public final class LightFieldNetwork {
    private static final int CELLS_PER_TICK = 32768;
    private static final long MIN_NANOS_PER_TICK = 2_000_000;
    private static final long MAX_NANOS_PER_TICK = 8_000_000;
    private static final byte[] EMPTY = new byte[0];
    private static final Map<ServerLevel, ServerState> servers = new WeakHashMap<>();
    private static final ArrayDeque<ServerLevel> scheduled = new ArrayDeque<>();
    private static final Map<Level, ClientState> clients = new WeakHashMap<>();

    private LightFieldNetwork() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(LightFieldNetwork::sent);
        NeoForge.EVENT_BUS.addListener(LightFieldNetwork::unwatch);
        NeoForge.EVENT_BUS.addListener(LightFieldNetwork::tick);
        NeoForge.EVENT_BUS.addListener(LightFieldNetwork::unload);
        NeoForge.EVENT_BUS.addListener(LightFieldNetwork::logout);
    }

    private static final class Watch {
        final Set<ServerPlayer> players = new HashSet<>();
        final LongOpenHashSet sections = new LongOpenHashSet();
        final Long2ObjectOpenHashMap<byte[]> last = new Long2ObjectOpenHashMap<>();
        final LevelChunk chunk;
        boolean active;

        Watch(LevelChunk chunk) {
            this.chunk = chunk;
        }
    }

    private static final class ServerState {
        final Long2ObjectOpenHashMap<Watch> watches = new Long2ObjectOpenHashMap<>();
        final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
        Work work;
        long completed;
        long lastNanos;
        boolean queued;
    }

    private static final class Work {
        final long section;
        final LightField.Snapshot snapshot;
        final byte[] values = new byte[2048];
        int cell;
        boolean lit;
        int tileAt = -1;
        LightField.Snapshot tile;

        Work(long section, LightField.Snapshot snapshot) {
            this.section = section;
            this.snapshot = snapshot;
        }
    }

    private static final class ClientState {
        final Long2ObjectOpenHashMap<byte[]> values = new Long2ObjectOpenHashMap<>();
        final Long2ObjectLinkedOpenHashMap<MergedLayer> merged = new Long2ObjectLinkedOpenHashMap<>();
        final LongLinkedOpenHashSet missing = new LongLinkedOpenHashSet();
        final LongOpenHashSet requested = new LongOpenHashSet();
        final LongOpenHashSet activeChunks = new LongOpenHashSet();
    }

    private record MergedLayer(DataLayer vanilla, byte[] values, DataLayer light) {
    }

    private static ClientState client(Level level) {
        return clients.computeIfAbsent(level, ignored -> new ClientState());
    }

    /** Called from chunk mesh workers too; only immutable arrays leave this lock. */
    static int sample(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return 0;
        }
        byte[] values = values(level, SectionPos.asLong(pos));
        if (values == null || values.length == 0) {
            return 0;
        }
        int cell = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
        return values[cell >> 1] >> ((cell & 1) << 2) & 15;
    }

    private static byte[] values(Level level, long section) {
        if (SectionPos.y(section) < level.getMinSection() || SectionPos.y(section) >= level.getMaxSection()) return null;
        synchronized (clients) {
            ClientState state = client(level);
            if (!state.activeChunks.contains(chunk(section))) {
                return null;
            }
            if (level.getChunkSource().getChunkForLighting(SectionPos.x(section), SectionPos.z(section)) == null) {
                return null;
            }
            byte[] values = state.values.get(section);
            if (values == null) {
                if (state.requested.add(section)) {
                    state.missing.add(section);
                }
            }
            return values;
        }
    }

    /** Read-only section view for bulk renderers. Never put fixture light in vanilla propagation or saved data. */
    public static DataLayer section(Level level, long section, DataLayer vanilla) {
        if (!level.isClientSide) return vanilla;
        byte[] values = values(level, section);
        if (values == null || values.length == 0) return vanilla;
        synchronized (clients) {
            ClientState state = client(level);
            MergedLayer cached = state.merged.getAndMoveToLast(section);
            if (cached != null && cached.vanilla == vanilla && cached.values == values) return cached.light;
            DataLayer light = merge(vanilla, values);
            if (cached == null && state.merged.size() >= LightField.CACHE_SECTIONS) state.merged.removeFirst();
            state.merged.put(section, new MergedLayer(vanilla, values, light));
            return light;
        }
    }

    static DataLayer merge(DataLayer vanilla, byte[] values) {
        if (vanilla == null || vanilla.isEmpty()) return new DataLayer(values.clone());
        DataLayer light = vanilla.copy();
        byte[] data = light.getData();
        for (int i = 0; i < data.length; i++) {
            int a = data[i] & 255, b = values[i] & 255;
            data[i] = (byte) (Math.max(a & 15, b & 15) | Math.max(a >> 4, b >> 4) << 4);
        }
        return light;
    }

    /** Main client thread only. A sample query never sends a packet from a mesh worker. */
    public static void requestMissing(Level level) {
        synchronized (clients) {
            ClientState state = clients.get(level);
            if (state != null) {
                for (int count = 0; count < 8 && !state.missing.isEmpty(); count++) {
                    PacketDistributor.sendToServer(new LightFieldPacket.Request(state.missing.removeFirstLong()));
                }
            }
        }
    }

    static void receive(Level level, long section, byte[] values) {
        if (!level.isClientSide || SectionPos.y(section) < level.getMinSection()
                || SectionPos.y(section) >= level.getMaxSection()
                || level.getChunkSource().getChunkForLighting(SectionPos.x(section), SectionPos.z(section)) == null) {
            return;
        }
        synchronized (clients) {
            ClientState state = client(level);
            if (!state.activeChunks.contains(chunk(section))) {
                return;
            }
            state.values.put(section, values);
            state.merged.remove(section);
            state.requested.add(section);
            state.missing.remove(section);
        }
        LightField.sectionChanged(level, section);
    }

    static void discardChunk(Level level, ChunkPos chunk) {
        synchronized (clients) {
            ClientState state = clients.get(level);
            if (state != null) {
                discard(state, chunk.toLong());
            }
        }
    }

    private static void discard(ClientState state, long chunk) {
        state.activeChunks.remove(chunk);
        state.values.keySet().removeIf((java.util.function.LongPredicate) id -> chunk(id) == chunk);
        state.merged.keySet().removeIf((java.util.function.LongPredicate) id -> chunk(id) == chunk);
        state.missing.removeIf((java.util.function.LongPredicate) id -> chunk(id) == chunk);
        state.requested.removeIf((java.util.function.LongPredicate) id -> chunk(id) == chunk);
    }

    static void hint(Level level, long chunk, boolean active) {
        LongOpenHashSet redraw = new LongOpenHashSet();
        synchronized (clients) {
            ClientState state = client(level);
            if (active) {
                state.activeChunks.add(chunk);
            } else {
                for (long section : state.values.keySet()) {
                    if (chunk(section) == chunk && state.values.get(section).length != 0) {
                        redraw.add(section);
                    }
                }
                discard(state, chunk);
            }
        }
        for (long section : redraw) {
            LightField.sectionChanged(level, section);
        }
    }

    private static long chunk(long section) {
        return ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section));
    }

    private static void sent(ChunkWatchEvent.Sent event) {
        ServerState state = servers.computeIfAbsent(event.getLevel(), ignored -> new ServerState());
        Watch watch = state.watches.computeIfAbsent(event.getPos().toLong(), ignored -> new Watch(event.getChunk()));
        watch.players.add(event.getPlayer());
        watch.active = !LightField.entries(event.getLevel(), event.getPos().toLong()).isEmpty();
        if (watch.active) {
            PacketDistributor.sendToPlayer(event.getPlayer(), new LightFieldPacket.Hint(
                    event.getLevel().dimension().location(), event.getPos().toLong(), true));
        }
        relevant(event.getLevel(), event.getPos().toLong(), watch);
        watch.last.clear(); // A newly watching player must receive a complete initial set.
        state.dirty.addAll(watch.sections);
        queue(event.getLevel(), state);
    }

    private static void unwatch(ChunkWatchEvent.UnWatch event) {
        ServerState state = servers.get(event.getLevel());
        if (state != null) {
            Watch watch = state.watches.get(event.getPos().toLong());
            if (watch != null && watch.players.remove(event.getPlayer()) && watch.players.isEmpty()) {
                state.watches.remove(event.getPos().toLong());
                state.dirty.removeIf((java.util.function.LongPredicate) id -> chunk(id) == event.getPos().toLong());
                if (state.work != null && chunk(state.work.section) == event.getPos().toLong()) {
                    state.work = null;
                }
            }
        }
    }

    /** No full beam volume is sampled: only watched terrain and previously queried sections enter the queue. */
    static void changed(ServerLevel level, AABB bounds) {
        changed(level, bounds, false);
    }

    static void changed(ServerLevel level, AABB bounds, boolean weightsOnly) {
        if (!level.getServer().isSameThread()) {
            level.getServer().execute(() -> changed(level, bounds, weightsOnly));
            return;
        }
        ServerState state = servers.get(level);
        if (state == null) {
            return;
        }
        int minX = SectionPos.blockToSectionCoord((int) Math.floor(bounds.minX));
        int maxX = SectionPos.blockToSectionCoord((int) Math.floor(bounds.maxX));
        int minZ = SectionPos.blockToSectionCoord((int) Math.floor(bounds.minZ));
        int maxZ = SectionPos.blockToSectionCoord((int) Math.floor(bounds.maxZ));
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                long chunk = ChunkPos.asLong(x, z);
                Watch watch = state.watches.get(chunk);
                if (watch != null) {
                    boolean active = !LightField.entries(level, chunk).isEmpty();
                    if (active != watch.active) {
                        watch.active = active;
                        LightFieldPacket.Hint hint = new LightFieldPacket.Hint(level.dimension().location(), chunk, active);
                        for (ServerPlayer player : watch.players) {
                            if (player.serverLevel() == level) {
                                PacketDistributor.sendToPlayer(player, hint);
                            }
                        }
                        if (!active) {
                            watch.sections.clear();
                            watch.last.clear();
                            state.dirty.removeIf((java.util.function.LongPredicate) id -> chunk(id) == chunk);
                            if (state.work != null && chunk(state.work.section) == chunk) {
                                state.work = null;
                            }
                        }
                    }
                    relevant(level, chunk, watch);
                    for (LongIterator it = watch.sections.iterator(); it.hasNext(); ) {
                        long section = it.nextLong();
                        if (LightField.sectionIntersects(section, bounds)) {
                            state.dirty.add(section);
                        }
                    }
                }
            }
        }
        if (!weightsOnly && state.work != null && LightField.sectionIntersects(state.work.section, bounds)) {
            state.dirty.add(state.work.section);
            state.work = null;
        }
        queue(level, state);
    }

    private static void relevant(ServerLevel level, long chunk, Watch watch) {
        var entries = LightField.entries(level, chunk);
        if (entries.isEmpty()) {
            return;
        }
        var sections = watch.chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            if (!sections[i].hasOnlyAir()) {
                for (int y = Math.max(0, i - 1); y <= Math.min(sections.length - 1, i + 1); y++) {
                    long section = SectionPos.asLong(watch.chunk.getPos().x, y + level.getMinSection(), watch.chunk.getPos().z);
                    if (watch.sections.contains(section)) continue;
                    if (LightField.affects(level, section)) watch.sections.add(section);
                }
            }
        }
    }

    static void request(ServerPlayer player, long section) {
        ServerLevel level = player.serverLevel();
        ServerState state = servers.get(level);
        Watch watch = state == null ? null : state.watches.get(chunk(section));
        if (watch != null && watch.players.contains(player) && SectionPos.y(section) >= level.getMinSection()
                && SectionPos.y(section) < level.getMaxSection()) {
            watch.sections.add(section);
            boolean affected = LightField.affects(level, section);
            if (!affected) {
                PacketDistributor.sendToPlayer(player,
                        new LightFieldPacket(level.dimension().location(), section, EMPTY));
                return;
            }
            byte[] known = watch.last.get(section);
            if (known != null && !state.dirty.contains(section) && (state.work == null || state.work.section != section)) {
                PacketDistributor.sendToPlayer(player, new LightFieldPacket(level.dimension().location(), section, known));
                return;
            }
            watch.last.remove(section); // Requests need a reply even when the newly computed values are unchanged.
            state.dirty.addAndMoveToFirst(section);
            queue(level, state);
        }
    }

    private static void queue(ServerLevel level, ServerState state) {
        if (!state.queued && (state.work != null || !state.dirty.isEmpty())) {
            state.queued = true;
            scheduled.addLast(level);
        }
    }

    private static void tick(ServerTickEvent.Post event) {
        long start = System.nanoTime();
        long budget = Math.max(MIN_NANOS_PER_TICK, Math.min(MAX_NANOS_PER_TICK,
                (45_000_000 - event.getServer().getAverageTickTimeNanos()) / 4));
        for (ServerState state : servers.values()) {
            state.lastNanos = 0;
        }
        if (scheduled.isEmpty()) {
            return;
        }
        int cells = 0, completed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (cells < CELLS_PER_TICK && completed < 64 && !scheduled.isEmpty()
                && System.nanoTime() - start < budget) {
            ServerLevel level = scheduled.removeFirst();
            ServerState state = servers.get(level);
            if (state == null) {
                continue;
            }
            state.queued = false;
            long passStart = System.nanoTime();
            if (state.work == null) {
                if (state.dirty.isEmpty()) {
                    continue;
                }
                long section = state.dirty.removeFirstLong();
                state.work = new Work(section, LightField.snapshot(level, section));
                if (state.work.snapshot == null || state.work.snapshot.entries().isEmpty()) state.work.cell = 4096;
            }
            Work work = state.work;
            Watch watch = state.watches.get(chunk(work.section));
            if (watch == null) {
                state.work = null;
                queue(level, state);
                continue;
            }
            int x = SectionPos.x(work.section) << 4, y = SectionPos.y(work.section) << 4, z = SectionPos.z(work.section) << 4;
            int passLimit = Math.min(CELLS_PER_TICK, cells + 256);
            while (work.cell < 4096 && cells < passLimit) {
                int tile = work.cell >> 6;
                if (work.tileAt != tile) {
                    work.tileAt = tile;
                    int tx = x + ((tile & 3) << 2), tz = z + ((tile >> 2 & 3) << 2), ty = y + ((tile >> 4) << 2);
                    work.tile = work.snapshot == null ? null : work.snapshot.tile(new AABB(tx, ty, tz, tx + 4, ty + 4, tz + 4));
                }
                if (work.tile == null || work.tile.entries().isEmpty()) {
                    cells += 64;
                    work.cell = (tile + 1) << 6;
                    continue;
                }
                int local = work.cell++ & 63;
                int index = ((tile >> 4) * 4 + (local >> 4)) << 8
                        | ((tile >> 2 & 3) * 4 + (local >> 2 & 3)) << 4 | (tile & 3) * 4 + (local & 3);
                pos.set(x + (index & 15), y + (index >> 8), z + ((index >> 4) & 15));
                int value = work.tile.sample(pos);
                work.values[index >> 1] |= (byte) (value << ((index & 1) << 2));
                work.lit |= value != 0;
                cells++;
                if (System.nanoTime() - start >= budget) {
                    break;
                }
            }
            if (work.cell == 4096) {
                byte[] values = work.lit ? work.values : EMPTY;
                if (!Arrays.equals(watch.last.put(work.section, values), values)) {
                    LightFieldPacket packet = new LightFieldPacket(level.dimension().location(), work.section, values);
                    for (ServerPlayer player : watch.players) {
                        if (player.serverLevel() == level) PacketDistributor.sendToPlayer(player, packet);
                    }
                }
                state.work = null;
                state.completed++;
                completed++;
            }
            state.lastNanos += System.nanoTime() - passStart;
            queue(level, state);
            if (System.nanoTime() - start >= budget) {
                break;
            }
        }
    }

    public record Metrics(int pendingSections, long completedSections, long lastTickNanos) {
    }

    public static Metrics metrics(ServerLevel level) {
        ServerState state = servers.get(level);
        return state == null ? new Metrics(0, 0, 0)
                : new Metrics(state.dirty.size() + (state.work == null ? 0 : 1), state.completed, state.lastNanos);
    }

    private static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            servers.remove(level);
            scheduled.remove(level);
        } else {
            synchronized (clients) {
                clients.remove(event.getLevel());
            }
        }
    }

    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            for (ServerState state : servers.values()) {
                state.watches.values().removeIf(watch -> {
                    watch.players.remove(player);
                    return watch.players.isEmpty();
                });
            }
        }
    }
}
