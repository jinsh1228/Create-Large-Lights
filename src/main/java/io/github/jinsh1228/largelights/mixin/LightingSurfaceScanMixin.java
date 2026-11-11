package io.github.jinsh1228.largelights.mixin;

import java.util.List;
import java.util.function.Consumer;

import org.haruyq.createmediasystems.client.SurfaceLight;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.client.LightModelSnapshots;
import io.github.jinsh1228.largelights.client.LightSurfaceGeometry;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/** Smaller patches prevent a partially hidden corner from suppressing a large slab or fence face. */
@Mixin(targets = "org.haruyq.createmediasystems.lighting.LightingIllumination$SurfaceScan", remap = false)
abstract class LightingSurfaceScanMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private BlockPos lamp;

    @Redirect(method = "read", at = @At(value = "INVOKE", target =
            "Lorg/haruyq/createmediasystems/client/SurfaceLight;forEachQuad(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;Ljava/util/function/Consumer;)V"))
    private void createLargeLights$patches(BlockGetter world, BlockPos pos, BlockState state, RandomSource random,
                                            Consumer<BakedQuad> consume) {
        if (!(level.getBlockState(lamp).getBlock() instanceof FloodlightBlock)) {
            SurfaceLight.forEachQuad(world, pos, state, random, consume);
            return;
        }
        if (state.getRenderShape() != RenderShape.MODEL) return;
        for (int side = 0; side <= 6; side++) {
            Direction direction = side == 6 ? null : Direction.from3DDataValue(side);
            if (direction != null && !Block.shouldRenderFace(state, level, pos, direction, pos.relative(direction))) continue;
            List<BakedQuad> quads = LightModelSnapshots.quads(level, pos, state, direction);
            if (!state.isSolidRender(level, pos)) quads = LightSurfaceGeometry.subdivide(quads);
            quads.forEach(consume);
        }
    }
}
