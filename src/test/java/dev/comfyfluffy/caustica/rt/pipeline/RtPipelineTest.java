package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtPipelineTest {
    @Test
    void radianceWaterRunsAnyHitForDistantTerrainHandoff() {
        int base = RtAccel.SBT_TERRAIN_RADIANCE_OFFSET;
        assertFalse(RtPipeline.hitGroupUsesAnyHit(base + RtAccel.BUCKET_SOLID));
        assertTrue(RtPipeline.hitGroupUsesAnyHit(base + RtAccel.BUCKET_CUTOUT));
        assertTrue(RtPipeline.hitGroupUsesAnyHit(base + RtAccel.BUCKET_TRANSLUCENT));
        assertTrue(RtPipeline.hitGroupUsesAnyHit(base + RtAccel.BUCKET_WATER));
    }

    @Test
    void shadowWaterStillRunsAnyHitForTransmission() {
        assertTrue(RtPipeline.hitGroupUsesAnyHit(
                RtAccel.SBT_TERRAIN_SHADOW_OFFSET + RtAccel.BUCKET_WATER));
    }
}
