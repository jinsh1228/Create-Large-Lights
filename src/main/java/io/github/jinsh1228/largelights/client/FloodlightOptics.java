package io.github.jinsh1228.largelights.client;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.haruyq.createmediasystems.api.client.WorldChanges;
import org.haruyq.createmediasystems.api.lighting.client.FixtureOptics;

import io.github.jinsh1228.largelights.CreateLargeLights;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** CMS selects this family's optics through its public API, including its background surface scans. */
public final class FloodlightOptics implements FixtureOptics {
    public static void register() {
        FixtureOptics.register(CreateLargeLights.MOD_ID, new FloodlightOptics());
        WorldChanges.onSectionChanged((x, y, z) -> LightModelSnapshots.sectionChanged(SectionPos.asLong(x, y, z)));
        WorldChanges.onChunkLoaded(LightModelSnapshots::chunkChanged);
        WorldChanges.onChunkUnloaded(LightModelSnapshots::chunkChanged);
        WorldChanges.onLevelChanged(ModelLightOcclusion::clear);
    }

    @Override
    public BlockHitResult trace(Level level, BlockPos fixture, Vec3 from, Vec3 to, Predicate<BlockPos> allowed) {
        BlockHitResult hit = ModelLightOcclusion.trace(level, fixture, from, to, allowed);
        // Null asks CMS to use its default trace; a transparent model gap must instead be an explicit miss.
        return hit != null ? hit : BlockHitResult.miss(to, Direction.getNearest(from.subtract(to)), BlockPos.containing(to));
    }

    @Override
    public boolean forEachQuad(Level level, BlockPos fixture, BlockPos pos, BlockState state, Consumer<BakedQuad> consume) {
        if (state.getRenderShape() != RenderShape.MODEL) return true;
        for (int side = 0; side <= 6; side++) {
            Direction direction = side == 6 ? null : Direction.from3DDataValue(side);
            if (direction != null && !Block.shouldRenderFace(state, level, pos, direction, pos.relative(direction))) continue;
            List<BakedQuad> quads = LightModelSnapshots.quads(level, pos, state, direction);
            if (!state.isSolidRender(level, pos)) quads = LightSurfaceGeometry.subdivide(quads);
            quads.forEach(consume);
        }
        return true;
    }
}
