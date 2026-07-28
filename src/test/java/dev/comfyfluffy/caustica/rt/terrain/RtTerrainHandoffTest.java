package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtTerrainHandoffTest {
    @Test
    void keepsDistantTerrainWhenNoCompleteVanillaColumnIsReady() {
        assertEquals(0.0f, RtTerrain.inscribedReadyRadiusBlocks(
                8.0, 8.0, 0, 0, -1));
    }

    @Test
    void handoffSquareNeverLeavesTheProvenReadyChunkRectangle() {
        assertEquals(7.5f, RtTerrain.inscribedReadyRadiusBlocks(
                8.0, 8.0, 0, 0, 0));
        assertEquals(23.5f, RtTerrain.inscribedReadyRadiusBlocks(
                8.0, 8.0, 0, 0, 1));
        assertEquals(0.0f, RtTerrain.inscribedReadyRadiusBlocks(
                16.0, 8.0, 1, 0, 0));
    }

    @Test
    void handoffMathRetainsPrecisionAtLargeWorldCoordinates() {
        int chunk = 1_250_000;
        double player = chunk * 16.0 + 4.0;
        assertEquals(3.5f, RtTerrain.inscribedReadyRadiusBlocks(
                player, player, chunk, chunk, 0));
    }
}
