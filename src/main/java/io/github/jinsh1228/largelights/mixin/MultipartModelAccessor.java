package io.github.jinsh1228.largelights.mixin;

import java.util.List;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.MultiPartBakedModel;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(MultiPartBakedModel.class)
public interface MultipartModelAccessor {
    @Accessor("selectors")
    List<Pair<Predicate<BlockState>, BakedModel>> createLargeLights$selectors();
}
