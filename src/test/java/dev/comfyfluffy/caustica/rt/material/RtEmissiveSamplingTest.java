package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void averageEmissionChromaPreservesColourDirection() {
        RtMaterialDesc.EmissionSummary summary =
                new RtMaterialDesc.EmissionSummary(0.8f, 0.4f, 0.2f, 0.47f, 0.1f);
        RtMaterialDesc desc = new RtMaterialDesc(0, RtMaterialDesc.Source.LAB_PBR, 0,
                0.5f, 0.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.LAB_PBR,
                1.0f, summary);

        int packed = RtEmissiveSampling.packedAverageChroma(desc);
        assertEquals(255, packed & 0xff);
        assertEquals(128, (packed >>> 8) & 0xff);
        assertEquals(64, (packed >>> 16) & 0xff);
    }

    @Test
    void squareRootEncodingRetainsLargeProposalEnergyWithoutHalfOverflow() {
        for (double weight : new double[]{1.0e-4, 0.01, 1.0, 10_000.0, 1.0e8}) {
            float encoded = RtEmissiveSampling.encodedTotalWeight(weight);
            assertTrue(encoded > 0.0f && encoded < 65_504.0f);
            double decoded = encoded * (double) encoded;
            assertEquals(weight, decoded, Math.max(1.0e-8, weight * 2.0e-6));
        }
        assertEquals(0.0f, RtEmissiveSampling.encodedTotalWeight(0.0));
        assertEquals(0.0f, RtEmissiveSampling.encodedTotalWeight(Double.NaN));
    }

    @Test
    void distanceWeightedProposalKeepsNearLightSamplesBoundedAndUnbiased() {
        double nearImportance = RtEmissiveSampling.distanceImportance(2.0, 0.0, 0.0,
                0.0, 0.0, 0.0);
        double farImportance = RtEmissiveSampling.distanceImportance(20.0, 0.0, 0.0,
                0.0, 0.0, 0.0);
        assertEquals(0.25, nearImportance, 1.0e-12);
        assertEquals(0.0025, farImportance, 1.0e-12);

        double proposalTotal = 3.0;
        double power = 0.5;
        double nearInversePdf = Math.pow(
                RtEmissiveSampling.encodedInverseProposalWeight(
                        proposalTotal, nearImportance * power), 2.0);
        double farInversePdf = Math.pow(
                RtEmissiveSampling.encodedInverseProposalWeight(
                        proposalTotal, farImportance * power), 2.0);
        assertEquals(proposalTotal / (nearImportance * power), nearInversePdf, 1.0e-5);
        assertEquals(proposalTotal / (farImportance * power), farInversePdf, 1.0e-3);
        assertEquals(100.0, farInversePdf / nearInversePdf, 1.0e-3);
    }

    @Test
    void sparseTextureCoverageCannotCreateInverseCoverageOutlier() {
        for (float coverage : new float[]{1.0f, 0.1f, 0.01f, 0.001f}) {
            RtMaterialDesc.EmissionSummary summary =
                    new RtMaterialDesc.EmissionSummary(coverage, coverage, coverage, coverage, coverage);
            RtMaterialDesc desc = new RtMaterialDesc(0, RtMaterialDesc.Source.LAB_PBR, 0,
                    0.5f, 0.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.LAB_PBR,
                    1.0f, summary);
            float power = RtEmissiveSampling.estimatedPower(desc, 0.0f);
            float encoded = RtEmissiveSampling.encodedTotalWeight(power);
            double sampledContribution = encoded * (double) encoded;

            assertEquals(coverage, sampledContribution, Math.max(1.0e-8, coverage * 2.0e-6));
            assertTrue(sampledContribution <= 1.0);
        }
    }

    private static RtMaterialDesc material(RtMaterialDesc.EmissionSource source, float strength) {
        return new RtMaterialDesc(0, RtMaterialDesc.Source.LAB_PBR, 0,
                0.5f, 0.0f, 1.0f, 0.0f, source, strength, SUMMARY);
    }
}
