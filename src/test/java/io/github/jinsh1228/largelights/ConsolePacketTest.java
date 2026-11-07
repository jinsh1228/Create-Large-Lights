package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class ConsolePacketTest {
    private static ConsolePacket packet(int group, int index, float value) {
        return new ConsolePacket(BlockPos.ZERO, group, ConsolePacket.Action.RECALL, index, value);
    }

    @Test
    void malformedPacketsAreRejectedBeforeAnythingIsLookedUp() {
        assertTrue(packet(0, 0, 0).valid());
        assertTrue(packet(5, 3, 1).valid());
        assertFalse(packet(-1, 0, 0).valid());
        assertFalse(packet(6, 0, 0).valid());
        assertFalse(packet(0, -1, 0).valid());
        assertFalse(packet(0, ConsoleSettings.SCENES, 0).valid());
        assertFalse(packet(0, 0, Float.NaN).valid());
        assertFalse(packet(0, 0, Float.POSITIVE_INFINITY).valid());
    }

    @Test
    void defaultScenesStepDownToDarkWithTheMasterUp() {
        float[] match = ConsoleSettings.defaultScene(0);
        float[] off = ConsoleSettings.defaultScene(3);
        for (int i = 0; i < ConsoleSettings.SCENE_SIZE - 1; i++) {
            assertTrue(match[i] == 1 && off[i] == 0);
        }
        assertTrue(match[ConsoleSettings.SCENE_SIZE - 1] == 1 && off[ConsoleSettings.SCENE_SIZE - 1] == 1);
    }
}
