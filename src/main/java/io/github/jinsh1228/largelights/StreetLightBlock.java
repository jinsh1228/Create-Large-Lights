package io.github.jinsh1228.largelights;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A street light: a lamp on an arm, set on a light pole, the arm out toward {@link #FACING}. It lights at dusk and
 * goes out at dawn by its own daylight sensor ({@link #AUTO}), or while redstone powers it, and lights the ground
 * below it as floodlights do (RealLight), in a wide cone. No renderer: its model shows it lit or not, so a street
 * of them costs nothing to draw.
 */
public class StreetLightBlock extends HorizontalDirectionalBlock implements EntityBlock, IWrenchable {
    private static final MapCodec<StreetLightBlock> CODEC = simpleCodec(StreetLightBlock::new);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** Whether its daylight sensor lights it; if not, only redstone does. */
    public static final BooleanProperty AUTO = BooleanProperty.create("auto");
    /** Daylight (sky light less the sky's darkening, as a daylight detector reads it) it lights below, and goes out above. */
    static final int DUSK = 8;
    static final int DAWN = 10;
    /** The cone it lights the ground in: wide, and short, as a street light's. */
    static final float SPREAD = 120;
    static final float RANGE = 32;
    static final int RADIUS = 12;
    private static final VoxelShape STEM = box(6, 0, 6, 10, 14, 10);
    private static final VoxelShape[] SHAPES = new VoxelShape[4];

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            // The arm, from the stem out to the block's edge toward facing.
            double x0 = facing.getStepX() < 0 ? 0 : facing.getStepX() > 0 ? 8 : 7;
            double x1 = facing.getStepX() < 0 ? 8 : facing.getStepX() > 0 ? 16 : 9;
            double z0 = facing.getStepZ() < 0 ? 0 : facing.getStepZ() > 0 ? 8 : 7;
            double z1 = facing.getStepZ() < 0 ? 8 : facing.getStepZ() > 0 ? 16 : 9;
            SHAPES[facing.get2DDataValue()] = Shapes.or(STEM, box(x0, 12, z0, x1, 14, z1));
        }
    }

    public StreetLightBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(LIT, false)
                .setValue(POWERED, false).setValue(AUTO, true));
    }

    @Override
    protected MapCodec<StreetLightBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT, POWERED, AUTO);
    }

    /** Its arm out the way the player faces, over the road they look at. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        boolean powered = context.getLevel().hasNeighborSignal(context.getClickedPos());
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection()).setValue(POWERED, powered)
                .setValue(LIT, powered);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()];
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos from,
                                   boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, from, moving);
        boolean powered = level.hasNeighborSignal(pos);
        if (!level.isClientSide && powered != state.getValue(POWERED)) {
            BlockState next = state.setValue(POWERED, powered);
            level.setBlock(pos, next.setValue(LIT, lit(level, pos, next)), Block.UPDATE_CLIENTS);
        }
    }

    /** Whether it should be lit now: powered, or dark enough by its sensor (with the dusk and dawn gap). */
    static boolean lit(Level level, BlockPos pos, BlockState state) {
        if (state.getValue(POWERED)) {
            return true;
        }
        if (!state.getValue(AUTO)) {
            return false;
        }
        int daylight = level.getBrightness(LightLayer.SKY, pos) - level.getSkyDarken();
        return state.getValue(LIT) ? daylight < DAWN : daylight <= DUSK;
    }

    /** The wrench switches the daylight sensor on and off (off, only redstone lights it). */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (!level.isClientSide) {
            BlockPos pos = context.getClickedPos();
            BlockState next = state.cycle(AUTO);
            level.setBlock(pos, next.setValue(LIT, lit(level, pos, next)), Block.UPDATE_CLIENTS);
            IWrenchable.playRotateSound(level, pos);
            Player player = context.getPlayer();
            if (player != null) {
                player.displayClientMessage(Component.translatable("create_large_lights.message.street_light."
                        + (next.getValue(AUTO) ? "auto" : "redstone")), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof Entity light) {
            RealLight.clear(level, light.placed);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != CreateLargeLights.STREET_LIGHT_ENTITY.get() ? null
                : (l, p, s, be) -> ((Entity) be).tick();
    }

    /** Where its lamp is, in its block: out along the arm, under the head. */
    static Vec3 lamp(BlockPos pos, Direction facing) {
        return Vec3.atCenterOf(pos).add(facing.getStepX() * 0.66, 0.1, facing.getStepZ() * 0.66);
    }

    public static class Entity extends BlockEntity {
        final RealLight.Placed placed = new RealLight.Placed();

        public Entity(BlockPos pos, BlockState state) {
            super(CreateLargeLights.STREET_LIGHT_ENTITY.get(), pos, state);
        }

        @Override
        public void onLoad() {
            super.onLoad();
            if (level != null && !level.isClientSide) {
                refresh();
            }
        }

        @Override
        public void setRemoved() {
            if (level != null) {
                LightField.remove(level, worldPosition);
            }
            super.setRemoved();
        }

        public int lightCount() {
            return placed.size();
        }

        /** Every RealLight.CHECK ticks: its sensor, then its illumination if anything changed. */
        void tick() {
            if (level != null && RealLight.due(level, worldPosition)) {
                refresh();
            }
        }

        private void refresh() {
            if (level == null || level.isClientSide) {
                return;
            }
            BlockState state = getBlockState();
            boolean lit = lit(level, worldPosition, state);
            if (lit != state.getValue(LIT)) {
                state = state.setValue(LIT, lit);
                level.setBlock(worldPosition, state, Block.UPDATE_CLIENTS);
            }
            float strength = lit && FloodlightConfig.REAL_LIGHT.get() && !RealLight.carried(level, worldPosition) ? 1 : 0;
            Direction facing = state.getValue(FACING);
            int signature = RealLight.signature(strength, 15, facing.get2DDataValue(), 0, SPREAD, RANGE);
            if (RealLight.update(level, worldPosition, placed, signature, () -> {
                Vec3 down = new Vec3(0, -1, 0);
                return new LightField.Beam(lamp(worldPosition, facing).add(down.scale(0.5)), down,
                        Math.tan(Math.toRadians(SPREAD) / 2), RANGE, strength, 15, RADIUS);
            })) {
                setChanged();
            }
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (placed.size() > 0) {
                tag.putLongArray("LightBlocks", placed.toArray());
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            placed.load(tag.getLongArray("LightBlocks"));
        }
    }
}
