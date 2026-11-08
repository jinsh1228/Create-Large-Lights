package io.github.jinsh1228.largelights.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.jinsh1228.largelights.LightField;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Geometry changes can alter fixture shadows even when vanilla opacity stays the same. */
@Mixin(Level.class)
abstract class LightFieldInvalidationMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void createLargeLights$changed(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            LightField.invalidate((Level) (Object) this, pos);
        }
    }
}
