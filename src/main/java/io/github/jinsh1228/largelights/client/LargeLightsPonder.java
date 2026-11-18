package io.github.jinsh1228.largelights.client;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import io.github.jinsh1228.largelights.Aim;
import io.github.jinsh1228.largelights.CreateLargeLights;
import io.github.jinsh1228.largelights.FloodlightBlock;
import io.github.jinsh1228.largelights.StreetLightBlock;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.haruyq.createmediasystems.lighting.ConsoleLayout;
import org.haruyq.createmediasystems.lighting.LightingConsoleBlock;
import org.haruyq.createmediasystems.lighting.LightingFeature;

final class LargeLightsPonder implements PonderPlugin {
    @Override
    public String getModId() {
        return CreateLargeLights.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(CreateLargeLights.FLOODLIGHT.getId(), CreateLargeLights.STADIUM_LIGHT.getId())
                .addStoryBoard("lighting_plate", LargeLightsPonder::floodlights);
        helper.forComponents(CreateLargeLights.STREET_LIGHT.getId(), CreateLargeLights.LIGHT_POLE.getId(),
                        CreateLargeLights.LIGHT_METER.getId())
                .addStoryBoard("lighting_plate", LargeLightsPonder::streetLights);
    }

    private static CreateSceneBuilder plate(SceneBuilder builder, String id, String title) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 7);
        scene.showBasePlate();
        scene.idle(10);
        return scene;
    }

    private static void floodlights(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = plate(builder, "floodlights", "Aiming and linking floodlights");
        BlockPos regular = util.grid().at(1, 3, 1);
        BlockPos stadium = util.grid().at(1, 3, 3);
        BlockPos target = util.grid().at(5, 0, 3);
        scene.world().setBlock(regular, CreateLargeLights.FLOODLIGHT.get().defaultBlockState()
                .setValue(FloodlightBlock.FACING, Direction.EAST), false);
        scene.world().setBlock(stadium, CreateLargeLights.STADIUM_LIGHT.get().defaultBlockState()
                .setValue(FloodlightBlock.FACING, Direction.EAST), false);
        for (BlockPos lamp : new BlockPos[]{regular, stadium}) {
            scene.world().setBlock(lamp.west(), Blocks.IRON_BLOCK.defaultBlockState(), false);
            scene.world().showSection(util.select().fromTo(lamp.west(), lamp), Direction.DOWN);
            scene.world().modifyBlockEntity(lamp, FloodlightBlock.Entity.class, light -> {
                light.yaw = Direction.EAST.toYRot();
                light.pitch = 0;
            });
        }
        scene.idle(15);
        scene.world().setBlock(stadium.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        scene.world().modifyBlock(stadium, state -> state.setValue(FloodlightBlock.POWER, 15), false);
        scene.world().modifyBlockEntity(stadium, FloodlightBlock.Entity.class, light ->
                light.setBlockState(light.getLevel().getBlockState(stadium)));
        say(scene, util.vector().topOf(stadium), "An unlinked floodlight turns on with redstone. A larger stadium light reaches farther.");
        var wrench = AllItems.WRENCH.asStack();
        scene.overlay().showControls(util.vector().topOf(stadium), Pointing.DOWN, 45).rightClick().withItem(wrench);
        scene.world().modifyBlockEntity(stadium, FloodlightBlock.Entity.class, light -> light.spread = 3);
        say(scene, util.vector().topOf(stadium), "Right-click with the Create Wrench to step through the beam widths.");
        scene.overlay().showControls(util.vector().topOf(stadium), Pointing.DOWN, 45).rightClick().whileSneaking().withItem(wrench);
        say(scene, util.vector().topOf(stadium), "Sneak and right-click with the Wrench to start aiming.");
        scene.overlay().showControls(util.vector().topOf(target), Pointing.DOWN, 45).rightClick();
        scene.world().modifyBlockEntity(stadium, FloodlightBlock.Entity.class, light -> {
            float[] aim = Aim.toward(util.vector().topOf(target).subtract(stadium.getCenter()), light.yaw);
            light.yaw = aim[0];
            light.pitch = aim[1];
        });
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, target, new AABB(target), 90);
        say(scene, util.vector().topOf(target), "Right-click the target to set the direction. Click the floodlight again to cancel.");

        BlockPos console = util.grid().at(5, 1, 5);
        for (var part : ConsoleLayout.parts(console, Direction.NORTH)) {
            scene.world().setBlock(part.pos(), ConsoleLayout.state(part,
                    LightingFeature.LIGHTING_CONSOLE.get().defaultBlockState(),
                    LightingFeature.LIGHTING_MONITOR.get().defaultBlockState(), Direction.NORTH, true), false);
            scene.world().showSection(util.select().position(part.pos()), Direction.DOWN);
        }
        scene.idle(20);
        var link = new ItemStack(LightingFeature.LIGHTING_LINK.get());
        scene.overlay().showControls(util.vector().topOf(console), Pointing.DOWN, 45).rightClick().withItem(link);
        say(scene, util.vector().topOf(console), "Use the LED Tuning Wrench on a formed console to select it.");
        scene.overlay().showControls(util.vector().topOf(stadium), Pointing.DOWN, 45).rightClick().withItem(link);
        scene.world().modifyBlockEntity(console, LightingConsoleBlock.Entity.class, desk -> {
            desk.formed = true;
            desk.settings.enabled = false;
            if (desk.getLevel().getBlockEntity(stadium) instanceof FloodlightBlock.Entity light) desk.bind(light, 0);
        });
        say(scene, util.vector().topOf(stadium), "Use it on the floodlight to link it to the selected group.");
        scene.world().modifyBlockEntity(console, LightingConsoleBlock.Entity.class, desk -> desk.settings.enabled = true);
        say(scene, util.vector().topOf(console), "On the floodlight page, turn on Lighting output and raise both the master and group faders.");
    }

    private static void streetLights(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = plate(builder, "street_lights", "Street lights and the light meter");
        BlockPos base = util.grid().at(3, 1, 3);
        BlockPos lamp = base.above(3);
        for (int y = 0; y < 3; y++) {
            BlockPos pole = base.above(y);
            scene.world().setBlock(pole, CreateLargeLights.LIGHT_POLE.get().defaultBlockState(), false);
            scene.world().showSection(util.select().position(pole), Direction.DOWN);
            scene.idle(5);
        }
        scene.world().setBlock(lamp, CreateLargeLights.STREET_LIGHT.get().defaultBlockState()
                .setValue(StreetLightBlock.FACING, Direction.SOUTH).setValue(StreetLightBlock.LIT, true), false);
        scene.world().showSection(util.select().position(lamp), Direction.DOWN);
        say(scene, util.vector().topOf(lamp), "Stack light poles and place a street light on top. It turns on automatically when it gets dark.");
        scene.overlay().showControls(util.vector().topOf(lamp), Pointing.DOWN, 45).rightClick().withItem(AllItems.WRENCH.asStack());
        scene.world().modifyBlock(lamp, state -> state.setValue(StreetLightBlock.AUTO, false)
                .setValue(StreetLightBlock.LIT, false), false);
        say(scene, util.vector().topOf(lamp), "Right-click with a Wrench to switch between automatic and redstone-only operation.");
        scene.overlay().showControls(util.vector().centerOf(base.south(2)), Pointing.DOWN, 45)
                .withItem(new ItemStack(CreateLargeLights.LIGHT_METER.get()));
        say(scene, util.vector().topOf(base.south(2).below()), "Hold the light meter to inspect block light and find nearby dark spots.");
    }

    private static void say(CreateSceneBuilder scene, Vec3 point, String text) {
        scene.overlay().showText(95).attachKeyFrame().placeNearTarget().pointAt(point).text(text);
        scene.idle(105);
    }
}
