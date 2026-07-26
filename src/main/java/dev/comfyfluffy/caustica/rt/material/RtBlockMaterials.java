package dev.comfyfluffy.caustica.rt.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.mixin.TextureAtlasAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Compiles block and entity LabPBR inputs into canonical pages with explicit semantic mip chains. */
public final class RtBlockMaterials {
    public static final RtBlockMaterials INSTANCE = new RtBlockMaterials();

    // Entry.features uses the shared MaterialHeader feature bits (RtMaterialRegistry.FEATURE_*).
    // FEATURE_OVERRIDE_EMISSION here means "override mask baked into surface1.a for this candidate".

    private static final int DEFAULT_PAGE_SIZE = 2048;
    private static final int MAX_PAGE_SIZE = 8192;
    private static final int GUTTER = 8;
    private static final int MAX_VALID_LOD = 3;
    private static final int PACK_ALIGNMENT = 1 << MAX_VALID_LOD;

    private final Map<TextureAtlasSprite, Entry> entries = new IdentityHashMap<>();
    // Logical full-texture/sprite name (entity/zombie/zombie or entity/chest/normal) to canonical page
    // mapping. Unlike block entries, albedo UVs are filled by
    // the material-table registry: full textures use [0,1], atlas sprites append their actual atlas rect.
    private final Map<Identifier, Entry> resourceEntries = new HashMap<>();
    private final List<Page> pages = new ArrayList<>();
    private Entry fallback;
    private boolean loggedFailure;

    private RtBlockMaterials() {
    }

    /** Immutable per-sprite page mapping consumed by the material registry. */
    public record Entry(int features, int pageIndex, int maxLod,
                        float materialU, float materialV, float materialDu, float materialDv,
                        float albedoU, float albedoV, float albedoInvDu, float albedoInvDv,
                        RtMaterialDesc.EmissionSummary emissionSummary,
                        RtMaterialDesc.EmissionSummary overrideEmissionSummary) {
    }

    private record Page(RtMaterialPageTexture surface0, RtMaterialPageTexture normalAo,
                        RtMaterialPageTexture surface1, int index) {
        void destroy() {
            surface0.destroy();
            normalAo.destroy();
            surface1.destroy();
        }
    }

    private static final class Candidate {
        final TextureAtlasSprite sprite;
        final Identifier name;
        final Identifier albedoLocation;
        final int features;
        final Identifier specLocation;
        final Identifier normalLocation;
        final int width;
        final int height;
        int page;
        int x;
        int y;
        RtMaterialDesc.EmissionSummary emissionSummary = RtMaterialDesc.EmissionSummary.NONE;
        RtMaterialDesc.EmissionSummary overrideEmissionSummary = RtMaterialDesc.EmissionSummary.NONE;

        Candidate(TextureAtlasSprite sprite, Identifier name, Identifier albedoLocation, int features,
                  Identifier specLocation, Identifier normalLocation, int width, int height) {
            this.sprite = sprite;
            this.name = name;
            this.albedoLocation = albedoLocation;
            this.features = features;
            this.specLocation = specLocation;
            this.normalLocation = normalLocation;
            this.width = width;
            this.height = height;
        }

        boolean blockSprite() {
            return sprite != null;
        }
    }

    private static final class LayoutPage {
        final int size;
        int x;
        int y;
        int rowHeight;

        LayoutPage(int size, boolean reserveFallback) {
            this.size = size;
            if (reserveFallback) y = align(1 + 2 * GUTTER, PACK_ALIGNMENT);
        }

        boolean place(Candidate candidate) {
            int cellWidth = align(candidate.width + 2 * GUTTER, PACK_ALIGNMENT);
            int cellHeight = align(candidate.height + 2 * GUTTER, PACK_ALIGNMENT);
            if (cellWidth > size || cellHeight > size) return false;
            if (x + cellWidth > size) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + cellHeight > size) return false;
            candidate.x = x + GUTTER;
            candidate.y = y + GUTTER;
            x += cellWidth;
            rowHeight = Math.max(rowHeight, cellHeight);
            return true;
        }
    }

    /** Drop the previous epoch's CPU mappings and GPU pages. Caller owns the idle/reload boundary. */
    public void reset() {
        entries.clear();
        resourceEntries.clear();
        fallback = null;
        for (Page page : pages) page.destroy();
        pages.clear();
    }

    /** Compile, pack, mip, upload, and publish block-atlas plus authored entity material pages. */
    public void prepareAll(RtContext ctx, int materialPageCapacity, RtEmissionSemantics emissionSemantics,
                           RtMaterialOverrides overrides) {
        List<TextureAtlasSprite> sprites = blockSprites();
        List<Candidate> authored = new ArrayList<>();
        int specCount = 0;
        int normalCount = 0;
        int heuristicCount = 0;
        int overrideMaskCount = 0;
        int largest = 1 + 2 * GUTTER;
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite == null) continue;
            Identifier name = sprite.contents().name();
            Identifier spec = sibling(name, "_s.png");
            Identifier normal = sibling(name, "_n.png");
            int features = 0;
            if (resourceExists(spec)) {
                features |= RtMaterialRegistry.FEATURE_SPEC;
                specCount++;
            }
            if (resourceExists(normal)) {
                features |= RtMaterialRegistry.FEATURE_NORMAL;
                normalCount++;
            }
            // Authored LabPBR owns emission whenever _s exists. Albedo inference is only compiled for
            // sprites proven to occur on an emitting block state.
            if ((features & RtMaterialRegistry.FEATURE_SPEC) == 0 && emissionSemantics.permits(sprite)) {
                features |= RtMaterialRegistry.FEATURE_HEURISTIC_EMISSION;
                heuristicCount++;
            }
            if (overrides.requestsEmissionMask(sprite)) {
                features |= RtMaterialRegistry.FEATURE_OVERRIDE_EMISSION;
                overrideMaskCount++;
            }
            if (features != 0) {
                int width = sprite.contents().width();
                int height = sprite.contents().height();
                largest = Math.max(largest, Math.max(width, height) + 2 * GUTTER);
                authored.add(new Candidate(sprite, name, null, features, spec, normal, width, height));
            }
        }
        Map<Identifier, Integer> resourceFeatures = discoverEntityMaterialResources(sprites, overrides);
        for (Map.Entry<Identifier, Integer> discovered : resourceFeatures.entrySet()) {
            Identifier albedo = discovered.getKey();
            int features = discovered.getValue();
            try (NativeImage image = load(albedo)) {
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) continue;
                Identifier name = logicalTextureName(albedo);
                int width = image.getWidth();
                int height = image.getHeight();
                Identifier spec = siblingTexture(albedo, "_s");
                Identifier normal = siblingTexture(albedo, "_n");
                largest = Math.max(largest, Math.max(width, height) + 2 * GUTTER);
                authored.add(new Candidate(null, name, albedo, features, spec, normal, width, height));
                if ((features & RtMaterialRegistry.FEATURE_SPEC) != 0) specCount++;
                if ((features & RtMaterialRegistry.FEATURE_NORMAL) != 0) normalCount++;
                if ((features & RtMaterialRegistry.FEATURE_OVERRIDE_EMISSION) != 0) overrideMaskCount++;
            } catch (Throwable t) {
                warnOnce("RT entity material albedo load failed for " + albedo, t);
            }
        }

        int pageSize = authored.isEmpty() ? 32 : Math.max(DEFAULT_PAGE_SIZE, nextPowerOfTwo(largest));
        if (pageSize > MAX_PAGE_SIZE) {
            CausticaMod.LOGGER.warn("RT material sprite exceeds canonical page limit ({} > {}); oversized maps use neutral fallback",
                    pageSize, MAX_PAGE_SIZE);
            pageSize = MAX_PAGE_SIZE;
            authored.removeIf(candidate -> candidate.width + 2 * GUTTER > MAX_PAGE_SIZE
                    || candidate.height + 2 * GUTTER > MAX_PAGE_SIZE);
            if (authored.isEmpty()) pageSize = 32;
        }
        authored.sort(Comparator.<Candidate>comparingInt(candidate -> candidate.height).reversed()
                .thenComparing(Comparator.comparingInt((Candidate candidate) -> candidate.width).reversed())
                .thenComparing(candidate -> candidate.name.toString()));

        List<LayoutPage> layouts = new ArrayList<>();
        layouts.add(new LayoutPage(pageSize, true));
        for (Candidate candidate : authored) {
            boolean placed = false;
            for (int i = 0; i < layouts.size(); i++) {
                if (layouts.get(i).place(candidate)) {
                    candidate.page = i;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                LayoutPage page = new LayoutPage(pageSize, false);
                if (!page.place(candidate)) continue;
                candidate.page = layouts.size();
                layouts.add(page);
            }
        }
        if (layouts.size() > materialPageCapacity) {
            throw new IllegalStateException("RT material pages require " + layouts.size()
                    + " descriptor slots but capacity is " + materialPageCapacity);
        }

        int mipCount = Integer.numberOfTrailingZeros(pageSize) + 1;
        PagePixels[] pagePixels = new PagePixels[layouts.size()];
        for (int pageIndex = 0; pageIndex < layouts.size(); pageIndex++) {
            pagePixels[pageIndex] = new PagePixels(pageSize, mipCount);
        }
        pagePixels[0].writeFallback();
        // Decode + blit in parallel: each candidate owns a disjoint (aligned, gutter-inclusive) cell of
        // its page, so writes never overlap; failures only touch the candidate's own fields.
        authored.parallelStream().forEach(candidate -> {
            try {
                Decoded decoded = decode(candidate);
                candidate.emissionSummary = decoded.emissionSummary();
                candidate.overrideEmissionSummary = decoded.overrideEmissionSummary();
                pagePixels[candidate.page].write(candidate, decoded.levels());
            } catch (Throwable t) {
                warnOnce("RT canonical material decode failed for " + candidate.name, t);
                candidate.page = -1;
            }
        });
        for (int pageIndex = 0; pageIndex < layouts.size(); pageIndex++) {
            PagePixels pixels = pagePixels[pageIndex];
            pages.add(new Page(
                    new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface0,
                            "material surface0 page " + pageIndex),
                    new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.normalAo,
                            "material normalAo page " + pageIndex),
                    new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface1,
                            "material surface1 page " + pageIndex),
                    pageIndex));
        }

        float fallbackUv = GUTTER / (float) pageSize;
        fallback = new Entry(0, 0, 0, fallbackUv, fallbackUv,
                1.0f / pageSize, 1.0f / pageSize, 0, 0, 1, 1,
                RtMaterialDesc.EmissionSummary.NONE, RtMaterialDesc.EmissionSummary.NONE);
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite != null) entries.put(sprite, fallbackFor(sprite));
        }
        for (Candidate candidate : authored) {
            if (candidate.page < 0) continue;
            int maxLod = maxLodFor(candidate.width, candidate.height);
            Entry entry = new Entry(candidate.features, candidate.page, maxLod,
                    candidate.x / (float) pageSize, candidate.y / (float) pageSize,
                    candidate.width / (float) pageSize, candidate.height / (float) pageSize,
                    candidate.blockSprite() ? candidate.sprite.getU0() : 0.0f,
                    candidate.blockSprite() ? candidate.sprite.getV0() : 0.0f,
                    candidate.blockSprite() ? inverseExtent(candidate.sprite.getU1() - candidate.sprite.getU0()) : 1.0f,
                    candidate.blockSprite() ? inverseExtent(candidate.sprite.getV1() - candidate.sprite.getV0()) : 1.0f,
                    candidate.emissionSummary, candidate.overrideEmissionSummary);
            if (candidate.blockSprite()) entries.put(candidate.sprite, entry);
            else resourceEntries.put(candidate.name, entry);
        }

        long bytesPerBundle = 0L;
        int w = pageSize;
        for (int mip = 0; mip < mipCount; mip++) {
            bytesPerBundle += (long) w * w * 4L;
            w = Math.max(1, w / 2);
        }
        CausticaMod.LOGGER.info("RT canonical material pages: blockSprites={}, entityResources={}, spec={}, normal={}, heuristicEmission={}, overrideMasks={}, pages={}, size={}x{}, validLod<={}, gpuMiB={}",
                sprites.size(), resourceEntries.size(), specCount, normalCount, heuristicCount, overrideMaskCount,
                pages.size(), pageSize, pageSize, MAX_VALID_LOD,
                String.format(java.util.Locale.ROOT, "%.2f", bytesPerBundle * pages.size() * 3.0 / (1024.0 * 1024.0)));
    }

    public void bindPages(RtPipeline pipeline, long sampler) {
        for (Page page : pages) {
            pipeline.setMaterialPage(page.index(), page.surface0().view(), page.normalAo().view(),
                    page.surface1().view(), sampler);
        }
    }

    public Entry entry(TextureAtlasSprite sprite) {
        return entries.getOrDefault(sprite, fallback);
    }

    /**
     * Legacy mesh-displacement hook. POM samples the canonical GPU normal/AO/height page directly, so
     * resource reload no longer duplicates every high-resolution height map in Java heap.
     */
    public HeightField heightField(TextureAtlasSprite sprite) {
        return null;
    }

    public static final class HeightField {
        private static final int MESH_BASE_RESOLUTION = 512;

        private final int width;
        private final int height;
        private final float[] samples;
        private final float minimum;
        private volatile MeshMipChain meshMipChain;

        private HeightField(int width, int height, float[] samples, float min, float max) {
            this.width = width;
            this.height = height;
            this.samples = samples;
            this.minimum = min;
        }

        static HeightField create(int width, int height, float[] samples) {
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            for (float sample : samples) {
                min = Math.min(min, sample);
                max = Math.max(max, sample);
            }
            // Opaque/default alpha and one-byte compression noise are not authored height fields.
            if (!(max - min > 2.0f / 255.0f)) {
                return null;
            }
            return new HeightField(width, height, samples, min, max);
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /**
         * Sprite-local sample using the same normalized-coordinate convention as the GPU sampler:
         * texel centres are at {@code (i + 0.5) / size}. The former {@code u * (size - 1)} mapping
         * compressed and shifted the CPU height field relative to the albedo/normal texture.
         */
        public float sample(float u, float v, boolean smoothing) {
            u = Math.max(0.0f, Math.min(1.0f, u));
            v = Math.max(0.0f, Math.min(1.0f, v));
            if (!smoothing) {
                int x = Math.max(0, Math.min(width - 1, (int) Math.floor(u * width)));
                int y = Math.max(0, Math.min(height - 1, (int) Math.floor(v * height)));
                return samples[y * width + x];
            }
            float fx = u * width - 0.5f;
            float fy = v * height - 0.5f;
            int ux0 = (int) Math.floor(fx);
            int uy0 = (int) Math.floor(fy);
            int x0 = Math.max(0, Math.min(width - 1, ux0));
            int y0 = Math.max(0, Math.min(height - 1, uy0));
            int x1 = Math.min(width - 1, x0 + 1);
            int y1 = Math.min(height - 1, y0 + 1);
            float tx = Math.max(0.0f, Math.min(1.0f, fx - ux0));
            float ty = Math.max(0.0f, Math.min(1.0f, fy - uy0));
            if (ux0 < 0 || ux0 >= width - 1) tx = 0.0f;
            if (uy0 < 0 || uy0 >= height - 1) ty = 0.0f;
            float h00 = samples[y0 * width + x0];
            float h10 = samples[y0 * width + x1];
            float h01 = samples[y1 * width + x0];
            float h11 = samples[y1 * width + x1];
            return (h00 + (h10 - h00) * tx)
                    + ((h01 + (h11 - h01) * tx) - (h00 + (h10 - h00) * tx)) * ty;
        }

        /**
         * Outward material-relative relief in [0, 1]. Preserve the authored LabPBR amplitude:
         * high-resolution packs commonly use only a narrow alpha interval (for example 238..255) for
         * subtle relief. Normalising that interval to the full range amplifies texture noise by 15x and
         * makes a 256x surface explode into unrelated blocks. Subtracting only the authored minimum
         * anchors the lowest texel on the original block face; brighter texels physically protrude
         * without opening a recessed cavity behind the relief.
         */
        public float displacement(float u, float v, boolean smoothing) {
            return Math.max(0.0f, Math.min(1.0f,
                    sample(u, v, smoothing) - minimum));
        }

        /**
         * Select the CPU height-map mip matching one physical mesh cell. Level zero retains up to 512x512
         * authored texels; lower requested mesh resolutions select area-filtered levels instead of arbitrary
         * point samples. The chain is built lazily only for sprites that enter the nearby real-geometry LOD.
         */
        public float meshLod(float footprintU, float footprintV) {
            MeshMipChain chain = meshMipChain();
            float texelFootprint = Math.max(
                    Math.abs(footprintU) * chain.widths[0],
                    Math.abs(footprintV) * chain.heights[0]);
            if (!(texelFootprint > 1.0f)) {
                return 0.0f;
            }
            float lod = (float) (Math.log(texelFootprint) / Math.log(2.0));
            return Math.max(0.0f, Math.min(chain.levels.length - 1.0f, lod));
        }

        /**
         * Sample the anti-aliased mesh representation at an explicitly selected LOD. Spatial filtering
         * still follows the smoothing switch; interpolation between mip levels is always retained because
         * it is footprint integration, not smoothing between the emitted flat relief squares.
         */
        public float displacementLod(float u, float v, float lod, boolean smoothing) {
            MeshMipChain chain = meshMipChain();
            float clampedLod = Math.max(0.0f, Math.min(chain.levels.length - 1.0f, lod));
            int lo = (int) Math.floor(clampedLod);
            int hi = Math.min(chain.levels.length - 1, lo + 1);
            float t = clampedLod - lo;
            float a = sampleLevel(chain.levels[lo], chain.widths[lo], chain.heights[lo], u, v, smoothing);
            float filtered = a;
            if (hi != lo && t > 1.0e-5f) {
                float b = sampleLevel(chain.levels[hi], chain.widths[hi], chain.heights[hi], u, v, smoothing);
                filtered = a + (b - a) * t;
            }
            return Math.max(0.0f, Math.min(1.0f, filtered - minimum));
        }

        private MeshMipChain meshMipChain() {
            MeshMipChain ready = meshMipChain;
            if (ready != null) {
                return ready;
            }
            synchronized (this) {
                ready = meshMipChain;
                if (ready == null) {
                    int baseWidth = Math.min(width, MESH_BASE_RESOLUTION);
                    int baseHeight = Math.min(height, MESH_BASE_RESOLUTION);
                    float[] base = baseWidth == width && baseHeight == height
                            ? samples : areaReduce(samples, width, height, baseWidth, baseHeight);
                    List<float[]> levels = new ArrayList<>();
                    List<Integer> widths = new ArrayList<>();
                    List<Integer> heights = new ArrayList<>();
                    levels.add(base);
                    widths.add(baseWidth);
                    heights.add(baseHeight);
                    int levelWidth = baseWidth;
                    int levelHeight = baseHeight;
                    float[] level = base;
                    while (levelWidth > 1 || levelHeight > 1) {
                        int nextWidth = Math.max(1, levelWidth >> 1);
                        int nextHeight = Math.max(1, levelHeight >> 1);
                        level = areaReduce(level, levelWidth, levelHeight, nextWidth, nextHeight);
                        levelWidth = nextWidth;
                        levelHeight = nextHeight;
                        levels.add(level);
                        widths.add(levelWidth);
                        heights.add(levelHeight);
                    }
                    float[][] levelArray = levels.toArray(float[][]::new);
                    int[] widthArray = new int[widths.size()];
                    int[] heightArray = new int[heights.size()];
                    for (int i = 0; i < widths.size(); i++) {
                        widthArray[i] = widths.get(i);
                        heightArray[i] = heights.get(i);
                    }
                    ready = new MeshMipChain(levelArray, widthArray, heightArray);
                    meshMipChain = ready;
                }
            }
            return ready;
        }

        private static float sampleLevel(float[] level, int width, int height,
                                         float u, float v, boolean smoothing) {
            u = Math.max(0.0f, Math.min(1.0f, u));
            v = Math.max(0.0f, Math.min(1.0f, v));
            if (!smoothing) {
                int x = Math.max(0, Math.min(width - 1, (int) Math.floor(u * width)));
                int y = Math.max(0, Math.min(height - 1, (int) Math.floor(v * height)));
                return level[y * width + x];
            }
            float fx = u * width - 0.5f;
            float fy = v * height - 0.5f;
            int ux0 = (int) Math.floor(fx);
            int uy0 = (int) Math.floor(fy);
            int x0 = Math.max(0, Math.min(width - 1, ux0));
            int y0 = Math.max(0, Math.min(height - 1, uy0));
            int x1 = Math.min(width - 1, x0 + 1);
            int y1 = Math.min(height - 1, y0 + 1);
            float tx = Math.max(0.0f, Math.min(1.0f, fx - ux0));
            float ty = Math.max(0.0f, Math.min(1.0f, fy - uy0));
            if (ux0 < 0 || ux0 >= width - 1) tx = 0.0f;
            if (uy0 < 0 || uy0 >= height - 1) ty = 0.0f;
            float h00 = level[y0 * width + x0];
            float h10 = level[y0 * width + x1];
            float h01 = level[y1 * width + x0];
            float h11 = level[y1 * width + x1];
            return (h00 + (h10 - h00) * tx)
                    + ((h01 + (h11 - h01) * tx) - (h00 + (h10 - h00) * tx)) * ty;
        }

        private static float[] areaReduce(float[] source, int sourceWidth, int sourceHeight,
                                          int targetWidth, int targetHeight) {
            float[] reduced = new float[targetWidth * targetHeight];
            for (int y = 0; y < targetHeight; y++) {
                float sourceY0 = y * sourceHeight / (float) targetHeight;
                float sourceY1 = (y + 1) * sourceHeight / (float) targetHeight;
                int firstY = Math.max(0, (int) Math.floor(sourceY0));
                int lastY = Math.min(sourceHeight - 1, (int) Math.ceil(sourceY1) - 1);
                for (int x = 0; x < targetWidth; x++) {
                    float sourceX0 = x * sourceWidth / (float) targetWidth;
                    float sourceX1 = (x + 1) * sourceWidth / (float) targetWidth;
                    int firstX = Math.max(0, (int) Math.floor(sourceX0));
                    int lastX = Math.min(sourceWidth - 1, (int) Math.ceil(sourceX1) - 1);
                    double weighted = 0.0;
                    double weight = 0.0;
                    for (int sourceY = firstY; sourceY <= lastY; sourceY++) {
                        float wy = Math.max(0.0f, Math.min(sourceY1, sourceY + 1.0f)
                                - Math.max(sourceY0, sourceY));
                        for (int sourceX = firstX; sourceX <= lastX; sourceX++) {
                            float wx = Math.max(0.0f, Math.min(sourceX1, sourceX + 1.0f)
                                    - Math.max(sourceX0, sourceX));
                            double sampleWeight = wx * wy;
                            weighted += source[sourceY * sourceWidth + sourceX] * sampleWeight;
                            weight += sampleWeight;
                        }
                    }
                    reduced[y * targetWidth + x] = weight > 0.0
                            ? (float) (weighted / weight) : 0.0f;
                }
            }
            return reduced;
        }

        private record MeshMipChain(float[][] levels, int[] widths, int[] heights) {
        }
    }

    public Map<TextureAtlasSprite, Entry> preparedEntries() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(entries));
    }

    public Map<Identifier, Entry> preparedResourceEntries() {
        return Collections.unmodifiableMap(new HashMap<>(resourceEntries));
    }

    public void destroy() {
        reset();
    }

    private Entry fallbackFor(TextureAtlasSprite sprite) {
        return new Entry(0, fallback.pageIndex(), 0,
                fallback.materialU, fallback.materialV, fallback.materialDu, fallback.materialDv,
                sprite.getU0(), sprite.getV0(), inverseExtent(sprite.getU1() - sprite.getU0()),
                inverseExtent(sprite.getV1() - sprite.getV0()), RtMaterialDesc.EmissionSummary.NONE,
                RtMaterialDesc.EmissionSummary.NONE);
    }

    private record Decoded(List<RtMaterialTextureData.Level> levels,
                           RtMaterialDesc.EmissionSummary emissionSummary,
                           RtMaterialDesc.EmissionSummary overrideEmissionSummary) {
    }

    private static Decoded decode(Candidate candidate) throws Exception {
        NativeImage spec = (candidate.features & RtMaterialRegistry.FEATURE_SPEC) != 0
                ? load(candidate.specLocation) : null;
        NativeImage normal = (candidate.features & RtMaterialRegistry.FEATURE_NORMAL) != 0
                ? load(candidate.normalLocation) : null;
        NativeImage resourceAlbedo = candidate.blockSprite() ? null : load(candidate.albedoLocation);
        try {
            int width = candidate.width;
            int height = candidate.height;
            float[] surface0 = new float[width * height * 4];
            float[] normalAo = new float[surface0.length];
            float[] surface1 = new float[surface0.length];
            float[] linearAlbedo = new float[surface0.length];
            float[] authoredEmission = spec != null ? new float[width * height] : null;
            NativeImage albedo = candidate.blockSprite()
                    ? ((SpriteContentsAccessor) candidate.sprite.contents()).caustica$originalImage()
                    : resourceAlbedo;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int albedoPixel = albedo != null ? albedo.getPixel(x, y) : -1;
                    float ar = RtMaterialTextureData.srgbToLinear(ARGB.red(albedoPixel));
                    float ag = RtMaterialTextureData.srgbToLinear(ARGB.green(albedoPixel));
                    float ab = RtMaterialTextureData.srgbToLinear(ARGB.blue(albedoPixel));
                    float aa = ARGB.alpha(albedoPixel) / 255.0f;
                    linearAlbedo[i] = ar;
                    linearAlbedo[i + 1] = ag;
                    linearAlbedo[i + 2] = ab;
                    linearAlbedo[i + 3] = aa;
                    if (spec != null) {
                        int pixel = sample(spec, x, y, width, height, candidate.blockSprite());
                        RtLabPbr.Specular decoded = RtLabPbr.decodeSpec(
                                ARGB.red(pixel) / 255.0f, ARGB.green(pixel) / 255.0f,
                                ARGB.blue(pixel) / 255.0f, ARGB.alpha(pixel) / 255.0f, ar, ag, ab);
                        surface0[i] = decoded.roughness();
                        surface0[i + 1] = decoded.metalness();
                        surface0[i + 2] = decoded.emission();
                        authoredEmission[y * width + x] = decoded.emission() * aa;
                        surface0[i + 3] = decoded.sss();
                        surface1[i] = decoded.f0r();
                        surface1[i + 1] = decoded.f0g();
                        surface1[i + 2] = decoded.f0b();
                    } else {
                        surface0[i] = 1.0f;
                        surface1[i] = surface1[i + 1] = surface1[i + 2] = 0.04f;
                    }
                    if (normal != null) {
                        int pixel = sample(normal, x, y, width, height, candidate.blockSprite());
                        float nx = ARGB.red(pixel) / 127.5f - 1.0f;
                        float ny = ARGB.green(pixel) / 127.5f - 1.0f;
                        float lengthSq = nx * nx + ny * ny;
                        if (lengthSq > 1.0f) {
                            float invLength = 1.0f / (float) Math.sqrt(lengthSq);
                            nx *= invLength;
                            ny *= invLength;
                        }
                        normalAo[i] = nx * 0.5f + 0.5f;
                        normalAo[i + 1] = ny * 0.5f + 0.5f;
                        normalAo[i + 2] = ARGB.blue(pixel) / 255.0f;
                        normalAo[i + 3] = ARGB.alpha(pixel) / 255.0f;
                    } else {
                        normalAo[i] = normalAo[i + 1] = 0.5f;
                        normalAo[i + 2] = 1.0f;
                    }
                }
            }
            RtMaterialDesc.EmissionSummary emissionSummary = RtMaterialDesc.EmissionSummary.NONE;
            if (spec != null) {
                emissionSummary = RtEmissionHeuristic.summarize(linearAlbedo, authoredEmission);
            }
            RtMaterialDesc.EmissionSummary overrideEmissionSummary = RtMaterialDesc.EmissionSummary.NONE;
            boolean heuristic = (candidate.features & RtMaterialRegistry.FEATURE_HEURISTIC_EMISSION) != 0;
            boolean overrideMask = (candidate.features & RtMaterialRegistry.FEATURE_OVERRIDE_EMISSION) != 0;
            if (heuristic || overrideMask) {
                RtEmissionHeuristic.Result emission = RtEmissionHeuristic.compile(linearAlbedo);
                float[] mask = emission.mask();
                for (int pixel = 0; pixel < mask.length; pixel++) {
                    if (heuristic) surface0[pixel * 4 + 2] = mask[pixel];
                    if (overrideMask) surface1[pixel * 4 + 3] = mask[pixel];
                }
                if (heuristic) emissionSummary = emission.summary();
                if (overrideMask) overrideEmissionSummary = emission.summary();
            }
            int maxLod = maxLodFor(width, height);
            return new Decoded(RtMaterialTextureData.mipChain(new RtMaterialTextureData.Level(width, height,
                    surface0, normalAo, surface1), maxLod),
                    emissionSummary, overrideEmissionSummary);
        } finally {
            if (spec != null) spec.close();
            if (normal != null) normal.close();
            if (resourceAlbedo != null) resourceAlbedo.close();
        }
    }

    private static final class PagePixels {
        final List<byte[]> surface0;
        final List<byte[]> normalAo;
        final List<byte[]> surface1;
        final int pageSize;

        PagePixels(int pageSize, int mipCount) {
            this.pageSize = pageSize;
            surface0 = allocate(pageSize, mipCount, 255, 0, 0, 0);
            normalAo = allocate(pageSize, mipCount, 128, 128, 255, 0);
            surface1 = allocate(pageSize, mipCount, 10, 10, 10, 0);
        }

        void writeFallback() {
            // Neutral arrays already contain the fallback texel and its replicated surroundings.
        }

        void write(Candidate candidate, List<RtMaterialTextureData.Level> levels) {
            for (int mip = 0; mip < levels.size(); mip++) {
                RtMaterialTextureData.Level level = levels.get(mip);
                int width = Math.max(1, pageSize >> mip);
                int cx = candidate.x >> mip;
                int cy = candidate.y >> mip;
                int gutter = Math.max(1, GUTTER >> mip);
                blit(surface0.get(mip), width, cx, cy, gutter, level.width(), level.height(), level.surface0());
                blit(normalAo.get(mip), width, cx, cy, gutter, level.width(), level.height(), level.normalAo());
                blit(surface1.get(mip), width, cx, cy, gutter, level.width(), level.height(), level.surface1());
            }
        }

        private static List<byte[]> allocate(int size, int mipCount, int r, int g, int b, int a) {
            List<byte[]> result = new ArrayList<>(mipCount);
            int width = size;
            for (int mip = 0; mip < mipCount; mip++) {
                byte[] values = new byte[width * width * 4];
                for (int i = 0; i < values.length; i += 4) {
                    values[i] = (byte) r;
                    values[i + 1] = (byte) g;
                    values[i + 2] = (byte) b;
                    values[i + 3] = (byte) a;
                }
                result.add(values);
                width = Math.max(1, width / 2);
            }
            return result;
        }

        private static void blit(byte[] dst, int dstWidth, int cx, int cy, int gutter,
                                 int srcWidth, int srcHeight, float[] src) {
            for (int dy = -gutter; dy < srcHeight + gutter; dy++) {
                int sy = Math.max(0, Math.min(srcHeight - 1, dy));
                int ty = cy + dy;
                if (ty < 0 || ty >= dstWidth) continue;
                for (int dx = -gutter; dx < srcWidth + gutter; dx++) {
                    int sx = Math.max(0, Math.min(srcWidth - 1, dx));
                    int tx = cx + dx;
                    if (tx < 0 || tx >= dstWidth) continue;
                    int si = (sy * srcWidth + sx) * 4;
                    int di = (ty * dstWidth + tx) * 4;
                    dst[di] = (byte) RtMaterialTextureData.unorm8(src[si]);
                    dst[di + 1] = (byte) RtMaterialTextureData.unorm8(src[si + 1]);
                    dst[di + 2] = (byte) RtMaterialTextureData.unorm8(src[si + 2]);
                    dst[di + 3] = (byte) RtMaterialTextureData.unorm8(src[si + 3]);
                }
            }
        }
    }

    static List<TextureAtlasSprite> blockSprites() {
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
        List<TextureAtlasSprite> sprites = ((TextureAtlasAccessor) atlas).caustica$sprites();
        return sprites != null ? sprites : List.of();
    }

    private static Map<Identifier, Integer> discoverEntityMaterialResources(
            List<TextureAtlasSprite> blockSprites, RtMaterialOverrides overrides) {
        Set<Identifier> blockNames = new HashSet<>();
        for (TextureAtlasSprite sprite : blockSprites) {
            if (sprite != null) blockNames.add(sprite.contents().name());
        }
        Map<Identifier, Integer> result = new LinkedHashMap<>();
        Map<Identifier, Resource> authored = Minecraft.getInstance().getResourceManager().listResources(
                "textures", id -> id.getPath().endsWith("_s.png") || id.getPath().endsWith("_n.png"));
        List<Identifier> ordered = new ArrayList<>(authored.keySet());
        ordered.sort(Comparator.comparing(Identifier::toString));
        for (Identifier material : ordered) {
            String path = material.getPath();
            boolean spec = path.endsWith("_s.png");
            String albedoPath = path.substring(0, path.length() - 6) + ".png";
            Identifier albedo = Identifier.fromNamespaceAndPath(material.getNamespace(), albedoPath);
            Identifier name = logicalTextureName(albedo);
            if (blockNames.contains(name) || !resourceExists(albedo)) continue;
            result.merge(albedo, spec ? RtMaterialRegistry.FEATURE_SPEC
                    : RtMaterialRegistry.FEATURE_NORMAL, (a, b) -> a | b);
        }
        for (RtMaterialOverrides.Rule rule : overrides.rules()) {
            if (rule.block() != null || blockNames.contains(rule.sprite())) {
                continue;
            }
            Identifier albedo = textureLocation(rule.sprite());
            if (resourceExists(albedo)) {
                int feature = rule.emissionStrength() != null && rule.emissionStrength() > 0.0f
                        ? RtMaterialRegistry.FEATURE_OVERRIDE_EMISSION : 0;
                result.merge(albedo, feature, (a, b) -> a | b);
            }
        }
        return result;
    }

    private static Identifier sibling(Identifier name, String suffix) {
        return Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + suffix);
    }

    private static Identifier textureLocation(Identifier logicalName) {
        return Identifier.fromNamespaceAndPath(logicalName.getNamespace(),
                "textures/" + logicalName.getPath() + ".png");
    }

    /** Strip the {@code textures/} prefix and {@code .png} suffix shared by all material resource paths. */
    static Identifier logicalTextureName(Identifier textureLocation) {
        String path = textureLocation.getPath();
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return Identifier.fromNamespaceAndPath(textureLocation.getNamespace(), path);
    }

    static int maxLodFor(int width, int height) {
        return Math.min(MAX_VALID_LOD, 31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
    }

    private static Identifier siblingTexture(Identifier albedo, String suffix) {
        String path = albedo.getPath();
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return Identifier.fromNamespaceAndPath(albedo.getNamespace(), path + suffix + ".png");
    }

    private static boolean resourceExists(Identifier location) {
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
    }

    private static NativeImage load(Identifier location) throws Exception {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return null;
        try (InputStream input = resource.get().open()) {
            return NativeImage.read(input);
        }
    }

    private static int sample(NativeImage image, int x, int y, int width, int height, boolean firstFrameOnly) {
        int sampledHeight = firstFrameOnly ? Math.min(image.getHeight(), image.getWidth()) : image.getHeight();
        int sx = Math.min(image.getWidth() - 1, x * image.getWidth() / width);
        int sy = Math.min(sampledHeight - 1, y * sampledHeight / height);
        return image.getPixel(sx, sy);
    }

    static float inverseExtent(float extent) {
        return Math.abs(extent) > 1.0e-12f ? 1.0f / extent : 0.0f;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) return 1;
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    // Synchronized: decode failures can now arrive from parallel workers.
    private synchronized void warnOnce(String message, Throwable throwable) {
        if (!loggedFailure) {
            loggedFailure = true;
            CausticaMod.LOGGER.warn(message, throwable);
        }
    }
}
