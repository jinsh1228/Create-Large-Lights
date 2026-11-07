package io.github.jinsh1228.largelights;

/**
 * What sets a floodlight apart from a stadium light: the beam widths a wrench steps through (degrees across) and how
 * far it reaches when unlinked, the widest a console may set, and how far across the surface it falls on its light
 * is laid (LightingIllumination.render's maxRadius; RealLight's too).
 */
public enum FloodlightKind {
    FLOODLIGHT(new float[]{20, 35, 50, 70}, 1, 128, 24, 0.3f, 9),
    STADIUM_LIGHT(new float[]{25, 40, 60, 80}, 2, 256, 48, 0.4f, 12);

    final float[] spreads;
    final int defaultSpread;
    public final float range;
    public final int radius;
    /**
     * How strongly it lights what it falls on, of a stage light's: one stage light alone already lights a face fully,
     * and a hall's floodlights overlap, adding up.
     */
    public final float intensity;
    /** How strongly the beam shows in the air, out of 255 at full strength. */
    public final float haze;

    FloodlightKind(float[] spreads, int defaultSpread, float range, int radius, float intensity, float haze) {
        this.spreads = spreads;
        this.defaultSpread = defaultSpread;
        this.range = range;
        this.radius = radius;
        this.intensity = intensity;
        this.haze = haze;
    }

    public int spreads() {
        return spreads.length;
    }

    /** The widest beam it takes, from the wrench or a console. */
    public float maxSpread() {
        return spreads[spreads.length - 1];
    }

    /** The beam's width across, in degrees, at setting {@code index} (kept within the settings there are). */
    public float spread(int index) {
        return spreads[Math.floorMod(index, spreads.length)];
    }
}
