package io.github.jinsh1228.largelights;

import org.haruyq.createmediasystems.Reach;
import org.haruyq.createmediasystems.api.sublevel.SubLevels;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** A change from the floodlight page to a console's ConsoleSettings, or a scene recalled or saved. */
public record ConsolePacket(BlockPos console, int group, Action action, int index, float value)
        implements CustomPacketPayload {
    public enum Action {
        SPREAD, REAL_LIGHT, MAX_LEVEL, FADE, RECALL, SAVE, TIMERS_ON, TIMER_ON, TIMER_TIME, TIMER_SCENE
    }

    public static final Type<ConsolePacket> TYPE = new Type<>(CreateLargeLights.id("console"));
    public static final StreamCodec<FriendlyByteBuf, ConsolePacket> CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.console);
                buf.writeVarInt(packet.group);
                buf.writeEnum(packet.action);
                buf.writeVarInt(packet.index);
                buf.writeFloat(packet.value);
            },
            buf -> new ConsolePacket(buf.readBlockPos(), buf.readVarInt(), buf.readEnum(Action.class), buf.readVarInt(),
                    buf.readFloat()));

    @Override
    public Type<ConsolePacket> type() {
        return TYPE;
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, ConsolePacket::handle);
    }

    /** Whether the packet asks for something possible, before anything is looked up. */
    public boolean valid() {
        return group >= 0 && group < LightingSettings.GROUPS && index >= 0
                && index < Math.max(ConsoleSettings.SCENES, ConsoleSettings.TIMERS) && Float.isFinite(value);
    }

    private static void handle(ConsolePacket packet, IPayloadContext context) {
        Player player = context.player();
        if (player.isSpectator() || !packet.valid()) {
            return;
        }
        LightingConsoleBlock.Entity part = Reach.blockEntity(player, packet.console, Reach.HAND,
                LightingConsoleBlock.Entity.class);
        LightingConsoleBlock.Entity console = part == null ? null : part.controller();
        if (console == null || !SubLevels.mayInteract(player.level(), player, console.getBlockPos())) {
            return;
        }
        apply(console, packet);
        console.changed();
    }

    public static void apply(LightingConsoleBlock.Entity console, ConsolePacket packet) {
        switch (packet.action) {
            case SPREAD -> ConsoleSettings.setSpread(console, packet.group, packet.value);
            case REAL_LIGHT -> ConsoleSettings.setRealLight(console, packet.group, packet.value != 0);
            case MAX_LEVEL -> ConsoleSettings.setMaxLevel(console, packet.group, Math.round(packet.value));
            case FADE -> ConsoleSettings.setFade(console, packet.value);
            case RECALL -> {
                ConsoleSettings.setScene(console, packet.index);
                SceneFades.start(console, ConsoleSettings.scene(console, packet.index));
            }
            case SAVE -> ConsoleSettings.saveScene(console, packet.index);
            case TIMERS_ON -> ConsoleSettings.setTimersOn(console, packet.value != 0);
            case TIMER_ON -> ConsoleSettings.setTimer(console, packet.index, packet.value != 0, null, null);
            case TIMER_TIME -> ConsoleSettings.setTimer(console, packet.index, null, Math.round(packet.value), null);
            case TIMER_SCENE -> ConsoleSettings.setTimer(console, packet.index, null, null, Math.round(packet.value));
        }
    }
}
