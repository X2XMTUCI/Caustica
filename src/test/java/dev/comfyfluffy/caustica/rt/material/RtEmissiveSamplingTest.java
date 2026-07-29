package dev.comfyfluffy.caustica.rt.material;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class RtEmissiveSamplingTest {
    private static final RtMaterialDesc.EmissionSummary SUMMARY =
            new RtMaterialDesc.EmissionSummary(0.2f, 0.4f, 0.1f, 0.25f, 0.5f);

    @Test
    void authoredEmissionUsesCompiledTexturePower() {
        assertEquals(0.25f, RtEmissiveSampling.estimatedPower(
                material(RtMaterialDesc.EmissionSource.LAB_PBR, 1.0f), 0.0f), 1.0e-6f);
    }

    @Test
    void stateGatedMaskIncludesActualBlockLightStrength() {
        assertEquals(0.125f, RtEmissiveSampling.estimatedPower(
                material(RtMaterialDesc.EmissionSource.HEURISTIC_MASK, 1.0f), 0.5f), 1.0e-6f);
    }

    @Test
    void overrideUsesAuthoredStrengthAndNoneCannotEnterProposal() {
        assertEquals(0.5f, RtEmissiveSampling.estimatedPower(
                material(RtMaterialDesc.EmissionSource.OVERRIDE, 2.0f), 0.0f), 1.0e-6f);
        assertEquals(0.0f, RtEmissiveSampling.estimatedPower(
                material(RtMaterialDesc.EmissionSource.NONE, 0.0f), 1.0f), 0.0f);
    }

    private static RtMaterialDesc material(RtMaterialDesc.EmissionSource source, float strength) {
        return new RtMaterialDesc(0, RtMaterialDesc.Source.LAB_PBR, 0,
                0.5f, 0.0f, 1.0f, 0.0f, source, strength, SUMMARY);
    }
}
