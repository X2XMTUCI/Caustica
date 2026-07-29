package dev.comfyfluffy.caustica.rt.material;

/**
 * CPU-side proposal metadata for emissive triangle sampling.
 *
 * <p>Every extracted triangle stores nine position floats followed by an estimate of its average
 * emitted luminance. The estimate is used only to build the proposal distribution; the selected
 * point is still traced through the regular closest-hit material path for the exact texture,
 * tint, orientation and occlusion.</p>
 */
public final class RtEmissiveSampling {
    public static final int FLOATS_PER_TRIANGLE = 10;
    public static final int POWER_OFFSET = 9;

    private RtEmissiveSampling() {
    }

    public static float estimatedPower(RtMaterialDesc desc, float fallbackEmission) {
        float texturePower = desc.emissionSummary().integratedLuminance();
        if (!(texturePower > 0.0f)) {
            return 0.0f;
        }
        float strength = switch (desc.emissionSource()) {
            case LAB_PBR -> desc.emissionStrength();
            case OVERRIDE -> desc.emissionStrength();
            case HEURISTIC_MASK, STATE_UNIFORM -> Math.max(0.0f, fallbackEmission);
            case NONE -> 0.0f;
        };
        float power = texturePower * strength;
        return Float.isFinite(power) && power > 0.0f ? power : 0.0f;
    }
}
