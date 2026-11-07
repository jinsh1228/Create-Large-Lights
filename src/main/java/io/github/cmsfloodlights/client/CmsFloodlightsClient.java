package io.github.cmsfloodlights.client;

import io.github.cmsfloodlights.CmsFloodlights;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = CmsFloodlights.MOD_ID, dist = Dist.CLIENT)
public class CmsFloodlightsClient {
    public CmsFloodlightsClient(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(CmsFloodlights.FLOODLIGHT_ENTITY.get(), FloodlightRenderer::new));
        NeoForge.EVENT_BUS.addListener(FloodlightAiming::onInteract);
        NeoForge.EVENT_BUS.addListener(FloodlightAiming::tick);
    }
}
