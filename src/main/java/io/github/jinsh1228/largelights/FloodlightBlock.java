package io.github.jinsh1228.largelights;

import java.util.List;

import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingSettings;
import org.haruyq.createmediasystems.lighting.LightingText;
import org.haruyq.createmediasystems.lighting.StageLightBlock;
import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import io.github.jinsh1228.largelights.client.FloodlightAiming;

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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A floodlight, mounted on the face it was placed against ({@link #FACING} points away from it). It works one of two
 * ways (Entity.beam). Unlinked, redstone runs it: lit as brightly as the signal, its beam width set with a wrench.
 * Linked to a lighting console, the console runs all of it: its group's brightness (with the master), beam width
 * (spread), reach (range) and colour, redstone and the wrench then doing nothing. Either way, where it points is
 * its own (sneak-use with an empty hand, FloodlightAiming), and it lights the world where its beam falls (RealLight).
 */
public class FloodlightBlock extends DirectionalBlock implements EntityBlock, IWrenchable {
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
        // Called with an item in hand too, before the item's own use: the lighting link and wrench need theirs.
        if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()
                || !(level.getBlockEntity(pos) instanceof Entity)) {
            return InteractionResult.PASS;
        }
        // Aimed by where the player next points (FloodlightAiming), sent back as an AimPacket.
        if (level.isClientSide) {
            FloodlightAiming.start(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** The wrench sets an unlinked floodlight's beam width; a linked one's is the console's. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (!(level.getBlockEntity(context.getClickedPos()) instanceof Entity light)) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (!level.isClientSide) {
            if (light.console != null) {
                if (player != null) {
                    player.displayClientMessage(Component.translatable("create_large_lights.message.linked_wrench"), true);
                }
            } else {
                light.spread = Math.floorMod(light.spreadSetting() + 1, kind.spreads());
                light.changed();
                IWrenchable.playRotateSound(level, context.getClickedPos());
                if (player != null) {
                    player.displayClientMessage(Component.translatable("create_large_lights.message.spread",
                            LightingText.number(Math.round(light.spreadDegrees()) + "°")), true);
                }
            }
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
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof Entity light) {
            RealLight.clear(level, light.placed);
            if (!movedByPiston) {
                light.unlink();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != CreateLargeLights.FLOODLIGHT_ENTITY.get() ? null
                : (l, p, s, be) -> RealLight.tick(l, p, (Entity) be);
    }

    /** A Create: Media Systems fixture, so the lighting console and its link drive it, with an aim of its own. */
    public static class Entity extends StageLightBlock.Entity {
        public float yaw;
        public float pitch = 90;
        /** Unlinked: which of its kind's beam widths (FloodlightKind.spread); -1 for the kind's default. */
        public int spread = -1;
        /** Ownership of old light blocks, retained until migration removes them. */
        final RealLight.Placed placed = new RealLight.Placed();
        /** The renderer's state for this floodlight (client only): its traced beam and its eased aim. */
        public Object renderTrace;
        public Object renderMotion;

        public Entity(BlockPos pos, BlockState state) {
            super(CreateLargeLights.FLOODLIGHT_ENTITY.get(), pos, state);
        }

        @Override
        public void onLoad() {
            super.onLoad();
            if (level != null && !level.isClientSide) {
                // NeoForge calls this after insertion, even when this chunk is outside simulation distance.
                RealLight.refresh(level, worldPosition, this);
            }
        }

        @Override
        public void setRemoved() {
            if (level != null) {
                LightField.remove(level, worldPosition);
            }
            super.setRemoved();
        }

        public FloodlightKind kind() {
            return getBlockState().getBlock() instanceof FloodlightBlock block ? block.kind : FloodlightKind.FLOODLIGHT;
        }

        int spreadSetting() {
            return spread < 0 ? kind().defaultSpread : spread;
        }

        /** The unlinked beam width, as the wrench last set it. */
        public float spreadDegrees() {
            return kind().spread(spreadSetting());
        }

        /**
         * How it shines now: from its console's group if linked, from redstone if not. Strength is 0 to 1 and
         * leaves out the console's music and video, and yaw and pitch its group's motion (the renderer adds those):
         * they are its own aim turned by the group's pan and tilt.
         */
        public record Beam(@Nullable LightingConsoleBlock.Entity console, @Nullable LightingSettings.Group group,
                           float strength, float spread, float range, float yaw, float pitch, int lightLimit) {
        }

        public Beam beam() {
            return beam(controller());
        }

        /** As {@link #beam()}, run by {@code owner} (this floodlight's controller, or null). */
        public Beam beam(@Nullable LightingConsoleBlock.Entity owner) {
            if (owner != null) {
                LightingSettings.Group settings = owner.settings.groups[group];
                float strength = owner.formed && owner.settings.enabled ? owner.settings.master * settings.brightness : 0;
                float[] aim = Aim.offset(yaw, pitch, settings.pan, settings.tilt);
                // Beam width and real light are the floodlight page's (ConsoleSettings): a stage light's spread stops at 70°.
                ConsoleSettings.View view = ConsoleSettings.view(owner);
                return new Beam(owner, settings, Mth.clamp(strength, 0, 1),
                        Mth.clamp(view.spread()[group], 1, kind().maxSpread()),
                        Mth.clamp(settings.range, 2, LightingSettings.MAX_RANGE), aim[0], aim[1], view.lightLimit(group));
            }
            // Linked to a console that isn't here (unloaded): dark till it is.
            float strength = console != null ? 0 : getBlockState().getValue(POWER) / 15f;
            return new Beam(null, null, strength, spreadDegrees(), kind().range, yaw, pitch, 15);
        }

        public void aim(float yaw, float pitch) {
            this.yaw = Mth.wrapDegrees(yaw);
            this.pitch = Mth.clamp(pitch, Aim.MIN_PITCH, Aim.MAX_PITCH);
            if (spread < 0) {
                spread = kind().defaultSpread;
            }
            changed();
        }

        /**
         * Its controller as the server looks at it often: the console it names, if that console still runs it.
         * Without CMS's link repair (controller()), which looks over all the console's fixtures; the console runs
         * that itself every second, and RealLight asks controller() now and then for consoles that moved.
         */
        @Nullable
        LightingConsoleBlock.Entity quickController() {
            if (console == null || level == null || !level.isLoaded(console)
                    || !(level.getBlockEntity(console) instanceof LightingConsoleBlock.Entity owner)) {
                return null;
            }
            return owner.controller() == owner && owner.fixtures.contains(worldPosition) ? owner : null;
        }

        /** Legacy light blocks awaiting migration, as BlockPos.asLong. */
        public long[] lightBlocks() {
            return placed.toArray();
        }

        public int lightCount() {
            return placed.size();
        }

        /** Floodlights share the console's floodlight page (client.FloodlightPage), so a group holds only them. */
        @Override
        public String family() {
            return CreateLargeLights.MOD_ID;
        }

        @Override
        public Component fixtureName() {
            return getBlockState().getBlock().getName();
        }

        @Override
        public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
            super.addToGoggleTooltip(tooltip, sneaking);
            Beam beam = beam();
            tooltip.add(Component.translatable(beam.console() != null ? "create_large_lights.tooltip.console"
                            : "create_large_lights.tooltip.redstone",
                    LightingText.number(Math.round(beam.strength() * 100) + "%")).withColor(LightingText.TEXT));
            tooltip.add(Component.translatable("create_large_lights.tooltip.beam",
                    LightingText.number(Math.round(beam.spread()) + "°"),
                    LightingText.number(Math.round(beam.range()) + " m")).withColor(LightingText.TEXT));
            tooltip.add(Component.translatable("create_large_lights.tooltip.aim",
                    // As a compass reads: north 0°, east 90°. With the console's pan and tilt, not its motion.
                    LightingText.number(Math.floorMod(Math.round(beam.yaw() + 180), 360) + "°"),
                    LightingText.number(Math.round(-beam.pitch()) + "°")).withColor(LightingText.TEXT));
            tooltip.add(Component.translatable("create_large_lights.tooltip.real_light",
                    LightingText.number(RealLight.lightLevel(beam.strength() * beam.lightLimit() / 15f))).withColor(LightingText.TEXT));
            return true;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putFloat("AimYaw", yaw);
            tag.putFloat("AimPitch", pitch);
            tag.putInt("Spread", spread);
            if (placed.size() > 0) {
                tag.putLongArray("LightBlocks", placed.toArray());
            }
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            yaw = Mth.wrapDegrees(tag.getFloat("AimYaw"));
            pitch = tag.contains("AimPitch") ? Mth.clamp(tag.getFloat("AimPitch"), Aim.MIN_PITCH, Aim.MAX_PITCH) : 90;
            spread = tag.contains("Spread") ? tag.getInt("Spread") : -1;
            placed.load(tag.getLongArray("LightBlocks"));
        }
    }
}
