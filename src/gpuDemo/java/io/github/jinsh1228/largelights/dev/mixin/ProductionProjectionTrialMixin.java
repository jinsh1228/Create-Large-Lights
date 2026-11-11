package io.github.jinsh1228.largelights.dev.mixin;

import io.github.jinsh1228.largelights.client.GpuLightProjection;
import io.github.jinsh1228.largelights.dev.GpuProjectionDemo;
import io.github.jinsh1228.largelights.dev.GpuBatchValidation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the original CPU and static GPU trial paths independent of the production renderer. */
@Mixin(value = GpuLightProjection.class, remap = false)
abstract class ProductionProjectionTrialMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private static void begin(CallbackInfo ci) { GpuProjectionDemo.beginProduction(); GpuBatchValidation.before(); }

    @Inject(method = "render", at = @At("RETURN"))
    private static void end(RenderLevelStageEvent event, CallbackInfo ci) { GpuProjectionDemo.endProduction(); GpuBatchValidation.after(event); }

    @Inject(method = "queue", at = @At("HEAD"), cancellable = true)
    private static void trial(CallbackInfoReturnable<Boolean> cir) {
        if (!GpuProjectionDemo.production()) cir.setReturnValue(false);
    }
}
