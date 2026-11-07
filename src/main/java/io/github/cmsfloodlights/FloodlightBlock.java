package io.github.cmsfloodlights;

import java.util.List;

import org.haruyq.createmediasystems.lighting.LightingText;
import org.haruyq.createmediasystems.lighting.StageLightBlock;
import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import io.github.cmsfloodlights.client.FloodlightAiming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A floodlight, mounted on the face it was placed against ({@link #FACING} points away from it). Linked to a
 * lighting console it is one of the console's fixtures; unlinked, redstone lights it, as brightly as the signal.
 */
public class FloodlightBlock extends DirectionalBlock implements EntityBlock {
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    private static final VoxelShape FLOODLIGHT_SHAPE = box(1, 1, 1, 15, 15, 15);
    private static final VoxelShape STADIUM_SHAPE = box(0, 0, 0, 16, 16, 16);

    public final FloodlightKind kind;
    private final MapCodec<FloodlightBlock> codec;

    public FloodlightBlock(Properties properties, FloodlightKind kind) {
        super(properties);
        this.kind = kind;
        codec = simpleCodec(p -> new FloodlightBlock(p, kind));
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.DOWN).setValue(POWER, 0));
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWER);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace())
                .setValue(POWER, context.getLevel().getBestNeighborSignal(context.getClickedPos()));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.getBlockEntity(pos) instanceof Entity light) {
            float[] aim = placedAim(state.getValue(FACING), placer == null ? 0 : placer.getYRot());
            light.aim(aim[0], aim[1]);
        }
    }

    /**
     * Pointing out from what it hangs on: down from a ceiling, out and down from a wall, and from the floor up and
     * away from whoever placed it.
     */
    static float[] placedAim(Direction facing, float placerYaw) {
        return switch (facing) {
            case DOWN -> new float[]{Mth.wrapDegrees(placerYaw), 90};
            case UP -> new float[]{Mth.wrapDegrees(placerYaw), -30};
            default -> new float[]{facing.toYRot(), 35};
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return kind == FloodlightKind.STADIUM_LIGHT ? STADIUM_SHAPE : FLOODLIGHT_SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        // Called with an item in hand too, before the item's own use: the lighting link must reach its own.
        if (!player.getMainHandItem().isEmpty() || !(level.getBlockEntity(pos) instanceof Entity light)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            // Aimed by where the player next points (FloodlightAiming), sent back as an AimPacket.
            if (level.isClientSide) {
                FloodlightAiming.start(pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            light.spread = Math.floorMod(light.spread + 1, kind.spreads());
            light.changed();
            player.displayClientMessage(Component.translatable("cms_floodlights.message.spread",
                    LightingText.number(Math.round(light.spreadDegrees()) + "°")), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos from,
                                   boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, from, moving);
        int power = level.getBestNeighborSignal(pos);
        if (!level.isClientSide && power != state.getValue(POWER)) {
            level.setBlock(pos, state.setValue(POWER, power), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && !movedByPiston
                && level.getBlockEntity(pos) instanceof Entity light) {
            light.unlink();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    /** A Create: Media Systems fixture, so the lighting console and its link drive it, with an aim of its own. */
    public static class Entity extends StageLightBlock.Entity {
        public float yaw;
        public float pitch = 90;
        /** Which of its kind's beam widths (FloodlightKind.spread). */
        public int spread = -1;

        public Entity(BlockPos pos, BlockState state) {
            super(CmsFloodlights.FLOODLIGHT_ENTITY.get(), pos, state);
        }

        public FloodlightKind kind() {
            return getBlockState().getBlock() instanceof FloodlightBlock block ? block.kind : FloodlightKind.FLOODLIGHT;
        }

        public float spreadDegrees() {
            return kind().spread(spread < 0 ? kind().defaultSpread : spread);
        }

        public void aim(float yaw, float pitch) {
            this.yaw = Mth.wrapDegrees(yaw);
            this.pitch = Mth.clamp(pitch, Aim.MIN_PITCH, Aim.MAX_PITCH);
            if (spread < 0) {
                spread = kind().defaultSpread;
            }
            changed();
        }

        @Override
        public Component fixtureName() {
            return getBlockState().getBlock().getName();
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
            super.addToGoggleTooltip(tooltip, sneaking);
            tooltip.add(Component.translatable("cms_floodlights.tooltip.beam",
                    LightingText.number(Math.round(spreadDegrees()) + "°"),
                    // As a compass reads: north 0°, east 90°.
                    LightingText.number(Math.floorMod(Math.round(yaw + 180), 360) + "°"),
                    LightingText.number(Math.round(-pitch) + "°")).withColor(LightingText.TEXT));
            if (console == null) {
                int power = getBlockState().getValue(POWER);
                tooltip.add(Component.translatable("cms_floodlights.tooltip.redstone",
                        LightingText.number(Math.round(power * 100 / 15f) + "%")).withColor(LightingText.TEXT));
            }
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putFloat("AimYaw", yaw);
            tag.putFloat("AimPitch", pitch);
            tag.putInt("Spread", spread);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            yaw = Mth.wrapDegrees(tag.getFloat("AimYaw"));
            pitch = tag.contains("AimPitch") ? Mth.clamp(tag.getFloat("AimPitch"), Aim.MIN_PITCH, Aim.MAX_PITCH) : 90;
            spread = tag.contains("Spread") ? tag.getInt("Spread") : -1;
        }
    }
}
