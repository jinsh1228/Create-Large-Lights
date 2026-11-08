package io.github.jinsh1228.largelights.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.client.LightSurfaceGeometry;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Smaller patches prevent a partially hidden corner from suppressing a large slab or fence face. */
@Mixin(targets = "org.haruyq.createmediasystems.lighting.LightingIllumination$SurfaceScan", remap = false)
abstract class LightingSurfaceScanMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private BlockPos lamp;

    @Redirect(method = "read", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/resources/model/BakedModel;getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)Ljava/util/List;"))
    private List<BakedQuad> createLargeLights$patches(BakedModel model, BlockState state, Direction side,
                                                    RandomSource random, ModelData data, RenderType type,
                                                    BlockPos pos) {
        List<BakedQuad> quads = model.getQuads(state, side, random, data, type);
        return level.getBlockState(lamp).getBlock() instanceof FloodlightBlock && !state.isSolidRender(level, pos)
                ? LightSurfaceGeometry.subdivide(quads) : quads;
    }
}
