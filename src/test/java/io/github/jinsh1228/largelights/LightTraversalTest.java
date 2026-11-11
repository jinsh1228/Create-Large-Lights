package io.github.jinsh1228.largelights;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightTraversalTest {
    @Test
    void voxelOrderAndEndpointBiasMatchVanillaIncludingTiesAndWorldEdges() {
        Random random = new Random(92218);
        for (int i = 0; i < 2500; i++) {
            double offset = i % 3 == 0 ? 29990000 : i % 3 == 1 ? -29990000 : 0;
            Vec3 from = new Vec3(offset + random.nextInt(64), random.nextInt(320), offset + random.nextInt(64));
            Vec3 to = from.add(random.nextInt(513) - 256, random.nextInt(513) - 256, random.nextInt(513) - 256);
            if (i % 2 == 0) from = from.add(random.nextDouble(), random.nextDouble(), random.nextDouble());
            if (i % 5 == 0) to = from.add(128, 128, 128);
            List<BlockPos> expected = new ArrayList<>(), actual = new ArrayList<>();
            BlockGetter.traverseBlocks(from, to, expected, (cells, pos) -> { cells.add(pos.immutable()); return null; }, cells -> null);
            assertTrue(LightTraversal.clear(from, to, new LightTraversal.Test() {
                public int skip(BlockPos pos) { return 0; }
                public boolean blocked(BlockPos pos) { actual.add(pos.immutable()); return false; }
            }));
            assertEquals(expected, actual, "ray " + i);
        }
    }

    @Test
    void emptyGroupsAndSectionsSkipQueriesWithoutChangingOcclusion() {
        Random random = new Random(8192);
        int[] queried = {0}, vanilla = {0};
        for (int i = 0; i < 4000; i++) {
            Vec3 from = new Vec3(random.nextDouble() * 256 - 128, random.nextDouble() * 256, random.nextDouble() * 256 - 128);
            Vec3 to = from.add(random.nextInt(513) - 256, random.nextInt(513) - 256, random.nextInt(513) - 256);
            boolean expected = BlockGetter.traverseBlocks(from, to, vanilla, (count, pos) -> {
                count[0]++;
                return blocked(pos) ? Boolean.TRUE : null;
            }, count -> null) == null;
            boolean actual = LightTraversal.clear(from, to, new LightTraversal.Test() {
                public int skip(BlockPos pos) { return empty(pos); }
                public boolean blocked(BlockPos pos) { queried[0]++; return LightTraversalTest.blocked(pos); }
            });
            assertEquals(expected, actual, "ray " + i);
        }
        assertTrue(queried[0] < vanilla[0] / 2, queried[0] + " vs " + vanilla[0]);
    }

    private static int empty(BlockPos pos) {
        if (((pos.getX() >> 4) + (pos.getY() >> 4) * 13 + (pos.getZ() >> 4) * 7 & 3) != 0) return 4;
        return ((pos.getX() >> 2) + (pos.getY() >> 2) * 7 + (pos.getZ() >> 2) * 11 & 1) != 0 ? 2 : 0;
    }
    private static boolean blocked(BlockPos pos) {
        return empty(pos) == 0 && (pos.getX() * 177 + pos.getY() * 37 + pos.getZ() * 73 & 63) == 0;
    }
}
