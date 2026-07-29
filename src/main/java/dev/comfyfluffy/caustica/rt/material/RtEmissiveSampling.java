package dev.comfyfluffy.caustica.rt.material;

/**
 * CPU-side proposal metadata for emissive triangle sampling.
 *
 * <p>Every extracted triangle stores positions, proposal power, three corner UVs, material ID and
 * primitive fallback emission. The estimate is used only to build the triangle distribution; UV
 * metadata lets raygen importance-resample the authored emission mask before the selected point is
 * traced through the regular closest-hit material path for the exact texture, tint, orientation
 * and occlusion.</p>
 */
public final class RtEmissiveSampling {
    public static final int FLOATS_PER_TRIANGLE = 18;
    public static final int POWER_OFFSET = 9;
    public static final int UV_OFFSET = 10;
    public static final int MATERIAL_OFFSET = 16;
    public static final int FALLBACK_OFFSET = 17;
    public static final int GPU_ENTRY_BYTES = 80;

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
