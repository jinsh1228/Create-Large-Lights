package io.github.jinsh1228.largelights;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Vanilla's voxel order and endpoint bias, without querying every voxel of a proven empty group. */
final class LightTraversal {
    interface Test {
        /** 0: inspect this 4-cube; 2: empty 4-cube; 4: empty 16-cube. */
        int skip(BlockPos pos);
        boolean blocked(BlockPos pos);
    }

    /** Shapes.block().clip != null, including vanilla's inside bias and strict face/endpoint tolerances. */
    static boolean hitsFullBlock(Vec3 from, Vec3 to, BlockPos pos) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        if (dx * dx + dy * dy + dz * dz < 1e-7) return false;
        double x = from.x + dx * .001 - pos.getX();
        double y = from.y + dy * .001 - pos.getY();
        double z = from.z + dz * .001 - pos.getZ();
        if (x >= 0 && x < 1 && y >= 0 && y < 1 && z >= 0 && z < 1) return true;
        return Math.abs(dx) > 1e-7 && face(from.x, from.y, from.z, dx, dy, dz,
                pos.getX() + (dx < 0 ? 1.0 : 0), pos.getY(), pos.getZ())
                || Math.abs(dy) > 1e-7 && face(from.y, from.z, from.x, dy, dz, dx,
                pos.getY() + (dy < 0 ? 1.0 : 0), pos.getZ(), pos.getX())
                || Math.abs(dz) > 1e-7 && face(from.z, from.x, from.y, dz, dx, dy,
                pos.getZ() + (dz < 0 ? 1.0 : 0), pos.getX(), pos.getY());
    }

    private static boolean face(double from, double u, double v, double delta, double du, double dv,
                                double plane, int minU, int minV) {
        double at = (plane - from) / delta;
        double hitU = u + at * du, hitV = v + at * dv;
        return at > 0 && at < 1 && minU - 1e-7 < hitU && hitU < minU + 1.0 + 1e-7
                && minV - 1e-7 < hitV && hitV < minV + 1.0 + 1e-7;
    }

    static boolean clear(Vec3 from, Vec3 to, Test test) {
        if (from.equals(to)) return true;
        double ex = Mth.lerp(-1e-7, to.x, from.x), ey = Mth.lerp(-1e-7, to.y, from.y), ez = Mth.lerp(-1e-7, to.z, from.z);
        double sx = Mth.lerp(-1e-7, from.x, to.x), sy = Mth.lerp(-1e-7, from.y, to.y), sz = Mth.lerp(-1e-7, from.z, to.z);
        int x = Mth.floor(sx), y = Mth.floor(sy), z = Mth.floor(sz);
        double dx = ex - sx, dy = ey - sy, dz = ez - sz;
        int ix = Mth.sign(dx), iy = Mth.sign(dy), iz = Mth.sign(dz);
        double tx = ix == 0 ? Double.MAX_VALUE : ix / dx;
        double ty = iy == 0 ? Double.MAX_VALUE : iy / dy;
        double tz = iz == 0 ? Double.MAX_VALUE : iz / dz;
        double nx = tx * (ix > 0 ? 1 - Mth.frac(sx) : Mth.frac(sx));
        double ny = ty * (iy > 0 ? 1 - Mth.frac(sy) : Mth.frac(sy));
        double nz = tz * (iz > 0 ? 1 - Mth.frac(sz) : Mth.frac(sz));
        int gx = Integer.MIN_VALUE, gy = 0, gz = 0, shift = 2;
        boolean empty = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (true) {
            if (x >> shift != gx || y >> shift != gy || z >> shift != gz) {
                int skip = test.skip(pos.set(x, y, z));
                empty = skip != 0;
                shift = Math.max(2, skip);
                gx = x >> shift; gy = y >> shift; gz = z >> shift;
            }
            if (!empty && test.blocked(pos.set(x, y, z))) return false;
            if (nx > 1 && ny > 1 && nz > 1) return true;
            // Keep additions and strict tie comparisons identical to BlockGetter.traverseBlocks.
            if (nx < ny) {
                if (nx < nz) { x += ix; nx += tx; }
                else { z += iz; nz += tz; }
            } else if (ny < nz) { y += iy; ny += ty; }
            else { z += iz; nz += tz; }
        }
    }
}
