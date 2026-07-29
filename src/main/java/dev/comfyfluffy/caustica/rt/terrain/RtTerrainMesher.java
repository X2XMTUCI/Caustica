package dev.comfyfluffy.caustica.rt.terrain;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.material.RtMaterialAbi;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtMaterials;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.material.RtMaterialDesc;
import dev.comfyfluffy.caustica.rt.material.RtEmissiveSampling;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

final class RtTerrainMesher {
    static float resolutionScaledDepth(float requestedDepth, int cellsX, int cellsY,
                                       float worldSpanU, float worldSpanV) {
        // Physical height is encoded by the normalized LabPBR alpha value, not by texel size. Scaling
        // depth by 16 / textureResolution made the same map 2x shallower at 32x and 16x shallower at
        // 256x. Grid resolution controls only spatial detail; the authored displacement range is stable.
        return requestedDepth;
    }

    /**
     * Reusable per-worker-thread meshing state. The mesh + captures are reset between tasks so their
     * backing arrays amortize across sections instead of re-growing per task. Everything the result carries out —
     * {@link PackedSection}, OMM data — is copied out of this state before the job returns, so reuse on
     * the next task cannot corrupt a queued result. The Fabric block emitter is also thread-confined and
     * reused; the fluid renderer stays per-task because it captures the dispatch context's model set.
     */
    static final class WorkerTessState {
        final QuadCapture capture = new QuadCapture();
        final QuadEmitter blockEmitter = Renderer.get().quadEmitter(capture::putFabric);
        final RandomSource blockRandom = RandomSource.createThreadLocalInstance(0L);
        final FluidCapture fluidCapture = new FluidCapture();
        final SectionMesh mesh = new SectionMesh();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        void reset(BlockColors blockColors, SpriteFinder blockSpriteFinder) {
            capture.blockColors = blockColors;
            capture.spriteFinder = blockSpriteFinder;
            capture.discardBlock(); // defensive: a prior job's throw could leave buffered quads
            fluidCapture.reset();
            mesh.reset();
        }
    }

    static final ThreadLocal<WorkerTessState> WORKER_TESS = ThreadLocal.withInitial(WorkerTessState::new);

    /**
     * Tessellate one section to a section-local CPU mesh and precompute pure-CPU sidecar data such as
     * the terrain opacity micromap. <b>Pure CPU + snapshot reads only</b> — no Vulkan, no shared mutable
     * state — so this is the unit a worker thread runs. The task captures one immutable material snapshot
     * and writes its IDs directly; publication performs no resource lookup or primitive-buffer patch.
     * Returns the mesh (possibly empty — caller checks {@code idx}).
     */
    static CpuSection buildCpuSection(BlockAndTintGetter region, BlockStateModelSet modelSet,
                                              QuadEmitter blockEmitter, RandomSource blockRandom,
                                              QuadCapture capture,
                                              FluidRenderer fluidRenderer, FluidCapture fluidCapture,
                                              SectionMesh mesh, BlockPos.MutableBlockPos m,
                                              RtMaterialRegistry.Snapshot materials,
                                              int scx, int scy, int scz) {
        capture.materials = materials;
        fluidCapture.materials = materials;
        tessellate(region, modelSet, blockEmitter, blockRandom, capture,
                fluidRenderer, fluidCapture, mesh, m, scx, scy, scz);
        if (mesh.isEmpty()) {
            return new CpuSection(null, null);
        }
        Geom cutout = mesh.cutoutOrEmpty();
        RtAccel.OpacityMicromapInput ommInput =
                RtTerrainOmm.buildInput(cutout.triCount(), cutout.cornerUv.elements(),
                        cutout.ommSprites.elements(), cutout.ommSprites.size());
        return new CpuSection(packSection(mesh, materials), ommInput);
    }

    private static PackedSection packSection(SectionMesh mesh, RtMaterialRegistry.Snapshot materials) {
        Geom[] buckets = mesh.buckets(); // { solid, cutout, translucent, water }, indexed by RtAccel.BUCKET_*
        int vertFloats = 0, idxCount = 0, uvFloats = 0, primFloats = 0, triCount = 0;
        int[] bucketTris = new int[buckets.length];
        for (int b = 0; b < buckets.length; b++) {
            vertFloats += buckets[b].verts.size();
            idxCount += buckets[b].idx.size();
            uvFloats += buckets[b].cornerUv.size();
            primFloats += buckets[b].prim.size();
            bucketTris[b] = buckets[b].triCount();
            triCount += bucketTris[b];
        }
        RtMaterialAbi.requireTerrainParity(primFloats, idxCount, uvFloats);

        float[] positions = new float[vertFloats];
        int[] indices = new int[idxCount];
        float[] uvs = new float[uvFloats];
        float[] material = new float[primFloats];
        int[] triBase = new int[buckets.length];
        int posOff = 0, idxOff = 0, uvOff = 0, matOff = 0, vertBase = 0, triAcc = 0;
        for (int b = 0; b < buckets.length; b++) {
            Geom geom = buckets[b];
            triBase[b] = triAcc;
            int vertSize = geom.verts.size();
            System.arraycopy(geom.verts.elements(), 0, positions, posOff, vertSize);
            int idxSize = geom.idx.size();
            int[] gi = geom.idx.elements();
            if (vertBase == 0) {
                System.arraycopy(gi, 0, indices, idxOff, idxSize);
            } else {
                for (int i = 0; i < idxSize; i++) {
                    indices[idxOff + i] = gi[i] + vertBase;
                }
            }
            int uvSize = geom.cornerUv.size();
            System.arraycopy(geom.cornerUv.elements(), 0, uvs, uvOff, uvSize);
            int matSize = geom.prim.size();
            System.arraycopy(geom.prim.elements(), 0, material, matOff, matSize);
            posOff += vertSize;
            idxOff += idxSize;
            uvOff += uvSize;
            matOff += matSize;
            vertBase += vertSize / 3;
            triAcc += bucketTris[b];
        }
        return new PackedSection(positions, indices, uvs, material, bucketTris, triBase,
                extractEmissiveTriangles(positions, indices, material, materials));
    }

    /**
     * Extract the actual triangles whose resolved material can emit. Positions remain section/entity
     * local; the owner applies its TLAS translation while filling the frame's ReSTIR light list.
     *
     * <p>The list is deliberately geometry-only. Raygen traces the finally selected sample back through
     * the normal closest-hit material path, so LabPBR/override/heuristic texture masks and tint are
     * evaluated at the exact sampled texel instead of approximating an entire block as uniformly bright.</p>
     */
    static float[] extractEmissiveTriangles(float[] positions, int[] indices, float[] material,
                                            RtMaterialRegistry.Snapshot materials) {
        FloatArrayList lights = new FloatArrayList();
        int triangleCount = indices.length / 3;
        for (int tri = 0; tri < triangleCount; tri++) {
            int primBase = tri * 12;
            float encodedEmission = material[primBase + 3];
            float fallbackEmission = encodedEmission >= 1.5f ? encodedEmission - 2.0f : encodedEmission;
            int materialId = Float.floatToRawIntBits(material[primBase + 8]);
            if (materialId < 0 || materialId >= materials.materialCount()) {
                continue;
            }
            RtMaterialDesc desc = materials.material(materialId);
            float estimatedPower = RtEmissiveSampling.estimatedPower(desc, fallbackEmission);
            if (desc.model() != RtMaterialRegistry.MODEL_OPAQUE
                    || !(estimatedPower > 0.0f)) {
                continue;
            }
            for (int corner = 0; corner < 3; corner++) {
                int vertex = indices[tri * 3 + corner] * 3;
                lights.add(positions[vertex]);
                lights.add(positions[vertex + 1]);
                lights.add(positions[vertex + 2]);
            }
            lights.add(estimatedPower);
        }
        return lights.toFloatArray();
    }

    private static void tessellate(BlockAndTintGetter region, BlockStateModelSet modelSet,
                                   QuadEmitter blockEmitter, RandomSource blockRandom, QuadCapture capture,
                                   FluidRenderer fluidRenderer, FluidCapture fluidCapture,
                                   SectionMesh mesh, BlockPos.MutableBlockPos m,
                                   int scx, int scy, int scz) {
        int sox = scx << 4, soy = scy << 4, soz = scz << 4;
        capture.cur = mesh;
        capture.view = region;
        fluidCapture.cur = mesh;
        for (int lx = 0; lx < 16; lx++) {
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = sox + lx, wy = soy + ly, wz = soz + lz;
                    m.set(wx, wy, wz);
                    BlockState state = region.getBlockState(m);
                    if (state.isAir()) {
                        continue;
                    }
                    // Fluids (water/lava, incl. waterlogged blocks): separate mesher, INVISIBLE render
                    // shape, so handled independently of the block model below. Emits section-local
                    // coords + atlas sprite UVs straight into the capturing consumer. Lava's block light
                    // (15) rides the emission channel (water emits 0).
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        fluidCapture.emission = state.getLightEmission() / 15f;
                        // Water is the dielectric fluid; lava stays an opaque emitter. Tagged per-prim
                        // so the path tracer can branch (see emitQuad).
                        fluidCapture.water = fluid.is(FluidTags.WATER);
                        RtFluidMesher.tesselate(region, m, fluidCapture, fluidRenderer.fluidModels, state, fluid);
                    }
                    if (state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BlockStateModel model = modelSet.get(state);
                    if (model == null) {
                        continue;
                    }
                    capture.state = state;
                    capture.pos = m;
                    Vec3 offset = state.getOffset(m);
                    capture.originX = lx + (float) offset.x;
                    capture.originY = ly + (float) offset.y;
                    capture.originZ = lz + (float) offset.z;
                    blockRandom.setSeed(state.getSeed(m));
                    model.emitQuads(blockEmitter, region, m, state, blockRandom, capture.cullTest);
                    capture.flushBlock(); // resolve coplanar ties (grass overlay / cross faces), then emit
                }
            }
        }
    }

    /** Pure-CPU worker result: tessellated mesh plus optional opacity micromap input for its cutout bucket. */
    record CpuSection(PackedSection packed, RtAccel.OpacityMicromapInput opacityMicromap) {
    }

    /** Worker-packed terrain payload; native preparation allocates buffers and bulk-copies these arrays. */
    record PackedSection(float[] positions, int[] indices, float[] uvs, float[] material,
                         int[] bucketTris, int[] triBase, float[] emissiveTriangles) {
    }


    /**
     * Transient CPU accumulator for one section's quads while tessellating. Split into per-material geometry
     * buckets so the BLAS can flag solid blocks {@code VK_GEOMETRY_OPAQUE_BIT}, keep true alpha cutout in
     * an any-hit bucket, and route translucent/water through closest-hit-only records for radiance but
     * any-hit records for shadow tint/pass-through. The buckets are concatenated in {@code BUCKET_*} order
     * into the packed section buffers during preparation, so each geometry's triangles occupy a contiguous range.
     */
    private static final class SectionMesh {
        // Conservative worker-side starting capacities. These trade a little transient RAM for avoiding the
        // repeated grow/copy ladder on normal terrain sections.
        private static final int OPAQUE_TRI_CAP = 768;
        private static final int CUTOUT_TRI_CAP = 256;
        private static final int TRANSLUCENT_TRI_CAP = 64;
        private static final int WATER_TRI_CAP = 128;
        // One bucket per fixed RtAccel terrain geometry: solid, cutout, translucent, water.
        private static final Geom EMPTY_GEOM = new Geom(0);
        Geom opaque;
        Geom cutout;
        Geom translucent;
        Geom water;
        private final Geom[] buckets = new Geom[RtAccel.TERRAIN_BUCKETS];

        Geom[] buckets() {
            buckets[RtAccel.BUCKET_SOLID] = geomOrEmpty(opaque);
            buckets[RtAccel.BUCKET_CUTOUT] = geomOrEmpty(cutout);
            buckets[RtAccel.BUCKET_TRANSLUCENT] = geomOrEmpty(translucent);
            buckets[RtAccel.BUCKET_WATER] = geomOrEmpty(water);
            return buckets;
        }

        Geom cutoutOrEmpty() {
            return geomOrEmpty(cutout);
        }

        Geom opaque() {
            return opaque != null ? opaque : (opaque = new Geom(OPAQUE_TRI_CAP));
        }

        Geom cutout() {
            return cutout != null ? cutout : (cutout = new Geom(CUTOUT_TRI_CAP));
        }

        Geom translucent() {
            return translucent != null ? translucent : (translucent = new Geom(TRANSLUCENT_TRI_CAP));
        }

        Geom water() {
            return water != null ? water : (water = new Geom(WATER_TRI_CAP));
        }

        private static Geom geomOrEmpty(Geom geom) {
            return geom != null ? geom : EMPTY_GEOM;
        }

        boolean isEmpty() {
            return (opaque == null || opaque.idx.isEmpty())
                    && (cutout == null || cutout.idx.isEmpty())
                    && (translucent == null || translucent.idx.isEmpty())
                    && (water == null || water.idx.isEmpty());
        }

        /** Empty the buckets keeping their backing arrays — the mesh is reused across jobs per worker thread. */
        void reset() {
            resetGeom(opaque);
            resetGeom(cutout);
            resetGeom(translucent);
            resetGeom(water);
        }

        private static void resetGeom(Geom geom) {
            if (geom != null) {
                geom.reset();
            }
        }
    }

    /** One geometry bucket's packed, section-local mesh data. */
    private static final class Geom {
        final FloatArrayList verts;
        final IntArrayList idx;
        // Lever B: per-triangle corner UVs in primitive order — 6 floats/triangle (3 corners x u,v),
        // aligned with `idx`'s triangle order so the hit shader reads cornerUv[3*pid + k] directly with no
        // index->vertex-UV gather. The index buffer is still emitted (above) for the BLAS build.
        final FloatArrayList cornerUv;
        // 12 lanes/triangle: normal float4, tint float4, then TerrainPrim's uint material metadata.
        final FloatArrayList prim;
        // One sprite per triangle for opacity micromap classification.
        final SpriteList ommSprites;

        Geom(int triCapacity) {
            int cap = Math.max(2, triCapacity);
            int quadCapacity = (cap + 1) >>> 1;
            verts = new FloatArrayList(quadCapacity * 12); // 4 xyz vertices per quad
            idx = new IntArrayList(cap * 3);
            cornerUv = new FloatArrayList(cap * 6);
            prim = new FloatArrayList(cap * 12);
            ommSprites = new SpriteList(cap);
        }

        int triCount() {
            return idx.size() / 3;
        }

        void reset() {
            verts.clear();       // fastutil clear() keeps the backing array
            idx.clear();
            cornerUv.clear();
            prim.clear();
            ommSprites.clear();
        }
    }

    /** Minimal growable sprite array for the worker path; avoids ArrayList object churn and per-copy gets. */
    private static final class SpriteList {
        private TextureAtlasSprite[] elements;
        private int size;

        SpriteList(int capacity) {
            elements = new TextureAtlasSprite[Math.max(2, capacity)];
        }

        void add(TextureAtlasSprite sprite) {
            if (size == elements.length) {
                TextureAtlasSprite[] grown = new TextureAtlasSprite[elements.length + (elements.length >>> 1)];
                System.arraycopy(elements, 0, grown, 0, elements.length);
                elements = grown;
            }
            elements[size++] = sprite;
        }

        TextureAtlasSprite[] elements() {
            return elements;
        }

        int size() {
            return size;
        }

        void copyInto(TextureAtlasSprite[] dst, int offset) {
            System.arraycopy(elements, 0, dst, offset, size);
        }

        void clear() {
            java.util.Arrays.fill(elements, 0, size, null); // don't retain sprites across atlas reloads
            size = 0;
        }
    }

    /** Captures final Fabric model quads into the current section's mesh. */
    private static final class QuadCapture {
        SectionMesh cur; // set before each block model emission
        RtMaterialRegistry.Snapshot materials;
        SpriteFinder spriteFinder;

        // Per-block context for biome tint, set before each model emission. We resolve it straight from
        // BlockColors and combine it with the Fabric quad's authored color before raster lighting, so the
        // path tracer receives unlit albedo rather than vanilla AO + directional shading.
        BlockColors blockColors;
        BlockAndTintGetter view;
        BlockState state;
        BlockPos pos;
        float originX, originY, originZ;
        private final BlockPos.MutableBlockPos cullPos = new BlockPos.MutableBlockPos();
        private final java.util.function.Predicate<Direction> cullTest = this::isCulled;

        // Coplanar-resolution: vanilla emits coincident quads that tie on depth in the BVH and flicker —
        // a block face's opaque base + its tinted cutout overlay (grass/snowy sides), and a cross model's
        // two-sided faces. put() buffers a block's quads here; flushBlock() (called per block) nudges all
        // but the first member of each coincident group outward along its own normal so each lands on its
        // own plane (base stays, overlay moves in front so its cutout reveals the base; cross back-face
        // separates from the front). Pooled — reset each block, never reallocated steady-state.
        private static final float OFFSET = 2.0e-4f;         // outward nudge (blocks) to break coplanar depth ties
        private static final float TRANSLUCENT_INSET = 2.0e-4f; // inward recess (blocks) for glass/ice vs coplanar neighbours
        private static final float COINCIDENT_EPS = 1.0e-4f; // verts this close are "the same" point
        private static final int RESOLVE_CAP = 128;          // skip the O(n^2) resolve for pathological blocks
        private static final int PRIM_FLAG_MESH_DISPLACED = 1;
        private static final int PRIM_FLAG_MESH_CLOSURE = 1 << 1;
        private final List<PendingQuad> pending = new ArrayList<>(8);
        private int pendingCount;
        // Only opaque, height-mapped displacement candidates are deferred until the section-wide
        // resolution is known. Ordinary/cutout/translucent quads still stream directly into SectionMesh.
        private int[] gidScratch = new int[0];
        private float[] heightScratch = new float[0];
        private final float[] displacedUScratch = new float[4];
        private final float[] displacedVScratch = new float[4];
        private final float[] displacedLocalUScratch = new float[4];
        private final float[] displacedLocalVScratch = new float[4];

        /** Capture a final Fabric Renderer API quad before raster AO/directional lighting is applied. */
        private void putFabric(MutableQuadView quad) {
            PendingQuad q = acquire();
            for (int i = 0; i < 4; i++) {
                q.x[i] = quad.x(i) + originX;
                q.y[i] = quad.y(i) + originY;
                q.z[i] = quad.z(i) + originZ;
                q.uv[i] = UVPair.pack(quad.u(i), quad.v(i));
            }

            float ex1 = q.x[1] - q.x[0], ey1 = q.y[1] - q.y[0], ez1 = q.z[1] - q.z[0];
            float ex2 = q.x[2] - q.x[0], ey2 = q.y[2] - q.y[0], ez2 = q.z[2] - q.z[0];
            float nx = ey1 * ez2 - ez1 * ey2, ny = ez1 * ex2 - ex1 * ez2, nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) { nx /= len; ny /= len; nz /= len; }
            q.nx = nx; q.ny = ny; q.nz = nz;

            ChunkSectionLayer layer = quad.chunkLayer();
            q.cutout = layer != ChunkSectionLayer.SOLID;
            q.translucent = layer == ChunkSectionLayer.TRANSLUCENT;
            // Fabric colors are authored albedo. Continuity uses them for already-resolved overlay tint;
            // ordinary biome-tinted quads retain tintIndex and are multiplied by the world tint below.
            int sr = 0, sg = 0, sb = 0;
            for (int i = 0; i < 4; i++) {
                int color = quad.color(i);
                sr += (color >> 16) & 0xFF;
                sg += (color >> 8) & 0xFF;
                sb += color & 0xFF;
            }
            float tr = sr / 1020f;
            float tg = sg / 1020f;
            float tb = sb / 1020f;
            int tintIndex = quad.tintIndex();
            q.tinted = tintIndex >= 0;
            if (tintIndex >= 0 && blockColors != null && state != null) {
                BlockTintSource src = blockColors.getTintSource(state, tintIndex);
                if (src != null) {
                    int rgb = src.colorInWorld(state, view, pos);
                    tr *= ((rgb >> 16) & 0xFF) * (1f / 255f);
                    tg *= ((rgb >> 8) & 0xFF) * (1f / 255f);
                    tb *= (rgb & 0xFF) * (1f / 255f);
                }
            }
            q.tr = tr; q.tg = tg; q.tb = tb;

            q.emission = quad.emissive() ? 1f : (state != null ? state.getLightEmission() / 15f : 0f);
            TextureAtlasSprite sprite = spriteFinder.find(quad);
            q.sprite = sprite;
            q.materialId = materials.resolve(sprite, state, q.translucent);
        }

        /** Fabric's cull predicate returns true when the nominal face should be discarded. */
        private boolean isCulled(Direction direction) {
            if (direction == null) {
                return false;
            }
            BlockState neighbor = view.getBlockState(cullPos.setWithOffset(pos, direction));
            return !Block.shouldRenderFace(state, neighbor, direction);
        }

        /** Acquire a pooled PendingQuad for the current block (grown on demand, count reset by flushBlock). */
        private PendingQuad acquire() {
            if (pendingCount == pending.size()) {
                pending.add(new PendingQuad());
            }
            return pending.get(pendingCount++);
        }

        /** Drop the current block's buffered quads without emitting (a meshing throw left them partial). */
        void discardBlock() {
            pendingCount = 0;
        }

        /** Resolve coplanar ties among the current block's quads, then emit them into the section buckets. */
        void flushBlock() {
            int n = pendingCount;
            if (n == 0) {
                return;
            }
            if (n >= 2 && n <= RESOLVE_CAP) {
                resolveCoplanar(n);
            }
            for (int i = 0; i < n; i++) {
                queueOrEmit(pending.get(i));
            }
            pendingCount = 0;
        }

        /**
         * Union coincident quads (same 4 corners, any winding) into groups, then within each group keep the
         * first member (the opaque/untinted base if present) in place and push the rest outward along their
         * own normals by {@link #OFFSET} × rank. Same-normal layers (grass base/overlay) fan out along one
         * direction; opposite-normal pairs (cross faces) separate because each moves along its own normal.
         */
        private void resolveCoplanar(int n) {
            int[] gid = gidScratch.length >= n ? gidScratch : (gidScratch = new int[n]);
            for (int i = 0; i < n; i++) {
                gid[i] = -1;
            }
            for (int i = 0; i < n; i++) {
                if (gid[i] != -1) {
                    continue;
                }
                gid[i] = i;
                for (int j = i + 1; j < n; j++) {
                    if (gid[j] == -1 && coincident(pending.get(i), pending.get(j))) {
                        gid[j] = i;
                    }
                }
            }
            for (int r = 0; r < n; r++) {
                if (gid[r] != r) {
                    continue; // not a group representative
                }
                int rank = 0;
                // Pass 1: bases (opaque + untinted) — the first stays put (rank 0), so the overlay lands in
                // front of it. Pass 2: overlays (cutout or tinted) — always pushed outward.
                for (int k = 0; k < n; k++) {
                    PendingQuad q = pending.get(k);
                    if (gid[k] == r && !(q.cutout || q.tinted)) {
                        if (rank > 0) {
                            offset(q, OFFSET * rank);
                        }
                        rank++;
                    }
                }
                for (int k = 0; k < n; k++) {
                    PendingQuad q = pending.get(k);
                    if (gid[k] == r && (q.cutout || q.tinted)) {
                        if (rank > 0) {
                            offset(q, OFFSET * rank);
                        }
                        rank++;
                    }
                }
            }
        }

        /** True if every corner of {@code a} coincides with a corner of {@code b} (same quad, any winding). */
        private static boolean coincident(PendingQuad a, PendingQuad b) {
            for (int k = 0; k < 4; k++) {
                boolean found = false;
                for (int m = 0; m < 4; m++) {
                    if (Math.abs(a.x[k] - b.x[m]) < COINCIDENT_EPS
                            && Math.abs(a.y[k] - b.y[m]) < COINCIDENT_EPS
                            && Math.abs(a.z[k] - b.z[m]) < COINCIDENT_EPS) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }

        /** Shift all four of a quad's corners by {@code d} along its (outward) normal. */
        private static void offset(PendingQuad q, float d) {
            for (int v = 0; v < 4; v++) {
                q.x[v] += q.nx * d;
                q.y[v] += q.ny * d;
                q.z[v] += q.nz * d;
            }
        }

        /** Emit an ordinary immutable terrain quad. POM changes only shader UV lookup. */
        private void queueOrEmit(PendingQuad q) {
            // Recess translucent (glass / ice) faces slightly into their own block. Vanilla culls a glass
            // face that touches a full solid block, but KEEPS the one touching a non-occluding neighbour
            // (slabs / stairs) — which lands exactly coplanar with that neighbour's face and z-fights. A tiny
            // inward inset makes the glass resolve consistently behind the neighbour's surface.
            if (q.translucent) {
                offset(q, -TRANSLUCENT_INSET);
            }
            Geom g = q.translucent ? cur.translucent() : (q.cutout ? cur.cutout() : cur.opaque());
            emitFlat(g, q);
        }

        /** Emit one ordinary resolved quad (2 triangles, corner UVs and per-primitive records). */
        private static void emitFlat(Geom g, PendingQuad q) {
            int base = g.verts.size() / 3;
            for (int k = 0; k < 4; k++) {
                g.verts.add(q.x[k]);
                g.verts.add(q.y[k]);
                g.verts.add(q.z[k]);
            }
            IntArrayList idx = g.idx;
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
            // Per-triangle corner UVs (primitive order matching the two triangles: 0,1,2 then 0,2,3).
            addTriUv(g, q.uv[0], q.uv[1], q.uv[2]);
            addTriUv(g, q.uv[0], q.uv[2], q.uv[3]);
            for (int t = 0; t < 2; t++) {
                addPrim(g, q, q.nx, q.ny, q.nz, 0);
            }
        }

        /**
         * Emit one flat, physically displaced square per height texel. Adjacent squares with different
         * heights are joined by vertical walls, producing a closed pixel/voxel relief rather than a
         * smoothly triangulated height field. Each square is two coplanar GPU triangles (unavoidable in
         * a triangle BLAS), but they share one plane and normal so the diagonal is not visible.
         */
        private void emitDisplaced(Geom g, PendingQuad q, RtBlockMaterials.HeightField field,
                                   float depth, int quality, boolean smoothing) {
            // Texture resolution owns the square count. There is no automatic topology/memory downgrade:
            // every eligible face uses the exact configured upper resolution.
            long dimensions = configureDisplacedGrid(q, field, quality);
            int cellsX = (int) (dimensions >>> 32);
            int cellsY = (int) dimensions;
            float[] qu = displacedUScratch;
            float[] qv = displacedVScratch;
            float[] localU = displacedLocalUScratch;
            float[] localV = displacedLocalVScratch;
            int cellCount = cellsX * cellsY;
            float[] heights = heightScratch.length >= cellCount
                    ? heightScratch : (heightScratch = new float[cellCount]);

            float effectiveDepth = resolutionScaledDepth(depth, field.width(), field.height(), 1.0f, 1.0f);

            // Estimate one cell's local-UV footprint. HeightField retains authored data up to 512x512,
            // and this selects an area-filtered semantic mip only when the user explicitly requests a
            // coarser mesh than the source texture.
            float duDx = 0.5f * ((localU[1] - localU[0]) + (localU[2] - localU[3])) / cellsX;
            float dvDx = 0.5f * ((localV[1] - localV[0]) + (localV[2] - localV[3])) / cellsX;
            float duDy = 0.5f * ((localU[3] - localU[0]) + (localU[2] - localU[1])) / cellsY;
            float dvDy = 0.5f * ((localV[3] - localV[0]) + (localV[2] - localV[1])) / cellsY;
            float heightLod = field.meshLod(Math.abs(duDx) + Math.abs(duDy),
                    Math.abs(dvDx) + Math.abs(dvDy));
            for (int y = 0; y < cellsY; y++) {
                float v = (y + 0.5f) / cellsY;
                for (int x = 0; x < cellsX; x++) {
                    float u = (x + 0.5f) / cellsX;
                    float sampleU = bilerp(localU, u, v);
                    float sampleV = bilerp(localV, u, v);
                    heights[y * cellsX + x] = field.displacementLod(
                            sampleU, sampleV, heightLod, smoothing) * effectiveDepth;
                }
            }

            if (cellsX == 1 && cellsY == 1) {
                float h = heights[0];
                addMicroQuad(g, q, qu, qv,
                        0.0f, 0.0f, h, 1.0f, 0.0f, h,
                        1.0f, 1.0f, h, 0.0f, 1.0f, h, true, false);
                return;
            }

            // The lowest authored height is anchored to the original block face. The backing plane
            // therefore seals the block at its real boundary instead of sitting half a relief-depth
            // inside it (the old recessed backing became the large false mirror/void rectangles).
            float baseHeight = -Math.max(1.0e-7f, effectiveDepth / 2048.0f);
            // Preserve a one-byte LabPBR height transition while ignoring only float noise. This keeps
            // the texel columns closed without inventing walls between numerically identical samples.
            final float wallEpsilon = Math.max(1.0e-8f, effectiveDepth / 510.0f);

            addMicroQuad(g, q, qu, qv,
                    0.0f, 0.0f, baseHeight, 1.0f, 0.0f, baseHeight,
                    1.0f, 1.0f, baseHeight, 0.0f, 1.0f, baseHeight, true, true);
            for (int y = 0; y < cellsY; y++) {
                float v0 = y / (float) cellsY;
                float v1 = (y + 1) / (float) cellsY;
                for (int x = 0; x < cellsX; x++) {
                    float u0 = x / (float) cellsX;
                    float u1 = (x + 1) / (float) cellsX;
                    float h = heights[y * cellsX + x];

                    // Flat top: visually one protruding square, despite its two coplanar BLAS triangles.
                    addMicroQuad(g, q, qu, qv,
                            u0, v0, h, u1, v0, h, u1, v1, h, u0, v1, h, true, false);

                    if (x + 1 < cellsX) {
                        float right = heights[y * cellsX + x + 1];
                        if (Math.abs(h - right) > wallEpsilon) {
                            addMicroQuad(g, q, qu, qv,
                                    u1, v0, h, u1, v1, h,
                                    u1, v1, right, u1, v0, right, false, true);
                        }
                    } else if (Math.abs(h - baseHeight) > wallEpsilon) {
                        addMicroQuad(g, q, qu, qv,
                                u1, v0, h, u1, v1, h,
                                u1, v1, baseHeight, u1, v0, baseHeight, false, true);
                    }
                    if (x == 0 && Math.abs(h - baseHeight) > wallEpsilon) {
                        addMicroQuad(g, q, qu, qv,
                                u0, v1, h, u0, v0, h,
                                u0, v0, baseHeight, u0, v1, baseHeight, false, true);
                    }

                    if (y + 1 < cellsY) {
                        float below = heights[(y + 1) * cellsX + x];
                        if (Math.abs(h - below) > wallEpsilon) {
                            addMicroQuad(g, q, qu, qv,
                                    u1, v1, h, u0, v1, h,
                                    u0, v1, below, u1, v1, below, false, true);
                        }
                    } else if (Math.abs(h - baseHeight) > wallEpsilon) {
                        addMicroQuad(g, q, qu, qv,
                                u1, v1, h, u0, v1, h,
                                u0, v1, baseHeight, u1, v1, baseHeight, false, true);
                    }
                    if (y == 0 && Math.abs(h - baseHeight) > wallEpsilon) {
                        addMicroQuad(g, q, qu, qv,
                                u0, v0, h, u1, v0, h,
                                u1, v0, baseHeight, u0, v0, baseHeight, false, true);
                    }
                }
            }
        }

        /**
         * Populate the reusable atlas/local-UV arrays and return the exact emitted cell dimensions packed
         * as {@code cellsX << 32 | cellsY}. Used by both section preflight and the emitter, preventing a
         * disagreement that could reintroduce traversal-order fallback.
         */
        private long configureDisplacedGrid(PendingQuad q, RtBlockMaterials.HeightField field, int quality) {
            int resolutionCap = Math.clamp(quality, 1, 512);
            float[] qu = displacedUScratch;
            float[] qv = displacedVScratch;
            float[] localU = displacedLocalUScratch;
            float[] localV = displacedLocalVScratch;
            float invDu = 1.0f / Math.max(1.0e-12f, q.sprite.getU1() - q.sprite.getU0());
            float invDv = 1.0f / Math.max(1.0e-12f, q.sprite.getV1() - q.sprite.getV0());
            for (int i = 0; i < 4; i++) {
                qu[i] = Float.intBitsToFloat((int) (q.uv[i] >>> 32));
                qv[i] = Float.intBitsToFloat((int) q.uv[i]);
                localU[i] = Math.max(0.0f, Math.min(1.0f,
                        (qu[i] - q.sprite.getU0()) * invDu));
                localV[i] = Math.max(0.0f, Math.min(1.0f,
                        (qv[i] - q.sprite.getV0()) * invDv));
            }

            float sourceTexelsX = 0.5f * (texelDistance(localU[0], localV[0], localU[1], localV[1],
                    field.width(), field.height())
                    + texelDistance(localU[3], localV[3], localU[2], localV[2],
                    field.width(), field.height()));
            float sourceTexelsY = 0.5f * (texelDistance(localU[0], localV[0], localU[3], localV[3],
                    field.width(), field.height())
                    + texelDistance(localU[1], localV[1], localU[2], localV[2],
                    field.width(), field.height()));
            int cellsX = Math.max(1, Math.min(resolutionCap, Math.round(sourceTexelsX)));
            int cellsY = Math.max(1, Math.min(resolutionCap, Math.round(sourceTexelsY)));
            return ((long) cellsX << 32) | (cellsY & 0xFFFFFFFFL);
        }

        private static float texelDistance(float u0, float v0, float u1, float v1,
                                           int textureWidth, int textureHeight) {
            float du = (u1 - u0) * textureWidth;
            float dv = (v1 - v0) * textureHeight;
            return (float) Math.sqrt(du * du + dv * dv);
        }

        private static void addMicroQuad(Geom g, PendingQuad q, float[] qu, float[] qv,
                                         float u0, float v0, float h0,
                                         float u1, float v1, float h1,
                                         float u2, float v2, float h2,
                                         float u3, float v3, float h3,
                                         boolean top, boolean closure) {
            float p0x = bilerp(q.x, u0, v0) + q.nx * h0;
            float p0y = bilerp(q.y, u0, v0) + q.ny * h0;
            float p0z = bilerp(q.z, u0, v0) + q.nz * h0;
            float p1x = bilerp(q.x, u1, v1) + q.nx * h1;
            float p1y = bilerp(q.y, u1, v1) + q.ny * h1;
            float p1z = bilerp(q.z, u1, v1) + q.nz * h1;
            float p2x = bilerp(q.x, u2, v2) + q.nx * h2;
            float p2y = bilerp(q.y, u2, v2) + q.ny * h2;
            float p2z = bilerp(q.z, u2, v2) + q.nz * h2;
            float p3x = bilerp(q.x, u3, v3) + q.nx * h3;
            float p3y = bilerp(q.y, u3, v3) + q.ny * h3;
            float p3z = bilerp(q.z, u3, v3) + q.nz * h3;

            float nx = q.nx, ny = q.ny, nz = q.nz;
            if (!top) {
                float e1x = p1x - p0x, e1y = p1y - p0y, e1z = p1z - p0z;
                float e2x = p2x - p0x, e2y = p2y - p0y, e2z = p2z - p0z;
                nx = e1y * e2z - e1z * e2y;
                ny = e1z * e2x - e1x * e2z;
                nz = e1x * e2y - e1y * e2x;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length <= 1.0e-8f) {
                    return;
                }
                nx /= length;
                ny /= length;
                nz /= length;
            }

            int base = g.verts.size() / 3;
            g.verts.add(p0x); g.verts.add(p0y); g.verts.add(p0z);
            g.verts.add(p1x); g.verts.add(p1y); g.verts.add(p1z);
            g.verts.add(p2x); g.verts.add(p2y); g.verts.add(p2z);
            g.verts.add(p3x); g.verts.add(p3y); g.verts.add(p3z);
            g.idx.add(base); g.idx.add(base + 1); g.idx.add(base + 2);
            g.idx.add(base); g.idx.add(base + 2); g.idx.add(base + 3);

            float t0u = bilerp(qu, u0, v0), t0v = bilerp(qv, u0, v0);
            float t1u = bilerp(qu, u1, v1), t1v = bilerp(qv, u1, v1);
            float t2u = bilerp(qu, u2, v2), t2v = bilerp(qv, u2, v2);
            float t3u = bilerp(qu, u3, v3), t3v = bilerp(qv, u3, v3);
            addTriUv(g, t0u, t0v, t1u, t1v, t2u, t2v);
            addTriUv(g, t0u, t0v, t2u, t2v, t3u, t3v);
            int flags = PRIM_FLAG_MESH_DISPLACED | (closure ? PRIM_FLAG_MESH_CLOSURE : 0);
            addPrim(g, q, nx, ny, nz, flags);
            addPrim(g, q, nx, ny, nz, flags);
        }

        private static void addPrim(Geom g, PendingQuad q, float nx, float ny, float nz, int flags) {
            FloatArrayList prim = g.prim;
            prim.add(nx);
            prim.add(ny);
            prim.add(nz);
            // normal.w = block-light emission (0..1) + a +2 flag for non-SOLID layers, so the closest
            // hit can opt SOLID terrain out of SSS (leaves/foliage keep it). See world.rchit.
            prim.add(q.cutout ? q.emission + 2f : q.emission);
            prim.add(q.tr);
            prim.add(q.tg);
            prim.add(q.tb);
            prim.add(0f);
            prim.add(Float.intBitsToFloat(q.materialId));
            prim.add(Float.intBitsToFloat(flags));
            prim.add(0f);
            prim.add(0f);
            g.ommSprites.add(q.sprite);
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static float bilerp(float[] values, float u, float v) {
            return lerp(lerp(values[0], values[1], u), lerp(values[3], values[2], u), v);
        }
    }

    /** One block's buffered quad, awaiting coplanar resolution before it is emitted into a section bucket. */
    private static final class PendingQuad {
        final float[] x = new float[4], y = new float[4], z = new float[4];
        final long[] uv = new long[4];
        float nx, ny, nz;
        boolean cutout; // non-SOLID render layer (alpha-tested) — also an overlay candidate
        boolean translucent; // TRANSLUCENT layer (stained glass / ice): colored-transmission dielectric
        boolean tinted; // tintIndex >= 0 — the tinted member of a base+overlay pair
        float tr, tg, tb, emission;
        int materialId;
        TextureAtlasSprite sprite;

        void copyFrom(PendingQuad source) {
            System.arraycopy(source.x, 0, x, 0, 4);
            System.arraycopy(source.y, 0, y, 0, 4);
            System.arraycopy(source.z, 0, z, 0, 4);
            System.arraycopy(source.uv, 0, uv, 0, 4);
            nx = source.nx;
            ny = source.ny;
            nz = source.nz;
            cutout = source.cutout;
            translucent = source.translucent;
            tinted = source.tinted;
            tr = source.tr;
            tg = source.tg;
            tb = source.tb;
            emission = source.emission;
            materialId = source.materialId;
            sprite = source.sprite;
        }

        void releaseReferences() {
            sprite = null;
        }
    }

    /** Append one triangle's 3 corner UVs (6 floats) from packed UVPairs. UVPair packs u in the high 32
     *  bits, v in the low 32 (atlas-space, no sprite remap needed). */
    private static void addTriUv(Geom g, long pa, long pb, long pc) {
        FloatArrayList c = g.cornerUv;
        c.add(Float.intBitsToFloat((int) (pa >>> 32)));
        c.add(Float.intBitsToFloat((int) pa));
        c.add(Float.intBitsToFloat((int) (pb >>> 32)));
        c.add(Float.intBitsToFloat((int) pb));
        c.add(Float.intBitsToFloat((int) (pc >>> 32)));
        c.add(Float.intBitsToFloat((int) pc));
    }

    /** Append one triangle's 3 corner UVs (6 floats) from float u,v pairs (fluid path). */
    private static void addTriUv(Geom g, float ua, float va, float ub, float vb, float uc, float vc) {
        FloatArrayList c = g.cornerUv;
        c.add(ua);
        c.add(va);
        c.add(ub);
        c.add(vb);
        c.add(uc);
        c.add(vc);
    }

    /**
     * Captures the quads {@link FluidRenderer} emits (water/lava) into the current section's mesh. It
     * is both the {@link FluidRenderer.Output} and the {@link VertexConsumer} it hands back. Vertices
     * arrive in groups of 4 (one quad) via the bulk {@code addVertex}; we keep position + atlas UV,
     * compute a geometric normal (sign is irrelevant — the closest-hit flips it toward the viewer), and
     * emit two triangles like {@link QuadCapture}. Coords are already section-local (FluidRenderer uses
     * {@code pos & 15}). The cardinal-lit vertex colour is dropped — albedo comes from the atlas in the
     * hit shader, same as blocks. Tint is left white (biome water tint is a deferred item).
     *
     * <p>Water faces are tagged in the per-prim {@code tint.w} slot ({@code 1.0} = water) so the path
     * tracer treats them as a smooth dielectric (Fresnel reflection + refraction + Beer–Lambert
     * absorption). Lava keeps {@code tint.w == 0.0} and stays an opaque emitter.
     */
    private static final class FluidCapture implements VertexConsumer, FluidRenderer.Output {
        SectionMesh cur;     // set before each section
        RtMaterialRegistry.Snapshot materials;
        float emission;      // set per fluid block (lava = 1, water = 0)
        boolean water;       // set per fluid block: true for water (dielectric), false for lava
        private int n;
        private final float[] qx = new float[4], qy = new float[4], qz = new float[4], qu = new float[4], qv = new float[4];
        private final int[] qc = new int[4]; // per-vertex packed ARGB (vanilla bakes the biome water tint here)

        /** Reset per-job assembly state (a mid-quad meshing throw could leave a partial quad buffered). */
        void reset() {
            n = 0;
        }

        @Override
        public VertexConsumer getBuilder(ChunkSectionLayer layer) {
            return this; // one capturing builder regardless of the fluid's render layer
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v,
                              int overlay, int light, float nx, float ny, float nz) {
            qx[n] = x; qy[n] = y; qz[n] = z; qu[n] = u; qv[n] = v; qc[n] = color;
            if (++n == 4) {
                emitQuad();
                n = 0;
            }
        }

        private void emitQuad() {
            // Water gets its own geometry → water bucket: its any-hit only passes shadow rays through (the
            // closest-hit does the dielectric), classified by geometry index with no memory load. Lava is an
            // opaque emitter → opaque bucket (no any-hit at all).
            Geom g = water ? cur.water() : cur.opaque();
            FloatArrayList verts = g.verts;
            IntArrayList idx = g.idx;
            int base = verts.size() / 3;
            for (int i = 0; i < 4; i++) {
                verts.add(qx[i]);
                verts.add(qy[i]);
                verts.add(qz[i]);
            }
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
            // Per-triangle corner UVs (primitive order: 0,1,2 then 0,2,3), matching the two triangles above.
            addTriUv(g, qu[0], qv[0], qu[1], qv[1], qu[2], qv[2]);
            addTriUv(g, qu[0], qv[0], qu[2], qv[2], qu[3], qv[3]);

            float ex1 = qx[1] - qx[0], ey1 = qy[1] - qy[0], ez1 = qz[1] - qz[0];
            float ex2 = qx[2] - qx[0], ey2 = qy[2] - qy[0], ez2 = qz[2] - qz[0];
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            int materialId = water ? materials.waterId() : materials.lavaId();
            // Biome water tint: vanilla's FluidRenderer bakes BiomeColors.getAverageWaterColor into the
            // per-vertex colour, so the average of the quad's four colours is this water body's tint. The
            // path tracer turns it into a per-channel Beer–Lambert extinction (ocean blue vs swamp green).
            // Lava keeps a white tint (its colour rides the emission channel, not absorption).
            float tr = 1f, tg = 1f, tb = 1f;
            if (water) {
                int sr = 0, sg = 0, sb = 0;
                for (int i = 0; i < 4; i++) {
                    sr += (qc[i] >> 16) & 0xFF;
                    sg += (qc[i] >> 8) & 0xFF;
                    sb += qc[i] & 0xFF;
                }
                tr = sr / 1020f; // 4 vertices * 255
                tg = sg / 1020f;
                tb = sb / 1020f;
            }
            FloatArrayList prim = g.prim;
            for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
                prim.add(nx);
                prim.add(ny);
                prim.add(nz);
                prim.add(emission);
                prim.add(tr);
                prim.add(tg);
                prim.add(tb);
                prim.add(0f);
                prim.add(Float.intBitsToFloat(materialId));
                prim.add(0f);
                prim.add(0f);
                prim.add(0f);
                g.ommSprites.add(null);
            }
        }

        // Unused VertexConsumer surface — FluidRenderer only calls the bulk addVertex above.
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

}
