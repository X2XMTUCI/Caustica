package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtTerrainSchedulingTest {
    @Test
    void blockEditsBypassEveryBackgroundStreamingQueue() throws IOException {
        Path rt = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "rt");
        String terrain = Files.readString(rt.resolve(Path.of("terrain", "RtTerrain.java")));
        String workers = Files.readString(rt.resolve(Path.of("terrain", "RtWorkerPool.java")));
        String gpu = Files.readString(rt.resolve("RtGpuExecutor.java"));

        assertTrue(terrain.contains("processDirtySections();"));
        assertTrue(terrain.contains("EDIT_INFLIGHT_HEADROOM"));
        assertTrue(terrain.contains("dispatchSectionBuild(dispatch, key, sx, sy, sz, edited);"));
        assertTrue(terrain.contains("RtWorkerPool.INSTANCE.submit(task.edited"));
        assertTrue(terrain.contains("}, task.edited);"));

        assertTrue(workers.contains("class WorkQueue extends LinkedBlockingDeque<Runnable>"));
        assertTrue(workers.contains("task instanceof UrgentTask ? offerFirst(task) : offerLast(task)"));

        assertTrue(gpu.contains("jobs.addFirst(job);"));
        assertTrue(gpu.contains("job.build.assign(nextBuildValue.incrementAndGet());"));
        assertFalse(gpu.contains("new Build(value)"));
    }
}
