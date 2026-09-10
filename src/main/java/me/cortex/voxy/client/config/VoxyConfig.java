package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainConfig;
import me.cortex.voxy.commonImpl.compat.sable.SableContraptionRenderDistance;
import net.neoforged.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class VoxyConfig {
    public enum LeafLodMode {
        FAST,
        BALANCED,
        QUALITY
    }

    public static final int MIN_REQUEST_DISTANCE = 8;
    public static final int MAX_REQUEST_DISTANCE = 48;
    public static final int MAX_CLOUD_DISTANCE = 128;
    public static final float MIN_SUBDIVISION_SIZE = 28.0f;
    public static final float MAX_SUBDIVISION_SIZE = 256.0f;

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.TRANSIENT)
            .create();

    public static final VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public boolean sableLodRendering = true;
    public boolean distantTrains = true;
    public boolean distantTracks = true;
    public boolean distantContraptions = true;
    public boolean distantBeacons = true;
    public int distantBeaconMaxChunks = 0;
    public boolean distantKinetics = true;
    public boolean kineticEnclosedCulling = true;
    // Zero uses the full LOD radius.
    public int distantTrainMaxChunks = 0;
    public int distantTrackMaxChunks = 0;
    public int distantContraptionMaxChunks = 0;
    public int distantKineticMaxChunks = 0;
    public boolean distantPowerGridWires = true;
    public int distantPowerGridWireMaxChunks = 0;
    public boolean distantSimulatedLasers = true;
    public int distantSimulatedLaserMaxChunks = 0;
    public boolean distantFramedBlocks = true;
    public int distantFramedBlocksMaxChunks = 0;
    public boolean distantLittleTiles = true;
    public int distantLittleTilesMaxChunks = 0;
    public boolean distantDomum = true;
    public int distantDomumMaxChunks = 0;
    public int distantContraptionGpuBudgetMiB = 48;
    public int distantKineticGpuBudgetMiB = 32;
    // Experimental, shader-pack supplied LOD-only programs. Resolved only during shader reload.
    public boolean lodLiteShading = false;
    // Vanilla-style box blend for biome-coloured LOD water. Zero preserves hard biome edges.
    public int biomeBlendRadius = 2;
    // "water" blends pure fluids; "water_grass" also blends grass and foliage.
    public String biomeBlendScope = "water";
    public int simulatedContraptionRenderDistancePercent = 100;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 123;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    public int fogDistancePercent = 100;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;
    public int renderPressure = 2;
    public int lodBoundaryBuffer = 1;
    public boolean enableLodBoundaryFade = true;
    public int lodBoundaryFadeLength = 16;
    public int lodBoundaryInset = 8;
    public int earthCurveRatio = 0;
    public boolean enableExtendedRequestDistance = false;
    public int requestDistance = 48;
    public String ssaoMode;
    public boolean useEnvironmentalFog = true;
    public String leafLodMode = "balanced";
    public boolean enableFarPlayerRendering = true;
    public boolean enableFarVehicleRendering = true;
    public boolean renderFarPlayerNames = true;
    public int farPlayerAnimationDistance = 0;
    public boolean shareFarPlayerPosition = true;
    public boolean joinMessageShown = false;
    public boolean upgradeCleanupNoticeShown = false;

    public int getRequestDistance() {
        return Math.clamp(this.requestDistance, MIN_REQUEST_DISTANCE, MAX_REQUEST_DISTANCE);
    }

    public int getFarEntityRenderDistanceBlocks() {
        return Math.clamp(Math.round(this.sectionRenderDistance * 32.0f * 16.0f), 64, 32768);
    }

    public int getRenderPressureLevel() {
        if (this.renderPressure < 0 || this.renderPressure > 4) {
            this.renderPressure = 2;
        }
        return this.renderPressure;
    }

    public LeafLodMode getLeafLodMode() {
        if (this.leafLodMode == null) {
            return LeafLodMode.BALANCED;
        }

        try {
            return LeafLodMode.valueOf(this.leafLodMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LeafLodMode.BALANCED;
        }
    }

    public void setLeafLodMode(LeafLodMode mode) {
        this.leafLodMode = mode.name().toLowerCase(Locale.ROOT);
    }

    // EclipticSeasons compat: recolor LOD terrain with seasonal snow (master switch for the eclipticseasons mixins).
    public boolean eclipticSeasonsSnowLod = true;
    // EclipticSeasons compat: re-import region LODs when the season changes.
    public boolean eclipticSeasonsLodAutoReload = false;
    // EclipticSeasons compat: rebuild the LOD renderer when the season changes.
    public boolean eclipticSeasonsReloadOnSeasonChange = false;

    // Print the build, its maintainer and the fork's repository to chat on world join.
    public boolean showJoinMessage = true;

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) {
            return SSAO.SSAOMode.AUTO;
        }

        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SSAO.SSAOMode.AUTO;
        }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        if (!VoxyCommon.isAvailable()) {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }

        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                VoxyConfig config = GSON.fromJson(reader, VoxyConfig.class);
                if (config != null) {
                    config.sanitize();
                    return config;
                }
                Logger.error("Failed to load Voxy config; resetting it");
            } catch (IOException | RuntimeException e) {
                Logger.error("Could not load Voxy config; resetting it", e);
                backupInvalidConfig(path);
            }
        }

        Logger.info("Config does not exist; creating a new one");
        var config = new VoxyConfig();
        config.save();
        return config;
    }

    public void sanitize() {
        this.sectionRenderDistance = Math.clamp(this.sectionRenderDistance, 2.0f, 64.0f);
        this.subDivisionSize = Math.clamp(this.subDivisionSize, MIN_SUBDIVISION_SIZE, MAX_SUBDIVISION_SIZE);
        this.requestDistance = Math.clamp(this.requestDistance, MIN_REQUEST_DISTANCE, MAX_REQUEST_DISTANCE);
        // Older builds measured this percentage against one sixteenth of the LOD radius.
        if (this.fogDistancePercent > 200) {
            this.fogDistancePercent = Math.max(5, Math.round(this.fogDistancePercent / 16.0f));
        }
        this.fogDistancePercent = Math.clamp(this.fogDistancePercent, 5, 200);
        this.skyFogDistance = Math.clamp(this.skyFogDistance, 0, 1024);
        this.cloudDistance = Math.clamp(this.cloudDistance, 0, MAX_CLOUD_DISTANCE);
        this.fogIntensity = Math.clamp(this.fogIntensity, 0.0f, 1.0f);
        this.fogDensity = Math.clamp(this.fogDensity, 0.0f, 1.0f);
        this.lodBoundaryBuffer = Math.clamp(this.lodBoundaryBuffer, 0, 4);
        this.lodBoundaryFadeLength = Math.clamp(this.lodBoundaryFadeLength, 8, 64);
        this.lodBoundaryInset = Math.clamp(this.lodBoundaryInset, 8, 32);
        this.setLeafLodMode(this.getLeafLodMode());
        this.farPlayerAnimationDistance = Math.clamp(this.farPlayerAnimationDistance, 0, 32768);
        this.biomeBlendRadius = Math.clamp(this.biomeBlendRadius, 0, 7);
        if (!"water".equals(this.biomeBlendScope) && !"water_grass".equals(this.biomeBlendScope)) {
            this.biomeBlendScope = "water";
        }
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            this.syncSableContraptionRenderDistance();
            this.syncDistantTrainConfig();
            return;
        }

        this.sanitize();
        Path path = getConfigPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(this));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Logger.error("Failed to write Voxy config", e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }

        this.syncSableContraptionRenderDistance();
        this.syncDistantTrainConfig();
    }

    private static void backupInvalidConfig(Path path) {
        try {
            Path backup = path.resolveSibling(path.getFileName() + ".invalid");
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.error("Failed to back up invalid Voxy config", e);
        }
    }

    public void syncSableContraptionRenderDistance() {
        SableContraptionRenderDistance.updateClientConfig(
                this.isRenderingEnabled() && this.sableLodRendering,
                this.sectionRenderDistance,
                this.simulatedContraptionRenderDistancePercent
        );
    }

    public void syncDistantTrainConfig() {
        DistantTrainConfig.updateClientConfig(
                this.isRenderingEnabled() && this.distantTrains,
                this.createRenderDistance(this.distantTrainMaxChunks)
        );
        DistantTrainConfig.updateClientContraptionConfig(
                this.isRenderingEnabled() && this.distantContraptions,
                this.createRenderDistance(this.distantContraptionMaxChunks)
        );
    }

    public double createLodRadius() {
        return 32.0 * 16.0 * this.sectionRenderDistance;
    }

    public double createRenderDistance(int maxChunks) {
        double lod = this.createLodRadius();
        return maxChunks > 0 ? Math.min(maxChunks * 16.0, lod) : lod;
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
