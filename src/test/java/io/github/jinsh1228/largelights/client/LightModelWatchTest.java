package io.github.jinsh1228.largelights.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class LightModelWatchTest {
    @Test
    void cmsPublicWorldChangesInvalidateTheAddonsCachedModels() {
        FloodlightOptics.register();
        LightModelSnapshots.clear();
        Object near = new Object(), far = new Object();
        AABB local = new AABB(0, 64, 0, 16, 80, 16), remote = local.move(512, 0, 0);
        long first = LightModelSnapshots.watch(near, local), other = LightModelSnapshots.watch(far, remote);
        org.haruyq.createmediasystems.api.client.WorldChanges.blocksChanged(4, 70, 4, 4, 70, 4);
        assertNotEquals(first, LightModelSnapshots.watch(near, local));
        assertEquals(other, LightModelSnapshots.watch(far, remote));
        LightModelSnapshots.clear();
    }

    @Test
    void onlyLocalChangesAndResourceReloadInvalidateAStationaryBeam() {
        LightModelSnapshots.clear();
        Object near = new Object(), far = new Object();
        AABB local = new AABB(0, 64, 0, 16, 80, 16), remote = local.move(512, 0, 0);
        long first = LightModelSnapshots.watch(near, local), other = LightModelSnapshots.watch(far, remote);
        LightModelSnapshots.sectionChanged(SectionPos.asLong(0, 4, 0));
        assertNotEquals(first, LightModelSnapshots.watch(near, local));
        assertEquals(other, LightModelSnapshots.watch(far, remote));
        long changed = LightModelSnapshots.watch(near, local);
        LightModelSnapshots.chunkChanged(0, 0);
        assertNotEquals(changed, LightModelSnapshots.watch(near, local));
        assertEquals(other, LightModelSnapshots.watch(far, remote));
        LightModelSnapshots.clear();
        assertNotEquals(other, LightModelSnapshots.watch(far, remote));
        LightModelSnapshots.clear();
    }
}
