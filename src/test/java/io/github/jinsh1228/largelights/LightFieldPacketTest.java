package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

class LightFieldPacketTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void illuminationAndEmptySectionsRoundTripWithNegativeCoordinates() {
        for (int size : new int[]{0, 2048}) {
            byte[] levels = new byte[size];
            for (int i = 0; i < size; i++) {
                levels[i] = (byte) i; // Every pair of 0..15 light levels, including signed bytes.
            }
            var original = new LightFieldPacket(DIMENSION, SectionPos.asLong(-13, -4, 27), levels);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                LightFieldPacket.CODEC.encode(buffer, original);
                var decoded = LightFieldPacket.CODEC.decode(buffer);
                assertEquals(original.dimension(), decoded.dimension());
                assertEquals(original.section(), decoded.section());
                assertArrayEquals(levels, decoded.values());
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void theWireDecoderRejectsAnOversizedSectionBeforeAllocation() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(DIMENSION);
            buffer.writeLong(0);
            buffer.writeVarInt(2049);
            assertThrows(DecoderException.class, () -> LightFieldPacket.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void chunkActivationAndSectionRequestsHaveDistinctCoordinates() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var hint = new LightFieldPacket.Hint(DIMENSION, ChunkPos.asLong(-13, 27), false);
            var request = new LightFieldPacket.Request(SectionPos.asLong(-13, -4, 27));
            LightFieldPacket.Hint.CODEC.encode(buffer, hint);
            LightFieldPacket.Request.CODEC.encode(buffer, request);
            assertEquals(hint, LightFieldPacket.Hint.CODEC.decode(buffer));
            assertEquals(request, LightFieldPacket.Request.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
