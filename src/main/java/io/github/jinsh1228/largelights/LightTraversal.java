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
