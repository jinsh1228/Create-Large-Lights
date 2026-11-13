package io.github.jinsh1228.largelights;

import java.lang.ref.WeakReference;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared transmission geometry. Rays retain vanilla's traversal and exact partial-block clipping. */
final class LightOcclusion implements LightField.Visibility {
    private static final int CACHED_SECTIONS = 2048;
    private final WeakReference<Level> reference;
    private final Long2ObjectLinkedOpenHashMap<Section> sections = new Long2ObjectLinkedOpenHashMap<>();
    private final LightReach reach = new LightReach(this::full);
    private long currentKey = Long.MIN_VALUE;
    private Section current;

    private static final class Section {
        final LevelChunkSection blocks;
        final long[] full = new long[64], shaped = new long[64];
        final Int2ObjectOpenHashMap<VoxelShape> shapes = new Int2ObjectOpenHashMap<>();
        long checked, occupied;

        Section(LevelChunkSection blocks) {
            this.blocks = blocks;
        }
    }

    LightOcclusion(Level level) {
        reference = new WeakReference<>(level);
    }

    private Section section(Level level, BlockPos pos) {
        long key = SectionPos.asLong(pos);
        if (key == currentKey) return current;
        Section known = sections.getAndMoveToLast(key);
        if (known != null) {
            currentKey = key;
            return current = known;
        }
        var chunk = level.getChunkSource().getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
        if (!(chunk instanceof ChunkAccess access)) return null;
        LevelChunkSection blocks = access.getSections()[level.getSectionIndex(pos.getY())];
        Section result = new Section(blocks);
        if (blocks.hasOnlyAir()) result.checked = -1L;
        if (sections.size() >= CACHED_SECTIONS) sections.removeFirst();
        sections.put(key, result);
        currentKey = key;
        return current = result;
    }

    private void classify(Level level, Section section, BlockPos at) {
        int x0 = at.getX() & 12, y0 = at.getY() & 12, z0 = at.getZ() & 12;
        long group = 1L << ((x0 >> 2) | (z0 << 0) | (y0 << 2));
        if ((section.checked & group) != 0) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = y0; y < y0 + 4; y++) {
            for (int z = z0; z < z0 + 4; z++) {
                for (int x = x0; x < x0 + 4; x++) {
                    BlockState state = section.blocks.getBlockState(x, y, z);
                    if (state.isAir()) continue;
                    pos.set((at.getX() & ~15) + x, (at.getY() & ~15) + y, (at.getZ() & ~15) + z);
                    int cell = y << 8 | z << 4 | x;
                    if (state.useShapeForLightOcclusion()) {
                        section.shaped[cell >> 6] |= 1L << cell;
                        section.occupied |= group;
                    } else if (state.getLightBlock(level, pos) >= 15) {
                        section.full[cell >> 6] |= 1L << cell;
                        section.occupied |= group;
                    }
                }
            }
        }
        section.checked |= group;
    }

    private boolean full(BlockPos pos) {
        Level level = reference.get();
        if (level == null || level.isOutsideBuildHeight(pos)) return false;
        Section section = section(level, pos);
        if (section == null) return false;
        classify(level, section, pos);
        int cell = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
        return (section.full[cell >> 6] & 1L << cell) != 0
                || (section.shaped[cell >> 6] & 1L << cell) != 0 && Block.isShapeFullBlock(shape(level, section, pos, cell));
    }

    private VoxelShape shape(Level level, Section section, BlockPos pos, int cell) {
        VoxelShape known = section.shapes.get(cell);
        if (known == null) {
            known = section.blocks.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15).getOcclusionShape(level, pos);
            section.shapes.put(cell, known);
        }
        return known;
    }

    @Override
    public synchronized boolean visible(BlockPos source, LightField.Beam beam, BlockPos target) {
        Level level = reference.get();
        if (level == null) return false;
        Vec3 from = beam.origin(), to = target.getCenter();
        if (!target.equals(source) && from.distanceToSqr(to) > 1e-7 && full(target)) return false;
        return LightTraversal.clear(from, to, new LightTraversal.Test() {
            Section group;
            @Override
            public int skip(BlockPos pos) {
                group = level.isOutsideBuildHeight(pos) ? null : section(level, pos);
                if (group == null) return 0;
                if (group.checked == -1L && group.occupied == 0) return 4;
                classify(level, group, pos);
                int bit = (pos.getX() & 12) >> 2 | pos.getZ() & 12 | (pos.getY() & 12) << 2;
                return (group.occupied & 1L << bit) == 0 ? 2 : 0;
            }
            @Override
            public boolean blocked(BlockPos pos) {
                if (pos.equals(source) || level.isOutsideBuildHeight(pos)) return false;
                if (group == null) {
                    var chunk = level.getChunkSource().getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
                    if (chunk == null) return LightTraversal.hitsFullBlock(from, to, pos);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.getLightBlock(level, pos) < 15 && !state.useShapeForLightOcclusion()) return false;
                    return state.useShapeForLightOcclusion() ? state.getOcclusionShape(level, pos).clip(from, to, pos) != null
                            : LightTraversal.hitsFullBlock(from, to, pos);
                }
                int cell = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
                if ((group.full[cell >> 6] & 1L << cell) != 0) return LightTraversal.hitsFullBlock(from, to, pos);
                return (group.shaped[cell >> 6] & 1L << cell) != 0 && shape(level, group, pos, cell).clip(from, to, pos) != null;
            }
        });
    }

    @Override
    public synchronized double range(BlockPos source, LightField.Beam beam) {
        return reach.limit(source, beam);
    }

    @Override
    public synchronized boolean hidden(BlockPos source, LightField.Beam beam, AABB box) {
        return reach.hidden(source, beam, box);
    }

    @Override
    public synchronized void invalidate(BlockPos pos) {
        currentKey = Long.MIN_VALUE;
        current = null;
        for (int x = (pos.getX() - 1) >> 4; x <= (pos.getX() + 1) >> 4; x++)
            for (int y = (pos.getY() - 1) >> 4; y <= (pos.getY() + 1) >> 4; y++)
                for (int z = (pos.getZ() - 1) >> 4; z <= (pos.getZ() + 1) >> 4; z++) sections.remove(SectionPos.asLong(x, y, z));
        reach.invalidate(new AABB(pos).inflate(1));
    }

    @Override
    public synchronized void invalidateChunk(int x, int z) {
        currentKey = Long.MIN_VALUE;
        current = null;
        sections.keySet().removeIf((java.util.function.LongPredicate) key -> Math.abs(SectionPos.x(key) - x) <= 1
                && Math.abs(SectionPos.z(key) - z) <= 1);
        reach.invalidate(new AABB(x * 16.0 - 1, -1e9, z * 16.0 - 1, x * 16.0 + 17, 1e9, z * 16.0 + 17));
    }
}
