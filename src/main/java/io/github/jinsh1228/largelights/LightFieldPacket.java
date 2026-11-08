package io.github.jinsh1228.largelights;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** CLL illumination only; vanilla sky/block light remains owned by Minecraft. */
public record LightFieldPacket(ResourceLocation dimension, long section, byte[] values) implements CustomPacketPayload {
    public static final Type<LightFieldPacket> TYPE = new Type<>(CreateLargeLights.id("light_field"));
    public static final StreamCodec<FriendlyByteBuf, LightFieldPacket> CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeResourceLocation(packet.dimension);
                buf.writeLong(packet.section);
                buf.writeByteArray(packet.values);
            }, buf -> new LightFieldPacket(buf.readResourceLocation(), buf.readLong(), buf.readByteArray(2048)));

    @Override
    public Type<LightFieldPacket> type() {
        return TYPE;
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, CODEC, (packet, context) -> context.enqueueWork(() -> {
            var level = context.player().level();
            if (level.dimension().location().equals(packet.dimension)
                    && (packet.values.length == 0 || packet.values.length == 2048)) {
                LightFieldNetwork.receive(level, packet.section, packet.values);
            }
        }));
        registrar.playToServer(Request.TYPE, Request.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                LightFieldNetwork.request(player, packet.section);
            }
        }));
        registrar.playToClient(Hint.TYPE, Hint.CODEC, (packet, context) -> context.enqueueWork(() -> {
            var level = context.player().level();
            if (level.dimension().location().equals(packet.dimension)) {
                LightFieldNetwork.hint(level, packet.chunk, packet.active);
            }
        }));
        LightFieldNetwork.register();
    }

    /** Absent chunks have no CLL field and therefore never request empty illumination sections. */
    public record Hint(ResourceLocation dimension, long chunk, boolean active) implements CustomPacketPayload {
        public static final Type<Hint> TYPE = new Type<>(CreateLargeLights.id("light_field_hint"));
        public static final StreamCodec<FriendlyByteBuf, Hint> CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeResourceLocation(packet.dimension);
                    buf.writeLong(packet.chunk);
                    buf.writeBoolean(packet.active);
                }, buf -> new Hint(buf.readResourceLocation(), buf.readLong(), buf.readBoolean()));

        @Override
        public Type<Hint> type() {
            return TYPE;
        }
    }

    /** Missing samples, including entities in empty sections, are requested once on the client thread. */
    public record Request(long section) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(CreateLargeLights.id("light_field_request"));
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeLong(packet.section), buf -> new Request(buf.readLong()));

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }
}
