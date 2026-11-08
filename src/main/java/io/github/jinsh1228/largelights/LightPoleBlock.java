package io.github.jinsh1228.largelights;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A street light's pole: a slim steel post, stacked as tall as the light should stand. */
public class LightPoleBlock extends Block {
    private static final VoxelShape SHAPE = box(6, 0, 6, 10, 16, 10);

    public LightPoleBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
