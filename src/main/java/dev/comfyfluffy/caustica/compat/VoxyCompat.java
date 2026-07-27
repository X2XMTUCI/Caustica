package dev.comfyfluffy.caustica.compat;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional reflection bridge to the Caustica edition of Voxy.
 *
 * <p>No Voxy class appears in Caustica's constant pool, so Caustica still starts normally when Voxy is
 * absent or a different Voxy build is installed. The provider publishes immutable 64-byte LOD quads;
 * all Vulkan buffers, BLASes, TLAS instances, shading and lifetime management remain inside Caustica.</p>
 */
public final class VoxyCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("voxy");
    private static final Api API = LOADED ? Api.create() : null;
    private static volatile List<DistantHorizonsCompat.LodMesh> snapshot = List.of();
    private static volatile long revision;
    private static volatile long observedSourceRevision = Long.MIN_VALUE;
    private static volatile int renderDistanceChunks;
    private static volatile boolean warned;

    private VoxyCompat() {
    }

    public static boolean enabled() {
        return API != null;
    }

    /** Poll the provider on the render thread; the RT planning worker only reads the immutable result. */
    public static void tick() {
        Api api = API;
        if (api == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.gameRenderer == null) {
            if (!snapshot.isEmpty()) {
                snapshot = List.of();
                revision++;
            }
            observedSourceRevision = Long.MIN_VALUE;
            renderDistanceChunks = 0;
            return;
        }
        try {
            var camera = minecraft.gameRenderer.mainCamera().position();
            int vanillaChunks = minecraft.options.renderDistance().get();
            Object value = api.poll.invoke(null, camera.x, camera.y, camera.z, vanillaChunks,
                    minecraft.level.getMinY(), minecraft.level.getMaxY());
            long sourceRevision = ((Number) api.revision.invoke(null)).longValue();
            renderDistanceChunks = ((Number) api.renderDistanceChunks.invoke(null)).intValue();
            if (sourceRevision == observedSourceRevision) return;
            List<?> meshes = value instanceof List<?> list ? list : List.of();
            ArrayList<DistantHorizonsCompat.LodMesh> converted = new ArrayList<>(meshes.size());
            for (Object mesh : meshes) {
                if (mesh == null) continue;
                Access access = api.access(mesh.getClass());
                converted.add(new DistantHorizonsCompat.LodMesh(
                        ((Number) access.key.invoke(mesh)).longValue(),
                        ((Number) access.version.invoke(mesh)).longValue(),
                        ((Number) access.originX.invoke(mesh)).intValue(),
                        ((Number) access.originY.invoke(mesh)).intValue(),
                        ((Number) access.originZ.invoke(mesh)).intValue(),
                        ((Number) access.width.invoke(mesh)).intValue(),
                        ((Number) access.dataPointWidth.invoke(mesh)).intValue(),
                        (byte[]) access.opaque.invoke(mesh),
                        (byte[]) access.transparent.invoke(mesh)));
            }
            snapshot = List.copyOf(converted);
            observedSourceRevision = sourceRevision;
            revision++;
        } catch (Throwable failure) {
            if (!snapshot.isEmpty()) {
                snapshot = List.of();
                revision++;
            }
            observedSourceRevision = Long.MIN_VALUE;
            if (!warned) {
                warned = true;
                CausticaMod.LOGGER.warn("Voxy is installed but its Caustica LOD bridge failed; "
                        + "use the bundled no-Sodium Voxy build", failure);
            }
        }
    }

    public static List<DistantHorizonsCompat.LodMesh> meshes() {
        return snapshot;
    }

    public static long revision() {
        return revision;
    }

    public static int renderDistanceChunks() {
        return renderDistanceChunks;
    }

    public static boolean reset() {
        Api api = API;
        snapshot = List.of();
        revision++;
        observedSourceRevision = Long.MIN_VALUE;
        renderDistanceChunks = 0;
        if (api == null) return false;
        try {
            api.reset.invoke(null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private record Access(Method key, Method version, Method originX, Method originY, Method originZ,
                          Method width, Method dataPointWidth, Method opaque, Method transparent) {
        static Access create(Class<?> type) throws ReflectiveOperationException {
            return new Access(type.getMethod("key"), type.getMethod("version"),
                    type.getMethod("originX"), type.getMethod("originY"), type.getMethod("originZ"),
                    type.getMethod("width"), type.getMethod("dataPointWidth"),
                    type.getMethod("opaque"), type.getMethod("transparent"));
        }
    }

    private static final class Api {
        final Method poll;
        final Method revision;
        final Method renderDistanceChunks;
        final Method reset;
        private volatile Class<?> meshType;
        private volatile Access meshAccess;

        private Api(Class<?> bridge) throws ReflectiveOperationException {
            poll = bridge.getMethod("poll", double.class, double.class, double.class,
                    int.class, int.class, int.class);
            revision = bridge.getMethod("revision");
            renderDistanceChunks = bridge.getMethod("renderDistanceChunks");
            reset = bridge.getMethod("reset");
        }

        static Api create() {
            try {
                Class<?> bridge = Class.forName("me.cortex.voxy.client.compat.CausticaBridge");
                Method available = bridge.getMethod("available");
                if (!Boolean.TRUE.equals(available.invoke(null))) return null;
                return new Api(bridge);
            } catch (Throwable ignored) {
                return null;
            }
        }

        Access access(Class<?> type) throws ReflectiveOperationException {
            Access current = meshAccess;
            if (current != null && meshType == type) return current;
            synchronized (this) {
                if (meshAccess == null || meshType != type) {
                    meshAccess = Access.create(type);
                    meshType = type;
                }
                return meshAccess;
            }
        }
    }
}
