package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

class RealLightTest {
    @Test
    void ratedLevelFollowsStrength() {
        assertEquals(0, RealLight.lightLevel(0));
        for (int signal = 1; signal <= 15; signal++) {
            assertEquals(signal, RealLight.lightLevel(signal / 15f));
        }
        assertEquals(1, RealLight.lightLevel(.01f));
        assertEquals(15, RealLight.lightLevel(2));
    }

    @Test
    void migrationDefersUnloadedLegacyLightsAndPersistsThem() {
        long near = new BlockPos(0, 64, 0).asLong();
        long far = new BlockPos(32, 64, 0).asLong();
        RealLight.Placed placed = new RealLight.Placed();
        placed.load(new long[]{near, far});
        RealLight.Pending pending = new RealLight.Pending();
        Set<Long> removed = new java.util.HashSet<>();
        RealLight.clear(placed, packed -> packed == near, removed::add, pending::add);
        assertEquals(Set.of(near), removed);
        assertEquals(0, placed.size());
        assertTrue(pending.isDirty());
        RealLight.Pending restored = RealLight.Pending.load(pending.save(new CompoundTag(), null), null);
        assertEquals(Set.of(far), restored.lights);
        restored.removeLoaded(packed -> false, removed::add);
        assertEquals(Set.of(near), removed);
        restored.removeLoaded(packed -> packed == far, removed::add);
        assertEquals(Set.of(near, far), removed);
        assertTrue(restored.lights.isEmpty());
        assertTrue(restored.isDirty());
    }

    @Test
    void freshAndReloadedFixturesRecalculateWithoutTimeOverflow() {
        RealLight.Placed placed = new RealLight.Placed();
        assertTrue(placed.needsUpdate(0, 0));
        placed.signature = 0;
        placed.updated = 100;
        assertFalse(placed.needsUpdate(0, 110));
        assertTrue(placed.needsUpdate(0, 100 + RealLight.REFRESH));
        assertTrue(placed.needsUpdate(0, 99));
        placed.load(new long[0]);
        assertTrue(placed.needsUpdate(0, 110));
    }
}
