package io.github.jinsh1228.largelights.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FloodlightPageTest {
    @Test
    void whitesFindTheirNearestTemperature() {
        // A bulb's warm white, and the floodlights' redstone white (nearest 5700K).
        assertEquals(0, FloodlightPage.nearestTemperature(0xFFA957));
        assertEquals(4, FloodlightPage.nearestTemperature(0xFFF1DC));
        assertEquals(5, FloodlightPage.nearestTemperature(0xFFFFFF));
    }
}
