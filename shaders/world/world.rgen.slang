// Perspective path tracer. invViewProj = inverse(proj * viewRot) maps clip to camera-relative world.
// Primary rays are reconstructed from two unprojected points (near and far plane) rather than assuming
// the eye is at the origin: Minecraft bakes view bobbing into the projection, so the effective eye comes
// from the matrix. camOffset shifts camera-relative space into the terrain's rebased coordinates. Depth
// is reversed-Z (near=1, far=0).
//
// Lighting is an iterative path-tracing loop in raygen, not recursion from closest-hit, so
// maxRayRecursionDepth stays 1. At each surface vertex:
//   * NEE toward the sun — a single analytic directional light, gated by one hit-object visibility query
//     (TerminateOnFirstHit | SkipClosestHit). Lambertian BRDF = albedo/PI.
//   * a cosine-weighted Lambertian continuation ray (throughput *= albedo; the PI and cos cancel).
// Sky / ambient light needs no separate AO pass: continuation rays that escape land in world.rmiss,
// which returns sky radiance — so soft sky fill and contact AO both fall out of the path integral.
// Russian roulette terminates deep paths. Output is HDR (R16G16B16A16_SFLOAT); the HDR -> LDR tonemap
// happens later at the display.comp seam.
//
// SER: the build emits EXT and NV shader-invocation-reorder variants from this source.
//
// Each frame emits a single SPP-averaged (still noisy) estimate; temporal convergence is the denoiser's
// job. With RR disabled the output is the raw noisy path trace — a reference view, not a converged image.
import world_common;

[[vk::push_constant]] WorldPushConstants pc;

[[vk::binding(0, 0)]] RaytracingAccelerationStructure topLevelAS;
// HDR trace target: linear radiance, may exceed 1. Tonemap happens later at the display.comp seam.
[[vk::binding(1, 0)]] [format("rgba16f")] RWTexture2D<float4> outImage;
// Guide buffers — first-hit (primary-visibility) attributes consumed by the denoiser/DLSS-RR. Written
// every frame regardless of accumulation. gNormal.w carries roughness; gDepth carries HW reversed-Z depth.
[[vk::binding(3, 0)]] [format("rgba16f")] RWTexture2D<float4> gNormal;     // xyz world normal, w roughness
[[vk::binding(4, 0)]] [format("rgba16f")] RWTexture2D<float4> gAlbedo;     // rgb diffuse albedo
[[vk::binding(5, 0)]] [format("r32f")]    RWTexture2D<float>  gDepth;      // HW reversed-Z depth
[[vk::binding(6, 0)]] [format("rg16f")]   RWTexture2D<float2> gMotion;     // screen-space motion (render px)
[[vk::binding(7, 0)]] [format("rgba16f")] RWTexture2D<float4> gSpecAlbedo; // specular albedo (0 — diffuse-only)
[[vk::binding(8, 0)]] [format("rg16f")]   RWTexture2D<float2> gSpecMotion; // reflection MVs for RR
// ReSTIR DI ping-pong history. A celestial sample stores its direction; a local sample stores its
// point relative to the current camera so RGBA16F keeps useful precision across distant/Voxy terrain.
// surface = oct-encoded normal.xy + camera distance + packed RIS normalization/sample count.
[[vk::binding(9, 0)]]  [format("rgba16f")] RWTexture2D<float4> restirA0;
[[vk::binding(10, 0)]] [format("rgba16f")] RWTexture2D<float4> restirA1;
[[vk::binding(11, 0)]] [format("rgba16f")] RWTexture2D<float4> restirB0;
[[vk::binding(12, 0)]] [format("rgba16f")] RWTexture2D<float4> restirB1;
// Shared canonical LabPBR height pages. Raygen samples these for visibility along the actual direction
// selected by each path vertex, so emissive and indirect paths receive the same relief masking as NEE.
[[vk::binding(1, 1)]] Sampler2D materialSurface0Tex[];
[[vk::binding(2, 1)]] Sampler2D materialNormalAoTex[];
[[vk::binding(3, 1)]] Sampler2D materialSurface1Tex[];

// Per-frame push data loaded once from the BDA buffer at the top of main() (same pattern as the GLSL
// `worldPush` global). Layout constants generated from this module's SPIR-V — see world_common.WorldPush.
static WorldPush worldPush;

// Ray payloads. Module-level statics so the helper functions (visibility / refractedGuideHit /
// specularReflectionMotion / tracePath) can share them, mirroring the GLSL rayPayloadEXT globals.
static Payload payload;
static float4 shadowVis; // rgb = transmittance; a = nearest water-crossing t (-1 = none)

// First-hit (primary-visibility) guide attributes, captured at bounce 0 of tracePath. The primary ray
// is deterministic (no AA jitter yet), so every SPP sample's bounce 0 yields identical values.
static float3 gv_normal;
static float3 gv_albedo;
static float3 gv_specAlb; // specular albedo fed to DLSS-RR for specular demodulation (0 = pure diffuse).
static float gv_rough;
static float gv_emission;
static uint gv_emissionSource;
// Linear VIEW-SPACE depth guide inputs; sky resolves to a large far value (no negative sentinel).
static float gv_depth;
static float3 gv_hitCamRel;       // primary-surface hit position relative to the current camera
static float3 gv_motionHitCamRel; // motion-guide hit position; water tracks the refracted hit instead
static bool gv_motionUseRefracted; // true when the MV tracks the refracted hit (its own reprojection delta)
// World-space displacement of the motion-guide surface since the previous frame (0 for static
// terrain/sky, an entity's per-vertex/object motion for a dynamic hit).
static float3 gv_motionObjDisp;

uint payloadMaterial() { return payload.flags & PAYLOAD_MATERIAL_MASK; }
uint payloadEmissionSource() {
    return (payload.flags >> PAYLOAD_EMISSION_SOURCE_SHIFT) & PAYLOAD_EMISSION_SOURCE_MASK;
}
// Set by world.rchit only on a MATERIAL_WATER hit (see PAYLOAD_WATER_ENTERING) — whether the ray was
// travelling into the water volume (vs. out of it) at this hit's face.
bool payloadWaterEntering() { return (payload.flags & PAYLOAD_WATER_ENTERING) != 0u; }
float payloadRoughness() { return unpackHalf2(payload.roughMetal).x; }
float payloadMetalness() { return unpackHalf2(payload.roughMetal).y; }
float payloadEmission() { return unpackHalf2(payload.emissionSss).x; }
float payloadSss() { return unpackHalf2(payload.emissionSss).y; }
float3 payloadAverageEmissionChroma() {
    uint packed = payload.flags >> 8;
    float3 chroma = float3(packed & 0xffu, (packed >> 8) & 0xffu,
            (packed >> 16) & 0xffu) * (1.0 / 255.0);
    float energy = luminance(chroma);
    return energy > 1.0e-5 ? chroma / energy : float3(0.0, 0.0, 0.0);
}
float payloadIor() { return unpackHalf2(payload.iorTransmission).x; }
float payloadTransmission() { return unpackHalf2(payload.iorTransmission).y; }
void payloadSetTraceState(bool showCelestial, float rayConeWidth, float rayConeSpread) {
    payload.flags = showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u;
    payload.rayCone = packHalf2(float2(rayConeWidth, rayConeSpread));
    payload.parallaxPageSign = 0x00FFFFFFu;
    payload.parallaxPlanarOffset = 0u;
    payload.parallaxOffset = 0.0;
}

static const float PI = 3.14159265359;
static const float INV_PI = 0.31830988618;

// The analytic sky (gradient + sun/moon discs + stars) is computed in world.rmiss. On a miss, raygen
// reads payload.albedo and accumulates that sky radiance. The directional NEE light is pushed separately
// as worldPush.lightDir / worldPush.lightRadiance / worldPush.sunDir.
static const float EMISSIVE_STRENGTH = 5.0; // HDR radiance of a full (level-15) emitter, modulated by albedo
static const float SURF_BIAS = 0.005;  // offset secondary-ray origins along the normal (anti-acne)
static const float RAY_TMIN = 0.001;   // tiny tmin: the normal offset already clears the surface

// Water is a smooth dielectric. IOR 1.333; absorption is per-block Beer-Lambert extinction.
static const float WATER_IOR = 1.333;
static const float3 SKY_SPEC_ALBEDO = float3(0.5, 0.5, 0.5); // RR guide default for sky pixels.
static const float3 SKY_DIFF_ALBEDO = float3(0.5, 0.5, 0.5);

// Stained glass / ice (material 3): a thin colored dielectric. IOR ~1.5 (soda-lime glass).
// Transmitted-ray origin offset when crossing a glass face. Must be SMALLER than the terrain-side
// TRANSLUCENT_INSET (RtTerrain, 2e-4 blocks): glass is recessed into its block by that inset, so a face
// touching a slab/stair leaves a tiny gap before the neighbour's surface. A large bias (SURF_BIAS) would
// restart the ray past that neighbour and see through it; this lands the ray inside the gap so the
// neighbour is still hit, while still clearing the glass surface to avoid self-intersection.
static const float GLASS_TRANSMIT_BIAS = 1.0e-4;

// Ray-cone texture LOD: raygen tracks a one-pixel footprint along the path, and closest-hit converts it
// to texture mip levels from the local UV/world-space scale. Diffuse/glossy bounces widen the cone so
// indirect texture fetches don't keep sampling mip 0 through broad lobes.
static const float RAY_CONE_MIN_SPREAD = 1.0e-5;
static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
static const float RAY_CONE_DIFFUSE_SPREAD = 0.25;
static const float RAY_CONE_GLOSSY_SPREAD_SCALE = 0.35;

float sampleParallaxHeight(uint page, float2 uv, float lod) {
    Sampler2D pageTex = materialNormalAoTex[NonUniformResourceIndex(page)];
    if ((worldPush.flags & 0x20u) == 0u) {
        uint w, h;
        pageTex.GetDimensions(w, h);
        float mip = floor(lod);
        float2 size = max(float2(1.0, 1.0), floor(float2(w, h) / exp2(mip)));
        uv = (floor(uv * size) + 0.5) / size;
        lod = mip;
    }
    return pageTex.SampleLevel(uv, lod).a;
}

// Trace the relief segment for an arbitrary outgoing path direction. Closest-hit exports the exact
// POM intersection, including its continuous physical height, so this starts at that intersection
// instead of re-sampling an approximate "first hit". It is called for direct light and for the actual
// radiance continuation at every bounce; arbitrary emissive blocks are therefore covered naturally.
float traceReliefVisibility(float3 dir, float cameraDistance) {
    uint page = payload.parallaxPageSign & 0x00FFFFFFu;
    if (page == 0x00FFFFFFu || worldPush.parallaxParams.x <= 1.0e-5
            || payload.parallaxOffset <= 1.0e-6) {
        return 1.0;
    }
    float3 geometricNormal = unpackOctNormal(payload.parallaxGeomOct);
    float3 tangent = unpackOctNormal(payload.parallaxTangentOct);
    float handedness = (payload.parallaxPageSign & 0x80000000u) != 0u ? -1.0 : 1.0;
    float3 bitangent = cross(geometricNormal, tangent) * handedness;
    float3 d = normalize(dir);
    float3 dirTs = float3(dot(d, tangent), dot(d, bitangent), dot(d, geometricNormal));
    if (dirTs.z <= 0.04) {
        return 0.0;
    }

    float2 lodScale = unpackHalf2(payload.parallaxLodScale);
    float lod = lodScale.x;
    float maxLod = float((payload.parallaxPageSign >> 24) & 0x7Fu);
    bool smoothing = (worldPush.flags & 0x20u) != 0u;
    // Must exactly match parallaxUv(): tracing a sharper height mip than the visible POM hit makes
    // the exported surface point start below the shadow height field and produces black self-shadow acne.
    float materialLod = min(smoothing ? lod + PARALLAX_SMOOTH_LOD_BIAS : floor(lod), maxLod);
    float2 localUv = payload.parallaxLocalUv;
    float maxDistance = max(worldPush.parallaxParams.w, 1.0);
    if (cameraDistance >= maxDistance) {
        return 1.0;
    }
    // Closest-hit exports the exact scale that produced this POM intersection (distance, remaining
    // texel detail, grazing angle and edge fade included). Reconstructing a different scale here
    // placed the visibility ray below the relief surface and created the large black cavities.
    float heightScale = lodScale.y;
    if (heightScale <= 1.0e-5) {
        return 1.0;
    }
    float4 materialUv = payload.parallaxMaterialUv;
    // parallaxOffset = heightScale * authoredHeight at the exact refined POM intersection.
    // Using it avoids the old second, potentially different first-hit decision in raygen.
    float startHeight = saturate(payload.parallaxOffset / max(heightScale, 1.0e-6));
    float remainingDepth = 1.0 - startHeight;
    if (remainingDepth <= 1.0e-5) {
        return 1.0;
    }

    // Use the entire configured POM quality. The old implementation silently halved it and capped
    // self-shadow traversal at 16 samples, which skipped thin blockers in high-resolution packs.
    int steps = clamp(int(round(worldPush.parallaxParams.y)), 4, 32);
    float2 totalOffset = (dirTs.xy / max(dirTs.z, 0.08))
            * (heightScale * remainingDepth);
    float heightBias = max(1.0 / 1024.0, 0.08 / float(steps));
    [loop]
    for (int stepIndex = 1; stepIndex <= 32; ++stepIndex) {
        if (stepIndex > steps) break;
        float t = float(stepIndex) / float(steps);
        float2 sampleUv = localUv + totalOffset * t;
        if (any(sampleUv < float2(0.0, 0.0)) || any(sampleUv > float2(1.0, 1.0))) break;
        float rayHeight = lerp(startHeight, 1.0, t);
        float blockerHeight = sampleParallaxHeight(page,
                materialUv.xy + sampleUv * materialUv.zw, materialLod);
        float blocker = blockerHeight - rayHeight;
        if (blocker > heightBias) {
            // Binary visibility, exactly like ordinary opaque scene geometry. Softness comes only
            // from path samples/light extent and temporal accumulation, never from an art multiplier.
            return 0.0;
        }
    }
    return 1.0;
}

float3 payloadHitPosition(float3 origin, float3 direction) {
    float3 hit = origin + direction * payload.hitT;
    if ((payload.parallaxPageSign & 0x00FFFFFFu) != 0x00FFFFFFu
            && payload.parallaxOffset > 0.0) {
        float3 geometricNormal = unpackOctNormal(payload.parallaxGeomOct);
        float3 tangent = unpackOctNormal(payload.parallaxTangentOct);
        float handedness = (payload.parallaxPageSign & 0x80000000u) != 0u ? -1.0 : 1.0;
        float3 bitangent = cross(geometricNormal, tangent) * handedness;
        float2 planarOffset = unpackHalf2(payload.parallaxPlanarOffset);
        hit += tangent * planarOffset.x + bitangent * planarOffset.y
                + geometricNormal * payload.parallaxOffset;
    }
    return hit;
}

// Per-channel Beer–Lambert extinction from a water body's biome tint (carried in payload.albedo for
// water hits, or worldPush.waterParams.xyz for the camera's own biome when starting submerged). A blue ocean
// tint (low red) absorbs red fastest → bluer with depth; swamp green-brown shifts the hue. The floor
// keeps even a white-tinted body very slightly absorbing so deep water never reads as clear vacuum.
static const float WATER_DENSITY = 0.05;
static const float3 WATER_ABSORB_FLOOR = float3(0.015, 0.010, 0.008);
float3 waterExtinction(float3 tint) {
    return WATER_ABSORB_FLOOR + WATER_DENSITY * (float3(1.0, 1.0, 1.0) - clamp(tint, 0.0, 1.0));
}

// Animated surface waves — a directional wave spectrum, gradient-only (the surface stays geometrically
// flat; only the normal tilts). Three things make it read as water rather than animated glass:
//  * Sharp-crested profile: each component is h = a·exp(S·(sin φ − 1)) — peaked crests, wide flat
//    troughs (pure sines look like rolling jelly). Still C∞ smooth with an exact analytic gradient.
//  * Real deep-water dispersion ω = √(g·k): long swell strides past (λ=14 m → ~4.7 m/s) while short
//    chop flutters nearly in place — wrong relative speeds are the biggest tell of fake water.
//  * Wind + meander: headings spread around a fixed wind direction (tight for the swell, wide for the
//    chop), and the largest components' phase is modulated along their crest (chain-ruled into the
//    gradient) so wavefronts wander instead of ruling straight periodic lines across the sea.
// The domain is world-anchored (see applyWaterWaves) so the pattern is pinned in world space, and the
// caustics (waterCaustic) differentiate this same field, so they stay in sync with the visible ripples.
static const float WATER_WAVE_STRENGTH = 0.3;  // slope scale (higher = choppier glints / more broken reflections)
static const float WAVE_SPEED = 0.8;           // global time scale; dispersion keeps relative speeds physical
static const int   WAVE_COUNT = 10;            // λ = 14 m … 0.38 m (each octave 1.5× shorter)
static const float WAVE_G = 9.81;
static const float2 WAVE_WIND = normalize(float2(1.0, 0.35)); // dominant travel direction
static const float WAVE_MEANDER = 1.1;         // crest phase-wobble amplitude (rad) on the long components
float2 waterWaveGrad(float2 p, float t) {
    // Per-wave steepness a·k: gentle in the long swell, concentrated in the mid/short chop.
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038 };
    t *= WAVE_SPEED;
    float2 g = float2(0.0, 0.0);
    float lambda = 14.0;
    for (int i = 0; i < WAVE_COUNT; i++) {
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);   // deep-water dispersion: phase speed √(g/k)
        // Heading: deterministic irregular spread about the wind, widening down the spectrum.
        float spread = (0.15 + 0.11 * float(i)) * sin(float(i) * 2.4 + 1.3);
        float cs = cos(spread), sn = sin(spread);
        float2 d = float2(WAVE_WIND.x * cs - WAVE_WIND.y * sn, WAVE_WIND.x * sn + WAVE_WIND.y * cs);
        float ph = k * dot(d, p) - w * t + 1.7 * float(i) * float(i);
        float2 dph = k * d;
        if (i < 4) { // meander only the components whose fronts are long enough to read as lines
            float2 pd = float2(-d.y, d.x);
            float wph = 0.4 * k * dot(pd, p) + 0.5 * w * t;
            ph += WAVE_MEANDER * sin(wph);
            dph += (WAVE_MEANDER * cos(wph) * 0.4 * k) * pd;
        }
        float S = 1.2 + 0.12 * float(i); // crest sharpness: the short chop is choppier
        float e = exp(S * (sin(ph) - 1.0));
        g += (Q[i] / k) * (S * cos(ph) * e) * dph; // a = Q/k; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ
        lambda /= 1.5;
    }
    return g * WATER_WAVE_STRENGTH;
}

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing side faces (|n.y| small)
// keep their flat geometric normal. `worldXZ` must be a world-stable coordinate (rebased hit + the pushed
// anchor) so the ripples stay pinned in the world. `nGeo` is already oriented toward the viewer, so the
// down-viewed underside (n.y < 0) takes the mirrored slope (-up).
float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    float2 grad = waterWaveGrad(worldXZ, t);
    float3 up = normalize(float3(-grad.x, 1.0, -grad.y));
    return nGeo.y >= 0.0 ? up : -up;
}

// ---- Water caustics. Sunlight refracting through the waved surface converges/diverges before it
// reaches an underwater receiver; the analytic wave field makes the true focusing factor computable
// instead of faked with a scrolling texture. Where the shadow ray of an underwater NEE vertex crossed
// water (recorded by world.rahit in shadowVis.a), evaluate the horizontal landing position of the
// refracted sun ray as a function of surface position and finite-difference it: the caustic intensity is
// the inverse Jacobian determinant of that surface→floor mapping (area compression = brightening, real
// fold caustics where det → 0). Because this uses the SAME wave field as the visible surface normals,
// the caustic pattern stays in sync with the ripples, and the per-sample sun-quad jitter (sampleSquare)
// shifts the pattern per sample → caustics physically blur with depth under DLSS-RR accumulation.
static const float CAUSTIC_EPS = 0.25; // finite-difference step (m); also low-passes sub-step wave detail
static const float CAUSTIC_MAX = 6.0;  // focusing clamp: keeps fold-line singularities from becoming fireflies

// Horizontal landing offset of a refracted sun ray entering the surface at world-stable xz, intersected
// with the receiver plane h metres below. inc = sunlight travel direction (downward, unit).
float2 causticLanding(float2 xz, float t, float3 inc, float h) {
    float2 g = waterWaveGrad(xz, t);
    float3 nw = normalize(float3(-g.x, 1.0, -g.y));
    float3 rdw = refract(inc, nw, 1.0 / WATER_IOR); // air→water: never TIR
    return xz + rdw.xz * (h / max(-rdw.y, 0.05));
}

float waterCaustic(float3 exitPos, float3 lightDir, float waterDist) {
    // Grazing light: the receiver-plane intersection degenerates and caustics vanish in reality anyway
    // (long slant paths absorb + the pattern smears out) — fade the effect to neutral 1.0.
    float fade = smoothstep(0.06, 0.18, lightDir.y);
    if (fade <= 0.0) return 1.0;
    float h = max(waterDist * lightDir.y, 0.05); // vertical depth of the receiver below the exit point
    float t = worldPush.waterParams.w;
    float3 inc = -lightDir;
    float2 base = exitPos.xz + worldPush.waterAnchor.xy;  // world-stable wave domain (same as applyWaterWaves)
    float2 p0 = causticLanding(base, t, inc, h);
    float2 px = causticLanding(base + float2(CAUSTIC_EPS, 0.0), t, inc, h);
    float2 pz = causticLanding(base + float2(0.0, CAUSTIC_EPS), t, inc, h);
    float det = abs((px.x - p0.x) * (pz.y - p0.y) - (px.y - p0.y) * (pz.x - p0.x));
    // Flat water ⇒ all landings displace identically ⇒ det = eps² ⇒ focus = 1 (energy-neutral); the
    // clamp bounds the fold-line spikes whose energy the surrounding darkening already paid for.
    float focus = (CAUSTIC_EPS * CAUSTIC_EPS) / max(det, 1.0e-5);
    return lerp(1.0, min(focus, CAUSTIC_MAX), fade);
}

// GGX BRDF. Roughness is clamped away from 0 so the delta sun's specular highlight stays finite
// and the VNDF sample is well-conditioned. alpha = roughness^2 (perceptual -> GGX) throughout.
static const float MIN_ROUGH = 0.045;

float luminance(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

// GGX normal distribution.
float ggxD(float ndh, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1.0e-7);
}

// Smith GGX masking term for one direction (separable form; G2 = G1(v) * G1(l)).
float ggxG1(float ndx, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

float3 fresnelSchlick(float cosT, float3 f0) {
    float m = clamp(1.0 - cosT, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}

// Henyey–Greenstein phase function (with 4π normalisation so it integrates to 1 over the sphere).
// g=0 → isotropic; g>0 → forward-scatter peak at cosT=1 (light and view aligned through a thin slab).
static const float SSS_G        = 0.6; // forward-scatter anisotropy; leaves/grass are quite directional
static const float SSS_STRENGTH = 1.0; // 1.0 ≈ 2.5× the Lambertian at peak (sss=1, cosT=1, backNdl=1)
static const int   MAX_SSS_BOUNCE = 1; // fire the transmission shadow ray on primary + first indirect hit only
float hg(float cosT, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(0.0, 1.0 + g2 - 2.0 * g * cosT), 1.5));
}

// NVIDIA RR guide-buffer helper: integrated/view-dependent specular reflectivity, not raw F0.
// alpha is GGX alpha (perceptual roughness squared), NoV is the first-hit view cosine.
float3 rrSpecularAlbedo(float3 specularColor, float alpha, float NoV) {
    NoV = abs(NoV);
    float NoV2 = NoV * NoV;
    float alpha2 = alpha * alpha;
    float4 X = float4(1.0, NoV, NoV2, NoV * NoV2);
    float4 Y = float4(1.0, alpha, alpha2, alpha * alpha2);

    float2 m1 = float2(
            dot(float2(0.99044, -1.28514), X.xy),
            dot(float2(1.29678, -0.755907), X.xy));
    float3 Xxyw = float3(X.x, X.y, X.w);
    float3 m2 = float3(
            dot(float3(1.0, 2.92338, 59.4188), Xxyw),
            dot(float3(20.3225, -27.0302, 222.592), Xxyw),
            dot(float3(121.563, 626.13, 316.627), Xxyw));
    float bias = dot(m1, Y.xy) / max(dot(m2, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    float2 m3 = float2(
            dot(float2(0.0365463, 3.32707), X.xy),
            dot(float2(9.0632, -9.04756), X.xy));
    float3 Xxzw = float3(X.x, X.z, X.w);
    float3 m4 = float3(
            dot(float3(1.0, 3.59685, -1.36772), Xxzw),
            dot(float3(9.04401, -16.3174, 9.22949), Xxzw),
            dot(float3(5.56589, 19.7886, -20.2123), Xxzw));
    float scale = dot(m3, Y.xy) / max(dot(m4, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    bias *= clamp(specularColor.g * 50.0, 0.0, 1.0);
    return specularColor * max(0.0, scale) + float3(max(0.0, bias), max(0.0, bias), max(0.0, bias));
}

// Exact (unpolarized) Fresnel reflectance for a dielectric interface. cosI is the incidence cosine on
// the incoming side; etaI/etaT are the IORs the ray travels from / into. Returns 1.0 on total internal
// reflection (sin of the transmitted angle >= 1), which matches refract() returning the zero vector —
// so reflect/refract selection and TIR stay consistent.
float fresnelDielectric(float cosI, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosI - etaT * cosT) / (etaI * cosI + etaT * cosT);
    float rp = (etaI * cosT - etaT * cosI) / (etaI * cosT + etaT * cosI);
    return 0.5 * (rs * rs + rp * rp);
}

// PCG hash RNG.
uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
float rndf(inout uint s) { return float(pcg(s)) * (1.0 / 4294967296.0); }

float3 primaryRayDir(float2 ndc) {
    float4 nearH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    return normalize(farP - nearP);
}

float primaryRayConeSpread(float2 ndc, float2 size, float3 dir) {
    float2 onePixelNdc = 2.0 / size;
    float3 dx = primaryRayDir(ndc + float2(onePixelNdc.x, 0.0));
    float3 dy = primaryRayDir(ndc + float2(0.0, onePixelNdc.y));
    return max(max(length(cross(dir, dx)), length(cross(dir, dy))), RAY_CONE_MIN_SPREAD);
}

// Cosine-weighted hemisphere sample about n (Malley's method). For a Lambertian BRDF this is the
// importance-sampling match: BRDF*cos/pdf = (albedo/PI)*cos / (cos/PI) = albedo, so the continuation
// throughput is just *= albedo with no PI/cos bookkeeping left over.
float3 cosineDir(float3 n, inout uint s) {
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 local = float3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1)));
    return normalize(local.x * t + local.y * b + local.z * n);
}

// Soft shadows: sample a direction within the light's SQUARE angular extent about `axis`. MC's
// sun/moon are square quads, so the NEE shadow ray samples a square (not a cone) — the same square the
// visible disc in world.rmiss uses, built from the same celestial tangent frame (right = arc-travel
// direction, from worldPush.celestial.xyz). Averaged over frames by DLSS-RR this yields soft penumbrae that
// widen with occluder distance (contact-hardening) for free. halfAngle <= 0 ⇒ exact direction (hard).
float3 sampleSquare(float3 axis, float halfAngle, inout uint s) {
    float3 right = normalize(cross(axis, worldPush.celestial.xyz));
    float3 up = cross(right, axis);
    float t = tan(halfAngle);
    float u = (rndf(s) * 2.0 - 1.0) * t;
    float v = (rndf(s) * 2.0 - 1.0) * t;
    return normalize(axis + u * right + v * up);
}

// ---- ReSTIR DI: celestial + every emissive geometry material ------------------------------------
// CPU extraction supplies real emitting triangles from terrain, DH/Voxy proxies, entities and block
// entities. A fresh local candidate is a uniform point on one triangle. Reservoir history stores that
// point rather than a list index, so chunk/entity list reordering cannot retarget an old reservoir.
// The selected local point is traced through the normal closest-hit path for exact geometry,
// orientation and occlusion. Textured emission is energy-averaged on the CPU: sampling the sparse mask
// again here created mathematically unbiased but visually catastrophic 50x-250x fireflies.
static const uint RESTIR_ENABLED = 0x80u;
static const uint RESTIR_SPATIAL = 0x100u;
static const uint RESTIR_HISTORY_VALID = 0x200u;
static const float RESTIR_MAX_M = 32.0;
static const float RESTIR_MAX_W = 64.0;

struct EmissiveTriangle {
    float4 p0;
    float4 p1;
    float4 p2;
    float4 uv01;    // retained upload ABI; not needed by the variance-bounded estimator
    float4 uv2Meta; // retained upload ABI; not needed by the variance-bounded estimator
};

struct RestirSample {
    // measure < 0: celestial direction. measure > 0: emitter point and average emitted power.
    float3 value;
    float measure;
};

struct RestirReservoir {
    RestirSample sample;
    float weightSum;
    float selectedPHat;
    float M;
};

float2 restirEncodeNormal(float3 value) {
    float3 n = normalize(value);
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    float2 oct = n.xy;
    if (n.z < 0.0) {
        oct = (1.0 - abs(oct.yx)) * float2(oct.x >= 0.0 ? 1.0 : -1.0,
                oct.y >= 0.0 ? 1.0 : -1.0);
    }
    return oct * 0.5 + 0.5;
}

float3 restirDecodeNormal(float2 encoded) {
    float2 oct = encoded * 2.0 - 1.0;
    float3 n = float3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * float2(n.x >= 0.0 ? 1.0 : -1.0,
                n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

float4 restirLoadPreviousSample(int2 pixel) {
    // Even frames write A and read B; odd frames write B and read A.
    return (worldPush.frameIndex & 1u) == 0u ? restirB0[pixel] : restirA0[pixel];
}

float4 restirLoadPreviousSurface(int2 pixel) {
    return (worldPush.frameIndex & 1u) == 0u ? restirB1[pixel] : restirA1[pixel];
}

void restirStoreCurrent(int2 pixel, float4 sampleData, float4 surfaceData) {
    if ((worldPush.frameIndex & 1u) == 0u) {
        restirA0[pixel] = sampleData;
        restirA1[pixel] = surfaceData;
    } else {
        restirB0[pixel] = sampleData;
        restirB1[pixel] = surfaceData;
    }
}

float restirPackState(float normalization, float sampleCount) {
    uint w = uint(round(saturate(normalization / RESTIR_MAX_W) * 255.0));
    uint m = uint(clamp(round(sampleCount), 1.0, RESTIR_MAX_M));
    // The payload stays below half exponent 0x1f, so image-store conversion preserves the bit pattern
    // instead of canonicalizing a NaN. f32tof16(restored) recovers both quantized lanes.
    return f16tof32((m << 8) | w);
}

void restirUnpackState(float packed, out float normalization, out float sampleCount) {
    uint bits = f32tof16(packed);
    normalization = float(bits & 0xffu) * (RESTIR_MAX_W / 255.0);
    sampleCount = float((bits >> 8) & 0x3fu);
}

void restirUpdate(inout RestirReservoir reservoir, RestirSample sample, float pHat,
                  float candidateWeight, float candidateM, inout uint seed) {
    if (!(candidateWeight > 0.0) || !(candidateM > 0.0)) {
        return;
    }
    float newWeightSum = reservoir.weightSum + candidateWeight;
    if (rndf(seed) * newWeightSum < candidateWeight) {
        reservoir.sample = sample;
        reservoir.selectedPHat = pHat;
    }
    reservoir.weightSum = newWeightSum;
    reservoir.M += candidateM;
}

float3 restirBrdfResponse(float3 n, float3 v, float3 lightDir, float3 diffAlb,
                          float3 F0, float rough, bool pbr) {
    float ndl = max(0.0, dot(n, lightDir));
    if (ndl <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 brdf = diffAlb * INV_PI;
    if (pbr) {
        float3 h = normalize(lightDir + v);
        float ndh = max(0.0, dot(n, h));
        float ndv = max(1.0e-4, dot(n, v));
        float vdh = max(0.0, dot(v, h));
        float D = ggxD(ndh, rough);
        float G = ggxG1(ndv, rough) * ggxG1(ndl, rough);
        float3 F = fresnelSchlick(vdh, F0);
        brdf += (D * G) * F / (4.0 * ndv * ndl);
    }
    return brdf * ndl;
}

float3 restirCelestialTarget(float3 n, float3 v, float3 lightDir, float3 diffAlb,
                             float3 F0, float rough, bool pbr) {
    return restirBrdfResponse(n, v, lightDir, diffAlb, F0, rough, pbr)
            * worldPush.lightRadiance.xyz;
}

bool restirDirection(RestirSample sample, float3 hitPos, out float3 direction, out float distance) {
    if (sample.measure < 0.0) {
        float length2 = dot(sample.value, sample.value);
        if (!(length2 > 0.8 && length2 < 1.2)) {
            direction = float3(0.0, 1.0, 0.0);
            distance = 0.0;
            return false;
        }
        direction = normalize(sample.value);
        distance = 10000.0;
        return true;
    }
    float3 delta = sample.value - hitPos;
    float distance2 = dot(delta, delta);
    if (!(sample.measure > 0.0 && sample.measure < 65504.0)
            || !(distance2 > 1.0e-4 && distance2 < 1.0e8)) {
        direction = float3(0.0, 1.0, 0.0);
        distance = 0.0;
        return false;
    }
    distance = sqrt(distance2);
    direction = delta / distance;
    return true;
}

float restirPHat(RestirSample sample, float3 hitPos, float3 n, float3 v,
                 float3 diffAlb, float3 F0, float rough, bool pbr) {
    float3 direction;
    float distance;
    if (!restirDirection(sample, hitPos, direction, distance)) {
        return 0.0;
    }
    float3 response = restirBrdfResponse(n, v, direction, diffAlb, F0, rough, pbr);
    if (sample.measure < 0.0) {
        return max(luminance(response * worldPush.lightRadiance.xyz), 0.0);
    }
    // Average emitted power is retained in the reservoir, so the target and the initial proposal are
    // expressed in the same measure. Chroma and emitter orientation are applied after endpoint
    // validation; omitting them here is legal for RIS and keeps temporal reuse compact.
    return max(luminance(response) * sample.measure
            / max(distance * distance, 0.0625), 0.0);
}

uint64_t restirLightAddress() {
    return uint64_t(worldPush.breakingPad0) | (uint64_t(worldPush.breakingPad1) << 32);
}

RestirSample restirFreshLocal(inout uint seed, out float inversePdf) {
    RestirSample sample;
    sample.value = float3(0.0, 0.0, 0.0);
    sample.measure = 0.0;
    inversePdf = 0.0;
    uint count = worldPush.breakingPad2;
    uint64_t address = restirLightAddress();
    if (count == 0u || address == 0u) {
        return sample;
    }
    float cdfTarget = rndf(seed);
    uint lo = 0u;
    uint hi = count - 1u;
    [loop]
    while (lo < hi) {
        uint mid = lo + ((hi - lo) >> 1u);
        if (ConstPtr<EmissiveTriangle>(address)[mid].p0.w < cdfTarget) {
            lo = mid + 1u;
        } else {
            hi = mid;
        }
    }
    uint index = lo;
    EmissiveTriangle triangle = ConstPtr<EmissiveTriangle>(address)[index];
    float3 p0 = triangle.p0.xyz;
    float3 p1 = triangle.p1.xyz;
    float3 p2 = triangle.p2.xyz;
    float area = 0.5 * length(cross(p1 - p0, p2 - p0));
    if (!(area > 1.0e-8)) {
        return sample;
    }
    float su = sqrt(rndf(seed));
    float v = rndf(seed);
    float3 bary = float3(1.0 - su, su * (1.0 - v), su * v);
    sample.value = bary.x * p0 + bary.y * p1 + bary.z * p2;
    sample.measure = triangle.p2.w;
    inversePdf = triangle.p1.w * triangle.p1.w;
    return sample;
}

bool restirPreviousCandidate(int2 pixel, int2 size, float3 receiverNormal,
                             float expectedPreviousDepth,
                             out RestirSample sample, out float normalization, out float sampleCount) {
    sample.value = float3(0.0, 1.0, 0.0);
    sample.measure = 0.0;
    normalization = 0.0;
    sampleCount = 0.0;
    if (pixel.x < 0 || pixel.y < 0 || pixel.x >= size.x || pixel.y >= size.y) {
        return false;
    }
    float4 sampleData = restirLoadPreviousSample(pixel);
    float4 surfaceData = restirLoadPreviousSurface(pixel);
    restirUnpackState(surfaceData.w, normalization, sampleCount);
    bool celestial = sampleData.w < 0.0;
    float valueLength2 = dot(sampleData.xyz, sampleData.xyz);
    // Every comparison deliberately rejects NaN as well as uninitialized/overflowed half-float data.
    if ((celestial && !(valueLength2 > 0.8 && valueLength2 < 1.2))
            || (!celestial && (!(sampleData.w > 0.0 && sampleData.w < 65504.0)
                    || !(valueLength2 < 1.0e12)))
            || !(normalization > 0.0 && normalization <= RESTIR_MAX_W)
            || !(surfaceData.z > 0.0 && surfaceData.z < 20000.0)
            || !(sampleCount >= 1.0 && sampleCount <= RESTIR_MAX_M)) {
        return false;
    }
    float3 previousNormal = restirDecodeNormal(surfaceData.xy);
    if (!(dot(previousNormal, receiverNormal) > 0.88)) {
        return false;
    }
    // A /time jump or the sun-to-moon handoff changes the emitter proposal itself. Do not drag an old
    // celestial direction across the sky; ordinary frame-to-frame motion remains well inside this cone.
    if (celestial) {
        float emitterReuseAngle = max(worldPush.lightDir.w * 4.0, 0.025);
        if (!(dot(normalize(sampleData.xyz), normalize(worldPush.lightDir.xyz))
                > cos(emitterReuseAngle))) {
            return false;
        }
    }
    float depthTolerance = max(0.35, expectedPreviousDepth * 0.035);
    if (!(abs(surfaceData.z - expectedPreviousDepth) <= depthTolerance)) {
        return false;
    }
    // Local points were stored relative to the previous camera. Convert them into this frame's rebased
    // coordinates. camDelta = currentCamera - previousCamera; camOffset is the current rebased camera.
    sample.value = celestial
            ? sampleData.xyz
            : sampleData.xyz + worldPush.camOffset - worldPush.camDelta;
    sample.measure = sampleData.w;
    return true;
}

RestirSample restirSelectDirect(int2 pixel, int2 size, float3 receiverPos, float3 historyHitPos,
                                float3 receiverNormal, float3 motionPrev, float3 v, float3 diffAlb, float3 F0,
                                float rough, bool pbr, bool useHistory,
                                inout uint seed, out float normalization) {
    RestirReservoir reservoir;
    reservoir.sample.value = normalize(worldPush.lightDir.xyz);
    reservoir.sample.measure = -1.0;
    reservoir.weightSum = 0.0;
    reservoir.selectedPHat = 0.0;
    reservoir.M = 0.0;

    int candidates = clamp(int(round(worldPush.fogControl.w)), 1, 8);
    bool hasLocal = worldPush.breakingPad2 > 0u && restirLightAddress() != 0u;
    [loop]
    for (int i = 0; i < candidates; ++i) {
        RestirSample candidate;
        float inversePdf = 1.0;
        if (hasLocal) {
            candidate = restirFreshLocal(seed, inversePdf);
        } else {
            candidate.value = worldPush.lightDir.xyz;
            if (worldPush.lightDir.w > 0.0) {
                candidate.value = sampleSquare(candidate.value, worldPush.lightDir.w, seed);
            }
            candidate.measure = -1.0;
        }
        // Never mix the vastly brighter unoccluded celestial target into the local-light reservoir:
        // indoors it would win selection, fail its final shadow test, and amplify the rare local sample
        // by the cross-domain normalization. The celestial domain is evaluated independently below.
        float pHat = restirPHat(candidate, receiverPos, receiverNormal, v, diffAlb, F0, rough, pbr);
        restirUpdate(reservoir, candidate, pHat, pHat * inversePdf, 1.0, seed);
    }

    // Reproject this receiver into the previous frame. Dynamic geometry supplies its own displacement;
    // worldPush.camDelta converts current camera-relative coordinates into the previous rebase/camera.
    float3 previousHitCamRel = historyHitPos - worldPush.camOffset + worldPush.camDelta - motionPrev;
    float4 previousClip = mul(worldPush.prevViewProj, float4(previousHitCamRel, 1.0));
    bool canReuse = useHistory && (worldPush.flags & RESTIR_HISTORY_VALID) != 0u
            && previousClip.w > 0.0;
    int2 previousPixel = int2(-1, -1);
    float expectedPreviousDepth = length(previousHitCamRel);
    if (canReuse) {
        float2 previousUv = previousClip.xy / previousClip.w * 0.5 + 0.5;
        previousPixel = int2(floor(previousUv * float2(size)));
    }

    int reuseCount = (worldPush.flags & RESTIR_SPATIAL) != 0u ? 5 : 1;
    [loop]
    for (int reuse = 0; reuse < reuseCount && canReuse; ++reuse) {
        int2 offset = int2(0, 0);
        if (reuse == 1) offset = int2(4, 0);
        else if (reuse == 2) offset = int2(-4, 0);
        else if (reuse == 3) offset = int2(0, 4);
        else if (reuse == 4) offset = int2(0, -4);
        // Rotate the cross per pixel/frame to avoid a persistent axis-aligned reuse pattern.
        uint rotation = (seed >> 8) & 3u;
        if (rotation == 1u) offset = int2(-offset.y, offset.x);
        else if (rotation == 2u) offset = -offset;
        else if (rotation == 3u) offset = int2(offset.y, -offset.x);

        RestirSample previousSample;
        float previousW;
        float previousM;
        if (!restirPreviousCandidate(previousPixel + offset, size, receiverNormal,
                expectedPreviousDepth, previousSample, previousW, previousM)) {
            continue;
        }
        // Geometry-list publication invalidates static history. This additional domain check covers a
        // dynamic emitter appearing/disappearing between frames without letting a celestial reservoir
        // contaminate the local one (or vice versa).
        if ((hasLocal && previousSample.measure < 0.0)
                || (!hasLocal && previousSample.measure > 0.0)) {
            continue;
        }
        float pHat = restirPHat(previousSample, receiverPos, receiverNormal,
                v, diffAlb, F0, rough, pbr);
        // Combine the previous reservoir as one weighted super-candidate:
        // w = pHat_current(y) * W_previous * M_previous.
        restirUpdate(reservoir, previousSample, pHat, pHat * previousW * previousM,
                previousM, seed);
    }

    if (reservoir.M > RESTIR_MAX_M) {
        reservoir.weightSum *= RESTIR_MAX_M / reservoir.M;
        reservoir.M = RESTIR_MAX_M;
    }
    normalization = reservoir.selectedPHat > 1.0e-8 && reservoir.M > 0.0
            ? clamp(reservoir.weightSum / (reservoir.M * reservoir.selectedPHat), 0.0, RESTIR_MAX_W)
            : 0.0;
    float receiverDepth = length(historyHitPos - worldPush.camOffset);
    if (useHistory) {
        float3 storedValue = reservoir.sample.measure > 0.0
                ? reservoir.sample.value - worldPush.camOffset
                : reservoir.sample.value;
        restirStoreCurrent(pixel,
                float4(storedValue, reservoir.sample.measure),
                float4(restirEncodeNormal(receiverNormal), receiverDepth,
                        restirPackState(normalization, reservoir.M)));
    }
    return reservoir.sample;
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. alpha = rough^2. Paired with the separable
// Smith term, the importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
float3 sampleGGXVNDF(float3 n, float3 ve, float rough, inout uint s) {
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 Ve = float3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float a = rough * rough;
    float3 Vh = normalize(float3(a * Ve.x, a * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float ss = 0.5 * (1.0 + Vh.z);
    p2 = (1.0 - ss) * sqrt(1.0 - p1 * p1) + ss * p2;
    float3 Nh = p1 * T1 + p2 * T2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * Vh;
    float3 Ne = normalize(float3(a * Nh.x, a * Nh.y, max(0.0, Nh.z))); // microfacet normal, tangent space
    return normalize(Ne.x * t + Ne.y * b + Ne.z * n);                 // -> world space
}

// TLAS instance-mask bits (set on the Java side, ANDed against these per-ray cull masks):
//   bit 0 (0x01) — secondary rays: shadows, GI bounces, reflections.
//   bit 1 (0x02) — the primary camera ray.
// Terrain / entities / block entities keep 0xFF (both bits). Particles use 0x02: primary-visible
// receiver billboards only. The first-person camera owner uses 0x01 (secondary only).
static const uint CULL_SECONDARY = 0x01u;
static const uint CULL_PRIMARY = 0x02u;
static const uint TERRAIN_BUCKETS = 4u;
static const uint SBT_RADIANCE = 0u;
static const uint SBT_SHADOW = TERRAIN_BUCKETS;
static const uint SBT_STRIDE_BUCKET = 1u;

RayDesc makeRay(float3 origin, float tmin, float3 dir, float tmax) {
    RayDesc r;
    r.Origin = origin;
    r.TMin = tmin;
    r.Direction = dir;
    r.TMax = tmax;
    return r;
}

// SER radiance trace: trace into a hit object, reorder threads by hit coherence, THEN invoke the
// hit/miss shader — this is where the divergent Section/Prim shading work (world.rchit/world.rahit)
// actually happens, so reordering before it is what pays for the reorder's own cost.
void traceRadianceReordered(uint cullMask, float3 ro, float tmin, float3 rd, float tmax) {
    HitObject hObj = HitObject::TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, 0u, makeRay(ro, tmin, rd, tmax), payload);
    ReorderThread(hObj);
    HitObject::Invoke(topLevelAS, hObj, payload);
}

float3 visibility(float3 origin, float3 dir, float tmax) {
    shadowVis = float4(1.0, 1.0, 1.0, -1.0); // a = water-crossing sentinel, filled by the water any-hit
    // Shadow SBT records run any-hit only for cutout/translucent/water. Cutout alpha-tests; translucent
    // and water tint shadowVis.rgb and pass through. Solid blocks terminate traversal. There is no closest
    // or miss shader worth executing, so use a hit object only as the traversal result.
    HitObject hObj = HitObject::TraceRay(topLevelAS,
            RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,
            CULL_SECONDARY, SBT_SHADOW, SBT_STRIDE_BUCKET, 0u,
            makeRay(origin, RAY_TMIN, dir, tmax), shadowVis);
    return hObj.IsMiss() ? shadowVis.rgb : float3(0.0, 0.0, 0.0);
}

/**
 * Trace the one selected local-emitter sample through the regular material closest-hit. The endpoint
 * must be near the sampled surface; an earlier hit is an occluder. The material payload carries the
 * CPU-averaged emission chromaticity. Combined with the area/power proposal's total weight, this
 * integrates every authored emission texture without resampling a sparse mask into extreme outliers.
 */
float3 restirLocalTarget(RestirSample sample, float3 origin, float3 receiverNormal, float3 v,
                         float3 diffAlb, float3 F0, float rough, bool pbr,
                         float rayConeWidth, float rayConeSpread) {
    float3 lightDir;
    float targetDistance;
    if (!restirDirection(sample, origin, lightDir, targetDistance)) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 receiverResponse = restirBrdfResponse(
            receiverNormal, v, lightDir, diffAlb, F0, rough, pbr);
    if (max(receiverResponse.r, max(receiverResponse.g, receiverResponse.b)) <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }

    Payload receiverPayload = payload;
    payload.hitT = -1.0;
    payloadSetTraceState(false, rayConeWidth, rayConeSpread);
    traceRadianceReordered(CULL_SECONDARY, origin, RAY_TMIN, lightDir, targetDistance + 0.25);
    float hitDistance = payload.hitT;
    float3 emittedChroma = payloadAverageEmissionChroma();
    float3 emitterNormal = payload.normal;
    uint emitterMaterial = payloadMaterial();
    payload = receiverPayload;

    // POM can move the visible texel outward from its macro triangle. Admit that bounded displacement,
    // but reject an arbitrary closer glowing object so the sampled triangle remains the proposal.
    float endpointTolerance = max(0.16, targetDistance * 0.002);
    if (!(hitDistance > 0.0) || abs(hitDistance - targetDistance) > endpointTolerance
            || max(emittedChroma.r, max(emittedChroma.g, emittedChroma.b)) <= 0.0
            || emitterMaterial != MATERIAL_OPAQUE) {
        return float3(0.0, 0.0, 0.0);
    }
    float cosEmitter = max(0.0, dot(emitterNormal, -lightDir));
    if (cosEmitter <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    return receiverResponse * emittedChroma * (EMISSIVE_STRENGTH * sample.measure) * cosEmitter
            / max(targetDistance * targetDistance, 0.0625);
}

float fogDensityAt(float3 position) {
    float worldY = position.y + worldPush.fogControl.y;
    float aboveBase = max(0.0, worldY - worldPush.fogControl.x);
    return worldPush.fogParams.x * exp(-worldPush.fogParams.y * aboveBase);
}

// Stable bounded aerial perspective. Four fixed taps integrate both density and directional-light
// visibility. On the primary camera segment only SUNLIT density contributes to extinction and scattering:
// a volume fully hidden from the dominant celestial light is visually absent instead of leaving a grey
// ambient veil under roofs/in caves. Averaging the four visibility taps gives a soft shaft boundary.
// Secondary path segments retain extinction without four extra shadow rays per bounce.
// rgb = in-scattered radiance, a = transmittance.
float4 integrateFogSegment(float3 origin, float3 direction, float distance, bool includeScattering) {
    if (worldPush.fogParams.x <= 1.0e-6 || distance <= 1.0e-4) {
        return float4(0.0, 0.0, 0.0, 1.0);
    }

    float densitySum = 0.0;
    float visibleDensitySum = 0.0;
    float3 lightDir = normalize(worldPush.lightDir.xyz);
    [unroll]
    for (int i = 0; i < 4; ++i) {
        float t = distance * (float(i) + 0.5) * 0.25;
        float3 samplePos = origin + direction * t;
        float density = fogDensityAt(samplePos);
        densitySum += density;
        if (includeScattering) {
            float3 vis = visibility(samplePos + lightDir * RAY_TMIN, lightDir, 10000.0);
            visibleDensitySum += density * saturate(luminance(vis));
        }
    }
    // Fog is visible because light reaches and scatters inside it. Using total density here used to dim
    // the background even when every volume tap was shadowed, so "unlit" fog remained plainly visible.
    // For the camera segment, mask the complete optical depth by traced celestial visibility. This makes
    // both in-scattering AND the whitening/extinction disappear in fully unlit regions.
    float opticalDensitySum = includeScattering ? visibleDensitySum : densitySum;
    float transmittance = exp(-opticalDensitySum * distance * 0.25);
    if (!includeScattering) {
        return float4(0.0, 0.0, 0.0, transmittance);
    }

    float day = worldPush.sunDir.w;
    float horizon = 1.0 - saturate(abs(direction.y));
    float3 nightFog = lerp(float3(0.008, 0.012, 0.025), float3(0.018, 0.026, 0.050), horizon);
    float3 dayFog = lerp(float3(0.30, 0.42, 0.58), float3(0.52, 0.57, 0.64), horizon);
    float3 fogColor = lerp(nightFog, dayFog, day);

    float3 light = max(worldPush.lightRadiance.xyz, float3(0.0, 0.0, 0.0));
    float lightPeak = max(light.r, max(light.g, light.b));
    float3 lightTint = lightPeak > 1.0e-5 ? light / lightPeak : float3(1.0, 1.0, 1.0);
    float alignment = saturate(dot(normalize(direction), lightDir));
    float forwardLobe = pow(alignment, lerp(3.0, 14.0, worldPush.fogParams.z));
    float shaftVisibility = densitySum > 1.0e-7
            ? saturate(visibleDensitySum / densitySum) : 1.0;
    fogColor += lightTint * forwardLobe * shaftVisibility * lerp(0.05, 0.38, day);
    fogColor = min(fogColor, float3(0.85, 0.85, 0.85));

    float3 inscatter = fogColor * ((1.0 - transmittance) * worldPush.fogControl.z);
    return float4(inscatter, transmittance);
}

float2 projectPrevNdc(float3 worldPos) {
    float4 clip = mul(worldPush.prevViewProj, float4(worldPos - worldPush.camOffset + worldPush.camDelta, 1.0));
    return clip.xy / clip.w;
}

// Previous-frame screen position of a planar reflection. The reflected image of a world point P seen
// in a planar reflector is the MIRROR image V = mirror(P) across the surface plane: the eye sees V
// along a straight line, so the reflection appears at proj(V). Project the mirror image directly.
// Gotcha: intersecting a reflection ray with the surface plane divides by dot(mirrorDir, n), which
// explodes at grazing angles and produces non-zero MV in a static scene. Mirroring is division-free, so
// static MV is bit-exact zero at any incidence. The reflector is assumed static (terrain / water);
// reflectedMotionPrev carries the reflected content's own displacement.
float2 previousReflectionNdc(float3 surfacePos, float3 surfaceNormal, float3 reflectedWorldPos, float3 reflectedMotionPrev) {
    float3 n = normalize(surfaceNormal); // mirror(P) is orientation-independent, so no viewer flip needed
    float3 prevHit = reflectedWorldPos - reflectedMotionPrev;
    float3 mirroredHit = prevHit - 2.0 * n * dot(prevHit - surfacePos, n);
    return projectPrevNdc(mirroredHit);
}

// Reflection (specular) motion vector for DLSS-RR. Covers smooth specular blocks (metals / LabPBR _s)
// and water reflections including underwater total-internal-reflection — any primary surface with a
// sharp specular lobe (specular albedo > 0, roughness <= 0.5). A sky miss is treated as a reflected
// hit at infinity along the reflected direction, so the same mirror-image reprojection handles surface
// and sky reflections uniformly (continuous as the reflected distance -> infinity).
float2 specularReflectionMotion(float3 surfacePos, float3 primaryDir, float2 currentNdc, float2 size, float primaryConeSpread) {
    if (length(gv_normal) < 0.5 || max(gv_specAlb.r, max(gv_specAlb.g, gv_specAlb.b)) <= 0.001 || gv_rough > 0.5) {
        return float2(0.0, 0.0);
    }

    float3 n = normalize(gv_normal);
    float3 specDir = reflect(primaryDir, n);
    // The bias is ONLY for the trace origin (so the reflection ray doesn't self-intersect the surface it
    // leaves). The hit is then reconstructed from the TRUE surface point `surfacePos`, NOT the biased
    // origin: reconstructing from `surfacePos + n*SURF_BIAS` bakes a constant `-n*SURF_BIAS` world offset
    // into the mirrored virtual point, and that offset's screen projection blows up at grazing angles (n
    // lies in the screen plane there) -> a non-zero MV in a static scene. Reconstructing from surfacePos
    // cancels the term so the mirror lands exactly on the view ray (static MV = bit-exact zero) WITHOUT
    // zeroing the trace bias the main path tracer needs to avoid surface acne.
    payload.hitT = -1.0;
    payloadSetTraceState(false,
            max(length(surfacePos - worldPush.camOffset) * primaryConeSpread, RAY_CONE_MIN_WIDTH),
            max(primaryConeSpread, RAY_CONE_MIN_SPREAD));
    traceRadianceReordered(CULL_SECONDARY, surfacePos + n * SURF_BIAS, RAY_TMIN, specDir, 10000.0);

    float3 reflectedHit;
    float3 reflectedMotionPrev;
    if (payload.hitT > 0.0) {
        reflectedHit = payloadHitPosition(surfacePos, specDir);
        // Reflected water/fluid is a static dielectric (its chit MV lane is repurposed); only opaque
        // geometry carries a real per-vertex displacement. Particles are off secondary rays (cull mask).
        reflectedMotionPrev = payloadMaterial() == MATERIAL_OPAQUE ? payload.motionPrev : float3(0.0, 0.0, 0.0);
    } else {
        reflectedHit = surfacePos + specDir * 1.0e6; // sky reflection: virtual image at infinity along specDir
        reflectedMotionPrev = float3(0.0, 0.0, 0.0);
    }
    float2 prevNdc = previousReflectionNdc(surfacePos, n, reflectedHit, reflectedMotionPrev);
    return (prevNdc - currentNdc) * 0.5 * size;
}

// One-interface refraction for the water guide buffers: trace the refracted ray from the surface into
// (or out of) the water and report the content it lands on. Diffuse AND specular albedo, plus MV, then
// track that hit as its own feature (the water surface itself has no true diffuse/specular response of
// its own worth demodulating by — it's a clear interface), while depth stays on the water surface.
// `refracted` is false on total internal reflection (no refracted hit exists); a sky miss returns a far
// point along the refracted direction, which the self-delta MV handles uniformly.
void refractedGuideHit(float3 surfacePos, float3 incidentDir, float3 surfaceNormal, bool inWaterAtSurface,
                       float materialIor,
                       float rayConeWidth, float rayConeSpread,
                       out float3 hitCamRel, out float3 motionPrev, out bool refracted, out float3 diffuseAlbedo,
                       out float3 specAlbedo) {
    hitCamRel = surfacePos - worldPush.camOffset;
    motionPrev = float3(0.0, 0.0, 0.0);
    refracted = false;
    diffuseAlbedo = float3(0.0, 0.0, 0.0);
    specAlbedo = float3(0.0, 0.0, 0.0);

    float etaI = inWaterAtSurface ? materialIor : 1.0;
    float etaT = inWaterAtSurface ? 1.0 : materialIor;
    float3 refractedDir = refract(incidentDir, surfaceNormal, etaI / etaT);
    if (dot(refractedDir, refractedDir) <= 0.0) {
        return; // total internal reflection: no refracted guide hit exists
    }
    refractedDir = normalize(refractedDir);
    refracted = true;

    float3 ro = surfacePos - surfaceNormal * SURF_BIAS;
    payload.hitT = -1.0;
    payloadSetTraceState(false, rayConeWidth, rayConeSpread);
    traceRadianceReordered(CULL_SECONDARY, ro, RAY_TMIN, refractedDir, 10000.0);
    if (payload.hitT < 0.0) {
        hitCamRel = (ro + refractedDir * 1.0e6) - worldPush.camOffset; // sky through the surface, at infinity
        motionPrev = float3(0.0, 0.0, 0.0);
        diffuseAlbedo = SKY_DIFF_ALBEDO; // sky guide: keep radiance out of the demodulation albedo
        specAlbedo = SKY_SPEC_ALBEDO;
        return;
    }

    float3 hitPos = payloadHitPosition(ro, refractedDir);
    hitCamRel = hitPos - worldPush.camOffset;
    uint material = payloadMaterial();
    motionPrev = material != MATERIAL_WATER ? payload.motionPrev : float3(0.0, 0.0, 0.0);
    if (material == MATERIAL_OPAQUE) {
        bool pbr = (worldPush.flags & 2u) != 0u;
        diffuseAlbedo = pbr ? payload.albedo * (1.0 - clamp(payloadMetalness(), 0.0, 1.0)) : payload.albedo;
        if (pbr) {
            float3 f0 = payload.f0;
            float rough = clamp(payloadRoughness(), MIN_ROUGH, 1.0);
            float NoV = clamp(dot(payload.normal, -refractedDir), 0.0, 1.0);
            specAlbedo = rrSpecularAlbedo(f0, rough * rough, NoV);
        }
    }
}

// One Monte Carlo path from (ro, rd). Returns accumulated radiance along the path.
float3 tracePath(float3 ro, float3 rd, float primaryConeSpread, bool useRestirPrimary,
                 int2 pixel, int2 imageSize, inout uint seed) {
    float3 L = float3(0.0, 0.0, 0.0);
    float3 throughput = float3(1.0, 1.0, 1.0);
    float rayConeWidth = 0.0;
    float rayConeSpread = max(primaryConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(worldPush.maxBounces);
    int rrStart = maxBounces <= 3 ? 1 : 2;
    bool pbr = (worldPush.flags & 2u) != 0u; // GGX BRDF + material guides, else Lambertian fallback
    // Start submerged if the camera is in water so the first segment gets the right medium orientation
    // and absorption when the eye is underwater.
    bool inWater = (worldPush.flags & 1u) != 0u;
    // Current medium extinction. Defaults to the camera-biome tint (correct when the eye starts
    // submerged, before any water surface is hit); refreshed to the hit water body's tint on each crossing.
    float3 waterExt = waterExtinction(worldPush.waterParams.xyz);
    bool waterWaves = (worldPush.flags & 16u) != 0u; // animated wave-normal perturbation
    // Sky-disc gate (see world.rmiss): the sun/moon disc is shown to the primary ray and to rays spawned
    // by a specular/dielectric bounce (mirror reflections + refractions of the sun/moon), but hidden from
    // diffuse-sampled continuation rays — those are already covered by the sun NEE, so the tiny disc
    // would be a double-counted firefly. Reset true on every specular/water bounce, false on a diffuse one.
    bool showCelestial = true;
    // A local emitter reached by the next ordinary BSDF continuation has already been integrated by
    // this vertex's all-light ReSTIR NEE. Keep directly visible emitters and specular chains intact.
    bool emissionCoveredByPreviousNee = false;
    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        payload.hitT = -1.0;
        payloadSetTraceState(showCelestial, rayConeWidth, rayConeSpread);
        // Radiance SBT records run any-hit only for true alpha cutout. Translucent/water go straight to
        // closest-hit for dielectric handling. Geometry is double-sided; the chit flips the normal.
        // Primary (bounce 0) is the camera ray (CULL_PRIMARY): sees particles but not the first-person
        // player. Bounce rays are secondary (CULL_SECONDARY): exclude particles, include the player.
        traceRadianceReordered(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0);

        // Deterministic segment integration removes binary medium-event noise and remains stable under
        // camera motion. Only the primary segment evaluates in-scattering; indirect paths get the same
        // Beer-Lambert extinction without multiplying shadow-ray cost at every bounce.
        float fogSegment = min(payload.hitT > 0.0 ? payload.hitT : worldPush.fogParams.w,
                worldPush.fogParams.w);
        if (!inWater) {
            float4 fog = integrateFogSegment(ro, rd, fogSegment, bounce == 0);
            L += throughput * fog.rgb;
            throughput *= fog.a;
        }

        if (payload.hitT < 0.0) {
            // world.rmiss writes sky radiance (gradient + sun/moon disc + stars) into payload.albedo. The
            // packed show-celestial flag decides whether this path segment may see the bright disc.
            float3 sky = payload.albedo;
            if (bounce == 0) { // primary ray hit sky: guides describe "no surface"
                gv_normal = float3(0.0, 0.0, 0.0);
                // Flat mid-grey albedo guide for sky (NOT the bright sky radiance): demodulating DLSS-RR
                // by the huge sun-disc radiance rings/artifacts around the sun. A constant albedo keeps
                // the brightness in the lighting channel where RR resolves it cleanly.
                gv_albedo = SKY_DIFF_ALBEDO;
                gv_specAlb = SKY_SPEC_ALBEDO;
                gv_rough = 1.0;
                gv_emission = 0.0;
                gv_emissionSource = EMISSION_SOURCE_NONE;
                gv_hitCamRel = rd * 1.0e6; // sky at infinity: only rotation parallax (-> large far depth)
                gv_motionHitCamRel = gv_hitCamRel;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = float3(0.0, 0.0, 0.0); // sky is static
            }
            L += throughput * sky; // escaped to sky
            break;
        }

        // Beer–Lambert: attenuate along the segment just travelled if it lay inside water. Applied to
        // every hit reached while submerged (the water-exit face or the underwater floor), shifting the
        // transmitted radiance with depth.
        if (inWater) {
            throughput *= exp(-waterExt * payload.hitT);
        }

        float3 n = payload.normal; // oriented toward the incoming ray by the closest-hit
        // The macro triangle hit is fixed in world space. POM's refined hitPos below deliberately follows
        // the viewing ray through the height field, so its tangent displacement changes as the camera
        // moves. That is correct for texture lookup/depth, but it must never anchor an ordinary TLAS
        // shadow ray: doing so makes an otherwise static object's shadow crawl over the receiver.
        float3 macroHitPos = ro + rd * payload.hitT;
        float3 hitPos = payloadHitPosition(ro, rd);
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);
        uint material = payloadMaterial();
        if (bounce == 0) {
            gv_emission = payloadEmission();
            gv_emissionSource = payloadEmissionSource();
        }

        // Stained glass / ice (material 3): a thin colored dielectric. Stochastically reflect (Fresnel
        // glint) or transmit; on transmission the throughput is multiplied by the pane's tint, so light
        // passing through is colored. payload.albedo is the transmission tint (texel rgb mixed toward
        // white by 1 − opacity). Treated as a thin pane, so the transmitted ray continues straight (no
        // refraction bend) — the instance stays double-sided and the normal already faces the incoming ray.
        if (material == MATERIAL_GLASS) {
            // Local-light NEE does not walk a dielectric chain. Once the BSDF continuation crosses or
            // reflects at glass, a later emitter hit is a distinct specular-path contribution.
            emissionCoveredByPreviousNee = false;
            float3 glassTint = payload.albedo;
            float materialIor = max(payloadIor(), 1.0e-3);
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);
            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            float F = fresnelDielectric(cosI, 1.0, materialIor);
            if (bounce == 0) { // primary glass hit: feed RR a smooth dielectric specular surface
                gv_normal = n;
                gv_rough = clamp(payloadRoughness(), MIN_ROUGH, 1.0);
                gv_specAlb = float3(F, F, F);
                gv_albedo = glassTint;            // demodulate by the transmission tint
                gv_hitCamRel = hitPos - worldPush.camOffset;
                gv_motionHitCamRel = gv_hitCamRel;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = payload.motionPrev; // 0 for static terrain
            }
            if (rndf(seed) < F) {
                rd = reflect(rd, n);
                ro = hitPos + n * SURF_BIAS;       // reflected ray stays on the incidence side
            } else {
                throughput *= glassTint * transmission; // colored, optionally partial transmission
                ro = hitPos - n * GLASS_TRANSMIT_BIAS; // cross to the far side without overshooting a close neighbour
            }
            showCelestial = true;                  // specular interface: the continuation ray may see the disc
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(seed) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        // Particles (material == 2): camera-facing receiver billboard. The instance mask keeps particles
        // off secondary rays, so this only fires at bounce 0; they receive direct light and one diffuse
        // path continuation into the scene, but never appear in shadows/reflections/GI or emit radiance.
        if (material == MATERIAL_PARTICLE) {
            float3 albedo = payload.albedo;
            gv_normal = n;
            gv_albedo = albedo; // RR demodulation target = the particle texel (keeps it sharp)
            gv_specAlb = float3(0.0, 0.0, 0.0);
            gv_rough = 1.0;
            gv_hitCamRel = hitPos - worldPush.camOffset;
            gv_motionHitCamRel = gv_hitCamRel;
            gv_motionUseRefracted = false;
            gv_motionObjDisp = payload.motionPrev; // per-particle MV: de-ghost moving particles

            float3 lightDir = worldPush.lightDir.xyz;
            float lightHalfAngle = worldPush.lightDir.w;
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
            }
            // Billboards are effectively two-sided receivers. Bias from the side facing the light; shadow
            // rays exclude particles anyway, but this keeps the origin sane when the light is behind the
            // camera side.
            float signedNdl = dot(n, lightDir);
            float ndl = abs(signedNdl);
            if (ndl > 0.0) {
                float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                float3 vis = visibility(shadowOrigin, lightDir, 10000.0);
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    L += throughput * albedo * INV_PI * worldPush.lightRadiance.xyz * ndl * vis;
                }
            }

            if (bounce >= maxBounces) {
                break;
            }
            throughput *= albedo;
            ro = hitPos + n * SURF_BIAS;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false; // diffuse receiver: direct sun/moon was handled by NEE above
            continue;
        }

        // Water: smooth dielectric interface. Choose reflection or refraction stochastically by the
        // Fresnel reflectance F — sampling by F leaves the throughput unchanged (lossless interface), so
        // there is no diffuse albedo multiply and no sun NEE here (the surface is specular). On
        // transmission, the new medium is read from this hit's face orientation (payloadWaterEntering),
        // not toggled — see PAYLOAD_WATER_ENTERING. The bounce cap bounds stacked refractions.
        if (material == MATERIAL_WATER) {
            // As with glass, local-light NEE stops at this dielectric interface, so preserve emitter
            // radiance reached through the reflected/refracted continuation.
            emissionCoveredByPreviousNee = false;
            float materialIor = max(payloadIor(), 1.0e-3);
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);
            // The primary-water guide traces another radiance ray through the global payload. Preserve
            // this interface's orientation before that nested trace overwrites payload.flags.
            bool materialEntering = payloadWaterEntering();
            // Ripple the (near-flat) surface normal before any Fresnel/guide use → glints, broken
            // reflections, wobbling refraction. This water body's biome tint (carried in payload.albedo
            // for water hits) sets the absorption applied to the next submerged segment.
            if (waterWaves) {
                // World-stable wave domain: rebased hit XZ + the pushed anchor (terrain origin mod 4096)
                // reconstructs a world-pinned coordinate that survives the per-move terrain rebase.
                n = applyWaterWaves(n, hitPos.xz + worldPush.waterAnchor.xy, worldPush.waterParams.w);
            }
            waterExt = waterExtinction(payload.albedo);
            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            if (bounce == 0) { // primary water hit: feed RR a smooth dielectric specular surface
                gv_normal = n;
                gv_rough = clamp(payloadRoughness(), MIN_ROUGH, 1.0);
                gv_hitCamRel = hitPos - worldPush.camOffset; // depth + reflection start at the water surface
                // Diffuse AND specular albedo + MV track the refracted hit (floor/shore seen through the
                // surface) so refracted content stops ghosting; depth stays on the water surface. Water's
                // own Fresnel reflectance is ignored here — a clear interface, not a demodulation target.
                float3 refractedAlbedo;
                float3 refractedSpecAlbedo;
                refractedGuideHit(hitPos, rd, n, inWater, materialIor, rayConeWidth, rayConeSpread,
                        gv_motionHitCamRel, gv_motionObjDisp,
                        gv_motionUseRefracted, refractedAlbedo, refractedSpecAlbedo);
                gv_albedo = refractedAlbedo;
                gv_specAlb = refractedSpecAlbedo;
            }
            float etaI = inWater ? materialIor : 1.0;
            float etaT = inWater ? 1.0 : materialIor;
            float F = fresnelDielectric(cosI, etaI, etaT);
            if (rndf(seed) < F) {
                rd = reflect(rd, n);
                ro = hitPos + n * SURF_BIAS; // reflected ray stays on the incidence side
            } else {
                rd = refract(rd, n, etaI / etaT); // F < 1 here, so this is never total internal reflection
                throughput *= transmission;
                ro = hitPos - n * SURF_BIAS;       // transmitted ray crosses to the far side
                // Derived from this hit's face orientation, not toggled — self-corrects instead of a
                // stray/missing face corrupting the medium for the rest of the path (see PAYLOAD_WATER_ENTERING).
                inWater = materialEntering;
            }
            // Specular interface (mirror reflect / clear refract): the continuation ray should see the
            // sun/moon disc directly — no NEE happened here to double-count it.
            showCelestial = true;
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(seed) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        float3 albedo = payload.albedo;
        float hitCameraDistance = length(hitPos - worldPush.camOffset);
        float sss = payloadSss(); // LabPBR SSS strength (0 when absent)
        // Continuation rays start at the visible relief point. Ordinary scene-shadow rays instead start
        // at the view-independent macro receiver. Local height-field visibility is evaluated separately
        // by traceReliefVisibility(), so relief still self-shadows without contaminating world-space
        // object-shadow placement with the camera-dependent POM intersection.
        bool reliefHit = (payload.parallaxPageSign & 0x00FFFFFFu) != 0x00FFFFFFu;
        float3 rayOriginNormal = reliefHit ? unpackOctNormal(payload.parallaxGeomOct) : n;
        float3 p = hitPos + rayOriginNormal * SURF_BIAS;
        float3 shadowSurfacePos = reliefHit ? macroHitPos : hitPos;
        float3 shadowP = shadowSurfacePos + rayOriginNormal * SURF_BIAS;
        float3 v = -rd; // view direction (toward the camera / incoming ray)

        // Material split. Metals tint F0 by albedo and have no diffuse lobe; dielectrics use a fixed
        // 0.04 F0. With PBR disabled this collapses to the Lambertian fallback.
        float rough = pbr ? clamp(payloadRoughness(), MIN_ROUGH, 1.0) : 1.0;
        float metal = pbr ? clamp(payloadMetalness(), 0.0, 1.0) : 0.0;
        float3 diffAlb = albedo * (1.0 - metal);
        // F0 comes from the chit: dielectric 0.04, a LabPBR custom/metal F0, or albedo for metals.
        float3 F0 = pbr ? payload.f0 : lerp(float3(0.04, 0.04, 0.04), albedo, metal);

        if (bounce == 0) { // primary-visibility surface: capture the denoiser guide buffers
            gv_normal = n;
            gv_albedo = pbr ? diffAlb : albedo;  // RR diffuse-albedo demodulation target
            gv_rough = rough;
            gv_specAlb = pbr ? rrSpecularAlbedo(F0, rough * rough, dot(n, v)) : float3(0.0, 0.0, 0.0);
            gv_hitCamRel = hitPos - worldPush.camOffset; // displaced camera-relative hit position
            gv_motionHitCamRel = gv_hitCamRel;
            gv_motionUseRefracted = false;
            gv_motionObjDisp = payload.motionPrev; // per-vertex world motion (0 for static terrain/sky)
        }

        // Emissive surfaces (lava, glowstone, torches, LabPBR/override emission, ...) add radiance when
        // directly visible or reached through a dielectric chain. An ordinary continuation hit is
        // suppressed after all-light NEE because ReSTIR already estimated that same direct path.
        float emission = payloadEmission();
        if (emission > 0.0 && !emissionCoveredByPreviousNee) {
            L += throughput * albedo * emission * EMISSIVE_STRENGTH;
        }
        emissionCoveredByPreviousNee = false;

        // NEE: direct light from the dominant celestial body (sun by day, moon by night) — Lambert
        // diffuse + GGX specular, one shadow ray. Jitter the light direction within its square angular
        // extent (worldPush.lightDir.w half-angle) so the shadow ray samples the light's quad — soft penumbrae
        // over accumulation. The same sampled direction drives ndl, the shadow ray, and the specular
        // half-vector.
        RestirSample directSample;
        directSample.value = worldPush.lightDir.xyz;
        directSample.measure = -1.0;
        float restirNormalization = 1.0;
        bool restirThisVertex = (worldPush.flags & RESTIR_ENABLED) != 0u;
        bool restirHistoryVertex = useRestirPrimary && bounce == 0;
        bool localRestirDomain = restirThisVertex && worldPush.breakingPad2 > 0u;
        if (restirThisVertex) {
            directSample = restirSelectDirect(pixel, imageSize, shadowP, hitPos, n, payload.motionPrev,
                    v, diffAlb, F0, rough, pbr, restirHistoryVertex, seed, restirNormalization);
        } else if (worldPush.lightDir.w > 0.0) {
            directSample.value = sampleSquare(directSample.value, worldPush.lightDir.w, seed);
        }
        float3 lightDir;
        float lightDistance;
        bool validDirectSample = restirDirection(directSample, shadowP, lightDir, lightDistance);
        if (validDirectSample && restirNormalization > 0.0) {
            // The same stochastic light sample used by NEE is traced through the local height field.
            // Over path accumulation this produces the relief penumbra without an artistic strength.
            float heightVis = traceReliefVisibility(lightDir, hitCameraDistance);
            if (heightVis > 0.001) {
                if (directSample.measure > 0.0) {
                    float3 localTarget = restirLocalTarget(directSample, shadowP, n, v,
                            diffAlb, F0, rough, pbr, rayConeWidth, rayConeSpread);
                    L += throughput * localTarget * (heightVis * restirNormalization);
                } else {
                    float3 directTarget = restirCelestialTarget(
                            n, v, lightDir, diffAlb, F0, rough, pbr);
                    float3 vis = visibility(shadowP, lightDir, 10000.0) * heightVis;
                    // Underwater receiver whose shadow ray crossed a water surface: scale the direct
                    // celestial irradiance by wave-refraction focusing at the exit point.
                    if (inWater && waterWaves && shadowVis.a > 0.0) {
                        vis *= waterCaustic(shadowP + lightDir * shadowVis.a, lightDir, shadowVis.a);
                    }
                    if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                        L += throughput * directTarget * vis * restirNormalization;
                    }
                }
            }
        }

        // Keep the analytic celestial domain independent from local geometry. Combining their raw
        // unoccluded targets in one reservoir made an underground sun candidate dominate, then vanish
        // at the final shadow test; the rare selected torch sample inherited that huge normalization
        // and appeared as a bright needle. A separate one-candidate celestial reservoir is exact here
        // because the sun/moon is the only light in this analytic domain.
        if (localRestirDomain) {
            float3 celestialDir = worldPush.lightDir.xyz;
            if (worldPush.lightDir.w > 0.0) {
                celestialDir = sampleSquare(celestialDir, worldPush.lightDir.w, seed);
            }
            float celestialHeightVis = traceReliefVisibility(celestialDir, hitCameraDistance);
            if (celestialHeightVis > 0.001) {
                float3 celestialTarget = restirCelestialTarget(
                        n, v, celestialDir, diffAlb, F0, rough, pbr);
                float3 celestialVis = visibility(shadowP, celestialDir, 10000.0)
                        * celestialHeightVis;
                if (inWater && waterWaves && shadowVis.a > 0.0) {
                    celestialVis *= waterCaustic(
                            shadowP + celestialDir * shadowVis.a, celestialDir, shadowVis.a);
                }
                if (max(celestialVis.r, max(celestialVis.g, celestialVis.b)) > 0.0) {
                    L += throughput * celestialTarget * celestialVis;
                }
            }
        }

        emissionCoveredByPreviousNee = localRestirDomain;

        // Thin-surface SSS transmission. Light entering from the back face scatters through toward the
        // viewer via a forward-biased HG phase (leaves/grass backlit by the sun glow when you look
        // through them toward the light source). Shadow ray fires from the back face to avoid
        // self-occlusion. cosT = dot(lightDir, rd): rd tracks the ray toward the sun when the sun is
        // behind the slab, giving cosT ≈ 1 → forward-scatter peak.
        if (sss > 0.0 && bounce <= MAX_SSS_BOUNCE) {
            // ReSTIR's selected direction is distributed for the front BRDF. SSS uses a separate
            // uniform light sample so its transmission estimate remains unbiased.
            float3 sssLightDir = lightDir;
            if (restirThisVertex) {
                sssLightDir = worldPush.lightDir.xyz;
                if (worldPush.lightDir.w > 0.0) {
                    sssLightDir = sampleSquare(sssLightDir, worldPush.lightDir.w, seed);
                }
            }
            float backNdl = max(0.0, dot(-n, sssLightDir));
            if (backNdl > 0.0) {
                float3 visB = visibility(shadowSurfacePos - rayOriginNormal * SURF_BIAS,
                        sssLightDir, 10000.0);
                // Same caustic as the front-face NEE — underwater kelp/seagrass transmission should
                // flicker with the same light bands as the floor around it.
                if (inWater && waterWaves && shadowVis.a > 0.0) {
                    visB *= waterCaustic(shadowSurfacePos + sssLightDir * shadowVis.a,
                            sssLightDir, shadowVis.a);
                }
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    float cosT = dot(sssLightDir, rd);
                    L += throughput * diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * backNdl * worldPush.lightRadiance.xyz * visB;
                }
            }
        }

        // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe, chosen by their
        // relative reflectance. With PBR off, use the cosine-weighted Lambertian fallback.
        if (pbr) {
            float ps = clamp(luminance(F0) / (luminance(F0) + luminance(diffAlb) + 1.0e-4), 0.1, 0.9);
            if (rndf(seed) < ps) {
                float3 h = sampleGGXVNDF(n, v, rough, seed);
                float3 l = reflect(rd, h); // rd = -v: reflect the incoming ray about the microfacet normal
                float ndl2 = dot(n, l);
                if (ndl2 <= 0.0) {
                    break; // microfacet reflects below the surface: terminate this path
                }
                // VNDF + separable Smith ⇒ weight = F · G2/G1(v) = F · G1(l); divide by the lobe pdf ps.
                float3 F = fresnelSchlick(max(0.0, dot(v, h)), F0);
                throughput *= F * ggxG1(ndl2, rough) / ps;
                ro = p;
                rd = l;
                rayConeSpread = max(rayConeSpread, rough * rough * RAY_CONE_GLOSSY_SPREAD_SCALE);
                showCelestial = true; // specular lobe: this ray is a (glossy) mirror — show the disc
            } else {
                throughput *= diffAlb / (1.0 - ps);
                ro = p;
                rd = cosineDir(n, seed);
                rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
                showCelestial = false; // diffuse lobe: disc covered by NEE, hide it (anti-firefly)
            }
        } else {
            throughput *= albedo; // Lambertian fallback (PI and cos cancel)
            showCelestial = false; // diffuse bounce: hide the disc (covered by NEE)
            ro = p;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
        }

        // Apply height-field masking to the direction actually selected for the next path segment.
        // If that ray later lands on an emissive block, its contribution is already attenuated here;
        // the same rule naturally covers indirect diffuse and glossy transport.
        throughput *= traceReliefVisibility(rd, hitCameraDistance);
        if (max(throughput.r, max(throughput.g, throughput.b)) <= 0.0) {
            break;
        }

        // Russian roulette on deeper bounces: survive with prob = max channel, rescale survivors.
        if (bounce >= rrStart) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(seed) > q) {
                break;
            }
            throughput /= q;
        }
    }
    return L;
}

[shader("raygeneration")]
void main() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0]; // load the per-frame push data from the BDA buffer
    int2 pix = int2(DispatchRaysIndex().xy);
    float2 size = float2(DispatchRaysDimensions().xy);
    float2 uv = (float2(pix) + 0.5) / size;
    float2 ndc = uv * 2.0 - 1.0; // unjittered pixel centre — the motion vector reprojects this

    // Offset the primary ray within the pixel by the sub-pixel jitter reported to DLSS-RR, so
    // successive frames sample different positions and RR can resolve detail above render resolution.
    // The guide buffers below come from this jittered first hit (they must match the jittered colour);
    // only the motion vector stays on the unjittered centre, which DLSS expects.
    float2 jndc = (uv + worldPush.jitter / size) * 2.0 - 1.0;

    float4 nearH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;

    float3 origin = nearP + worldPush.camOffset;
    float3 dir = normalize(farP - nearP);
    float rayConeSpread = primaryRayConeSpread(jndc, size, dir);

    uint seed = (uint(pix.x) * 1973u + uint(pix.y) * 9277u + 26699u) ^ (worldPush.frameIndex * 2654435761u);

    bool restirEnabled = (worldPush.flags & RESTIR_ENABLED) != 0u;
    // Invalidate the current slot first. Sky/water/glass/particle primary hits must not leave a stale
    // opaque-surface reservoir behind for a future reprojection.
    if (restirEnabled) {
        restirStoreCurrent(pix, float4(0.0, 0.0, 0.0, 0.0), float4(0.0, 0.0, 0.0, 0.0));
    }

    uint spp = max(worldPush.spp, 1u);
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    for (uint s = 0u; s < spp; s++) {
        seed = pcg(seed); // decorrelate per-sample paths (shared camera ray, no AA jitter yet)
        // Update one reservoir per pixel/frame. Additional SPP paths stay independent conventional
        // estimators, so they cannot overwrite or over-count the temporal reservoir.
        frameRadiance += tracePath(origin, dir, rayConeSpread, restirEnabled && s == 0u,
                pix, int2(DispatchRaysDimensions().xy), seed);
    }
    frameRadiance /= float(spp);

    // Hardware (non-linear, reversed-Z) depth for DLSS-RR + Frame Generation: project the camera-relative
    // primary hit through the forward view-projection and take ndc z/w — exactly the value a rasterizer
    // would write. Reversed-Z (near=1, far=0), so DLSS gets the DepthInverted flag; sky's far hit -> ~0.
    // Depth follows the primary surface (water included); only the MV tracks the refracted hit.
    float4 curClip = mul(worldPush.curViewProj, float4(gv_hitCamRel, 1.0));
    gv_depth = curClip.w > 0.0 ? curClip.z / curClip.w : 0.0;

    // Guide buffers: first-hit attributes for the denoiser / DLSS-RR (written every frame).
    gNormal[pix] = float4(gv_normal, gv_rough);
    gAlbedo[pix] = float4(gv_albedo, 1.0);
    gDepth[pix] = gv_depth;
    gSpecAlbedo[pix] = float4(gv_specAlb, 1.0); // specular albedo for RR demodulation

    // Motion vector: reproject this guide point through the previous frame's view-projection.
    // Use the guide point's actual current projection (equal to jndc for an ordinary primary hit);
    // volumetric events use their sampled world position and therefore reproject consistently too.
    // For a dynamic surface, previous-frame position is the current point minus its world-space
    // displacement; subtracting gv_motionObjDisp de-cameras the MV.
    float4 prevClip = mul(worldPush.prevViewProj, float4(gv_motionHitCamRel + worldPush.camDelta - gv_motionObjDisp, 1.0));
    float2 prevNdc = prevClip.xy / prevClip.w;
    float2 curNdc = curClip.xy / curClip.w;
    // Water MV guide: track the one-refraction hit (the floor seen through the surface), not the water
    // surface, so refracted content stops ghosting. That hit is its own feature: its screen motion is
    // its own reprojection delta (prev - current projection) — bit-exact zero when static, no Snell
    // solve needed. The constant per-frame refraction offset cancels out of prev - current.
    if (gv_motionUseRefracted) {
        float4 curClipRefr = mul(worldPush.curViewProj, float4(gv_motionHitCamRel, 1.0));
        curNdc = curClipRefr.xy / curClipRefr.w;
    }
    float2 motion = (prevNdc - curNdc) * 0.5 * size;
    gMotion[pix] = motion;
    float2 specMotion = specularReflectionMotion(gv_hitCamRel + worldPush.camOffset, dir, jndc, size, rayConeSpread);
    gSpecMotion[pix] = specMotion;

    // Debug guide-buffer visualization: bypass accumulation and show a guide directly.
    if (pc.debugView != 0u) {
        float3 dbg;
        if (pc.debugView == 1u) {
            dbg = gv_normal * 0.5 + 0.5;            // world normal -> [0,1]
        } else if (pc.debugView == 2u) {
            dbg = gv_albedo;                        // diffuse albedo
        } else if (pc.debugView == 3u) {
            dbg = float3(gv_depth, gv_depth, gv_depth); // HW reversed-Z depth (near=white, far/sky=black)
        } else if (pc.debugView == 4u) {
            dbg = float3(gv_rough, gv_rough, gv_rough); // roughness
        } else if (pc.debugView == 6u) {
            dbg = gv_specAlb;                       // specular albedo
        } else if (pc.debugView == 7u) {
            dbg = float3(clamp(0.5 + specMotion * 0.05, 0.0, 1.0), 0.5); // reflection motion
        } else if (pc.debugView == 8u) {
            dbg = float3(gv_emission, gv_emission, gv_emission); // resolved emission mask/strength
        } else if (pc.debugView == 9u) {
            float3 sourceColor = gv_emissionSource == EMISSION_SOURCE_OVERRIDE ? float3(1.0, 0.2, 1.0)
                    : gv_emissionSource == EMISSION_SOURCE_LAB_PBR ? float3(0.15, 0.8, 1.0)
                    : gv_emissionSource == EMISSION_SOURCE_HEURISTIC ? float3(1.0, 0.45, 0.05)
                    : gv_emissionSource == EMISSION_SOURCE_UNIFORM ? float3(1.0, 1.0, 1.0)
                    : float3(0.0, 0.0, 0.0);
            dbg = sourceColor * (gv_emissionSource == EMISSION_SOURCE_NONE
                    ? 0.0 : max(0.2, clamp(gv_emission, 0.0, 1.0)));
        } else {
            dbg = float3(clamp(0.5 + motion * 0.05, 0.0, 1.0), 0.5); // motion: red=+x, green=+y
        }
        outImage[pix] = float4(dbg, 1.0);
        return;
    }

    // Single noisy estimate; the denoiser (DLSS-RR) handles temporal convergence.
    outImage[pix] = float4(frameRadiance, 1.0);
}
