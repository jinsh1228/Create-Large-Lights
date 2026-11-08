package io.github.jinsh1228.largelights.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
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
    private static MethodHandle shaderPack;
    private static MethodHandle containing, intersecting, box;
    private static boolean compatFailed;
    private static boolean packActive;

    private GpuProjectionLifecycle() {
    }

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            if (capable == null) {
                compatibility();
                capable = GpuProjectionState.supported();
            }
            boolean previousPack = packActive;
            packActive = shaderPackActive();
            if (packActive && !previousPack) clear();
            if (capable && !packActive && !compatFailed) GpuLightProjection.beginFrame();
        });
        // Match CMS's original surface pass, before its LOWEST-priority haze flush and translucent terrain.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, (RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                    && Boolean.TRUE.equals(capable) && !packActive && !compatFailed) {
                GpuLightProjection.render(event);
            }
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
        if (level == null || level != Minecraft.getInstance().level || !Boolean.TRUE.equals(capable) || packActive || compatFailed) {
            return false;
        }
        try {
            return containing.invoke(level, source) == null;
        } catch (Throwable failure) {
            failed(failure);
            return false;
        }
    }

    /** A static source must also retain CMS's moving receivers and moving occluders. */
    static boolean staticGeometry(Level level, AABB bounds) {
        if (compatFailed) return false;
        try {
            Object area = box.invoke(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
            return !((Iterable<?>) intersecting.invoke(level, area)).iterator().hasNext();
        } catch (Throwable failure) {
            failed(failure);
            return false;
        }
    }

    private static void clear() {
        Runnable release = () -> {
            GpuLightProjection.clear();
            GpuLightGeometry.clear();
        };
        if (RenderSystem.isOnRenderThread()) release.run(); else RenderSystem.recordRenderCall(release::run);
    }

    private static void compatibility() {
        try {
            Class<?> companion = Class.forName("dev.ryanhcode.sable.companion.SableCompanion");
            Object instance = companion.getField("INSTANCE").get(null);
            Class<?> bounds = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3d");
            var lookup = MethodHandles.publicLookup();
            box = lookup.findConstructor(bounds, MethodType.methodType(void.class,
                    double.class, double.class, double.class, double.class, double.class, double.class));
            for (Method method : companion.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 2 && parameters[0].isAssignableFrom(Level.class)) {
                    if (method.getName().equals("getContaining") && parameters[1].isAssignableFrom(BlockPos.class)) {
                        containing = lookup.unreflect(method).bindTo(instance);
                    } else if (method.getName().equals("getAllIntersecting") && parameters[1].isAssignableFrom(bounds)) {
                        intersecting = lookup.unreflect(method).bindTo(instance);
                    }
                }
            }
            if (containing == null || intersecting == null) throw new NoSuchMethodException("Sable companion spatial API");
            if (ModList.get().isLoaded("iris")) {
                Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
                shaderPack = lookup.findVirtual(api.getClass(), "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(api);
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            failed(failure);
        }
    }

    private static boolean shaderPackActive() {
        if (shaderPack == null) return false;
        try {
            return (boolean) shaderPack.invoke();
        } catch (Throwable failure) {
            failed(failure);
            return true;
        }
    }

    private static void failed(Throwable failure) {
        if (!compatFailed) {
            compatFailed = true;
            LogUtils.getLogger().warn("GPU lighting compatibility API unavailable; retaining CMS surface lighting", failure);
        }
    }
}
