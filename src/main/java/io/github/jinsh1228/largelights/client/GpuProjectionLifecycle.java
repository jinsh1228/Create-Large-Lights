package io.github.jinsh1228.largelights.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import org.haruyq.createmediasystems.api.sublevel.SubLevels;
import org.haruyq.createmediasystems.api.client.Shaders;
import org.haruyq.createmediasystems.api.client.WorldChanges;
import org.haruyq.createmediasystems.api.lighting.client.LightRays;

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
import net.neoforged.neoforge.event.level.LevelEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** Capability checks and lifetimes shared by the production projection path. */
public final class GpuProjectionLifecycle {
    private static Boolean capable;
    private static boolean compatFailed, packActive;
    private static LightRays spatial;
    private static Level spatialLevel;
    private static long spatialFrame = Long.MIN_VALUE;

    private GpuProjectionLifecycle() {
    }

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            if (capable == null) capable = GpuProjectionState.supported();
            boolean previousPack = packActive;
            packActive = Shaders.packInUse();
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
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> clear());
        modBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                (ResourceManagerReloadListener) resources -> clear()));
    }

    public static boolean allow(Level level, BlockPos source) {
        if (level == null || level != Minecraft.getInstance().level || !Boolean.TRUE.equals(capable) || packActive || compatFailed) return false;
        try {
            return !SubLevels.onSubLevel(level, source);
        } catch (RuntimeException | LinkageError failure) {
            failed(failure);
            return false;
        }
    }

    /** CMS owns the render poses; share one immutable spatial snapshot across this frame's projectors. */
    static boolean staticGeometry(Level level, AABB bounds) {
        if (compatFailed) return false;
        try {
            long frame = WorldChanges.frame();
            if (spatial == null || spatialLevel != level || spatialFrame != frame) {
                spatial = LightRays.rendered(level, BlockPos.ZERO);
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
