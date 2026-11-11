package io.github.jinsh1228.largelights.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import org.haruyq.createmediasystems.SableCompat;
import org.haruyq.createmediasystems.client.IrisCompat;
import org.haruyq.createmediasystems.lighting.LightingIllumination;
import org.haruyq.createmediasystems.lighting.LightingRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** Capability checks and lifetimes shared by the production projection path. */
public final class GpuProjectionLifecycle {
    private static Boolean capable;
    private static boolean compatFailed, packActive;
    private static LightingRaycast spatial;
    private static Level spatialLevel;
    private static long spatialFrame = Long.MIN_VALUE;

    private GpuProjectionLifecycle() {
    }

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            if (capable == null) capable = GpuProjectionState.supported();
            boolean previousPack = packActive;
            packActive = IrisCompat.shaderPackInUse();
            if (packActive && !previousPack) clear();
            if (capable && !packActive && !compatFailed) GpuLightProjection.beginFrame();
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, (RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                    && Boolean.TRUE.equals(capable) && !packActive && !compatFailed) GpuLightProjection.render(event);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel().isClientSide()) clear();
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            if (event.getLevel().isClientSide()) {
                var pos = event.getChunk().getPos();
                Minecraft.getInstance().execute(() -> GpuLightGeometry.chunkChanged(pos.x, pos.z));
            }
        });
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> clear());
        modBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                (ResourceManagerReloadListener) resources -> clear()));
    }

    public static boolean allow(Level level, BlockPos source) {
        if (level == null || level != Minecraft.getInstance().level || !Boolean.TRUE.equals(capable) || packActive || compatFailed) return false;
        try {
            return SableCompat.containing(level, source) == null;
        } catch (RuntimeException | LinkageError failure) {
            failed(failure);
            return false;
        }
    }

    /** CMS owns the render poses; share one immutable spatial snapshot across this frame's projectors. */
    static boolean staticGeometry(Level level, AABB bounds) {
        if (compatFailed) return false;
        try {
            long frame = LightingIllumination.frame();
            if (spatial == null || spatialLevel != level || spatialFrame != frame) {
                spatial = LightingRaycast.rendered(level, BlockPos.ZERO);
                spatialLevel = level;
                spatialFrame = frame;
            }
            return !spatial.hasSubLevels(bounds);
        } catch (RuntimeException | LinkageError failure) {
            failed(failure);
            return false;
        }
    }

    private static void clear() {
        Runnable release = () -> {
            spatial = null;
            spatialLevel = null;
            GpuLightProjection.clear();
            GpuLightGeometry.clear();
        };
        if (RenderSystem.isOnRenderThread()) release.run(); else RenderSystem.recordRenderCall(release::run);
    }

    private static void failed(Throwable failure) {
        if (!compatFailed) {
            compatFailed = true;
            LogUtils.getLogger().warn("GPU lighting compatibility API unavailable; retaining CMS surface lighting", failure);
        }
    }
}
