package io.github.jinsh1228.largelights.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.jinsh1228.largelights.Aim;

class FloodlightPageTest {
    @Test
    void whitesFindTheirNearestTemperature() {
        // A bulb's warm white, and the floodlights' redstone white (nearest 5700K).
        assertEquals(0, FloodlightPage.nearestTemperature(0xFFA957));
        assertEquals(4, FloodlightPage.nearestTemperature(0xFFF1DC));
        assertEquals(5, FloodlightPage.nearestTemperature(0xFFFFFF));
    }

    @Test
    void aimPadReachesBothTiltLimitsWithNeutralAtItsCentre() {
        assertEquals(-85, FloodlightPage.aimTilt(0.15), 1e-4);
        assertEquals(Aim.NEUTRAL_TILT, FloodlightPage.aimTilt(0.625), 1e-4);
        assertEquals(85, FloodlightPage.aimTilt(1.1), 1e-4);
        assertEquals(0.15, FloodlightPage.aimZ(-85), 1e-4);
        assertEquals(0.625, FloodlightPage.aimZ(Aim.NEUTRAL_TILT), 1e-4);
        assertEquals(1.1, FloodlightPage.aimZ(85), 1e-4);
    }

    @Test
    void draggingToTheDisplayedTiltPreservesItsValueAcrossTheWholeRange() {
        for (float tilt : new float[]{-85, -70, -50, 0, Aim.NEUTRAL_TILT, 60, 85}) {
            assertEquals(tilt, FloodlightPage.aimTilt(FloodlightPage.aimZ(tilt)), 1e-4);
        }
        assertEquals(-85, FloodlightPage.aimTilt(-1), 1e-4);
        assertEquals(85, FloodlightPage.aimTilt(2), 1e-4);
    }
}
