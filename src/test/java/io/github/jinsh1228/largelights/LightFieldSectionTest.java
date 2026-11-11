package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

class LightFieldSectionTest {
    @Test
    void everyPackedNibblePairKeepsTheBrighterSourceWithoutChangingEitherInput() {
        for (int batch = 0; batch < 32; batch++) {
            byte[] vanilla = new byte[2048], fixture = new byte[2048];
            for (int i = 0; i < 2048; i++) {
                int pair = batch * 2048 + i;
                vanilla[i] = (byte) (pair >> 8);
                fixture[i] = (byte) pair;
            }
            byte[] before = vanilla.clone();
            byte[] fixtureBefore = fixture.clone();
            DataLayer first = new DataLayer(before), second = new DataLayer(fixtureBefore);
            DataLayer merged = LightFieldNetwork.merge(new DataLayer(vanilla), fixture);
            for (int cell = 0; cell < 4096; cell++) {
                int x = cell & 15, y = cell >> 8, z = cell >> 4 & 15;
                assertEquals(Math.max(first.get(x, y, z), second.get(x, y, z)), merged.get(x, y, z));
            }
            assertArrayEquals(before, vanilla);
            assertArrayEquals(fixtureBefore, fixture);
        }
    }

    @Test
    void missingVanillaSectionUsesAnIndependentCopyOfFixtureData() {
        byte[] fixture = new byte[2048];
        Arrays.fill(fixture, (byte) 0x27);
        for (DataLayer vanilla : new DataLayer[]{null, new DataLayer()}) {
            DataLayer merged = LightFieldNetwork.merge(vanilla, fixture);
            assertEquals(7, merged.get(0, 0, 0));
            assertEquals(2, merged.get(1, 0, 0));
            merged.set(0, 0, 0, 15);
            assertEquals(0x27, fixture[0]);
        }
    }

    @Test
    void UniformVanillaLightIsPreservedWhereFixtureDataIsDark() {
        DataLayer vanilla = new DataLayer(12);
        byte[] fixture = new byte[2048];
        fixture[0] = 15;
        DataLayer merged = LightFieldNetwork.merge(vanilla, fixture);
        assertEquals(15, merged.get(0, 0, 0));
        assertEquals(12, merged.get(1, 0, 0));
        assertEquals(12, merged.get(15, 15, 15));
        assertEquals(12, vanilla.get(0, 0, 0));
        assertTrue(vanilla.isDefinitelyHomogenous());
    }
}
