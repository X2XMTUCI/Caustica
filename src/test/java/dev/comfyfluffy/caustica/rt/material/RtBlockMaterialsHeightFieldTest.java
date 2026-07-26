package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtBlockMaterialsHeightFieldTest {
    private static final float EPS = 1.0e-5f;

    @Test
    void narrowAuthoredRangeKeepsItsPhysicalAmplitude() {
        float low = 238.0f / 255.0f;
        float high = 1.0f;
        RtBlockMaterials.HeightField field = RtBlockMaterials.HeightField.create(
                2, 1, new float[]{low, high});

        float lowDisplacement = field.displacement(0.25f, 0.5f, false);
        float highDisplacement = field.displacement(0.75f, 0.5f, false);
        assertEquals(0.0f, lowDisplacement, EPS);
        assertEquals(high - low, highDisplacement - lowDisplacement, EPS);
        assertTrue(highDisplacement - lowDisplacement < 0.07f,
                "238..255 must not be stretched to the full displacement depth");
    }

    @Test
    void highResolutionCheckerboardIsAreaFilteredBeforeMeshing() {
        int size = 256;
        float[] samples = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                samples[y * size + x] = ((x + y) & 1) == 0 ? 0.0f : 1.0f;
            }
        }
        RtBlockMaterials.HeightField field = RtBlockMaterials.HeightField.create(size, size, samples);

        float lod32 = field.meshLod(1.0f / 32.0f, 1.0f / 32.0f);
        assertEquals(3.0f, lod32, EPS);
        assertEquals(0.5f, field.displacementLod(0.37f, 0.61f, lod32, false), EPS);

        float lod10 = field.meshLod(1.0f / 10.0f, 1.0f / 10.0f);
        assertTrue(lod10 > 4.5f);
        assertEquals(0.5f, field.displacementLod(0.37f, 0.61f, lod10, false), EPS);

        float lod256 = field.meshLod(1.0f / 256.0f, 1.0f / 256.0f);
        assertEquals(0.0f, lod256, EPS);
        assertEquals(0.0f, field.displacementLod(
                0.5f / size, 0.5f / size, lod256, false), EPS);
    }

    @Test
    void nativeSixteenPixelMapRetainsOneSquarePerTexel() {
        int size = 16;
        float[] samples = new float[size * size];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 1.0f;
        }
        samples[0] = 0.0f;
        RtBlockMaterials.HeightField field = RtBlockMaterials.HeightField.create(size, size, samples);

        float lod = field.meshLod(1.0f / size, 1.0f / size);
        assertEquals(0.0f, lod, EPS);
        assertEquals(0.0f, field.displacementLod(
                0.5f / size, 0.5f / size, lod, false), EPS);
    }
}
