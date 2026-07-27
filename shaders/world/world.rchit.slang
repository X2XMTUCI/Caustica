// Closest-hit shader. Geometry is per-section: InstanceID() indexes a section table (BDA array reached
// from the push constant) holding this section's {prim, uv} buffer addresses. Per-primitive
// {normal, tint} plus barycentric atlas UVs produce the payload guides; lighting is deferred to raygen.
//
// tableAddr/entityTableAddr are read from the inline push-constant lanes (pc), not through the
// WorldPush BDA struct: they're read on every single hit (the hottest chain in the frame), and going
// through worldPushAddr first would cost an extra global-memory load just to find the address of the
// load we actually want. The cold WorldPush struct is dereferenced only for the breaking overlay.
import world_common;

[[vk::push_constant]] WorldPushConstants pc;

[[vk::binding(2, 0)]] Sampler2D blockAlbedoAtlas;
// Independent bindless arrays: per-draw entity albedo slots and compact canonical material-page bundles.
[[vk::binding(0, 1)]] Sampler2D entityAlbedoTex[];
[[vk::binding(1, 1)]] Sampler2D materialSurface0Tex[];
[[vk::binding(2, 1)]] Sampler2D materialNormalAoTex[];
[[vk::binding(3, 1)]] Sampler2D materialSurface1Tex[];

void payloadSetPacked(inout Payload payload, uint material, float roughness, float metalness,
        float emission, float sss, float ior, float transmission, uint emissionSource) {
    payload.flags = material | (emissionSource << PAYLOAD_EMISSION_SOURCE_SHIFT);
    payload.roughMetal = packHalf2(float2(roughness, metalness));
    payload.emissionSss = packHalf2(float2(emission, sss));
    payload.iorTransmission = packHalf2(float2(ior, transmission));
}

uint materialEmissionSource(MaterialHeader header, float emission) {
    if (emission <= 0.0) return EMISSION_SOURCE_NONE;
    if ((header.features & MATERIAL_FEATURE_OVERRIDE_EMISSION) != 0u) return EMISSION_SOURCE_OVERRIDE;
    if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) return EMISSION_SOURCE_LAB_PBR;
    if ((header.features & MATERIAL_FEATURE_HEURISTIC_EMISSION) != 0u) return EMISSION_SOURCE_HEURISTIC;
    return EMISSION_SOURCE_UNIFORM;
}

// Strength of the LabPBR _n ambient-occlusion (blue) applied to albedo. Kept mild because the path
// tracer already computes its own AO from sky-visibility rays (avoid double-darkening).
static const float NORMAL_AO_STRENGTH = 0.5;

static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
static const float RAY_CONE_MIN_TEXEL_FOOTPRINT = 1.0e-4;
static const float RAY_CONE_MAX_LOD = 12.0;

float2 texSizePx(Sampler2D t) {
    uint w, h;
    t.GetDimensions(w, h);
    return float2(w, h);
}

float rayConeHitWidth(uint rayCone) {
    float2 cone = unpackHalf2(rayCone);
    return max(cone.x + cone.y * max(RayTCurrent(), 0.0), RAY_CONE_MIN_WIDTH);
}

float edgeTexelsPerWorld(float3 p0, float3 p1, float2 uv0, float2 uv1, float2 textureSizePx) {
    float worldLen = length(p1 - p0);
    if (worldLen <= 1.0e-7) {
        return 0.0;
    }
    return length((uv1 - uv0) * textureSizePx) / worldLen;
}

float rayConeTextureLod(uint rayCone, float2 textureSizePx, float3 p0, float3 p1, float3 p2, float2 uv0, float2 uv1, float2 uv2) {
    float texelsPerWorld = max(edgeTexelsPerWorld(p0, p1, uv0, uv1, textureSizePx),
            max(edgeTexelsPerWorld(p1, p2, uv1, uv2, textureSizePx),
                edgeTexelsPerWorld(p2, p0, uv2, uv0, textureSizePx)));
    float footprint = max(rayConeHitWidth(rayCone) * texelsPerWorld, RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

float rayConeUnitUvLod(uint rayCone, float2 textureSizePx) {
    float footprint = max(rayConeHitWidth(rayCone) * max(textureSizePx.x, textureSizePx.y), RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

// LabPBR _n decode (shared): rotate the tangent-space normal into world space via a TBN built from the
// hit triangle's vertex positions (VK_KHR_ray_tracing_position_fetch) + UVs. `n` must already be
// oriented toward the viewer (`vdir`). Clamps the result above the horizon so grazing perturbations
// don't invert it through the surface (the black-spot fix). Returns AO (blue) via `ao`; falls back to
// `n` on a degenerate UV triangle. Instance transforms are translation-only, so object edges equal
// world edges.
float3 perturbNormal(float3 n, float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2, float3 vdir, float4 ntex, out float ao) {
    ao = 1.0;
    float2 g1 = t1 - t0;
    float2 g2 = t2 - t0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return n;
    }
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    float3 T = normalize(traw - n * dot(n, traw));       // Gram-Schmidt against the viewer-oriented normal
    float3 B = cross(n, T) * (det < 0.0 ? -1.0 : 1.0);   // handedness from the UV winding
    float2 nxy = ntex.xy * 2.0 - 1.0;                    // R,G = tangent-space X,Y
    float nz = sqrt(max(0.0, 1.0 - dot(nxy, nxy)));      // reconstruct Z
    float3 nm = normalize(nxy.x * T + nxy.y * B + nz * n);
    float NoV = dot(nm, vdir);
    if (NoV < 0.02) {
        nm = normalize(nm + vdir * (0.02 - NoV));        // keep above the horizon (no flip)
    }
    ao = lerp(1.0, ntex.b, NORMAL_AO_STRENGTH);
    return nm;
}

struct Surface {
    float3 normal;
    float3 f0;
    float roughness;
    float metalness;
    float emission;
    float sss;
    float ao;
};

// Canonical material pages use a compact page index shared by their three dedicated arrays.
float4 samplePageSurface0(uint page, float2 uv, float lod) {
    Sampler2D pageTex = materialSurface0Tex[NonUniformResourceIndex(page)];
    if ((ConstPtr<WorldPush>(pc.worldPushAddr)[0].flags & 0x20u) == 0u) {
        uint w, h;
        pageTex.GetDimensions(w, h);
        float mip = floor(lod);
        float2 size = max(float2(1.0, 1.0), floor(float2(w, h) / exp2(mip)));
        uv = (floor(uv * size) + 0.5) / size;
        lod = mip;
    }
    return pageTex.SampleLevel(uv, lod);
}
float4 samplePageSurface1(uint page, float2 uv, float lod) {
    Sampler2D pageTex = materialSurface1Tex[NonUniformResourceIndex(page)];
    if ((ConstPtr<WorldPush>(pc.worldPushAddr)[0].flags & 0x20u) == 0u) {
        uint w, h;
        pageTex.GetDimensions(w, h);
        float mip = floor(lod);
        float2 size = max(float2(1.0, 1.0), floor(float2(w, h) / exp2(mip)));
        uv = (floor(uv * size) + 0.5) / size;
        lod = mip;
    }
    return pageTex.SampleLevel(uv, lod);
}
float4 samplePageNormalAo(uint page, float2 uv, float lod) {
    Sampler2D pageTex = materialNormalAoTex[NonUniformResourceIndex(page)];
    if ((ConstPtr<WorldPush>(pc.worldPushAddr)[0].flags & 0x20u) == 0u) {
        uint w, h;
        pageTex.GetDimensions(w, h);
        float mip = floor(lod);
        float2 size = max(float2(1.0, 1.0), floor(float2(w, h) / exp2(mip)));
        uv = (floor(uv * size) + 0.5) / size;
        lod = mip;
    }
    return pageTex.SampleLevel(uv, lod);
}

// Height intersection follows the same switch. Disabled mode snaps every LabPBR channel above and this
// height channel to exact mip texel centres, so "off" is genuinely pixel-aligned rather than merely
// applying nearest filtering to the height while leaving the mapped normals smooth.
float samplePageHeight(uint page, float2 uv, float lod) {
    Sampler2D pageTex = materialNormalAoTex[NonUniformResourceIndex(page)];
    if ((ConstPtr<WorldPush>(pc.worldPushAddr)[0].flags & 0x20u) == 0u) {
        uint w, h;
        pageTex.GetDimensions(w, h);
        float mip = floor(lod);
        float2 size = max(float2(1.0, 1.0), floor(float2(w, h) / exp2(mip)));
        uv = (floor(uv * size) + 0.5) / size;
        lod = mip;
    }
    return pageTex.SampleLevel(uv, lod).a;
}

// LabPBR stores height in the alpha channel of the _n texture. Trace a short view-space ray through
// that height field and return a displaced albedo UV. The configured camera distance keeps full
// relief through 80% of its range, then fades continuously so the cutoff cannot visibly pop.
// Fade before the sprite boundary and reject rays that leave it. Clamping a marching ray to the edge
// stretches the last texel column into long spikes and makes it pop as the camera moves.
float2 parallaxUv(MaterialHeader header, float2 atlasUv, float lod, float3 geometricNormal,
        float3 p0, float3 p1, float3 p2, float2 uv0, float2 uv1, float2 uv2, float3 vdir,
        out float reliefOffset, out float reliefScale, out float2 reliefPlanarOffset) {
    reliefOffset = 0.0;
    reliefScale = 0.0;
    reliefPlanarOffset = float2(0.0, 0.0);
    if ((header.features & MATERIAL_FEATURE_NORMAL) == 0u) {
        return atlasUv;
    }

    float2 g1 = uv1 - uv0;
    float2 g2 = uv2 - uv0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return atlasUv;
    }

    float3 n = geometricNormal;
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    float3 T = normalize(traw - n * dot(n, traw));
    float3 B = cross(n, T) * (det < 0.0 ? -1.0 : 1.0);
    float rawNoV = max(dot(vdir, n), 0.0);
    float3 viewTs = float3(dot(vdir, T), dot(vdir, B), max(rawNoV, 0.08));

    float2 localUv = clamp((atlasUv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    float4 parallaxParams = worldPush.parallaxParams;
    float maxDistance = max(parallaxParams.w, 1.0);
    float3 hitPosition = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
    float cameraDistance = length(hitPosition - worldPush.camOffset);
    float distanceFade = 1.0 - smoothstep(maxDistance * 0.8, maxDistance, cameraDistance);
    float maxMaterialLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    // maxMaterialLod is the highest SAFE canonical-page mip (currently capped at 3 by the page gutter),
    // not log2 of the authored texture resolution. Treating it as a resolution made detailFade equal
    // 0.5 even at mip 0 and zero at mip 1, so a 256x material lost POM after only a few blocks regardless
    // of the distance slider. Sampling is still clamped to maxMaterialLod below; only the user-selected
    // distance is allowed to fade the physical relief amplitude.
    // Only suppress the singular near-horizontal case. The previous 0.12..0.30 fade made ground POM
    // full for roughly five blocks and gone by thirteen, overriding a 64-block distance slider.
    float grazingFade = smoothstep(0.002, 0.006, rawNoV);
    float edgeDistance = min(min(localUv.x, 1.0 - localUv.x), min(localUv.y, 1.0 - localUv.y));
    float edgeFade = smoothstep(0.015, 0.09, edgeDistance);
    float heightScale = parallaxParams.x * distanceFade * grazingFade * edgeFade;
    if (heightScale <= 1.0e-5) {
        return atlasUv;
    }
    reliefScale = heightScale;

    uint page = header.texturePage;
    bool smoothing = (worldPush.flags & 0x20u) != 0u;
    float materialLod = min(smoothing ? lod + PARALLAX_SMOOTH_LOD_BIAS : floor(lod), maxMaterialLod);
    int maxLayerCount = clamp(int(round(parallaxParams.y)), 4, 32);
    // A view-dependent rounded layer count changes abruptly during tiny camera rotations and makes
    // high-contrast height fields shimmer. A fixed configured count is temporally stable.
    int layerCount = maxLayerCount;
    float layerDepth = 1.0 / float(layerCount);
    float2 deltaUv = (viewTs.xy / viewTs.z) * (heightScale / float(layerCount));
    float2 currentUv = localUv;
    float currentDepth = 0.0;
    float sampledDepth = 1.0 - samplePageHeight(page,
            header.materialUv.xy + currentUv * header.materialUv.zw, materialLod);

    [loop]
    for (int stepIndex = 0; stepIndex < 32 && currentDepth < sampledDepth; ++stepIndex) {
        float2 nextUv = currentUv - deltaUv;
        if (any(nextUv < float2(0.0, 0.0)) || any(nextUv > float2(1.0, 1.0))) {
            reliefScale = 0.0;
            return atlasUv;
        }
        currentUv = nextUv;
        currentDepth += layerDepth;
        sampledDepth = 1.0 - samplePageHeight(page,
                header.materialUv.xy + currentUv * header.materialUv.zw, materialLod);
    }

    // Smooth mode refines the crossing. Pixel mode intentionally keeps the discrete layer hit so it
    // cannot reintroduce interpolated/rounded relief after the user disabled smoothing.
    float2 frontUv = currentUv + deltaUv;
    float frontDepth = max(0.0, currentDepth - layerDepth);
    float2 backUv = currentUv;
    float backDepth = currentDepth;
    if (smoothing) {
        [unroll]
        for (int refine = 0; refine < 4; ++refine) {
            float2 midUv = (frontUv + backUv) * 0.5;
            float midDepth = (frontDepth + backDepth) * 0.5;
            float mapDepth = 1.0 - samplePageHeight(page,
                    header.materialUv.xy + midUv * header.materialUv.zw, materialLod);
            if (midDepth < mapDepth) {
                frontUv = midUv;
                frontDepth = midDepth;
            } else {
                backUv = midUv;
                backDepth = midDepth;
            }
        }
    }
    float2 displacedLocalUv = smoothing ? (frontUv + backUv) * 0.5 : currentUv;
    // The layer crossing chooses the displaced UV, but it is only a bounded numerical search result.
    // Place the physical shading point on the actual height-map sample at that UV; deriving its height
    // from the quantized crossing put the point slightly below the surface and made binary path rays
    // self-occlude entire texel regions.
    float authoredHeight = samplePageHeight(page,
            header.materialUv.xy + displacedLocalUv * header.materialUv.zw, materialLod);
    reliefOffset = heightScale * saturate(authoredHeight);
    reliefPlanarOffset = displacedLocalUv - localUv;
    return header.albedoUv.xy + displacedLocalUv / header.albedoUv.zw;
}

void payloadDisableParallax(inout Payload payload) {
    payload.parallaxPageSign = 0x00FFFFFFu;
    payload.parallaxPlanarOffset = 0u;
    payload.parallaxOffset = 0.0;
}

// Export the displaced height-field point and its UV basis so raygen can evaluate visibility for the
// actual direction it samples at this vertex (direct, diffuse, or GGX), independent of light type.
void payloadSetParallax(inout Payload payload, MaterialHeader header, float2 atlasUv, float lod,
        float3 geometricNormal, float3 p0, float3 p1, float3 p2,
        float2 uv0, float2 uv1, float2 uv2, float reliefOffset, float reliefScale,
        float2 reliefPlanarOffset) {
    float4 params = ConstPtr<WorldPush>(pc.worldPushAddr)[0].parallaxParams;
    if ((header.features & MATERIAL_FEATURE_NORMAL) == 0u || params.x <= 1.0e-5
            || reliefOffset <= 1.0e-6 || reliefScale <= 1.0e-6) {
        payloadDisableParallax(payload);
        return;
    }
    float2 g1 = uv1 - uv0;
    float2 g2 = uv2 - uv0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        payloadDisableParallax(payload);
        return;
    }
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    float3 tangent = normalize(traw - geometricNormal * dot(geometricNormal, traw));
    uint maxLod = (header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK;
    payload.parallaxPageSign = (header.texturePage & 0x00FFFFFFu) | ((maxLod & 0x7Fu) << 24)
            | (det < 0.0 ? 0x80000000u : 0u);
    payload.parallaxGeomOct = packOctNormal(geometricNormal);
    payload.parallaxTangentOct = packOctNormal(tangent);
    payload.parallaxLodScale = packHalf2(float2(lod, reliefScale));
    payload.parallaxMaterialUv = header.materialUv;
    payload.parallaxLocalUv = clamp((atlasUv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    payload.parallaxPlanarOffset = packHalf2(reliefPlanarOffset);
    payload.parallaxOffset = reliefOffset;
}

// The sole terrain/entity material evaluation path. LabPBR categorical bytes have already been
// decoded on the CPU; every sample below is a continuously filterable physical channel with semantic mips.
Surface evaluateMaterial(MaterialHeader header, float2 atlasUv, float lod, float3 albedo,
        float3 geometricNormal, float3 p0, float3 p1, float3 p2,
        float2 uv0, float2 uv1, float2 uv2, float3 vdir,
        float fallbackRoughness, float fallbackMetalness, float fallbackEmission, bool allowSss) {
    Surface surface;
    surface.normal = geometricNormal;
    surface.roughness = fallbackRoughness;
    surface.metalness = fallbackMetalness;
    surface.f0 = lerp(float3(0.04, 0.04, 0.04), albedo, fallbackMetalness);
    surface.emission = fallbackEmission;
    surface.sss = 0.0;
    surface.ao = 1.0;

    float2 localUv = clamp((atlasUv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    float2 pageUv = header.materialUv.xy + localUv * header.materialUv.zw;
    float maxMaterialLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    float materialLod = min(lod, maxMaterialLod);
    uint page = header.texturePage;
    if ((header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_HEURISTIC_EMISSION
            | MATERIAL_FEATURE_OVERRIDE_EMISSION)) != 0u) {
        float4 surface0 = samplePageSurface0(page, pageUv, materialLod);
        // surface1 feeds both the LabPBR F0 (rgb) and the override emission mask (a) — fetch it once.
        float4 surface1 = (header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_OVERRIDE_EMISSION)) != 0u
                ? samplePageSurface1(page, pageUv, materialLod) : float4(0.0, 0.0, 0.0, 0.0);
        if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) {
            surface.roughness = surface0.r;
            surface.metalness = surface0.g;
            surface.emission = surface0.b;
            surface.sss = allowSss ? surface0.a : 0.0;
            surface.f0 = surface1.rgb;
        } else {
            // Eligibility is compiled from block-state semantics; the primitive's exact light level
            // supplies strength, while the page supplies only the normalized spatial mask.
            surface.emission = surface0.b * fallbackEmission;
        }
        if ((header.features & MATERIAL_FEATURE_OVERRIDE_EMISSION) != 0u) {
            float strength = float((header.features >> MATERIAL_EMISSION_STRENGTH_SHIFT)
                    & MATERIAL_EMISSION_STRENGTH_MASK)
                    * (MATERIAL_MAX_OVERRIDE_EMISSION_STRENGTH / float(MATERIAL_EMISSION_STRENGTH_MASK));
            surface.emission = surface1.a * strength;
        }
    }
    if ((header.features & MATERIAL_FEATURE_NORMAL) != 0u) {
        float4 normalAo = samplePageNormalAo(page, pageUv, materialLod);
        surface.normal = perturbNormal(geometricNormal, p0, p1, p2, uv0, uv1, uv2,
                vdir, normalAo, surface.ao);
    }
    return surface;
}

// Block-breaking overlay: shared by opaque terrain AND stained-glass/ice. Reconstructs the hit position
// in the same rebased space the breaking list was pushed in, nudges it into the solid block along
// -normal to get the block's integer coordinate, then decal-projects the local [0,1] position onto
// whichever axis pair the normal is most aligned with (same idea as vanilla's
// SheetedDecalTextureGenerator) and multiply-blends the matching destroy-stage crack texture in,
// mirroring vanilla's crumbling blend (DST_COLOR*SRC_COLOR, doubled).
float3 applyBreaking(float3 albedo, uint rayCone, float3 rayOrigin, float3 rayDir, float hitT, float3 n) {
    ConstPtr<WorldPush> pcRef = ConstPtr<WorldPush>(pc.worldPushAddr);
    uint breakCount = pcRef[0].breakCount;
    if (breakCount == 0u) {
        return albedo;
    }
    float3 hitPos = rayOrigin + rayDir * hitT;
    int3 blockPos = int3(floor(hitPos - n * 0.01));
    for (uint bi = 0u; bi < breakCount; ++bi) {
        int4 ps = pcRef[0].breaking[bi].posSlot;
        if (all(ps.xyz == blockPos)) {
            float3 local = hitPos - float3(blockPos);
            float3 an = abs(n);
            float2 decalUv = (an.x >= an.y && an.x >= an.z) ? local.zy
                           : (an.y >= an.z) ? local.xz
                           : local.xy;
            float decalLod = rayConeUnitUvLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(ps.w)]));
            float3 crack = srgbToLinear(entityAlbedoTex[NonUniformResourceIndex(ps.w)].SampleLevel(decalUv, decalLod).rgb);
            return clamp(crack * albedo, 0.0, 1.0);
        }
    }
    return albedo;
}

float dhMaterialRoughness(uint material, float fallbackRoughness) {
    if (material == DH_MATERIAL_METAL) return 0.28;
    if (material == DH_MATERIAL_SNOW) return 0.38;
    if (material == DH_MATERIAL_WOOD) return 0.72;
    if (material == DH_MATERIAL_STONE) return 0.78;
    if (material == DH_MATERIAL_DEEPSLATE) return 0.84;
    if (material == DH_MATERIAL_TERRACOTTA) return 0.76;
    if (material == DH_MATERIAL_LEAVES) return 0.84;
    if (material == DH_MATERIAL_GRASS) return 0.92;
    if (material == DH_MATERIAL_DIRT) return 0.96;
    if (material == DH_MATERIAL_SAND) return 0.98;
    if (material == DH_MATERIAL_NETHER_STONE) return 0.88;
    if (material == DH_MATERIAL_LAVA) return 0.68;
    if (material == DH_MATERIAL_ILLUMINATED) return clamp(fallbackRoughness, 0.18, 0.90);
    return clamp(fallbackRoughness, 0.25, 1.0);
}

float dhMaterialMetalness(uint material, float fallbackMetalness) {
    if (material == DH_MATERIAL_METAL) return 1.0;
    if (material == DH_MATERIAL_ILLUMINATED) return clamp(fallbackMetalness, 0.0, 1.0);
    return 0.0;
}

float dhMaterialSss(uint material) {
    if (material == DH_MATERIAL_LEAVES) return 0.18;
    if (material == DH_MATERIAL_GRASS) return 0.055;
    if (material == DH_MATERIAL_SNOW) return 0.08;
    return 0.0;
}

[shader("closesthit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    // Native DH LOD triangles participate in camera, GI, reflection and shadow rays through the same TLAS.
    if ((InstanceID() & DH_INSTANCE_KIND) == DH_INSTANCE_KIND) {
        Section sec = ConstPtr<Section>(pc.dhTableAddr)[InstanceID() & IDX_MASK];
        uint pid = PrimitiveIndex() + sec.triBase[GeometryIndex()];
        TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[pid];
        float3 sourceNormal = normalize(pr.normal.xyz);
        float3 n = sourceNormal;
        if (dot(n, -WorldRayDirection()) < 0.0) n = -n;
        uint dhSurface = uint(pr.tint.w + 0.5);
        uint dhMaterial = pr.flags;
        bool water = dhSurface == DH_SURFACE_WATER;
        bool glass = dhSurface == DH_SURFACE_GLASS;
        bool emissive = dhSurface == DH_SURFACE_EMISSIVE;
        bool emissiveGlass = dhSurface == DH_SURFACE_EMISSIVE_GLASS;
        float dhEmission = (emissive || emissiveGlass) ? clamp(asfloat(pr.aux0), 0.0, 4.0) : 0.0;
        bool entering = dot(WorldRayDirection(), sourceNormal) < 0.0;
        MaterialHeader dhHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];

        payloadDisableParallax(payload);
        if (glass || emissiveGlass) {
            float opticalAlpha = clamp(asfloat(emissiveGlass ? pr.aux1 : pr.aux0), 0.04, 0.80);
            float3 glassTint = glassTransmissionFilter(pr.tint.rgb, opticalAlpha);
            float ior = max(dhHeader.params.z, 1.45);
            float f0Base = (ior - 1.0) / (ior + 1.0);
            float f0Scalar = f0Base * f0Base;
            payload.albedo = glassTint;
            payload.normal = n;
            payload.hitT = RayTCurrent();
            payload.motionPrev = float3(0.0, 0.0, 0.0);
            payload.f0 = float3(f0Scalar, f0Scalar, f0Scalar);
            payloadSetPacked(payload, MATERIAL_GLASS,
                    clamp(dhHeader.params.x, 0.025, 0.16), 0.0, dhEmission, 0.0,
                    -ior, max(dhHeader.params.w, 0.97),
                    emissiveGlass ? EMISSION_SOURCE_UNIFORM : EMISSION_SOURCE_NONE);
            return;
        }

        payload.albedo = pr.tint.rgb;
        payload.normal = n;
        payload.hitT = RayTCurrent();
        payload.motionPrev = float3(0.0, 0.0, 0.0);
        float roughness = water ? 0.04 : dhMaterialRoughness(dhMaterial, dhHeader.params.x);
        float metalness = water ? 0.0 : dhMaterialMetalness(dhMaterial, dhHeader.params.y);
        float sss = water ? 0.0 : dhMaterialSss(dhMaterial);
        payload.f0 = water ? float3(0.02, 0.02, 0.02)
                : lerp(float3(0.04, 0.04, 0.04), payload.albedo, metalness);
        payloadSetPacked(payload, water ? MATERIAL_WATER : MATERIAL_OPAQUE,
                roughness, metalness, dhEmission, sss,
                water ? 1.333 : max(dhHeader.params.z, 1.0), water ? 1.0 : 0.0,
                emissive ? EMISSION_SOURCE_UNIFORM : EMISSION_SOURCE_NONE);
        if (water && entering) payload.flags |= PAYLOAD_WATER_ENTERING;
        return;
    }
    float2 attribs = attr.barycentrics;
    uint rayCone = payload.rayCone;

    // Particle billboard: same geom-table/UV/bindless-atlas path as entities. Albedo is atlas texel *
    // per-particle colour; raygen treats material 2 as a primary-visible diffuse receiver with no
    // emission or specular response. Reached only by the primary ray (instance mask 0x02). Cutout: rahit.
    if ((InstanceID() & PARTICLE_BIT) != 0u) {
        uint idx = InstanceID() & IDX_MASK;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[idx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        ConstPtr<uint> ib = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvb = ConstPtr<float2>(g.uvAddr);
        uint p0 = ib[3u * pid + 0u];
        uint p1 = ib[3u * pid + 1u];
        uint p2 = ib[3u * pid + 2u];
        float3 pbary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 puv = pbary.x * uvb[p0] + pbary.y * uvb[p1] + pbary.z * uvb[p2];
        int pslot = int(pr.tint.w + 0.5);
        float3x3 o2w = (float3x3)ObjectToWorld3x4();
        float3 pp0 = mul(o2w, HitTriangleVertexPosition(0));
        float3 pp1 = mul(o2w, HitTriangleVertexPosition(1));
        float3 pp2 = mul(o2w, HitTriangleVertexPosition(2));
        float particleLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(pslot)]),
                pp0, pp1, pp2, uvb[p0], uvb[p1], uvb[p2]);
        float3 pn = normalize(pr.normal.xyz);
        if (dot(pn, WorldRayDirection()) > 0.0) {
            pn = -pn;
        }
        payload.albedo = srgbToLinear(entityAlbedoTex[NonUniformResourceIndex(pslot)].SampleLevel(puv, particleLod).rgb) * pr.tint.rgb;
        payload.normal = pn;
        payload.hitT = RayTCurrent();
        // Per-particle motion vector: interpolate the captured per-vertex displacement (uniform across
        // the billboard's verts) with the same indices/barycentrics as the UV. dispAddr == 0 falls back
        // to rigidDisp, which particles write as zero.
        if (g.dispAddr != 0) {
            ConstPtr<float4> pd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = pbary.x * pd[p0].xyz + pbary.y * pd[p1].xyz + pbary.z * pd[p2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = float3(0.0, 0.0, 0.0);
        payloadDisableParallax(payload);
        payloadSetPacked(payload, MATERIAL_PARTICLE, 1.0, 0.0, 0.0, 0.0,
                1.0, 0.0, EMISSION_SOURCE_NONE);
        return;
    }
    // Dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
    // geometry; the low bits index the entity geometry table, not the section table.
    if ((InstanceID() & ENTITY_BIT) != 0u) {
        uint eidx = InstanceID() & ~ENTITY_BIT;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[eidx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        // Rigid-reuse instances (still / spinning entities re-referencing a cached BLAS) carry a real
        // rotation in the instance transform; per-frame-built entities are identity. The cached prim
        // normal (and the TBN source positions below) are object-space — rotate them into world space.
        // Identity/translation instances make this a no-op.
        float3x3 entityO2w = (float3x3)ObjectToWorld3x4();
        // Orient the geometric normal toward the viewer first (so the _n TBN is built in the right
        // hemisphere — same fix as terrain).
        float3 vdir = -WorldRayDirection();
        float3 n = normalize(mul(entityO2w, pr.normal.xyz));
        bool entering = dot(WorldRayDirection(), n) < 0.0;
        if (dot(n, vdir) < 0.0) {
            n = -n;
        }
        float3 geometricNormal = n;
        // Interpolate the captured entity-texture UV (same scheme as terrain) and sample the bindless
        // per-type texture; tint.rgb is the model colour multiplier (white for most, tinted for sheep/…).
        ConstPtr<uint> eidxBuf = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> euv = ConstPtr<float2>(g.uvAddr);
        uint e0 = eidxBuf[3u * pid + 0u];
        uint e1 = eidxBuf[3u * pid + 1u];
        uint e2 = eidxBuf[3u * pid + 2u];
        float3 ebary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 euvCoord = ebary.x * euv[e0] + ebary.y * euv[e1] + ebary.z * euv[e2];
        int texSlot = int(pr.tint.w + 0.5);
        float3 ep0 = mul(entityO2w, HitTriangleVertexPosition(0));
        float3 ep1 = mul(entityO2w, HitTriangleVertexPosition(1));
        float3 ep2 = mul(entityO2w, HitTriangleVertexPosition(2));
        float entityLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(texSlot)]),
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2]);
        if ((ConstPtr<WorldPush>(pc.worldPushAddr)[0].flags & 0x20u) == 0u) {
            entityLod = floor(entityLod);
        }
        MaterialHeader header = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
        float entityReliefOffset;
        float entityReliefScale;
        float2 entityReliefPlanarOffset;
        entityReliefOffset = 0.0;
        entityReliefScale = 0.0;
        entityReliefPlanarOffset = float2(0.0, 0.0);
        // Any-hit accepts/rejects alpha-tested entity geometry at the original UV. Displacing only the
        // later closest-hit lookup makes coverage come from one texel while colour/PBR come from another.
        // Keep POM on the opaque BLAS geometry only; cutout/translucent entities retain normal/PBR maps
        // at the exact UV whose alpha any-hit already validated.
        bool entityAllowsParallax = GeometryIndex() == 0u && header.model == MATERIAL_OPAQUE;
        if (entityAllowsParallax) {
            euvCoord = parallaxUv(header, euvCoord, entityLod, geometricNormal, ep0, ep1, ep2,
                    euv[e0], euv[e1], euv[e2], vdir,
                    entityReliefOffset, entityReliefScale, entityReliefPlanarOffset);
        }
        float4 entityTexel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(euvCoord, entityLod);
        uint material = header.model;
        float3 baseAlbedo = srgbToLinear(entityTexel.rgb) * pr.tint.rgb;
        float3 albedo = material == MATERIAL_GLASS
                ? lerp(float3(1.0, 1.0, 1.0), baseAlbedo, entityTexel.a) : baseAlbedo;
        Surface surface = evaluateMaterial(header, euvCoord, entityLod, albedo, geometricNormal,
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir,
                header.params.x, header.params.y, pr.normal.w, material == MATERIAL_OPAQUE);
        n = surface.normal;

        payload.albedo = albedo * surface.ao;
        payload.normal = n;
        payload.hitT = RayTCurrent();
        // Per-vertex motion vector: interpolate the captured per-vertex displacement with the same
        // indices/barycentrics used for the UV above. Rotation and skeletal/lid animation use a
        // per-vertex buffer; pure whole-object translation is packed into rigidDisp with no buffer.
        if (g.dispAddr != 0) {
            ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = surface.f0;
        if (material == MATERIAL_OPAQUE && entityAllowsParallax) {
            payloadSetParallax(payload, header, euvCoord, entityLod, geometricNormal,
                    ep0, ep1, ep2, euv[e0], euv[e1], euv[e2],
                    entityReliefOffset, entityReliefScale, entityReliefPlanarOffset);
        } else {
            payloadDisableParallax(payload);
        }
        float emission = material == MATERIAL_OPAQUE ? surface.emission : 0.0;
        float sss = material == MATERIAL_OPAQUE ? surface.sss : 0.0;
        payloadSetPacked(payload, material, surface.roughness, surface.metalness,
                emission, sss, header.params.z, header.params.w,
                materialEmissionSource(header, emission));
        if (material == MATERIAL_WATER && entering) {
            payload.flags |= PAYLOAD_WATER_ENTERING;
        }
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    uint bucket = GeometryIndex();
    // Any-hit/SBT opt: the section BLAS has one fixed geometry per material bucket (solid / cutout /
    // translucent / water). PrimitiveIndex() restarts at 0 per geometry, so re-add this geometry's
    // triangle base to land in the section's packed prim/uv arrays (concatenated in BUCKET order).
    // Water prims keep tint.w == 1, converted to the packed material enum below.
    uint pid = PrimitiveIndex() + sec.triBase[bucket];
    TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[pid];
    MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
    float3 n = normalize(pr.normal.xyz);
    float3 tint = pr.tint.rgb;

    // Water prims are single-sided, wound outward from the fluid volume (RtFluidMesher), so the
    // UNFLIPPED geometric normal is a reliable "which side is water" signal: a ray travelling against
    // it is entering the volume, one travelling with it is exiting. Captured before the toward-viewer
    // flip below and carried in the payload so raygen can set inWater from hit orientation instead of
    // toggling parity per crossing (a single missing/extra face no longer desyncs the rest of the path).
    bool entering = dot(WorldRayDirection(), n) < 0.0;

    // Lever B: per-triangle corner UVs in primitive order. uvs[3*pid + k] is a contiguous,
    // directly-addressed load (no index buffer, no scattered vertex-UV gather), so it issues as soon as
    // pid is known and its latency overlaps the prim fetch instead of serialising behind an index load.
    // The index buffer still exists for the BLAS build; the shading path just no longer reads it.
    ConstPtr<float2> uvs = ConstPtr<float2>(sec.uvAddr);
    float2 uv0 = uvs[3u * pid + 0u];
    float2 uv1 = uvs[3u * pid + 1u];
    float2 uv2 = uvs[3u * pid + 2u];
    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    float2 uv = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    float3 tp0 = HitTriangleVertexPosition(0);
    float3 tp1 = HitTriangleVertexPosition(1);
    float3 tp2 = HitTriangleVertexPosition(2);
    float blockLod = rayConeTextureLod(rayCone, texSizePx(blockAlbedoAtlas), tp0, tp1, tp2, uv0, uv1, uv2);
    if ((ConstPtr<WorldPush>(pc.worldPushAddr)[0].flags & 0x20u) == 0u) {
        // The atlas sampler is nearest within a mip, but its mip mode is trilinear. Quantizing the LOD
        // makes the disabled mode visibly crisp instead of blending two pixel-art mip levels.
        blockLod = floor(blockLod);
    }

    // Orient the GEOMETRIC normal toward the viewer FIRST (double-sided geometry), so the normal-map TBN
    // is built in the correct hemisphere. Doing this before the map — rather than flipping the perturbed
    // normal afterward — is what avoids the grazing-angle black spots: a post-flip could invert a tipped
    // mapped normal through the surface and light the back face.
    float3 vdir = -WorldRayDirection(); // toward the viewer
    if (dot(n, vdir) < 0.0) {
        n = -n;
    }
    float3 geometricNormal = n;
    float terrainReliefOffset;
    float terrainReliefScale;
    float2 terrainReliefPlanarOffset;
    terrainReliefOffset = 0.0;
    terrainReliefScale = 0.0;
    terrainReliefPlanarOffset = float2(0.0, 0.0);
    // Terrain any-hit likewise alpha-tests the undisplaced atlas UV. Restrict relief to the opaque
    // geometry bucket so leaves, plants, grass overlays and translucent blocks cannot shade a different
    // texel than the one that established their coverage.
    bool terrainAllowsParallax = bucket == BUCKET_SOLID
            && materialHeader.model == MATERIAL_OPAQUE;
    if (terrainAllowsParallax) {
        uv = parallaxUv(materialHeader, uv, blockLod, geometricNormal,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                terrainReliefOffset, terrainReliefScale, terrainReliefPlanarOffset);
    }

    // Stained glass / ice: a thin colored filter resolved in raygen
    // (material 3). albedo carries the transmission tint = texel rgb mixed toward white by (1 − opacity),
    // so a more opaque texel tints transmitted light more strongly.
    if (materialHeader.model == MATERIAL_GLASS) {
        float4 gtex = blockAlbedoAtlas.SampleLevel(uv, blockLod);
        float3 gtexRgb = srgbToLinear(gtex.rgb);
        float3 glassAlbedo = lerp(float3(1.0, 1.0, 1.0), gtexRgb * tint, gtex.a);
        Surface glassSurface = evaluateMaterial(materialHeader, uv, blockLod, glassAlbedo, geometricNormal,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                materialHeader.params.x, materialHeader.params.y, 0.0, false);
        n = glassSurface.normal;
        // Translucent blocks (glass, ice, …) are breakable too — apply the same overlay here, reusing
        // gtex (already sampled above) rather than re-fetching blockAlbedoAtlas.
        payload.albedo = applyBreaking(glassAlbedo * glassSurface.ao,
                rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n);
        payload.normal = n;
        payload.hitT = RayTCurrent();
        payload.motionPrev = float3(0.0, 0.0, 0.0);
        payload.f0 = glassSurface.f0;
        payloadDisableParallax(payload);
        payloadSetPacked(payload, MATERIAL_GLASS, glassSurface.roughness,
                glassSurface.metalness, 0.0, 0.0, materialHeader.params.z,
                materialHeader.params.w, EMISSION_SOURCE_NONE);
        return;
    }

    // Water (tint.w == 1) carries the pure biome water tint (no grey water-texture multiply): raygen
    // shades the surface as a clear dielectric and only needs the tint to derive the per-channel
    // Beer–Lambert absorption. Opaque terrain uses textured albedo.
    payload.albedo = materialHeader.model == MATERIAL_WATER
            ? tint : srgbToLinear(blockAlbedoAtlas.SampleLevel(uv, blockLod).rgb) * tint;
    payload.hitT = RayTCurrent();
    payload.motionPrev = float3(0.0, 0.0, 0.0); // static terrain: camera-only motion vector
    uint material = materialHeader.model == MATERIAL_WATER ? MATERIAL_WATER : MATERIAL_OPAQUE;

    // normal.w packs the 0..1 block-light level plus a +2 offset flag for non-SOLID (cutout /
    // translucent) render layers, set at extraction so the hit can opt SOLID terrain out of SSS below.
    float ew = pr.normal.w;
    bool nonSolid = ew >= 1.5;
    float emission = nonSolid ? ew - 2.0 : ew; // heuristic emission source (block light level)
    Surface surface = evaluateMaterial(materialHeader, uv, blockLod, payload.albedo, geometricNormal,
            tp0, tp1, tp2, uv0, uv1, uv2, vdir,
            materialHeader.params.x, materialHeader.params.y, emission, nonSolid);
    n = surface.normal;
    payload.albedo *= surface.ao;
    // Block-breaking overlay: opaque/cutout terrain (water skips — fluids aren't breakable). Evaluate
    // it after normal mapping so decal projection uses the same shading orientation as the old path.
    if (material != MATERIAL_WATER) {
        payload.albedo = applyBreaking(payload.albedo, rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n);
    }
    payload.normal = n;
    payload.f0 = surface.f0;
    if (material == MATERIAL_OPAQUE && terrainAllowsParallax) {
        payloadSetParallax(payload, materialHeader, uv, blockLod, geometricNormal,
                tp0, tp1, tp2, uv0, uv1, uv2,
                terrainReliefOffset, terrainReliefScale, terrainReliefPlanarOffset);
    } else {
        payloadDisableParallax(payload);
    }
    payloadSetPacked(payload, material, surface.roughness, surface.metalness,
            surface.emission, surface.sss, materialHeader.params.z, materialHeader.params.w,
            materialEmissionSource(materialHeader, surface.emission));
    if (material == MATERIAL_WATER && entering) {
        payload.flags |= PAYLOAD_WATER_ENTERING;
    }
}
