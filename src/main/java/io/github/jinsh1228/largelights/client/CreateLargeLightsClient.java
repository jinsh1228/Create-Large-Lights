package io.github.jinsh1228.largelights.client;

import io.github.jinsh1228.largelights.CreateLargeLights;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

@Mod(value = CreateLargeLights.MOD_ID, dist = Dist.CLIENT)
public class CreateLargeLightsClient {
    public CreateLargeLightsClient(IEventBus modBus) {
        LightFieldUpdates.register();
        GpuProjectionLifecycle.register(modBus);
        modBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                (ResourceManagerReloadListener) resources -> {
                    ModelLightOcclusion.clear();
                    LightSurfaceGeometry.clear();
                }));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(CreateLargeLights.FLOODLIGHT_ENTITY.get(), FloodlightRenderer::new));
        NeoForge.EVENT_BUS.addListener(FloodlightAiming::onInteract);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ModelLightOcclusion.clear());
        NeoForge.EVENT_BUS.addListener(FloodlightAiming::tick);
        FloodlightPage.register();
        NeoForge.EVENT_BUS.addListener(LightMeter::tick);
        NeoForge.EVENT_BUS.addListener(LightMeter::render);
    }
}
