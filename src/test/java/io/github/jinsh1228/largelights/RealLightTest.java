package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.Test;

class RealLightTest {
    @Test
    void lightFollowsStrength() {
        assertEquals(0, RealLight.lightLevel(0));
        // Redstone signal n of 15 lights at level n.
        for (int signal = 1; signal <= 15; signal++) {
            assertEquals(signal, RealLight.lightLevel(signal / 15f));
        }
        // Anything lit is at least level 1.
        assertEquals(1, RealLight.lightLevel(0.01f));
        assertEquals(15, RealLight.lightLevel(2));
    }

    @Test
    void samplesAreAboutASpacingApartWhereTheBeamFalls() {
        // A beam 40 blocks across, a light every 5 blocks: 9 samples across.
        assertEquals(9, RealLight.grid(20, 5));
        // A beam narrower than the spacing lights its middle only.
        assertEquals(2, RealLight.disk(RealLight.grid(0.5, 5)).length);
        assertEquals(RealLight.MAX_GRID, RealLight.grid(1000, 3));
    }

    @Test
    void diskStartsInTheMiddleAndStaysInside() {
        double[] disk = RealLight.disk(9);
        assertEquals(0, disk[0]);
        assertEquals(0, disk[1]);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < disk.length; i += 2) {
            assertTrue(disk[i] * disk[i] + disk[i + 1] * disk[i + 1] <= 1.0001);
            // Each sample once: the middle isn't added twice.
            assertTrue(seen.add(disk[i] + "," + disk[i + 1]));
        }
        assertEquals(2, RealLight.disk(1).length);
        // Made once, then shared.
        assertTrue(RealLight.disk(9) == disk);
    }
}
