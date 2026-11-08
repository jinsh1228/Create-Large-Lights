package io.github.jinsh1228.largelights;

import org.haruyq.createmediasystems.SableCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Turns the floodlight at {@code light} to face {@code target}, a point in the world the player pointed at. */
public record AimPacket(BlockPos light, Vec3 target) implements CustomPacketPayload {
    public static final Type<AimPacket> TYPE = new Type<>(CreateLargeLights.id("aim"));
    public static final StreamCodec<FriendlyByteBuf, AimPacket> CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.light);
                buf.writeVec3(packet.target);
            },
            buf -> new AimPacket(buf.readBlockPos(), buf.readVec3()));
    /** How far a player may be from the floodlight they aim (they may step back to see where it should go). */
    public static final double PLAYER_REACH = 96;
    /** How far from the floodlight its target may be. */
    public static final double TARGET_REACH = 320;

    @Override
    public Type<AimPacket> type() {
        return TYPE;
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, AimPacket::handle);
    }

    private static void handle(AimPacket packet, IPayloadContext context) {
        Player player = context.player();
        if (player.isSpectator()) {
            return;
        }
        var level = player.level();
        if (!level.isLoaded(packet.light) || !(level.getBlockEntity(packet.light) instanceof FloodlightBlock.Entity light)) {
            return;
        }
        Vec3 center = SableCompat.toWorld(level, packet.light, Vec3.atCenterOf(packet.light));
        if (player.distanceToSqr(center) > PLAYER_REACH * PLAYER_REACH
                || center.distanceToSqr(packet.target) > TARGET_REACH * TARGET_REACH
                || !SableCompat.mayInteract(level, player, packet.light)
                || !Double.isFinite(packet.target.x + packet.target.y + packet.target.z)) {
            return;
        }
        // In the floodlight's own block space, so a light on a moving Sable structure aims as it sits there.
        Vec3 toward = SableCompat.toBlockSpace(level, packet.light, packet.target).subtract(Vec3.atCenterOf(packet.light));
        if (toward.lengthSqr() < 0.25) {
            return;
        }
        float[] aim = Aim.toward(toward, light.yaw);
        light.aim(aim[0], aim[1]);
        player.displayClientMessage(Component.translatable("create_large_lights.message.aimed"), true);
    }
}
