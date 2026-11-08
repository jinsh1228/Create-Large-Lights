package io.github.jinsh1228.largelights.mixin;

import java.util.List;

import io.github.jinsh1228.largelights.FloodlightBlock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Register off-screen fixtures even when their section is beyond the terrain's vertical compile distance. */
@Mixin(value = FloodlightBlock.Entity.class, remap = false)
abstract class FloodlightVisibilityMixin {
    @Inject(method = "onLoad", at = @At("RETURN"))
    private void createLargeLights$loaded(CallbackInfo ci) {
        var light = (FloodlightBlock.Entity) (Object) this;
        var level = light.getLevel();
        if (level != null && level.isClientSide && level == Minecraft.getInstance().level) {
            Minecraft.getInstance().levelRenderer.updateGlobalBlockEntities(List.of(), List.of(light));
        }
    }

    @Inject(method = "setRemoved", at = @At("RETURN"))
    private void createLargeLights$removed(CallbackInfo ci) {
        var light = (FloodlightBlock.Entity) (Object) this;
        var level = light.getLevel();
        if (level != null && level.isClientSide && level == Minecraft.getInstance().level) {
            Minecraft.getInstance().levelRenderer.updateGlobalBlockEntities(List.of(light), List.of());
        }
    }
}
