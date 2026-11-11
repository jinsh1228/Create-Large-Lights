package io.github.jinsh1228.largelights.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.jinsh1228.largelights.LightField;
import io.github.jinsh1228.largelights.LightFieldNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

/** Adds fixture light at queries, without feeding it back into vanilla propagation or saved light data. */
@Mixin(LightEngine.class)
abstract class LightEngineMixin {
    @Shadow @Final protected LightChunkGetter chunkSource;

    @Inject(method = "getLightValue(Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private void createLargeLights$light(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if ((Object) this instanceof BlockLightEngine && chunkSource.getLevel() instanceof Level level) {
            cir.setReturnValue(Math.max(cir.getReturnValue(), Math.min(15, LightField.sample(level, pos))));
        }
    }

    @Inject(method = "getDataLayerData", at = @At("RETURN"), cancellable = true)
    private void createLargeLights$section(SectionPos section, CallbackInfoReturnable<DataLayer> cir) {
        if ((Object) this instanceof BlockLightEngine && chunkSource.getLevel() instanceof Level level && level.isClientSide) {
            cir.setReturnValue(LightFieldNetwork.section(level, section.asLong(), cir.getReturnValue()));
        }
    }
}
