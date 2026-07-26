package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtTerrainMesherTest {
    private static final float EPS = 1.0e-7f;

    @Test
    void sixteenTexelsKeepConfiguredPhysicalDepth() {
        assertEquals(0.125f,
                RtTerrainMesher.resolutionScaledDepth(0.125f, 16, 16, 1.0f, 1.0f),
                EPS);
    }

    @Test
    void highResolutionMapsKeepTheSamePhysicalDepth() {
        assertEquals(0.125f,
                RtTerrainMesher.resolutionScaledDepth(0.125f, 256, 256, 1.0f, 1.0f),
                EPS);
    }

    @Test
    void meshResolutionDoesNotChangePhysicalDepth() {
        assertEquals(0.125f,
                RtTerrainMesher.resolutionScaledDepth(0.125f, 128, 256, 0.5f, 1.0f),
                EPS);
    }
}
