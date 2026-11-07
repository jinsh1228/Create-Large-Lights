package io.github.cmsfloodlights;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
        assertEquals(1, RealLight.disk(RealLight.grid(0.5, 5)).size());
        assertEquals(RealLight.MAX_GRID, RealLight.grid(1000, 3));
    }

    @Test
    void diskStartsInTheMiddleAndStaysInside() {
        List<double[]> disk = RealLight.disk(9);
        assertArrayEquals(new double[]{0, 0}, disk.getFirst());
        for (double[] sample : disk) {
            assertTrue(sample[0] * sample[0] + sample[1] * sample[1] <= 1.0001);
        }
        // Each sample once: the middle isn't added twice.
        assertEquals(disk.size(), disk.stream().map(s -> s[0] + "," + s[1]).distinct().count());
        assertEquals(1, RealLight.disk(1).size());
    }
}
