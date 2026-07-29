package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import dev.comfyfluffy.caustica.compat.VoxyCompat;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link CausticaConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcaustica.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** Runtime-tunable RT options, in display order. Paired two-per-row by {@code OptionsList.addSmall}. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            spp(),
            maxBounces(),
            sunSize(),
            restirEnabled(),
            restirCandidates(),
            restirSpatialReuse(),
            entities(),
            particles(),
            waterWaves(),
            parallaxEnabled(),
            parallaxStrength(),
            parallaxSmoothing(),
            parallaxDistance(),
            fogEnabled(),
            fogDensity(),
            fogHeightFalloff(),
            fogAnisotropy(),
            fogDistance(),
            proceduralClouds(),
            motionBlurEnabled(),
            motionBlurStrength(),
            bloomEnabled(),
            bloomStrength(),
            dlssQuality(),
            hdrEnabled(),
            hdrPaperWhite(),
            hdrPeak(),
            debugView(),
        };
    }

    /** Live settings supplied by the bundled no-Sodium Voxy bridge. */
    public static OptionInstance<?>[] voxyOptions() {
        return new OptionInstance<?>[] {
            OptionInstance.createBoolean(
                    "caustica.options.voxy.enabled",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("caustica.options.voxy.enabled.tooltip")),
                    VoxyCompat.active(),
                    enabled -> {
                        if (VoxyCompat.setActive(enabled)) {
                            RtDistantHorizonsTerrain.INSTANCE.requestFullRefresh();
                        }
                    }),
            OptionInstance.createBoolean(
                    "caustica.options.voxy.ingest",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("caustica.options.voxy.ingest.tooltip")),
                    VoxyCompat.ingestEnabled(),
                    VoxyCompat::setIngestEnabled),
            new OptionInstance<>(
                    "caustica.options.voxy.distance",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("caustica.options.voxy.distance.tooltip")),
                    (caption, sections) -> Options.genericValueLabel(caption,
                            Component.translatable("caustica.options.voxy.chunks", sections * 32)),
                    // Voxy stores this value in 32-block sections. Exposing the native steps avoids
                    // hundreds of config writes and full desired-set recalculations during one drag.
                    new OptionInstance.IntRange(1, 16),
                    Math.clamp(VoxyCompat.configuredRenderDistanceChunks() / 32, 1, 16),
                    sections -> VoxyCompat.setConfiguredRenderDistanceChunks(sections * 32))
        };
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "caustica.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> restirEnabled() {
        return bool("caustica.options.rt.restir", CausticaConfig.Rt.Restir.ENABLED);
    }

    private static OptionInstance<Integer> restirCandidates() {
        IntSetting setting = CausticaConfig.Rt.Restir.CANDIDATES;
        return new OptionInstance<>(
            "caustica.options.rt.restirCandidates",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.restirCandidates.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Boolean> restirSpatialReuse() {
        return bool("caustica.options.rt.restirSpatial", CausticaConfig.Rt.Restir.SPATIAL_REUSE);
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    private static OptionInstance<Boolean> parallaxEnabled() {
        return bool("caustica.options.rt.parallax", CausticaConfig.Rt.Composite.PARALLAX_ENABLED);
    }

    private static OptionInstance<Integer> parallaxStrength() {
        FloatSetting setting = CausticaConfig.Rt.Composite.PARALLAX_STRENGTH;
        return new OptionInstance<>(
            "caustica.options.rt.parallaxStrength",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.parallaxStrength.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 400),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 400),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Boolean> parallaxSmoothing() {
        return bool("caustica.options.rt.parallaxSmoothing",
                CausticaConfig.Rt.Composite.PARALLAX_SMOOTHING);
    }

    private static OptionInstance<Integer> parallaxDistance() {
        FloatSetting setting = CausticaConfig.Rt.Composite.PARALLAX_DISTANCE;
        return new OptionInstance<>(
            "caustica.options.rt.parallaxDistance",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.parallaxDistance.tooltip")),
            (caption, blocks) -> Options.genericValueLabel(caption, Component.literal(blocks + " blocks")),
            new OptionInstance.IntRange(16, 256),
            Math.clamp(Math.round(setting.value()), 16, 256),
            blocks -> setting.set(blocks.floatValue()));
    }

    private static OptionInstance<Boolean> fogEnabled() {
        return bool("caustica.options.rt.volumetricFog", CausticaConfig.Rt.Fog.ENABLED);
    }

    private static OptionInstance<Integer> fogDensity() {
        FloatSetting setting = CausticaConfig.Rt.Fog.DENSITY;
        return new OptionInstance<>(
            "caustica.options.rt.fogDensity",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.fogDensity.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 2000.0f), 0, 100),
            percent -> setting.set(percent / 2000.0f));
    }

    private static OptionInstance<Integer> fogHeightFalloff() {
        FloatSetting setting = CausticaConfig.Rt.Fog.HEIGHT_FALLOFF;
        return new OptionInstance<>(
            "caustica.options.rt.fogHeightFalloff",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fogHeightFalloff.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 2000.0f), 0, 100),
            percent -> setting.set(percent / 2000.0f));
    }

    private static OptionInstance<Integer> fogAnisotropy() {
        FloatSetting setting = CausticaConfig.Rt.Fog.ANISOTROPY;
        return new OptionInstance<>(
            "caustica.options.rt.fogAnisotropy",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fogAnisotropy.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 90),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 90),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Integer> fogDistance() {
        FloatSetting setting = CausticaConfig.Rt.Fog.MAX_DISTANCE;
        return new OptionInstance<>(
            "caustica.options.rt.fogDistance",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fogDistance.tooltip")),
            (caption, blocks) -> Options.genericValueLabel(caption, Component.literal(blocks + " blocks")),
            new OptionInstance.IntRange(16, 256),
            Math.clamp(Math.round(setting.value()), 16, 256),
            blocks -> setting.set(blocks.floatValue()));
    }

    private static OptionInstance<Boolean> motionBlurEnabled() {
        return bool("caustica.options.rt.motionBlur", CausticaConfig.Rt.Post.MOTION_BLUR_ENABLED);
    }

    private static OptionInstance<Boolean> proceduralClouds() {
        return bool("caustica.options.rt.proceduralClouds", CausticaConfig.Rt.Clouds.ENABLED);
    }

    private static OptionInstance<Integer> motionBlurStrength() {
        FloatSetting setting = CausticaConfig.Rt.Post.MOTION_BLUR_STRENGTH;
        return new OptionInstance<>(
            "caustica.options.rt.motionBlurStrength",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.motionBlurStrength.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Boolean> bloomEnabled() {
        return bool("caustica.options.rt.bloom", CausticaConfig.Rt.Post.BLOOM_ENABLED);
    }

    private static OptionInstance<Integer> bloomStrength() {
        FloatSetting setting = CausticaConfig.Rt.Post.BLOOM_STRENGTH;
        return new OptionInstance<>(
            "caustica.options.rt.bloomStrength",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.bloomStrength.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            percent -> setting.set(percent / 100.0f));
    }

    // NVSDK_NGX_PerfQuality_Value, ordered performance -> quality for the slider. Per NVIDIA's DLSS-RR
    // programming guide, Ray Reconstruction only supports Performance(0), Balanced(1), Quality(2),
    // Ultra-Performance(3), and DLAA(5) — Ultra Quality(4) is not a valid PerfQualityValue for RR (its
    // optimal-settings query returns a zeroed render size for it) and is deliberately excluded here.
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("caustica.options.rt.hdr", CausticaConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Codec.INT),
            Math.clamp(setting.value(), 0, 9),
            setting::set);
    }

    /** Rebuild DH's render cache and Caustica's RT proxy after changing DH quality settings. */
    public static Button distantHorizonsRefreshButton() {
        Button button = Button.builder(Component.translatable("caustica.options.rt.dhRefresh"), clicked -> {
            boolean dhReloaded = DistantHorizonsCompat.reloadRenderDataCache();
            RtDistantHorizonsTerrain.INSTANCE.requestFullRefresh();
            clicked.setMessage(Component.translatable(dhReloaded
                    ? "caustica.options.rt.dhRefresh.queued"
                    : "caustica.options.rt.dhRefresh.rtOnly"));
        }).width(310).build();
        button.active = DistantHorizonsCompat.enabled() && Minecraft.getInstance().level != null;
        return button;
    }

    /** Drop Voxy's generated proxy cache and start a bounded rebuild around the current camera. */
    public static Button voxyRefreshButton() {
        Button button = Button.builder(Component.translatable("caustica.options.voxy.refresh"), clicked -> {
            boolean reset = VoxyCompat.reset();
            RtDistantHorizonsTerrain.INSTANCE.requestFullRefresh();
            clicked.setMessage(Component.translatable(reset
                    ? "caustica.options.voxy.refresh.queued"
                    : "caustica.options.voxy.refresh.unavailable"));
        }).width(310).build();
        button.active = VoxyCompat.enabled() && Minecraft.getInstance().level != null;
        return button;
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
