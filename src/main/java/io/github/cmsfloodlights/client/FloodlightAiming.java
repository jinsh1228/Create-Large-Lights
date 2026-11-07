package io.github.cmsfloodlights.client;

import org.jetbrains.annotations.Nullable;

import io.github.cmsfloodlights.AimPacket;
import io.github.cmsfloodlights.FloodlightBlock;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Aiming a floodlight from afar: sneak-use it with an empty hand, then use wherever it should light (up to 256
 * blocks off, or the sky). Using the floodlight itself again stops.
 */
public final class FloodlightAiming {
    /** How long the aim waits for its target, in ticks. */
    private static final int TIMEOUT = 600;
    /** Held, use repeats every 4 ticks: the floodlight used again this soon is the same press. */
    private static final int REPEAT = 8;
    private static final double PICK_REACH = 256;

    @Nullable
    private static BlockPos aiming;
    private static long started;

    private FloodlightAiming() {
    }

    public static void start(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (pos.equals(aiming)) {
            if (mc.level.getGameTime() - started > REPEAT) {
                stop("cms_floodlights.message.aim_cancelled");
            }
            return;
        }
        aiming = pos.immutable();
        started = mc.level.getGameTime();
        mc.player.displayClientMessage(Component.translatable("cms_floodlights.message.aiming"), true);
    }

    private static void stop(String message) {
        aiming = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && message != null) {
            mc.player.displayClientMessage(Component.translatable(message), true);
        }
    }

    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (aiming == null || !event.isUseItem() || mc.player == null || mc.level == null) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        HitResult hit = mc.player.pick(PICK_REACH, 1, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                && block.getBlockPos().equals(aiming)) {
            if (mc.level.getGameTime() - started > REPEAT) {
                stop("cms_floodlights.message.aim_cancelled");
            }
            return;
        }
        PacketDistributor.sendToServer(new AimPacket(aiming, hit.getLocation()));
        aiming = null;
    }

    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (aiming == null) {
            return;
        }
        if (mc.level == null || mc.player == null) {
            aiming = null;
            return;
        }
        long elapsed = mc.level.getGameTime() - started;
        if (elapsed > TIMEOUT || elapsed < 0 || !(mc.level.getBlockEntity(aiming) instanceof FloodlightBlock.Entity)
                || mc.player.distanceToSqr(aiming.getCenter()) > AimPacket.PLAYER_REACH * AimPacket.PLAYER_REACH) {
            stop("cms_floodlights.message.aim_cancelled");
        } else if (elapsed % 20 == 0) {
            mc.player.displayClientMessage(Component.translatable("cms_floodlights.message.aiming"), true);
        }
    }
}
