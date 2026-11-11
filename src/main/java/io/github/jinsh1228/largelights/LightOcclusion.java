package io.github.jinsh1228.largelights;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared transmission geometry. Rays retain vanilla's traversal and exact partial-block clipping. */
final class LightOcclusion implements LightField.Visibility {
    private static final int CACHED_SECTIONS = 2048;
    private final WeakReference<Level> reference;
    private final Long2ObjectLinkedOpenHashMap<Section> sections = new Long2ObjectLinkedOpenHashMap<>();
    private final LightReach reach = new LightReach(this::full);
    private long currentKey = Long.MIN_VALUE;
    private Section current;
    private final Long2ObjectLinkedOpenHashMap<Cell[]> tubes = new Long2ObjectLinkedOpenHashMap<>();
    private static final Cell[] DENSE = new Cell[9];
    private record Cell(BlockPos pos, VoxelShape shape) { }

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

    /** A one-group halo makes this a superset of vanilla's fine traversal, including edge/corner ties. */
    private Cell[] tube(Level level, BlockPos group) {
        long key = group.asLong();
        Cell[] known = tubes.getAndMoveToLast(key);
        if (known != null) return known;
        List<Cell> cells = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        outer: for (int gy = group.getY() - 1; gy <= group.getY() + 1; gy++) {
            for (int gz = group.getZ() - 1; gz <= group.getZ() + 1; gz++) {
                for (int gx = group.getX() - 1; gx <= group.getX() + 1; gx++) {
                    pos.set(gx * 4, gy * 4, gz * 4);
                    if (level.isOutsideBuildHeight(pos)) continue;
                    Section section = section(level, pos);
                    if (section == null) { known = DENSE; break outer; }
                    classify(level, section, pos);
                    int x0 = pos.getX() & 12, y0 = pos.getY() & 12, z0 = pos.getZ() & 12;
                    long bit = 1L << ((x0 >> 2) | z0 | (y0 << 2));
                    if ((section.occupied & bit) == 0) continue;
                    for (int i = 0; i < 64; i++) {
                        int x = x0 + (i & 3), z = z0 + (i >> 2 & 3), y = y0 + (i >> 4);
                        int cell = y << 8 | z << 4 | x;
                        if (((section.full[cell >> 6] | section.shaped[cell >> 6]) & 1L << cell) == 0) continue;
                        pos.set(gx * 4 + (i & 3), gy * 4 + (i >> 4), gz * 4 + (i >> 2 & 3));
                        VoxelShape shape = (section.full[cell >> 6] & 1L << cell) != 0 ? Shapes.block() : shape(level, section, pos, cell);
                        if (shape.isEmpty()) continue;
                        if (cells.size() == 8) { known = DENSE; break outer; }
                        cells.add(new Cell(pos.immutable(), shape));
                    }
                }
            }
        }
        if (known == null) known = cells.toArray(Cell[]::new);
        if (tubes.size() >= 8192) tubes.removeFirst();
        tubes.put(key, known);
        return known;
    }

    private boolean clearTube(Level level, BlockPos source, Vec3 from, Vec3 to) {
        if (from.distanceToSqr(to) > 512 * 512) return false;
        return BlockGetter.traverseBlocks(from.scale(.25), to.scale(.25), level, (world, group) -> {
            Cell[] cells = tube(world, group);
            if (cells == DENSE) return true;
            for (Cell cell : cells) {
                if (!cell.pos.equals(source) && nearCell(from, to, cell.pos) && cell.shape.clip(from, to, cell.pos) != null) return true;
            }
            return null;
        }, world -> null) == null;
    }

    private static boolean nearCell(Vec3 from, Vec3 to, BlockPos pos) {
        double first = 0, last = 1;
        for (int axis = 0; axis < 3; axis++) {
            double start = axis == 0 ? from.x : axis == 1 ? from.y : from.z;
            double delta = (axis == 0 ? to.x : axis == 1 ? to.y : to.z) - start;
            double min = (axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ()) - .001;
            if (delta == 0) { if (start < min || start > min + 1.002) return false; }
            else {
                double a = (min - start) / delta, b = (min + 1.002 - start) / delta;
                first = Math.max(first, Math.min(a, b)); last = Math.min(last, Math.max(a, b));
                if (first > last) return false;
            }
        }
        return true;
    }

    @Override
    public synchronized boolean visible(BlockPos source, LightField.Beam beam, BlockPos target) {
        Level level = reference.get();
        if (level == null) return false;
        Vec3 from = beam.origin(), to = target.getCenter();
        if (!target.equals(source) && from.distanceToSqr(to) > 1e-7 && full(target)) return false;
        if (clearTube(level, source, from, to)) return true;
        return BlockGetter.traverseBlocks(from, to, level, (world, pos) -> {
            if (pos.equals(source) || world.isOutsideBuildHeight(pos)) return null;
            Section section = section(world, pos);
            if (section == null) {
                var chunk = world.getChunkSource().getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) return Shapes.block().clip(from, to, pos);
                BlockState state = chunk.getBlockState(pos);
                if (state.getLightBlock(world, pos) < 15 && !state.useShapeForLightOcclusion()) return null;
                return (state.useShapeForLightOcclusion() ? state.getOcclusionShape(world, pos) : Shapes.block()).clip(from, to, pos);
            }
            classify(world, section, pos);
            int cell = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
            if ((section.full[cell >> 6] & 1L << cell) != 0) return Shapes.block().clip(from, to, pos);
            if ((section.shaped[cell >> 6] & 1L << cell) != 0) return shape(world, section, pos, cell).clip(from, to, pos);
            return null;
        }, world -> null) == null;
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
        for (int x = (pos.getX() >> 2) - 1; x <= (pos.getX() >> 2) + 1; x++)
            for (int y = (pos.getY() >> 2) - 1; y <= (pos.getY() >> 2) + 1; y++)
                for (int z = (pos.getZ() >> 2) - 1; z <= (pos.getZ() >> 2) + 1; z++) tubes.remove(BlockPos.asLong(x, y, z));
        for (int x = (pos.getX() - 1) >> 4; x <= (pos.getX() + 1) >> 4; x++)
            for (int y = (pos.getY() - 1) >> 4; y <= (pos.getY() + 1) >> 4; y++)
                for (int z = (pos.getZ() - 1) >> 4; z <= (pos.getZ() + 1) >> 4; z++) sections.remove(SectionPos.asLong(x, y, z));
        reach.invalidate(new AABB(pos).inflate(1));
    }

    @Override
    public synchronized void invalidateChunk(int x, int z) {
        currentKey = Long.MIN_VALUE;
        current = null;
        tubes.keySet().removeIf((java.util.function.LongPredicate) key -> Math.abs((BlockPos.getX(key) >> 2) - x) <= 1
                && Math.abs((BlockPos.getZ(key) >> 2) - z) <= 1);
        sections.keySet().removeIf((java.util.function.LongPredicate) key -> Math.abs(SectionPos.x(key) - x) <= 1
                && Math.abs(SectionPos.z(key) - z) <= 1);
        reach.invalidate(new AABB(x * 16.0 - 1, -1e9, z * 16.0 - 1, x * 16.0 + 17, 1e9, z * 16.0 + 17));
    }
}
