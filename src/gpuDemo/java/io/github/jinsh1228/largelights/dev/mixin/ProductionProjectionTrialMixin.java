package io.github.jinsh1228.largelights.dev.mixin;

import io.github.jinsh1228.largelights.client.GpuLightProjection;
import io.github.jinsh1228.largelights.dev.GpuProjectionDemo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the original CPU and static GPU trial paths independent of the production renderer. */
@Mixin(value = GpuLightProjection.class, remap = false)
abstract class ProductionProjectionTrialMixin {
    @Inject(method = "queue", at = @At("HEAD"), cancellable = true)
    private static void trial(CallbackInfoReturnable<Boolean> cir) {
        if (!GpuProjectionDemo.production()) cir.setReturnValue(false);
    }
}
