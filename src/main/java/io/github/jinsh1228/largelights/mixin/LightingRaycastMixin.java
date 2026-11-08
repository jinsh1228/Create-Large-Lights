package io.github.jinsh1228.largelights.mixin;

import java.util.function.Predicate;

import org.haruyq.createmediasystems.lighting.LightingRaycast;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.client.LightModelSnapshots;
import io.github.jinsh1228.largelights.client.ModelLightOcclusion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Keep CMS's world/sub-level transforms, replacing only a floodlight's block-space visual occlusion. */
@Mixin(value = LightingRaycast.class, remap = false)
abstract class LightingRaycastMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private BlockPos ignored;
    @Unique private boolean createLargeLights$modelOcclusion;

    @Inject(method = "sectionChanged", at = @At("HEAD"))
    private static void createLargeLights$section(long key, CallbackInfo ci) {
        LightModelSnapshots.sectionChanged(key);
    }

    @Inject(method = "chunkChanged", at = @At("HEAD"))
    private static void createLargeLights$chunk(int x, int z, CallbackInfo ci) {
        LightModelSnapshots.chunkChanged(x, z);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void createLargeLights$fixture(CallbackInfo ci) {
        createLargeLights$modelOcclusion = level.isClientSide
                && level.getBlockState(ignored).getBlock() instanceof FloodlightBlock;
    }

    @Inject(method = "trace", at = @At("HEAD"), cancellable = true)
    private void createLargeLights$trace(Vec3 from, Vec3 to, Predicate<BlockPos> allowed,
                                         CallbackInfoReturnable<BlockHitResult> cir) {
        if (createLargeLights$modelOcclusion) {
            cir.setReturnValue(ModelLightOcclusion.trace(level, ignored, from, to, allowed));
        }
    }
}
