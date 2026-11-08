package io.github.jinsh1228.largelights.dev;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.decoration.copycat.CopycatModel;

import io.github.jinsh1228.largelights.client.LightModelSnapshots;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Normal dev scene only: actual Create models, beyond the vanilla models in the GPU comparison. */
final class ModelDataDemo {
    private static final BlockPos[] WOOD = {new BlockPos(4, 170, 0), new BlockPos(4, 170, 4)};
    private static final BlockPos[] STONE = {new BlockPos(8, 170, 0), new BlockPos(8, 170, 4)};
    private static boolean done;
    private static int failures;

    private ModelDataDemo() {
    }

    static int tick(Minecraft mc, int ticks) {
        if (done) return 0;
        if (ticks == 60) mc.getSingleplayerServer().execute(() -> build(mc.getSingleplayerServer().overworld()));
        if (ticks < 160) return 0;
        for (int i = 0; i < WOOD.length; i++) {
            if (!(mc.level.getBlockEntity(WOOD[i]) instanceof CopycatBlockEntity wood)
                    || !(mc.level.getBlockEntity(STONE[i]) instanceof CopycatBlockEntity stone)
                    || !wood.getMaterial().is(Blocks.OAK_PLANKS) || !stone.getMaterial().is(Blocks.STONE)) {
                if (ticks < 300) return 0;
                check(false, "Create copycat materials reached the client before the deadline");
                return finish();
            }
        }
        try {
            for (int i = 0; i < WOOD.length; i++) verify(mc, WOOD[i], STONE[i]);
        } catch (RuntimeException error) {
            failures++;
            LogUtils.getLogger().error("MODEL_DATA CHECK FAIL: runtime verification threw", error);
        }
        return finish();
    }

    private static int finish() {
        done = true;
        LogUtils.getLogger().info("MODEL_DATA demo done: {} checks failed", failures);
        return failures;
    }

    private static void build(ServerLevel level) {
        BlockState[] states = {AllBlocks.COPYCAT_STEP.getDefaultState(), AllBlocks.COPYCAT_PANEL.getDefaultState()};
        for (int i = 0; i < states.length; i++) {
            place(level, WOOD[i], states[i], Blocks.OAK_PLANKS.defaultBlockState());
            place(level, STONE[i], states[i], Blocks.STONE.defaultBlockState());
        }
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state, BlockState material) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof CopycatBlockEntity copycat) {
            copycat.setConsumedItem(new ItemStack(material.getBlock()));
            copycat.setMaterial(material);
        }
    }

    private static void verify(Minecraft mc, BlockPos wood, BlockPos stone) {
        BlockState state = mc.level.getBlockState(wood);
        String model = mc.getBlockRenderer().getBlockModel(state).getClass().getSimpleName();
        List<List<BakedQuad>> before = quads(mc, wood);
        List<List<BakedQuad>> other = quads(mc, stone);
        check(state == mc.level.getBlockState(stone), model + " uses identical block states at both positions");
        check(sprites(before).contains("minecraft:block/oak_planks") && sprites(other).contains("minecraft:block/stone"),
                model + " resolves position-specific wood/stone sprites: " + sprites(before) + " / " + sprites(other));
        check(before.equals(quads(mc, wood)), model + " reuses its unchanged position snapshot");

        CopycatBlockEntity copycat = (CopycatBlockEntity) mc.level.getBlockEntity(wood);
        // Load only the material data, without redraw/sendBlockUpdated. This isolates requestModelDataUpdate:
        // native Create setMaterial normally sends both the ModelData refresh and a render-dirty notification.
        var data = copycat.saveCustomOnly(mc.level.registryAccess());
        data.put("Material", NbtUtils.writeBlockState(Blocks.BRICKS.defaultBlockState()));
        data.put("Item", new ItemStack(Blocks.BRICKS).save(mc.level.registryAccess()));
        copycat.loadCustomOnly(data, mc.level.registryAccess());
        check(copycat.getMaterial().is(Blocks.BRICKS) && state == mc.level.getBlockState(wood),
                model + " material changes without a block-state change");
        check(before.equals(quads(mc, wood))
                        && CopycatModel.getMaterial(mc.level.getModelDataManager().getAt(wood)).is(Blocks.OAK_PLANKS),
                model + " retains its previous snapshot before the explicit ModelData refresh");
        copycat.requestModelDataUpdate();
        List<List<BakedQuad>> after = quads(mc, wood);
        check(!before.equals(after) && sprites(after).contains("minecraft:block/bricks")
                        && !sprites(after).contains("minecraft:block/oak_planks"),
                model + " refresh discards the stale snapshot: " + sprites(after));
        check(sprites(quads(mc, stone)).contains("minecraft:block/stone"),
                model + " leaves the other position's material intact");
        check(sprites(before).contains("minecraft:block/oak_planks"), model + " keeps the old snapshot immutable");
    }

    private static List<List<BakedQuad>> quads(Minecraft mc, BlockPos pos) {
        List<List<BakedQuad>> result = new ArrayList<>(7);
        for (int side = 0; side <= 6; side++) {
            result.add(LightModelSnapshots.quads(mc.level, pos, mc.level.getBlockState(pos),
                    side == 6 ? null : Direction.from3DDataValue(side)));
        }
        return result;
    }

    private static Set<String> sprites(List<List<BakedQuad>> sides) {
        Set<String> names = new HashSet<>();
        for (var side : sides) for (var quad : side) names.add(quad.getSprite().contents().name().toString());
        return names;
    }

    private static void check(boolean passed, String message) {
        if (!passed) failures++;
        LogUtils.getLogger().info("MODEL_DATA CHECK {}: {}", passed ? "PASS" : "FAIL", message);
    }
}
