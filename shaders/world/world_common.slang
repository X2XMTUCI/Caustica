// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

// Read-only device pointer with explicit std430 layout. The layout parameter is LOAD-BEARING: a bare
// Ptr<T> gets Slang's "natural" layout (no vec4 16-byte alignment → e.g. WorldPush.sunDir lands at 200
// instead of the Java writer's 208, silently reading garbage) and additionally requires the
// scalarBlockLayout device feature. Keep std430 unless RtDeviceBringup explicitly enables that feature;
// this alias is the single layout switch and generated Java serialization stays in lock-step.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;

// Push constant block. The hot inline lanes avoid dereferencing WorldPush for values that are read in
// hit shaders or used for raygen control flow.
public struct WorldPushConstants {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint64_t dhTableAddr;
    public uint64_t materialTableAddr;
    public uint frameIndex;
    public uint debugView;
};

// Block-breaking overlay entry: xyz = breaking block position (rebased, same space as gl_WorldRay*),
// w = bindless entityAlbedoTex slot of the destroy-stage texture.
public struct BreakEntry {
    public int4 posSlot;
};

// Per-frame world data, read through a 64-bit device address. WorldPushData.java is generated from this
// reflected type, including its serializer, byte size, vector/matrix types, and fixed-array capacities.
public struct WorldPush {
    public float4x4 invViewProj;
    public float3   camOffset;
    public uint64_t tableAddr;        // section table
    public uint     frameIndex;
    // Keep the following matrix explicitly 16-byte aligned across Vulkan SDK Slang releases. Some
    // standalone Slang builds otherwise reflect the implicit std430 tail padding after frameIndex as
    // scalar layout and emit an invalid physical-storage-buffer offset.
    public uint     frameMatrixPad;
    public float4x4 prevViewProj;
    public float3   camDelta;
    public uint     spp;
    public float2   jitter;
    public uint64_t entityTableAddr;  // entity geometry table
    public uint     flags;            // bit0 camera submerged, bit1 PBR, bit4 water waves, bit5 POM smoothing
    public uint     maxBounces;
    public uint     sunVectorPad0;
    public uint     sunVectorPad1;
    public float4   sunDir;           // xyz true sun direction, w dayFactor 0..1
    public float4   lightDir;         // xyz active NEE light dir, w square half-angle (rad)
    public float4   lightRadiance;    // xyz HDR light radiance, w star brightness
    public float4   moonDir;          // xyz moon direction, w moonPhase (0 full .. 4 new)
    public float4   celestial;        // xyz celestial rotation axis, w star angle (rad)
    public float4   sunUv;            // vanilla sun sprite atlas rect (u0,v0,u1,v1)
    public float4   moonUv;           // vanilla current-moon-phase sprite atlas rect
    public float4   waterParams;      // xyz camera-biome water tint, w wave time (s)
    public float4   waterAnchor;      // xy world-stable wave-domain anchor
    public float4x4 curViewProj;      // forward camera-relative view-projection
    public uint     breakCount;
    public uint     breakingPad0;
    public uint     breakingPad1;
    public uint     breakingPad2;
    public BreakEntry breaking[8];    // Java capacity and breakCount serialization are generated from this
    public float4   parallaxParams;   // x relief depth, y path-traced height steps, z unused, w max distance
    public float4   fogParams;        // x density, y height falloff, z anisotropy, w max distance
    public float4   fogControl;       // x base world height, y rebase origin Y, z scattering albedo
};

// Radiance-ray payload (location 0). Member order and types are a cross-stage ABI.
public struct Payload {
    public float3 albedo;      // hit: block albedo. miss: sky radiance.
    public float3 normal;      // hit: geometric normal, viewer-oriented.
    public float  hitT;        // >= 0 on hit, < 0 on miss.
    public float3 motionPrev;  // per-vertex world displacement since last frame.
    public float3 f0;          // specular F0.
    public uint   flags;       // bits 0..1 material, bit 2 celestial, bit 3 water-entering, bits 4..6 emission source.
    public uint   roughMetal;  // packHalf2x16(roughness, metalness)
    public uint   emissionSss; // packHalf2x16(emission, sss)
    public uint   iorTransmission; // packHalf2x16(IOR, transmission factor)
    public uint   rayCone;     // packHalf2x16(width, spread)
    // Compact state for arbitrary-direction height-field visibility in raygen. A disabled page sentinel
    // keeps non-POM materials on the zero-cost path. Basis vectors use octahedral 2x16-bit encoding.
    public uint   parallaxPageSign; // page bits 0..23, max LOD bits 24..30, UV handedness bit 31
    public uint   parallaxGeomOct;
    public uint   parallaxTangentOct;
    public uint   parallaxLodScale; // packHalf2(material LOD, exact POM height-field scale)
    public float4 parallaxMaterialUv;
    public float2 parallaxLocalUv;
    public uint   parallaxPlanarOffset; // packHalf2(displaced local UV - original local UV)
    public float  parallaxOffset;   // continuous outward relief displacement in block/world units
};

public static const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Set by world.rchit on a MATERIAL_WATER hit: true when the incoming ray travels against the prim's
// outward face normal (entering the water volume), false when it exits (water -> air). Bit 2
// (PAYLOAD_SHOW_CELESTIAL) is only meaningful on a miss and this bit only on a hit, so they never need
// to be read together, but they get distinct bits anyway to keep the two concerns unambiguous.
public static const uint PAYLOAD_WATER_ENTERING = 8u;
public static const uint PAYLOAD_EMISSION_SOURCE_SHIFT = 4u;
public static const uint PAYLOAD_EMISSION_SOURCE_MASK = 7u;
public static const uint EMISSION_SOURCE_NONE = 0u;
public static const uint EMISSION_SOURCE_UNIFORM = 1u;
public static const uint EMISSION_SOURCE_HEURISTIC = 2u;
public static const uint EMISSION_SOURCE_LAB_PBR = 3u;
public static const uint EMISSION_SOURCE_OVERRIDE = 4u;

public static const uint PAYLOAD_MATERIAL_MASK = 3u;
public static const uint MATERIAL_OPAQUE = 0u;
public static const uint MATERIAL_WATER = 1u;
public static const uint MATERIAL_PARTICLE = 2u;
public static const uint MATERIAL_GLASS = 3u;

// Distant Horizons retains coarse geometry, vertex colours and a mini-material category, but no
// original block sprite. The CPU importer stores these surface classes in TerrainPrim.tint.w.
public static const uint DH_SURFACE_SOLID = 0u;
public static const uint DH_SURFACE_WATER = 1u;
public static const uint DH_SURFACE_GLASS = 2u;
public static const uint DH_SURFACE_EMISSIVE = 3u;
public static const uint DH_SURFACE_EMISSIVE_GLASS = 4u;

public static const uint DH_MATERIAL_UNKNOWN = 0u;
public static const uint DH_MATERIAL_LEAVES = 1u;
public static const uint DH_MATERIAL_STONE = 2u;
public static const uint DH_MATERIAL_WOOD = 3u;
public static const uint DH_MATERIAL_METAL = 4u;
public static const uint DH_MATERIAL_DIRT = 5u;
public static const uint DH_MATERIAL_LAVA = 6u;
public static const uint DH_MATERIAL_DEEPSLATE = 7u;
public static const uint DH_MATERIAL_SNOW = 8u;
public static const uint DH_MATERIAL_SAND = 9u;
public static const uint DH_MATERIAL_TERRACOTTA = 10u;
public static const uint DH_MATERIAL_NETHER_STONE = 11u;
public static const uint DH_MATERIAL_WATER = 12u;
public static const uint DH_MATERIAL_GRASS = 13u;
public static const uint DH_MATERIAL_AIR = 14u;
public static const uint DH_MATERIAL_ILLUMINATED = 15u;

// Entity per-triangle record (48 B). The final lane mirrors TerrainPrim's integer material metadata.
public struct Prim {
    public float4 normal; // xyz = geometric normal, w = per-primitive emission strength
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Terrain per-triangle record (48 B). The final 16-byte lane contains integer geometry-local metadata;
// shared surface parameters live once in MaterialHeader instead of being copied into every triangle.
public struct TerrainPrim {
    public float4 normal;
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Compiled material record (80 B). Source-format data has already been decoded into canonical pages;
// texturePage indexes the dedicated canonical page arrays. materialUv maps sprite-local UV into the packed
// page, while albedoUv maps the vanilla atlas rectangle back to sprite-local UV.
public struct MaterialHeader {
    public uint model;
    public uint features;        // low feature bits; bits 24..31 = maximum valid material-page LOD
    public uint texturePage;
    public uint extensionOffset; // zero when no optional extension record is present
    public float4 materialUv; // packed-page uvMin.xy + uvExtent.xy
    public float4 albedoUv;   // vanilla atlas uvMin.xy + inverse uvExtent.xy
    public float4 params;  // roughness, metalness, IOR, transmission
    public float4 average; // whole-sprite linear-input average RGBA for translucent shadow filtering
};

public static const uint MATERIAL_FEATURE_SPEC = 1u;
public static const uint MATERIAL_FEATURE_NORMAL = 2u;
public static const uint MATERIAL_FEATURE_HEURISTIC_EMISSION = 4u;
public static const uint MATERIAL_FEATURE_OVERRIDE_EMISSION = 8u;
public static const uint MATERIAL_FEATURE_STOCHASTIC_ALPHA = 16u;
public static const uint MATERIAL_EMISSION_STRENGTH_SHIFT = 8u;
public static const uint MATERIAL_EMISSION_STRENGTH_MASK = 255u;
public static const float MATERIAL_MAX_OVERRIDE_EMISSION_STRENGTH = 4.0;
public static const uint MATERIAL_MAX_LOD_SHIFT = 24u;
public static const uint MATERIAL_MAX_LOD_MASK = 255u;

// Per-section geometry record: packed buffer addresses + per-bucket triangle offsets
// (gl_PrimitiveID restarts at 0 per geometry, so hit shaders re-add triBase[geometryIndex]).
public struct Section {
    public uint64_t primAddr;
    public uint64_t uvAddr;
    public uint triBase[4]; // fixed bucket order: solid/cutout/translucent/water
};

// Entity geometry record: packed buffer addresses, motion, and per-geometry triangle bases. Entity BLAS
// geometry order is opaque/any-hit; PrimitiveIndex restarts at zero for each geometry.
public struct EntityGeom {
    public uint64_t primAddr;
    public uint64_t idxAddr;
    public uint64_t uvAddr;
    public uint64_t dispAddr;
    public float4 rigidDisp;
    public uint triBase[2];
    public uint2 reserved;
};

// TLAS instance-custom-index encoding + terrain geometry buckets (shared by rahit/rchit).
public static const uint ENTITY_BIT = 0x800000u;
public static const uint PARTICLE_BIT = 0x400000u; // particles share the entity cutout path
public static const uint DH_INSTANCE_KIND = ENTITY_BIT | PARTICLE_BIT;
public static const uint IDX_MASK = 0x3FFFFFu;     // low 22 bits = geom-table index
public static const uint BUCKET_SOLID = 0u;
public static const uint BUCKET_CUTOUT = 1u;
public static const uint BUCKET_TRANSLUCENT = 2u;
public static const uint BUCKET_WATER = 3u;
// The view intersection and every relief visibility ray must sample the identical height-field LOD.
// A mismatch makes the shadow ray start inside a sharper/coarser version of the surface (black acne).
public static const float PARALLAX_SMOOTH_LOD_BIAS = 0.35;

// packHalf2x16 equivalent (Slang core module has no GLSL pack builtin; f32tof16 returns the half
// bit-pattern in the low 16 bits).
public uint packHalf2(float2 v) {
    return (f32tof16(v.y) << 16) | (f32tof16(v.x) & 0xFFFFu);
}

public float2 unpackHalf2(uint p) {
    return float2(f16tof32(p & 0xFFFFu), f16tof32(p >> 16));
}

public uint packOctNormal(float3 value) {
    float3 n = normalize(value);
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    float2 oct = n.xy;
    if (n.z < 0.0) {
        oct = (1.0 - abs(oct.yx)) * float2(oct.x >= 0.0 ? 1.0 : -1.0,
                oct.y >= 0.0 ? 1.0 : -1.0);
    }
    uint2 q = uint2(round(saturate(oct * 0.5 + 0.5) * 65535.0));
    return q.x | (q.y << 16);
}

public float3 unpackOctNormal(uint packed) {
    float2 oct = float2(float(packed & 0xFFFFu), float(packed >> 16)) / 65535.0 * 2.0 - 1.0;
    float3 n = float3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * float2(n.x >= 0.0 ? 1.0 : -1.0,
                n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

// Vanilla's block/entity atlases are ordinary sRGB-encoded PNGs sampled through a plain UNORM view (no
// hardware sRGB decode), so the raw texel is gamma-encoded. The path tracer's BRDF math (Lambertian/GGX,
// Beer-Lambert, NEE) is only correct on linear radiance/albedo, so every COLOR texture sample (not the
// LabPBR _s/_n data maps, which are already linear data channels) must go through this before it's used
// in any lighting calculation.
public float3 srgbToLinear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4, 2.4, 2.4));
    float3 isHi = step(float3(0.04045, 0.04045, 0.04045), c);
    return lerp(lo, hi, isHi);
}

// Turn DH's baked transparent vertex colour into a stable optical filter. Saturation keeps stained
// glass coloured even when the source alpha is low, while clear glass remains close to neutral.
public float3 glassTransmissionFilter(float3 linearColor, float alpha) {
    float3 color = max(linearColor, float3(0.0, 0.0, 0.0));
    float hi = max(color.r, max(color.g, color.b));
    float lo = min(color.r, min(color.g, color.b));
    float saturation = hi > 1.0e-4 ? (hi - lo) / hi : 0.0;
    float dyeStrength = clamp(max(alpha, saturation * 0.92), 0.0, 0.96);
    return max(lerp(float3(1.0, 1.0, 1.0), color, dyeStrength),
            float3(0.012, 0.012, 0.012));
}
