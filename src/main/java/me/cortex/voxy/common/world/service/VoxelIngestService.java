package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.compat.DomumOrnamentumCompat;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    private record IngestSection(int cx, int cy, int cz, WorldEngine world, LevelChunk chunk,
                                 BlockEntity[] domumBlockEntities, LevelChunkSection section,
                                 DataLayer blockLight, DataLayer skyLight,
                                 me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.SectionSnapshot littleTiles){}
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        var task = this.ingestQueue.pop();

        var section = task.section;
        long tIngest = me.cortex.voxy.commonImpl.VoxyProfile.begin();
        try {
            //Inside the try: the queue holds a world ref per task and the finally below releases it, so
            //anything that can throw has to be covered or the world can never be closed again
            DomumOrnamentumCompat.beginSection(
                    task.world.getMapper(), task.world.storage, task.domumBlockEntities,
                    task.section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.beginSection(task.world.getMapper(), task.world.storage, task.chunk, task.section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.beginSection(task.world.getMapper(), task.world.storage, task.chunk, task.section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.beginSection(
                    task.world.storage, task.littleTiles, task.section, task.cx, task.cy, task.cz);
            //Read off the section rather than the chunk's block entities: sections streamed by VSS arrive
            //with no chunk at all, and a beacon is a block whether or not its block entity is here.
            long tBeacon = me.cortex.voxy.commonImpl.VoxyProfile.begin();
            me.cortex.voxy.common.world.other.BeaconScanner.scan(
                    task.world.getBeaconIndex(), section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.VoxyProfile.end("ingest/beaconScan", tBeacon);
            var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

            if (section.hasOnlyAir() && task.blockLight==null && task.skyLight==null) {//If the chunk section has lighting data, propagate it
                //All-air sections with no light data are treated as above-surface sky (vanilla stores no
                //DataLayer there; chunk senders push exactly this shape for the sections they skip). Zero-lit
                //air would black out neighbor-lit surfaces at the higher lod levels.
                WorldUpdater.insertUpdate(task.world, vs.uniformAir(me.cortex.voxy.common.world.other.Mapper.airWithLight(0x0F)));
            } else {
                VoxelizedSection csec = WorldConversionFactory.convert(
                        vs,
                        task.world.getMapper(),
                        section.getStates(),
                        section.getBiomes(),
                        getLightingSupplier(task)
                );
                WorldVoxilizedSectionMipper.mipSection(csec, task.world.getMapper());
                WorldUpdater.insertUpdate(task.world, csec);
            }
        } finally {
            DomumOrnamentumCompat.endSection();
            me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.endSection();
            me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.endSection();
            me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.endSection();
            //The queue holds a ref per task rather than a one-shot markActive stamp, so a large backlog
            //on a laggy system cannot let the idle cleaner close the world out from under its own
            //pending ingests
            task.world.releaseRef();
            me.cortex.voxy.commonImpl.VoxyProfile.end("ingest/section", tIngest);
        }
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = task.skyLight;
        var bla = task.blockLight;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        return supplier;
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        // Snapshot and group once on the caller thread. Each section job receives only its Domum
        // entities, avoiding repeated traversal of the live block-entity map on ingest workers.
        var domumBlockEntities = DomumOrnamentumCompat.captureBlockEntitiesBySection(chunk);
        var littleTiles = me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.capture(chunk);

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        int i = chunk.getMinSection() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.hasOnlyAir();
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            i = chunk.getMinSection() - 1;
            for (var section : chunk.getSections()) {
                i++;
                if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
                engine.acquireRef();
                this.ingestQueue.add(new IngestSection(
                        chunk.getPos().x, i, chunk.getPos().z, engine, chunk,
                        domumBlockEntities.forSection(i), section, null, null,
                        littleTiles == null ? null : littleTiles.section(i)));
                try {
                    this.service.execute();
                } catch (Exception e) {
                    Logger.error("Executing had an error: assume shutting down, aborting",e);
                    break;
                }
            }
        }

        if (!gotLighting) {
            return false;
        }

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);


        i = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);

            var bl = blp.getDataLayerData(pos);
            if (bl != null) {
                bl = bl.copy();
            }

            var sl = slp.getDataLayerData(pos);
            if (sl != null) {
                sl = sl.copy();
            } else {
                //Sections above the sky-light storage range have no DataLayer but are implicitly fully lit.
                //Null-data sections are uniform, so probe one block for the value; dark sections and
                //skylight-less dimensions probe 0 and stay unchanged.
                int uniform = slp.getLightValue(pos.origin());
                if (uniform > 0) {
                    sl = new DataLayer(uniform);
                }
            }

            //If its null for either, assume failure to obtain lighting and ignore section
            //if (blNone && slNone) {
            //    continue;
            //}
            engine.acquireRef();
            this.ingestQueue.add(new IngestSection(
                    chunk.getPos().x, i, chunk.getPos().z, engine, chunk,
                    domumBlockEntities.forSection(i), section, bl, sl,
                    littleTiles == null ? null : littleTiles.section(i)));//TODO: fixme, this is technically not safe todo on the chunk load ingest, we need to copy the section data so it cant be modified while being read
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting",e);
                break;
            }
        }
        return true;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
        //Every queued task still holds a world ref - drain and release so worlds can close
        while (!this.ingestQueue.isEmpty()) {
            var task = this.ingestQueue.pop();
            if (task != null) {
                task.world().releaseRef();
            }
        }
    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        engine.acquireRef();
        BlockEntity[] domumBlockEntities =
                DomumOrnamentumCompat.captureBlockEntities(chunk, section);
        var littleTiles = me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.capture(chunk);
        this.ingestQueue.add(new IngestSection(
                x, y, z, engine, chunk, domumBlockEntities, section, bl, sl,
                littleTiles == null ? null : littleTiles.section(y)));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            //Task stays queued; shutdown's queue drain releases its ref exactly once
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            return false;
        }
    }

    //Sections that arrive without an owning chunk - VSS streams them from the server, so the client has
    //no LevelChunk and no block entities to read. This signature is what VSS 0.2.8 resolves by reflection
    //to install its column consumer; it registers the consumer inside the same try as the lookup, so the
    //lookup failing takes the whole server-fed ingest path with it rather than just this call.
    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        return rawIngest(id, recoverChunk(id, x, z), section, x, y, z, bl, sl);
    }

    //The variant compats read a section's block entities to re-register Domum and copycat materials, and
    //bail with no chunk - so a section arriving without one publishes plain block ids OVER voxels that
    //already carried their dressing, and that write goes to disk. A server-fed section is not required
    //to be a chunk the client lacks: the sender covers a radius that overlaps what is loaded here.
    //
    //The dimension key is checked rather than assuming the client is where the section is for. This pack
    //runs sable sub-levels and generated mirror_* dimensions, so the active level is often not the one an
    //engine belongs to, and a chunk fetched from the wrong level would decorate with the wrong materials.
    //No match, or nothing loaded there, leaves the chunk null and the section undressed - what it was.
    private static LevelChunk recoverChunk(WorldIdentifier id, int chunkX, int chunkZ) {
        if (id == null) {
            return null;
        }
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            return null;
        }
        try {
            //Named only here, so this class never resolves a client type on a dedicated server
            return me.cortex.voxy.client.ClientChunkRecovery.find(id, chunkX, chunkZ);
        } catch (Throwable ignored) {
            return null;
        }
    }

    //The owning chunk has to come along: the variant compats (Domum, Create copycats) read the section's
    //block entities in beginSection to re-register their materials, and with a null chunk they bail, so a
    //re-ingest through here would republish the section stripped of its dressing.
    public static boolean rawIngest(WorldIdentifier id, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, chunk, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (chunk == null) {
            me.cortex.voxy.commonImpl.PerfStats.sectionIngestedChunkless.increment();
        } else {
            me.cortex.voxy.commonImpl.PerfStats.sectionIngestedWithChunk.increment();
        }
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        return engine.instanceIn.getIngestService().rawIngest0(engine, chunk, section, x, y, z, bl, sl);
    }
}
