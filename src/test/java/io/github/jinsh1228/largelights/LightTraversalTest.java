package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Random;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class LightTraversalTest {
    @Test
    void coarseHaloContainsEveryFineCellAtWorldLimitsAndExactGridTies() {
        Random random = new Random(7331);
        for (int trial = 0; trial < 2000; trial++) {
            double offset = trial % 2 == 0 ? 29_990_000 : -29_990_000;
            Vec3 from = new Vec3(offset + random.nextInt(16), 128 + random.nextDouble(), offset + random.nextInt(16));
            Vec3 to = from.add(random.nextInt(513) - 256, random.nextInt(513) - 256, random.nextInt(513) - 256);
            LongOpenHashSet groups = new LongOpenHashSet();
            BlockGetter.traverseBlocks(from.scale(.25), to.scale(.25), groups, (set, p) -> { set.add(p.asLong()); return null; }, set -> null);
            BlockGetter.traverseBlocks(from, to, groups, (set, p) -> {
                int x = p.getX() >> 2, y = p.getY() >> 2, z = p.getZ() >> 2;
                boolean covered = false;
                for (int dx = -1; dx <= 1 && !covered; dx++) for (int dy = -1; dy <= 1 && !covered; dy++)
                    for (int dz = -1; dz <= 1 && !covered; dz++) covered = set.contains(BlockPos.asLong(x + dx, y + dy, z + dz));
                assertTrue(covered, "fine traversal must lie inside the conservative halo");
                return null;
            }, set -> null);
        }
    }
}
