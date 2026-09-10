package me.cortex.voxy;

import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * Main mod class for Voxy on NeoForge.
 *
 * Handles config registration and config screen setup.
 * Actual initialization happens via mixins (MixinRenderSystem).
 */
@Mod("voxy")
public class Voxy {
    public static final String MODID = "voxy";

    private final me.cortex.voxy.compat.far.FarEntityService farEntityService = new me.cortex.voxy.compat.far.FarEntityService();

    public Voxy(IEventBus modEventBus, ModContainer container) {
        //Terrain streaming is handled by the external VSS mod; on a dedicated server voxy only
        //provides the sable contraption ticket hook (MixinServerLevel) and, with Create installed,
        //the distant-train pose sampler. Everything else is client side.

        modEventBus.addListener(Voxy::registerPayloads);

        //Far players / ridden vehicles: server samples player snapshots, client renders lightweight
        //proxies past the entity view distance
        modEventBus.addListener(this::registerFarEntityPayloads);
        NeoForge.EVENT_BUS.addListener(this.farEntityService::onServerTick);
        NeoForge.EVENT_BUS.addListener(this.farEntityService::onPlayerLoggedOut);

        if (ModList.get().isLoaded("create")) {
            //Server-side train sampling (works on the integrated server too). The sampler class is
            //the only place that touches Create classes, so it must stay behind this gate.
            NeoForge.EVENT_BUS.register(me.cortex.voxy.commonImpl.compat.create.CreateTrainSampler.INSTANCE);
            //Dedicated-server uniform ceiling for distant-train streaming (voxy-server.toml). Loads on
            //dedicated and integrated servers alike; pushes its values into the sampler's control point.
            me.cortex.voxy.commonImpl.compat.create.CreateServerConfig.register(container, modEventBus);
        }

        // Only register client config on client side
        if (FMLLoader.getDist() == Dist.CLIENT) {
            // Register NeoForge config
            VoxyNeoForgeConfig.register(container);

            // Register the built-in NeoForge config screen
            container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

            // Build/maintainer/repo line on world join (showJoinMessage in voxy-config.json)
            NeoForge.EVENT_BUS.register(me.cortex.voxy.client.VoxyJoinMessage.INSTANCE);

            // Voxy's Sodium video-settings page is registered by VoxyConfigMenu (@ConfigEntryPointForge,
            // Sodium 0.8 native config API), not here.

            // EclipticSeasons compat: rebuild the LOD renderer on season change. Gated on the mod being present
            // so the snow-LOD code (which references EclipticSeasons client classes) never loads without it.
            if (ModList.get().isLoaded("eclipticseasons")) {
                NeoForge.EVENT_BUS.register(me.cortex.voxy.client.core.compat.eclipticseasons.VoxyEsHandler.INSTANCE);
            }

            //Distant train rendering is Create-free on the client (poses + baked meshes arrive over
            //our own payloads), so it registers unconditionally. Bogeys go through Create's own
            //style renderers and need the mod present. Rendering hooks the tail of the LOD pipeline
            //so LOD terrain depth occludes trains and tracks; the event bus only handles cleanup.
            var trainRenderer = new me.cortex.voxy.client.compat.create.DistantTrainRenderer();
            NeoForge.EVENT_BUS.register(trainRenderer);
            me.cortex.voxy.client.compat.LodPipelineHooks.register(trainRenderer);
            //Occlusion recorder behind /voxy debug trains occlusion (Create-free)
            me.cortex.voxy.client.compat.LodPipelineHooks.frameDebugProbe =
                    me.cortex.voxy.client.compat.create.DistantOcclusionDebug.PROBE;
            if (ModList.get().isLoaded("create")) {
                //Bogey snapshot capture touches Create's registries, so it stays behind this gate
                me.cortex.voxy.client.compat.create.DistantTrainRenderer.bogeyMeshProvider =
                        me.cortex.voxy.client.compat.create.DistantBogeyMeshes::getOrCapture;
                //Track LOD reads the client-synced TrackGraph directly, so it needs Create present.
                //Create's bezier BEs are clamped to the view distance by MixinTrackRenderer so they
                //hand their distant spans to this renderer instead of floating past the LOD.
                var trackRenderer = new me.cortex.voxy.client.compat.create.DistantTrackRenderer();
                NeoForge.EVENT_BUS.register(trackRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.register(trackRenderer);

                //Distant contraption snapshots: freeze bearings/pistons/gantries/mounted contraptions
                //the player walked past and draw them statically beyond the render distance. Tick hook
                //refreshes snapshots in range; the LOD hook draws the frozen ones.
                var contraptionRenderer = new me.cortex.voxy.client.compat.create.DistantContraptionRenderer();
                NeoForge.EVENT_BUS.register(contraptionRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.register(contraptionRenderer);

                //Frozen kinetic moving parts (shafts/cogs past the render distance) drawn per-section
                //in the LOD; the cull mixins queue captures/removals, the tick hook bakes them.
                var kineticRenderer = new me.cortex.voxy.client.compat.create.DistantKineticRenderer();
                NeoForge.EVENT_BUS.register(kineticRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.register(kineticRenderer);

                //Ship-borne kinetics render natively (a ship is one connected drivetrain - copies
                //cannot keep adjacent shafts in sync); the cull exempts them entirely.
            }

            if (ModList.get().isLoaded("littletiles")) {
                var littleTilesRenderer = new me.cortex.voxy.client.compat.littletiles.LittleTilesDistantRenderer();
                NeoForge.EVENT_BUS.register(littleTilesRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.register(littleTilesRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.registerTranslucent(littleTilesRenderer);
            }

            if (ModList.get().isLoaded("domum_ornamentum")) {
                var domumRenderer = new me.cortex.voxy.client.compat.domum.DomumDistantRenderer();
                NeoForge.EVENT_BUS.register(domumRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.register(domumRenderer);
            }

            if (ModList.get().isLoaded("powergrid")) {
                var wireRenderer = new me.cortex.voxy.client.compat.powergrid.PowerGridWireRenderer();
                NeoForge.EVENT_BUS.register(wireRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.register(wireRenderer);
            }

            if (ModList.get().isLoaded("simulated")) {
                var laserRenderer = new me.cortex.voxy.client.compat.simulated.DistantLaserRenderer();
                NeoForge.EVENT_BUS.register(laserRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.registerTranslucent(laserRenderer);
            }

            //Beacon beams derived from the voxel store, so one shows up whether or not its chunk was
            //ever loaded this session. Vanilla, not create - registered unconditionally.
            var beaconRenderer = new me.cortex.voxy.client.core.beacon.DistantBeaconRenderer();
            NeoForge.EVENT_BUS.register(beaconRenderer);
            me.cortex.voxy.client.compat.LodPipelineHooks.register(beaconRenderer);
        }
    }

    private static void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToClient(
                me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriageShapePayload.TYPE,
                me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriageShapePayload.CODEC,
                (payload, ctx) -> {
                    if (FMLLoader.getDist() == Dist.CLIENT) {
                        ctx.enqueueWork(() -> me.cortex.voxy.client.compat.create.DistantTrainManager.handleShape(payload));
                    }
                });
        registrar.playToClient(
                me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.TrainPosesPayload.TYPE,
                me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.TrainPosesPayload.CODEC,
                (payload, ctx) -> {
                    if (FMLLoader.getDist() == Dist.CLIENT) {
                        ctx.enqueueWork(() -> me.cortex.voxy.client.compat.create.DistantTrainManager.handlePoses(payload));
                    }
                });
        registrar.playToClient(
                me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ContraptionPosesPayload.TYPE,
                me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ContraptionPosesPayload.CODEC,
                (payload, ctx) -> {
                    if (FMLLoader.getDist() == Dist.CLIENT && ModList.get().isLoaded("create")) {
                        ctx.enqueueWork(() -> me.cortex.voxy.client.compat.create.DistantContraptionManager
                                .handleRemotePoses(payload));
                    }
                });
    }

    private void registerFarEntityPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("voxy")
                .versioned(Integer.toString(me.cortex.voxy.compat.far.FarEntityProtocol.VERSION))
                .optional();

        registrar.playToServer(
                me.cortex.voxy.compat.far.FarEntityProtocol.HelloPayload.TYPE,
                me.cortex.voxy.compat.far.FarEntityProtocol.HelloPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        this.farEntityService.handleHello((net.minecraft.server.level.ServerPlayer) context.player(), payload.hello())));

        if (FMLLoader.getDist() == Dist.CLIENT) {
            registrar.playToClient(
                    me.cortex.voxy.compat.far.FarEntityProtocol.PlayersPayload.TYPE,
                    me.cortex.voxy.compat.far.FarEntityProtocol.PlayersPayload.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() -> me.cortex.voxy.compat.far.FarEntityClient.handle(payload.batch())));
        } else {
            registrar.playToClient(
                    me.cortex.voxy.compat.far.FarEntityProtocol.PlayersPayload.TYPE,
                    me.cortex.voxy.compat.far.FarEntityProtocol.PlayersPayload.STREAM_CODEC,
                    (payload, context) -> { });
        }
    }
}
