package io.github.jinsh1228.largelights;

import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
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
    private static final long NANOS_PER_TICK = 2_000_000;
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

        Work(long section, LightField.Snapshot snapshot) {
            this.section = section;
            this.snapshot = snapshot;
        }
    }

    private static final class ClientState {
        final Long2ObjectOpenHashMap<byte[]> values = new Long2ObjectOpenHashMap<>();
        final LongLinkedOpenHashSet missing = new LongLinkedOpenHashSet();
        final LongOpenHashSet requested = new LongOpenHashSet();
        final LongOpenHashSet activeChunks = new LongOpenHashSet();
    }

    private static ClientState client(Level level) {
        return clients.computeIfAbsent(level, ignored -> new ClientState());
    }

    /** Called from chunk mesh workers too; only immutable arrays leave this lock. */
    static int sample(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return 0;
        }
        byte[] values;
        synchronized (clients) {
            ClientState state = client(level);
            long section = SectionPos.asLong(pos);
            if (!state.activeChunks.contains(chunk(section))) {
                return 0;
            }
            if (level.getChunkSource().getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                return 0;
            }
            values = state.values.get(section);
            if (values == null) {
                if (state.requested.add(section)) {
                    state.missing.add(section);
                }
                return 0;
            }
        }
        if (values.length == 0) {
            return 0;
        }
        int cell = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
        return values[cell >> 1] >> ((cell & 1) << 2) & 15;
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
                            state.dirty.removeIf((java.util.function.LongPredicate) id -> chunk(id) == chunk);
                            if (state.work != null && chunk(state.work.section) == chunk) {
                                state.work = null;
                            }
                        }
                    }
                    relevant(level, chunk, watch);
                    for (long section : watch.sections) {
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
                    for (LightField.Entry entry : entries) {
                        if (entry.beam().intersectsSection(section)) {
                            watch.sections.add(section);
                            break;
                        }
                    }
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
            boolean affected = false;
            for (LightField.Entry entry : LightField.entries(level, chunk(section))) {
                affected |= entry.beam().intersectsSection(section);
            }
            if (!affected) {
                PacketDistributor.sendToPlayer(player,
                        new LightFieldPacket(level.dimension().location(), section, EMPTY));
                return;
            }
            state.dirty.add(section);
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
        for (ServerState state : servers.values()) {
            state.lastNanos = 0;
        }
        if (scheduled.isEmpty()) {
            return;
        }
        int cells = 0, completed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (cells < CELLS_PER_TICK && completed < 2 && !scheduled.isEmpty()
                && System.nanoTime() - start < NANOS_PER_TICK) {
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
                boolean affected = false;
                for (LightField.Entry entry : LightField.entries(level, chunk(state.work.section))) {
                    affected |= entry.beam().intersectsSection(state.work.section);
                }
                if (!affected) {
                    state.work.cell = 4096;
                }
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
                int index = work.cell++;
                pos.set(x + (index & 15), y + (index >> 8), z + ((index >> 4) & 15));
                int value = work.snapshot == null ? 0 : work.snapshot.sample(pos);
                work.values[index >> 1] |= (byte) (value << ((index & 1) << 2));
                work.lit |= value != 0;
                cells++;
                if (System.nanoTime() - start >= NANOS_PER_TICK) {
                    break;
                }
            }
            if (work.cell == 4096) {
                LightFieldPacket packet = new LightFieldPacket(level.dimension().location(), work.section,
                        work.lit ? work.values : EMPTY);
                for (ServerPlayer player : watch.players) {
                    if (player.serverLevel() == level) {
                        PacketDistributor.sendToPlayer(player, packet);
                    }
                }
                state.work = null;
                state.completed++;
                completed++;
            }
            state.lastNanos += System.nanoTime() - passStart;
            queue(level, state);
            if (System.nanoTime() - start >= NANOS_PER_TICK) {
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
