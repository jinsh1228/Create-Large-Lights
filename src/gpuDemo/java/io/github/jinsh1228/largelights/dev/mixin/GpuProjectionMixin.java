package io.github.jinsh1228.largelights.dev.mixin;

import org.haruyq.createmediasystems.lighting.LightingIllumination;
import org.haruyq.createmediasystems.lighting.LightingRaycast;
import org.haruyq.createmediasystems.lighting.StageLightBlock;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.dev.GpuProjectionDemo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

/** Trial only: retain the original lens and air beam, replacing the surface pass for the test fixture. */
@Mixin(value = LightingIllumination.class, remap = false)
abstract class GpuProjectionMixin {
    @Inject(method = "render(Lorg/haruyq/createmediasystems/lighting/StageLightBlock$Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;DFFILorg/haruyq/createmediasystems/lighting/LightingRaycast$Hit;Lorg/haruyq/createmediasystems/lighting/LightingRaycast;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private static void trialSurface(StageLightBlock.Entity lamp, Vec3 origin, Vec3 direction, double halfAngle,
                                     float range, float strength, int color, LightingRaycast.Hit hit,
                                     LightingRaycast rays, Matrix4f matrix, MultiBufferSource buffers, int radius,
                                     CallbackInfo ci) {
        if (GpuProjectionDemo.gpu() && lamp instanceof FloodlightBlock.Entity fixture
                && GpuProjectionDemo.isLamp(fixture.getBlockPos())) {
            ci.cancel();
        }
    }
}
