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
     * Camera-local proposal importance. Multiplying the area/power proposal by inverse squared
     * distance makes a nearby visible emitter a common bounded sample instead of a one-in-thousands
     * HDR firefly. The one-block floor keeps lights intersecting the camera neighbourhood finite.
     */
    public static double distanceImportance(double x, double y, double z,
                                            double cameraX, double cameraY, double cameraZ) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (!Double.isFinite(distanceSquared)) {
            return 0.0;
        }
        return 1.0 / Math.max(1.0, distanceSquared);
    }

    /**
     * Store a fresh candidate's inverse point-sample PDF as a square root in the triangle metadata.
     * Raygen squares it only while inserting that candidate; it is deliberately not retained in the
     * temporal reservoir, whose measure is the selected emitter's physical average power.
     */
    public static float encodedInverseProposalWeight(double proposalTotalWeight, double importance) {
        if (!(proposalTotalWeight > 0.0) || !Double.isFinite(proposalTotalWeight)
                || !(importance > 0.0) || !Double.isFinite(importance)) {
            return 0.0f;
        }
        return (float) Math.min(65_500.0, Math.sqrt(proposalTotalWeight / importance));
    }

    /** Compatibility helper for a proposal without an additional importance multiplier. */
    public static float encodedTotalWeight(double totalWeight) {
        return encodedInverseProposalWeight(totalWeight, 1.0);
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
