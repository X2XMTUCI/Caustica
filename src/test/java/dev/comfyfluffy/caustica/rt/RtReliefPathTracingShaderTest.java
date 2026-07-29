package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RtReliefPathTracingShaderTest {
    private static String shader(String name) throws IOException {
        return Files.readString(Path.of("shaders", "world", name));
    }

    @Test
    void closestHitExportsEveryReliefIntersectionAndIgnoresLegacyShadowToggle() throws IOException {
        String common = shader("world_common.slang");
        String closestHit = shader("world.rchit.slang");

        assertTrue(common.contains("public uint   parallaxPageSign"));
        assertTrue(common.contains("public float  parallaxOffset"));
        assertTrue(common.contains("public uint   parallaxLodScale"));
        assertTrue(common.contains("public uint   parallaxPlanarOffset"));
        assertTrue(closestHit.contains("payloadSetParallax(payload"));
        assertTrue(closestHit.contains("payload.parallaxTangentOct = packOctNormal"));
        assertTrue(closestHit.contains("payload.parallaxLodScale = packHalf2(float2(lod, reliefScale))"));
        assertTrue(common.contains("w max distance"));
        assertTrue(common.contains(
                "public static const float PARALLAX_SMOOTH_LOD_BIAS = 0.35;"));
        assertTrue(closestHit.contains(
                "1.0 - smoothstep(maxDistance * 0.8, maxDistance, cameraDistance)"));
        assertFalse(closestHit.contains("remainingTexels"));
        assertFalse(closestHit.contains("float detailFade"));
        assertTrue(closestHit.contains(
                "parallaxParams.x * distanceFade * grazingFade * edgeFade"));
        assertTrue(closestHit.contains("smoothstep(0.002, 0.006, rawNoV)"));
        assertTrue(closestHit.contains(
                "payload.parallaxPlanarOffset = packHalf2(reliefPlanarOffset);"));
        assertTrue(closestHit.contains("float authoredHeight = samplePageHeight(page,"));
        assertTrue(closestHit.contains("reliefOffset = heightScale * saturate(authoredHeight);"));
        assertFalse(closestHit.contains("reliefOffset = heightScale * saturate(1.0 - hitDepth)"));
        assertFalse(closestHit.contains("lod >= 3.0"));
        assertFalse(closestHit.contains("parallaxParams.z"));
    }

    @Test
    void raygenTestsDirectAndContinuationDirectionsAtEveryBounce() throws IOException {
        String raygen = shader("world.rgen.slang");

        assertTrue(raygen.contains("for (int bounce = 0; bounce <= maxBounces; bounce++)"));
        assertTrue(raygen.contains(
                "float heightVis = traceReliefVisibility(lightDir, hitCameraDistance);"));
        assertTrue(raygen.contains(
                "throughput *= traceReliefVisibility(rd, hitCameraDistance);"));
        assertTrue(raygen.contains("float2 lodScale = unpackHalf2(payload.parallaxLodScale);"));
        assertTrue(raygen.contains("float heightScale = lodScale.y;"));
        assertTrue(raygen.contains(
                "smoothing ? lod + PARALLAX_SMOOTH_LOD_BIAS : floor(lod)"));
        assertTrue(shader("world.rchit.slang").contains(
                "smoothing ? lod + PARALLAX_SMOOTH_LOD_BIAS : floor(lod)"));
        assertTrue(raygen.contains("float2 planarOffset = unpackHalf2(payload.parallaxPlanarOffset);"));
        assertTrue(raygen.contains(
                "float3 rayOriginNormal = reliefHit ? unpackOctNormal(payload.parallaxGeomOct) : n;"));
        assertTrue(raygen.contains("float3 p = hitPos + rayOriginNormal * SURF_BIAS;"));
        assertTrue(raygen.contains("float3 macroHitPos = ro + rd * payload.hitT;"));
        assertTrue(raygen.contains("float3 shadowSurfacePos = reliefHit ? macroHitPos : hitPos;"));
        assertTrue(raygen.contains("visibility(shadowP, lightDir, 10000.0) * heightVis"));
        assertFalse(raygen.contains("visibility(p, lightDir, 10000.0) * heightVis"));
        assertFalse(raygen.contains("payload.parallaxOffset / max(worldPush.parallaxParams.x"));
        assertTrue(raygen.contains("payload.parallaxPageSign = 0x00FFFFFFu;"));
        assertFalse(raygen.contains("parallaxVisibility("));
        assertFalse(raygen.contains("parallaxParams.z"));
        assertFalse(raygen.contains("PARALLAX_SHADOWS"));
    }

    @Test
    void alphaTestedGeometryKeepsCoverageAndShadingOnTheSameUv() throws IOException {
        String closestHit = shader("world.rchit.slang");
        String anyHit = shader("world.rahit.slang");
        String pipeline = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "rt", "pipeline", "RtPipeline.java"));

        assertTrue(closestHit.contains(
                "bool entityAllowsParallax = GeometryIndex() == 0u && header.model == MATERIAL_OPAQUE;"));
        assertTrue(closestHit.contains(
                "bool terrainAllowsParallax = bucket == BUCKET_SOLID"));
        assertTrue(closestHit.contains(
                "material == MATERIAL_OPAQUE && entityAllowsParallax"));
        assertTrue(closestHit.contains(
                "material == MATERIAL_OPAQUE && terrainAllowsParallax"));
        assertTrue(pipeline.contains(
                "|| bucket == RtAccel.BUCKET_TRANSLUCENT"));
        assertTrue(pipeline.contains(
                "|| bucket == RtAccel.BUCKET_WATER;"));
        assertTrue(anyHit.contains(
                "if (!shadowRay)"));
        assertTrue(anyHit.contains(
                "blockAlbedoAtlas.SampleLevel(uv, 0.0).a <= TRANSLUCENT_ALPHA_EPSILON"));
        assertTrue(anyHit.contains(
                "static const float TRANSLUCENT_ALPHA_EPSILON = 1.0 / 255.0;"));
    }

    @Test
    void materialPagesNeededByRaygenAreVisibleToThatStage() throws IOException {
        String pipeline = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "rt", "pipeline", "RtPipeline.java"));

        assertTrue(pipeline.contains("b == MATERIAL_SURFACE0_BINDING"));
        assertTrue(pipeline.contains("b == MATERIAL_NORMAL_AO_BINDING"));
        assertTrue(pipeline.contains("b == MATERIAL_SURFACE1_BINDING"));
    }

    @Test
    void primaryFogExtinctionUsesTheTracedCelestialVisibilityMask() throws IOException {
        String raygen = shader("world.rgen.slang");

        assertTrue(raygen.contains(
                "visibleDensitySum += density * saturate(luminance(vis));"));
        assertTrue(raygen.contains(
                "float opticalDensitySum = includeScattering ? visibleDensitySum : densitySum;"));
        assertTrue(raygen.contains(
                "float transmittance = exp(-opticalDensitySum * distance * 0.25);"));
        assertFalse(raygen.contains(
                "float transmittance = exp(-densitySum * distance * 0.25);"));
    }

    @Test
    void restirUsesValidatedReservoirsForCelestialAndTexturedEmissiveGeometry() throws IOException {
        String raygen = shader("world.rgen.slang");
        String composite = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "rt", "RtComposite.java"));
        String terrainMesher = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "rt", "terrain", "RtTerrainMesher.java"));
        String entities = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "rt", "entity", "RtEntities.java"));

        assertTrue(raygen.contains("RESTIR_HISTORY_VALID"));
        assertTrue(raygen.contains("restirLoadPreviousSample"));
        assertTrue(raygen.contains("dot(previousNormal, receiverNormal) > 0.88"));
        assertTrue(raygen.contains("abs(surfaceData.z - expectedPreviousDepth) <= depthTolerance"));
        assertTrue(raygen.contains("pHat * previousW * previousM"));
        assertTrue(raygen.contains("restirNormalization"));
        assertTrue(raygen.contains("visibility(shadowP, lightDir, 10000.0) * heightVis"));
        assertFalse(raygen.contains("visibility(shadowP, candidateDir"));
        assertTrue(raygen.contains("struct EmissiveTriangle"));
        assertTrue(raygen.contains("RestirSample restirFreshLocal"));
        assertTrue(raygen.contains("float3 restirLocalTarget"));
        assertTrue(raygen.contains("payloadAverageEmissionChroma"));
        assertTrue(raygen.contains("float totalProposalWeight = sample.measure * sample.measure"));
        assertTrue(raygen.contains("bool restirThisVertex = (worldPush.flags & RESTIR_ENABLED) != 0u"));
        assertTrue(raygen.contains("reservoir.sample.value - worldPush.camOffset"));
        assertTrue(raygen.contains("sampleData.xyz + worldPush.camOffset - worldPush.camDelta"));
        assertTrue(raygen.contains("!emissionCoveredByPreviousNee"));
        assertTrue(raygen.contains("bool localRestirDomain = restirThisVertex"));
        assertTrue(raygen.contains("float3 celestialVis = visibility(shadowP, celestialDir, 10000.0)"));
        assertFalse(raygen.contains("RESTIR_LOCAL_MIX"));
        assertTrue(composite.contains("private static final int GUIDE_COUNT = 10"));
        assertTrue(composite.contains("worldPipeline.setExtraStorageImage(9, restirB1.view)"));
        assertTrue(composite.contains("flags |= 0b1000000000"));
        assertTrue(composite.contains("MAX_EMISSIVE_LIGHT_TRIANGLES = 65_536"));
        assertTrue(composite.contains("STATIC_EMISSIVE_LIGHT_TRIANGLE_LIMIT = 60_000"));
        assertTrue(composite.contains("DISTANT_EMISSIVE_LIGHT_TRIANGLE_BUDGET = 512"));
        assertTrue(composite.contains("LOCAL_EMISSIVE_LIGHT_RADIUS = 64.0f"));
        assertTrue(composite.contains("finalizeEmissiveDistribution(dst, total)"));
        assertTrue(raygen.contains("[mid].p0.w < cdfTarget"));
        assertFalse(raygen.contains("restirEmissionProxy"));
        assertFalse(raygen.contains("TEXEL_CANDIDATES"));
        assertFalse(raygen.contains("innerNormalization"));
        assertTrue(composite.contains("(int) emissiveLightAddress"));
        assertTrue(composite.contains("emissiveLightCount"));
        assertTrue(terrainMesher.contains("extractEmissiveTriangles"));
        assertTrue(terrainMesher.contains("RtEmissiveSampling.estimatedPower"));
        assertTrue(terrainMesher.contains("!(estimatedPower > 0.0f)"));
        assertFalse(terrainMesher.contains(
                "desc.emissionSource() == RtMaterialDesc.EmissionSource.NONE"));
        assertTrue(entities.contains("snapshotEmissiveCapture"));
        assertTrue(entities.contains("writeEmissiveTriangles"));
    }

    @Test
    void animatedEnchantmentGlintDoesNotBecomeDuplicateOpaqueRtGeometry() throws IOException {
        String collector = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "rt", "entity", "RtEntityCollector.java"));

        assertTrue(collector.contains(
                "if (capture == null || isEnchantmentGlint(renderType))"));
        assertTrue(collector.contains("renderType == RenderTypes.armorEntityGlint()"));
        assertTrue(collector.contains("renderType == RenderTypes.entityGlint()"));
        assertTrue(collector.contains("renderType == RenderTypes.glint()"));
        assertTrue(collector.contains("renderType == RenderTypes.glintTranslucent()"));
    }

    @Test
    void distantTerrainHandoffUsesExactPublishedSectionMask() throws IOException {
        String anyHit = shader("world.rahit.slang");
        String composite = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "rt", "RtComposite.java"));

        assertTrue(anyHit.contains("bool vanillaRtSectionReady(float3 hitPos, WorldPush worldPush)"));
        assertTrue(anyHit.contains("mask[7] != 0x43535452u"));
        assertTrue(anyHit.contains("mask[8 + word]"));
        assertTrue(anyHit.contains("if (vanillaRtSectionReady(hitPos, worldPush))"));
        assertFalse(anyHit.contains("max(fromCamera.x, fromCamera.y) <= worldPush.waterAnchor.z"));
        assertTrue(composite.contains("RtTerrain.writeDistantReadyMask(readyMask)"));
        assertTrue(composite.contains("(int) (readyMaskAddress >>> 32)"));
    }

    @Test
    void baselineBytecodeOverlayAddsPersistentDistanceSliderAndPushValue() throws IOException {
        String patcher = Files.readString(Path.of("tools", "RemoveParallaxShadowOption.java"));

        assertTrue(patcher.contains("\"parallaxDistance\""));
        assertTrue(patcher.contains("\"PARALLAX_DISTANCE\""));
        assertTrue(patcher.contains("new LdcInsnNode(64.0f)"));
        assertTrue(patcher.contains("new LdcInsnNode(16.0f)"));
        assertTrue(patcher.contains("new LdcInsnNode(256.0f)"));
        assertTrue(patcher.contains("\"value\", \"()F\""));
    }
}
