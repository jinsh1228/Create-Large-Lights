package io.github.jinsh1228.largelights.client;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

/** Occlusion against the surfaces actually drawn, including the holes in cutout textures. */
public final class ModelLightOcclusion {
    private static final double EPSILON = 1e-9;

    private ModelLightOcclusion() {
    }

    /** Drops model and heap texture snapshots together when resources are reloaded. */
    public static void clear() {
        LightModelSnapshots.clear();
    }

    /** CMS's block-space trace: its outer clip still handles screens, sub-level poses and the nearest hit. */
    public static BlockHitResult trace(Level level, BlockPos ignored, Vec3 from, Vec3 to,
                                       Predicate<BlockPos> allowed) {
        return BlockGetter.traverseBlocks(from, to, level, (world, pos) -> {
            if (pos.equals(ignored) || !allowed.test(pos) || level.isOutsideBuildHeight(pos)) {
                return null;
            }
            var chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
            if (chunk == null) {
                // Unknown terrain remains opaque, without forcing a chunk to load.
                return Shapes.block().clip(from, to, pos);
            }
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                return null;
            }
            if (state.isSolidRender(level, pos) && state.isCollisionShapeFullBlock(level, pos)) {
                return Shapes.block().clip(from, to, pos);
            }
            if (state.getRenderShape() != RenderShape.MODEL) {
                return state.getCollisionShape(level, pos, CollisionContext.empty()).clip(from, to, pos);
            }
            List<Quad> quads = LightModelSnapshots.occluders(level, pos, state);
            if (quads.isEmpty()) {
                return state.getCollisionShape(level, pos, CollisionContext.empty()).clip(from, to, pos);
            }
            Vec3 offset = Vec3.atLowerCornerOf(pos).add(state.getOffset(level, pos));
            Hit hit = intersect(quads, from.subtract(offset), to.subtract(offset));
            return hit == null ? null : new BlockHitResult(from.lerp(to, hit.fraction()),
                    Direction.getNearest(hit.normal().x, hit.normal().y, hit.normal().z), pos.immutable(), false);
        }, world -> null);
    }

    @FunctionalInterface
    interface Alpha {
        boolean opaque(double u, double v);
    }

    /** Local model vertices and sprite-local UVs; no live texture or model data is read by an intersection. */
    record Quad(Vec3[] points, float[] uv, Alpha alpha) {
        Quad {
            points = points.clone();
            uv = uv.clone();
        }
    }

    record Hit(double fraction, Vec3 normal) {
    }

    static Hit intersect(List<Quad> quads, Vec3 from, Vec3 to) {
        Hit nearest = null;
        Vec3 delta = to.subtract(from);
        for (Quad quad : quads) {
            for (int triangle = 0; triangle < 2; triangle++) {
                Hit hit = triangle(quad, 0, triangle == 0 ? 1 : 2, triangle == 0 ? 2 : 3, from, delta);
                if (hit != null && (nearest == null || hit.fraction() < nearest.fraction())) {
                    nearest = hit;
                }
            }
        }
        return nearest;
    }

    private static Hit triangle(Quad quad, int a, int b, int c, Vec3 from, Vec3 delta) {
        Vec3 origin = quad.points[a];
        double ex = quad.points[b].x - origin.x, ey = quad.points[b].y - origin.y, ez = quad.points[b].z - origin.z;
        double fx = quad.points[c].x - origin.x, fy = quad.points[c].y - origin.y, fz = quad.points[c].z - origin.z;
        double px = delta.y * fz - delta.z * fy, py = delta.z * fx - delta.x * fz, pz = delta.x * fy - delta.y * fx;
        double determinant = ex * px + ey * py + ez * pz;
        // Both sides occlude: model winding must not let a ray through the back of a thin face.
        if (Math.abs(determinant) < EPSILON) {
            return null;
        }
        double ox = from.x - origin.x, oy = from.y - origin.y, oz = from.z - origin.z;
        double u = (ox * px + oy * py + oz * pz) / determinant;
        double qx = oy * ez - oz * ey, qy = oz * ex - ox * ez, qz = ox * ey - oy * ex;
        double v = (delta.x * qx + delta.y * qy + delta.z * qz) / determinant;
        double fraction = (fx * qx + fy * qy + fz * qz) / determinant;
        if (u < -EPSILON || v < -EPSILON || u + v > 1 + EPSILON || fraction < 0 || fraction > 1) {
            return null;
        }
        double textureU = quad.uv[a * 2] * (1 - u - v) + quad.uv[b * 2] * u + quad.uv[c * 2] * v;
        double textureV = quad.uv[a * 2 + 1] * (1 - u - v) + quad.uv[b * 2 + 1] * u + quad.uv[c * 2 + 1] * v;
        if (!quad.alpha.opaque(textureU, textureV)) {
            return null;
        }
        Vec3 normal = new Vec3(ey * fz - ez * fy, ez * fx - ex * fz, ex * fy - ey * fx).normalize();
        return new Hit(fraction, normal.dot(delta) > 0 ? normal.scale(-1) : normal);
    }
}
