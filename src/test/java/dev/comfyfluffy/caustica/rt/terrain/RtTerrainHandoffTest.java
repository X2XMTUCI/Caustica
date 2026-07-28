package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtTerrainHandoffTest {
    @Test
    void packsSectionsInYThenZThenXOrder() {
        assertEquals(0, RtTerrain.readyMaskBitIndex(0, 0, 0, 5, 7));
        assertEquals(4, RtTerrain.readyMaskBitIndex(4, 0, 0, 5, 7));
        assertEquals(5, RtTerrain.readyMaskBitIndex(0, 0, 1, 5, 7));
        assertEquals(35, RtTerrain.readyMaskBitIndex(0, 1, 0, 5, 7));
    }

    @Test
    void adjacentMaskCellsDoNotAlias() {
        int sizeX = 65;
        int sizeZ = 65;
        assertEquals(1, RtTerrain.readyMaskBitIndex(1, 0, 0, sizeX, sizeZ)
                - RtTerrain.readyMaskBitIndex(0, 0, 0, sizeX, sizeZ));
        assertEquals(sizeX, RtTerrain.readyMaskBitIndex(0, 0, 1, sizeX, sizeZ)
                - RtTerrain.readyMaskBitIndex(0, 0, 0, sizeX, sizeZ));
        assertEquals(sizeX * sizeZ, RtTerrain.readyMaskBitIndex(0, 1, 0, sizeX, sizeZ)
                - RtTerrain.readyMaskBitIndex(0, 0, 0, sizeX, sizeZ));
    }
}
