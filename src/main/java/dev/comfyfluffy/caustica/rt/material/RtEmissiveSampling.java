package dev.comfyfluffy.caustica.rt.material;

/**
 * CPU-side proposal metadata for emissive triangle sampling.
 *
 * <p>Every extracted triangle stores positions, proposal power, three corner UVs, material ID and
 * primitive fallback emission. The current variance-bounded estimator uses average emitted energy
 * for proposal construction and traces the selected point for exact geometry, orientation and
 * occlusion. The UV metadata stays in the upload ABI for backwards-compatible cached sidecars.</p>
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

    /**
     * Pack only the chromaticity of the average emitted radiance. The maximum channel is normalized
     * to one before quantization, so the shader can recover a stable colour direction without storing
     * per-light metadata in the temporal reservoir. Absolute energy remains in {@link #estimatedPower}.
     */
    public static int packedAverageChroma(RtMaterialDesc desc) {
        RtMaterialDesc.EmissionSummary summary = desc.emissionSummary();
        float r = Math.max(0.0f, summary.averageR());
        float g = Math.max(0.0f, summary.averageG());
        float b = Math.max(0.0f, summary.averageB());
        float max = Math.max(r, Math.max(g, b));
        if (!(max > 0.0f) || !Float.isFinite(max)) {
            return 0;
        }
        int ri = Math.round(Math.min(1.0f, r / max) * 255.0f);
        int gi = Math.round(Math.min(1.0f, g / max) * 255.0f);
        int bi = Math.round(Math.min(1.0f, b / max) * 255.0f);
        return ri | (gi << 8) | (bi << 16);
    }

    /**
     * RGBA16F reservoir history cannot retain an unbounded total light weight directly. Store its
     * square root; raygen squares it after endpoint validation.
     */
    public static float encodedTotalWeight(double totalWeight) {
        if (!(totalWeight > 0.0) || !Double.isFinite(totalWeight)) {
            return 0.0f;
        }
        return (float) Math.min(65_500.0, Math.sqrt(totalWeight));
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
