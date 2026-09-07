package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;


public class RenderDataFactory {
    private static final boolean BUILD_OCCUPANCY_SET = false;

    private static final boolean CHECK_NEIGHBOR_FACE_OCCLUSION = true;
    private static final boolean DISABLE_CULL_SAME_OCCLUDES = false;//TODO: FIX TRANSLUCENTS (e.g. stained glass) breaking on chunk boarders with this set to false (it might be something else????)

    private static final boolean VERIFY_MESHING = VoxyCommon.isVerificationFlagOn("verifyMeshing");

    //TODO: MAKE a render cache that caches each WorldSection directional face generation, cause then can just pull that directly
    // instead of needing to regen the entire thing

    //Ok so the idea for fluid rendering is to make it use a seperate mesher and use a different code path for it
    // since fluid states are explicitly overlays over the base block
    // can do funny stuff like double rendering


    private final WorldEngine world;
    private final ModelFactory modelMan;

    //private final long[] sectionData = new long[32*32*32*2];
    private final long[] sectionData = new long[32*32*32*2];
    private final long[] neighboringFaces = new long[32*32*6];
    //private final int[] neighboringOpaqueMasks = new int[32*6];

    private final int[] opaqueMasks = new int[32*32];
    private final int[] nonOpaqueMasks = new int[32*32];
    private final int[] fluidMasks = new int[32*32];//Used to separately mesh fluids, allowing for fluid + blockstate
    private final short[] biomeIds = new short[32*32*32];


    //TODO: emit directly to memory buffer instead of long arrays

    //Each axis gets a max quad count of 2^16 (65536 quads) since that is the max the basic geometry manager can handle
    private final MemoryBuffer quadBuffer = new MemoryBuffer(8*(8*(1<<16)));//6 faces + dual direction + translucents
    private final long quadBufferPtr = this.quadBuffer.address;
    private final int[] quadCounters = new int[8];
    //Set when a bucket filled up and quads were dropped; reported once per build rather than per quad,
    //since a section that overflows does so thousands of times.
    private boolean bucketOverflowed;


    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    private int quadCount = 0;

    private final OccupancySet occupancy;

    //Wont work for double sided quads
    private final class Mesher extends ScanMesher2D {
        public int auxiliaryPosition = 0;
        public boolean doAuxiliaryFaceOffset = true;
        public int axis = 0;//Y,Z,X

        //Note x, z are in top right
        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            this.emitQuad(x, z, length, width, data, -1);
        }

        private void emitShapedFluidQuad(int x, int z, int payload, long data) {
            this.emitQuad(x, z, 1, 1, data, payload & 0xFF);
        }

        private void emitQuad(int x, int z, int length, int width, long data, int encodedSizeBits) {
            if (VERIFY_MESHING) {
                if (length<1||length>16) {
                    throw new IllegalStateException("length out of bounds: " + length);
                }
                if (width<1||width>16) {
                    throw new IllegalStateException("width out of bounds: " + width);
                }
                if (x<0||x>31) {
                    throw new IllegalStateException("x out of bounds: " + x);
                }
                if (z<0||z>31) {
                    throw new IllegalStateException("z out of bounds: " + z);
                }
                if (x-(length-1)<0 || z-(width-1)<0) {
                    throw new IllegalStateException("dim out of bounds: " + (x-(length-1))+", " + (z-(width-1)));
                }
            }

            RenderDataFactory.this.quadCount++;

            x -= length-1;
            z -= width-1;

            if (this.axis == 2) {
                //Need to swizzle the data if on x axis
                int tmp = x;
                x = z;
                z = tmp;

                tmp = length;
                length = width;
                width = tmp;
            }

            //Lower 26 bits can be auxiliary data since that is where quad position information goes;
            int auxData = (int) (data&((1<<26)-1));
            data &= ~((1L<<26)-1);

            int axisSide = auxData&1;
            int type = (auxData>>1)&3;//Translucent, double side, directional

            if (VERIFY_MESHING) {
                if (type == 3) {
                    throw new IllegalStateException();
                }
            }

            //Shift up if is negative axis
            int auxPos = this.auxiliaryPosition;
            auxPos += 1-(this.doAuxiliaryFaceOffset?axisSide:1);//Shift

            if (VERIFY_MESHING) {
                if (auxPos > 31) {
                    throw new IllegalStateException("OOB face: " + auxPos + ", " + axisSide);
                }
            }

            final int axis = this.axis;
            int face = (axis<<1)|axisSide;

            int encodedPosition = face;
            encodedPosition |= encodedSizeBits >= 0
                    ? encodedSizeBits << 3
                    : ((width - 1) << 7) | ((length - 1) << 3);
            encodedPosition |= x << (axis==2?16:21);
            encodedPosition |= z << (axis==1?16:11);
            int shiftAmount = axis==0?16:(axis==1?11:21);
            //shiftAmount += ;
            encodedPosition |= auxPos << (shiftAmount);

            long quad = data | Integer.toUnsignedLong(encodedPosition);


            int bufferIdx = type+(type==2?face:0);//Translucent, double side, directional
            //Buckets 2..7 take one face direction each, so 32*32*32 is their ceiling and the 2^16 slots
            //are generous. Buckets 0 (translucent) and 1 (double sided) take all six directions, whose
            //ceiling is three times the slots they have - a section packed with two colours of stained
            //glass reaches it exactly. Past that the write lands in the next bucket's region, and the
            //geometry manager packs bucket sizes into 16 bits, so the count wraps to a wrong small
            //number and corrupts its neighbour's draw parameters as well.
            //
            //quadCount is rolled back with the drop: the two counts have to agree, or the last bucket's
            //reported size covers quads that were never written and the GPU draws whatever was in the
            //buffer.
            if (RenderDataFactory.this.quadCounters[bufferIdx] >= (1<<16)) {
                RenderDataFactory.this.quadCount--;
                RenderDataFactory.this.bucketOverflowed = true;
                return;
            }
            long bufferOffset = (RenderDataFactory.this.quadCounters[bufferIdx]++)*8L + bufferIdx*8L*(1<<16);
            MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + bufferOffset, quad);


            //Update AABB bounds
            if (axis == 0) {//Y
                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, auxPos);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, auxPos);

                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
            } else if (axis == 1) {//Z
                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, auxPos);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, auxPos);

                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, z);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, z + width);
            } else {//X
                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, auxPos);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, auxPos);

                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, x);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, x + length);

                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
            }
        }
    }

    private final Mesher blockMesher = new Mesher();
    private final Mesher seondaryblockMesher = new Mesher();//Used for dual non-opaque geometry

    public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
        this(world, modelManager, emitMeshlets, false);
    }
    public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets, boolean generateOccupancy) {
        this.world = world;
        this.modelMan = modelManager;
        if (generateOccupancy && BUILD_OCCUPANCY_SET) {
            this.occupancy = new OccupancySet();
        } else {
            this.occupancy = null;
        }
    }

    private static long getQuadTyping(long metadata) {//2 bits
        return 0b111L&(0b000_000_010_100L>>(ModelQueries._isTranslucent(metadata)*6+ModelQueries._isDoubleSided(metadata)*3));
    }

    private static long packPartialQuadData(int modelId, long state, long metadata) {
        //This uses hardcoded data to shuffle things
        long lightAndBiome =  (state&((0x1FFL<<47)|(0xFFL<<56)))>>>1;
        lightAndBiome &= ~(ModelQueries._notIsBiomeColoured(metadata) * (0x1FFL << 46));//46 not 47 because is already shifted by 1 THIS WASTED 4 HOURS ;-; aaaaaAAAAAA
        lightAndBiome &= ~(ModelQueries._isFullyOpaque(metadata)*(0xFFL << 55));//If its fully opaque it always uses neighbor light?

        long quadData = lightAndBiome;
        quadData |= Integer.toUnsignedLong(modelId)<<26;
        quadData |= getQuadTyping(metadata);//Returns the typing already shifted by 1
        return quadData;
    }

    private final int[] blendBiomeScratch = new int[15 * 15];

    private void applyBiomeBlend(long[] raw, int neighborMsk, boolean neighborsLoaded) {
        int radius = me.cortex.voxy.client.config.VoxyConfig.CONFIG.biomeBlendRadius;
        if (radius <= 0) {
            return;
        }
        radius = Math.min(radius, 7);
        boolean waterOnly = !"water_grass".equals(
                me.cortex.voxy.client.config.VoxyConfig.CONFIG.biomeBlendScope);
        if (waterOnly) {
            boolean anyFluid = false;
            for (int mask : this.fluidMasks) {
                if (mask != 0) {
                    anyFluid = true;
                    break;
                }
            }
            if (!anyFluid) {
                return;
            }
        }

        var palette = this.modelMan.blendPalette();
        var colours = palette.snapshot();
        if (colours.rowBaseByModelId().length == 0) {
            return;
        }
        int window = radius * 2 + 1;
        int sampleCount = window * window;

        for (int i = 0; i < 32 * 32 * 32; i++) {
            long metadata = this.sectionData[i * 2 + 1];
            if (metadata == 0 || !ModelQueries.isBiomeColoured(metadata)) {
                continue;
            }
            if (!ModelQueries.isFluid(metadata)) {
                if (waterOnly || ModelQueries.containsFluid(metadata)) {
                    continue;
                }
            }

            int x = i & 31;
            int z = (i >> 5) & 31;
            int y = i >> 10;
            int centerBiome = (int) ((raw[i] >>> 47) & 0x1FF);
            boolean mixed = false;
            int sampleIndex = 0;

            for (int dz = -radius; dz <= radius; dz++) {
                int nz = z + dz;
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = x + dx;
                    int biome = centerBiome;
                    if (nx >= 0 && nx < 32 && nz >= 0 && nz < 32) {
                        long sample = raw[nx | (nz << 5) | (y << 10)];
                        if (!Mapper.isAir(sample)) {
                            biome = (int) ((sample >>> 47) & 0x1FF);
                        }
                    } else if (neighborsLoaded) {
                        long sample = 0;
                        if (nx == -1 && nz >= 0 && nz < 32 && (neighborMsk & 1) != 0) {
                            sample = this.neighboringFaces[nz | (y << 5)];
                        } else if (nx == 32 && nz >= 0 && nz < 32 && (neighborMsk & 2) != 0) {
                            sample = this.neighboringFaces[(nz | (y << 5)) + 32 * 32];
                        } else if (nz == -1 && nx >= 0 && nx < 32 && (neighborMsk & 16) != 0) {
                            sample = this.neighboringFaces[(nx | (y << 5)) + 32 * 32 * 4];
                        } else if (nz == 32 && nx >= 0 && nx < 32 && (neighborMsk & 32) != 0) {
                            sample = this.neighboringFaces[(nx | (y << 5)) + 32 * 32 * 5];
                        }
                        if (sample != 0 && !Mapper.isAir(sample)) {
                            biome = (int) ((sample >>> 47) & 0x1FF);
                        }
                    }
                    this.blendBiomeScratch[sampleIndex++] = biome;
                    mixed |= biome != centerBiome;
                }
            }
            if (!mixed) {
                continue;
            }

            int rowModel = (int) ((this.sectionData[i * 2] >>> 26) & 0xFFFF);
            int red = 0;
            int green = 0;
            int blue = 0;
            boolean unavailable = false;
            for (int j = 0; j < sampleCount; j++) {
                int colour = colours.colourOf(rowModel, this.blendBiomeScratch[j]);
                if (colour == -1) {
                    unavailable = true;
                    break;
                }
                red += colour & 0xFF;
                green += (colour >>> 8) & 0xFF;
                blue += (colour >>> 16) & 0xFF;
            }
            if (unavailable) {
                continue;
            }

            int blended = (red / sampleCount)
                    | ((green / sampleCount) << 8)
                    | ((blue / sampleCount) << 16);
            int paletteIndex = palette.indexFor(blended);
            if (paletteIndex < 0) {
                continue;
            }
            long quadData = this.sectionData[i * 2];
            quadData &= ~((0x1FFL << 46) | (0xFL << 42));
            quadData |= ((long) (paletteIndex & 0x1FF)) << 46;
            quadData |= ((long) ((paletteIndex >>> 9) & 0xF)) << 42;
            quadData |= 1L << 63;
            this.sectionData[i * 2] = quadData;
        }
    }

    private int prepareSectionData(final long[] rawSectionData) {
        final var sectionData = this.sectionData;
        if (sectionData.length != 32*32*32*2 || rawSectionData.length != 32*32*32) {
            throw new IllegalStateException("Unexpected section buffer size");
        }
        final var rawModelIds = this.modelMan._unsafeRawAccess();
        long opaque = 0;
        long notEmpty = 0;
        long pureFluid = 0;
        long partialFluid = 0;

        int neighborAcquireMskAndFlags = 0;//-+x, -+z, -+y
        int i = 0;
        for (int q = 0; q < 512; q++) {
            for (int j = 0; j < 64; i++, j++) {
                i &= 32*32*32-1;
                long block = rawSectionData[i];//Get the block mapping
                int i2 = (i * 2) & ((32*32*32*2-1)^1);
                this.biomeIds[i] = (short) Mapper.getBiomeId(block);
                if (Mapper.isAir(block)) {//If it is air, just emit lighting
                    sectionData[i2] = (block & (0xFFL << 56)) >>> 1;
                    sectionData[i2 + 1] = 0;
                } else {
                    int modelId = rawModelIds[Mapper.getBlockId(block)];
                    if (modelId == -1) {//Failed, so just return error
                        return Mapper.getBlockId(block) | (1 << 31);
                    }
                    if (modelId == 0) {//modelId == 0, its basicly air so set it as air
                        sectionData[i2] = (block & (0xFFL << 56)) >>> 1;
                        sectionData[i2 + 1] = 0;
                    } else {
                        //TODO: cache the results of this, then link it to `block` do same optimization as SaveLoadSystem3

                        long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);

                        sectionData[i2] = packPartialQuadData(modelId, block, modelMetadata);
                        sectionData[i2 + 1] = modelMetadata;

                        notEmpty |= 1L << j;
                        opaque |= ModelQueries._isFullyOpaque(modelMetadata)<<j;
                        pureFluid |= ModelQueries._isFluid(modelMetadata)<<j;
                        partialFluid |= ModelQueries._containsFluid(modelMetadata)<<j;
                    }
                }
            }
            if (notEmpty != 0) {
                neighborAcquireMskAndFlags |= getNeighborMsk(notEmpty, i);
                neighborAcquireMskAndFlags |= opaque!=0?(1<<6):0;
                this.opaqueMasks[(i >> 5) - 2] = (int) opaque;
                this.opaqueMasks[(i >> 5) - 1] = (int) (opaque>>>32);
                long nonOpaque = (notEmpty^opaque)&~pureFluid;
                notEmpty = 0;
                opaque = 0;
                this.nonOpaqueMasks[(i >> 5) - 2] = (int) nonOpaque;
                this.nonOpaqueMasks[(i >> 5) - 1] = (int) (nonOpaque>>>32);
                long fluid = pureFluid|partialFluid;
                pureFluid = 0;
                partialFluid = 0;
                this.fluidMasks[(i >> 5) - 2] = (int) fluid;
                this.fluidMasks[(i >> 5) - 1] = (int) (fluid>>>32);
            }
        }
        return neighborAcquireMskAndFlags;
    }

    private static int getNeighborMsk(long notEmpty, int i) {
        int packedEmpty = (int) ((notEmpty >>>32)| notEmpty);

        int neighborMsk = 0;
        //-+x
        neighborMsk += packedEmpty&1;//-x
        neighborMsk += (packedEmpty>>>30)&0b10;//+x

        //notEmpty = (notEmpty != 0)?1:0;
        neighborMsk += ((((i - 1) >> 10) == 0) ? 0b100 : 0)*(packedEmpty!=0?1:0);//-y
        neighborMsk += ((((i - 1) >> 10) == 31) ? 0b1000 : 0)*(packedEmpty!=0?1:0);//+y
        neighborMsk += (((((i - 33) >> 5) & 0x1F) == 0) ? 0b10000 : 0)*(((int) notEmpty)!=0?1:0);//-z
        neighborMsk += (((((i - 1) >> 5) & 0x1F) == 31) ? 0b100000 : 0)*((notEmpty >>>32)!=0?1:0);//+z
        return neighborMsk;
    }

    private void clearNeighborFaceSlice(int slice) {
        this.fillNeighborFaceSlice(slice, 0L);
    }

    private void fillNeighborFaceSlice(int slice, long value) {
        Arrays.fill(this.neighboringFaces, slice * 32 * 32, (slice + 1) * 32 * 32, value);
    }

    //A uniform neighbour has the same value on every face voxel, so fill the slice instead of touching
    //the section's array - without this every mesh build would materialise 256KiB for each of its six
    //neighbours, which would make the uniform-section optimisation a net loss.
    private boolean fillSliceIfUniform(WorldSection sec, int slice) {
        if (!sec.isUniform()) {
            return false;
        }
        this.fillNeighborFaceSlice(slice, sec.getUniformValue());
        me.cortex.voxy.commonImpl.PerfStats.neighborFaceUniformFill.increment();
        return true;
    }

    private int acquireNeighborData(WorldSection section, int msk) {
        int unavailableMsk = 0;
        // Performance-first: do not synchronously load missing neighbor sections while building render meshes.
        // If a neighbor is not already in the active cache, treat it as air for this build. This avoids storage/IO
        // stalls during fast flight; when the neighbor is later loaded/updated, normal dirty events can rebuild meshes.
        if ((msk&1)!=0) {//-x
            var sec = this.world.acquireIfExists(section.lvl, section.x - 1, section.y, section.z);
            if (sec == null) {
                unavailableMsk |= 1;
                this.clearNeighborFaceSlice(0);
            } else {
                //Note this is not thread safe! (but eh, fk it)
                if (!this.fillSliceIfUniform(sec, 0)) {
                    var raw = sec.materialize();
                    for (int i = 0; i < 32*32; i++) {
                        this.neighboringFaces[i] = raw[(i<<5)+31];//pull the +x faces from the section
                    }
                }
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
        if ((msk&2)!=0) {//+x
            var sec = this.world.acquireIfExists(section.lvl, section.x + 1, section.y, section.z);
            if (sec == null) {
                unavailableMsk |= 2;
                this.clearNeighborFaceSlice(1);
            } else {
                //Note this is not thread safe! (but eh, fk it)
                if (!this.fillSliceIfUniform(sec, 1)) {
                    var raw = sec.materialize();
                    for (int i = 0; i < 32*32; i++) {
                        this.neighboringFaces[i+32*32] = raw[(i<<5)];//pull the -x faces from the section
                    }
                }
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }

        if ((msk&4)!=0) {//-y
            var sec = this.world.acquireIfExists(section.lvl, section.x, section.y - 1, section.z);
            if (sec == null) {
                unavailableMsk |= 4;
                this.clearNeighborFaceSlice(2);
            } else {
                //Note this is not thread safe! (but eh, fk it)
                if (!this.fillSliceIfUniform(sec, 2)) {
                    var raw = sec.materialize();
                    for (int i = 0; i < 32*32; i++) {
                        this.neighboringFaces[i+32*32*2] = raw[i|(0x1F<<10)];//pull the +y faces from the section
                    }
                }
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
        if ((msk&8)!=0) {//+y
            var sec = this.world.acquireIfExists(section.lvl, section.x, section.y + 1, section.z);
            if (sec == null) {
                unavailableMsk |= 8;
                //Never-ingested space above the surface is open sky (vanilla stores no sky DataLayer there and
                //chunk senders skip all-air sections); zero-lit air here would black out every neighbor-lit top
                //face. Ingested-but-dark sections (caves) still copy through below.
                long skyAir = Mapper.airWithLight(0x0F);
                for (int i = 0; i < 32*32; i++) {
                    this.neighboringFaces[i+32*32*3] = skyAir;
                }
            } else {
                //Note this is not thread safe! (but eh, fk it)
                if (!this.fillSliceIfUniform(sec, 3)) {
                    var raw = sec.materialize();
                    for (int i = 0; i < 32*32; i++) {
                        this.neighboringFaces[i+32*32*3] = raw[i];//pull the -y faces from the section
                    }
                }
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }

        if ((msk&16)!=0) {//-z
            var sec = this.world.acquireIfExists(section.lvl, section.x, section.y, section.z - 1);
            if (sec == null) {
                unavailableMsk |= 16;
                this.clearNeighborFaceSlice(4);
            } else {
                //Note this is not thread safe! (but eh, fk it)
                if (!this.fillSliceIfUniform(sec, 4)) {
                    var raw = sec.materialize();
                    for (int i = 0; i < 32*32; i++) {
                        this.neighboringFaces[i+32*32*4] = raw[Integer.expand(i,0b11111_00000_11111)|(0x1F<<5)];//pull the +z faces from the section
                    }
                }
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
        if ((msk&32)!=0) {//+z
            var sec = this.world.acquireIfExists(section.lvl, section.x, section.y, section.z + 1);
            if (sec == null) {
                unavailableMsk |= 32;
                this.clearNeighborFaceSlice(5);
            } else {
                //Note this is not thread safe! (but eh, fk it)
                if (!this.fillSliceIfUniform(sec, 5)) {
                    var raw = sec.materialize();
                    for (int i = 0; i < 32*32; i++) {
                        this.neighboringFaces[i+32*32*5] = raw[Integer.expand(i,0b11111_00000_11111)];//pull the -z faces from the section
                    }
                }
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
        return unavailableMsk;
    }

    private static final long LM = (0xFFL<<55);

    private int fluidModelId(int voxelIndex) {
        long quad = this.sectionData[voxelIndex * 2];
        long metadata = this.sectionData[voxelIndex * 2 + 1];
        int modelId = (int) ((quad >>> 26) & 0xFFFF);
        return ModelQueries.containsFluid(metadata)
                ? this.modelMan.getFluidClientStateId(modelId)
                : modelId;
    }

    private int fluidKind(int voxelIndex) {
        if (!hasFluid(voxelIndex)) return 0;
        return this.modelMan.getVanillaFluidKind(fluidModelId(voxelIndex));
    }

    private int rawFluidModelId(long raw) {
        if (Mapper.isAir(raw)) return 0;
        int modelId = this.modelMan.getModelId(Mapper.getBlockId(raw));
        long metadata = this.modelMan.getModelMetadataFromClientId(modelId);
        return ModelQueries.containsFluid(metadata)
                ? this.modelMan.getFluidClientStateId(modelId)
                : ModelQueries.isFluid(metadata) ? modelId : 0;
    }

    private int rawFluidKind(long raw) {
        int modelId = rawFluidModelId(raw);
        return modelId == 0 ? 0 : this.modelMan.getVanillaFluidKind(modelId);
    }

    private long rawModelMetadata(long raw) {
        if (Mapper.isAir(raw)) return 0;
        int modelId = this.modelMan.getModelId(Mapper.getBlockId(raw));
        return this.modelMan.getModelMetadataFromClientId(modelId);
    }

    private int rawFluidHeight(long raw) {
        int modelId = rawFluidModelId(raw);
        return modelId == 0 ? 0 : ModelQueries.fluidHeight(
                this.modelMan.getModelMetadataFromClientId(modelId));
    }

    private long horizontalNeighborRaw(int x, int y, int z) {
        if (x == -1 && z >= 0 && z < 32) {
            return this.neighboringFaces[z + y * 32];
        }
        if (x == 32 && z >= 0 && z < 32) {
            return this.neighboringFaces[32 * 32 + z + y * 32];
        }
        if (z == -1 && x >= 0 && x < 32) {
            return this.neighboringFaces[4 * 32 * 32 + x + y * 32];
        }
        if (z == 32 && x >= 0 && x < 32) {
            return this.neighboringFaces[5 * 32 * 32 + x + y * 32];
        }
        return 0;
    }

    private int effectiveRawFluidHeight(long raw, int x, int y, int z, int kind) {
        int height = rawFluidHeight(raw);
        if (kind == 0 || y == 31) return height;
        long above = horizontalNeighborRaw(x, y + 1, z);
        return rawFluidKind(above) == kind ? 9 : height;
    }

    private int fluidDescriptor(int voxelIndex) {
        int modelId = fluidModelId(voxelIndex);
        long metadata = this.modelMan.getModelMetadataFromClientId(modelId);
        return (ModelQueries.isBiomeColoured(metadata) ? 1 << 5 : 0) | ModelQueries.fluidHeight(metadata);
    }

    private int effectiveFluidHeight(int voxelIndex) {
        int height = fluidDescriptor(voxelIndex) & 31;
        int y = voxelIndex >>> 10;
        int kind = fluidKind(voxelIndex);
        if (kind != 0 && y < 31 && fluidKind(voxelIndex + 1024) == kind) {
            return 9;
        }
        if (kind != 0 && y == 31) {
            int x = voxelIndex & 31;
            int z = (voxelIndex >>> 5) & 31;
            if (rawFluidKind(this.neighboringFaces[3 * 32 * 32 + x + z * 32]) == kind) {
                return 9;
            }
        }
        return height;
    }

    private float fluidCornerHeight(int voxelIndex, int cornerX, int cornerZ) {
        int kind = fluidKind(voxelIndex);
        if (kind == 0) return effectiveFluidHeight(voxelIndex);

        int x = voxelIndex & 31;
        int z = (voxelIndex >>> 5) & 31;
        int y = voxelIndex >>> 10;
        float weightedHeight = 0.0f;
        float weight = 0.0f;
        for (int dz = cornerZ - 1; dz <= cornerZ; dz++) {
            for (int dx = cornerX - 1; dx <= cornerX; dx++) {
                int sx = x + dx;
                int sz = z + dz;
                float sampleHeight;
                if (sx >= 0 && sx < 32 && sz >= 0 && sz < 32) {
                    int sample = sx | (sz << 5) | (y << 10);
                    if (fluidKind(sample) == kind) {
                        sampleHeight = effectiveFluidHeight(sample);
                    } else {
                        long metadata = this.sectionData[sample * 2 + 1];
                        sampleHeight = ModelQueries.isFullyOpaque(metadata) ? -1.0f : 0.0f;
                    }
                } else {
                    long raw = horizontalNeighborRaw(sx, y, sz);
                    if (rawFluidKind(raw) == kind) {
                        sampleHeight = effectiveRawFluidHeight(raw, sx, y, sz, kind);
                    } else {
                        sampleHeight = ModelQueries.isFullyOpaque(rawModelMetadata(raw)) ? -1.0f : 0.0f;
                    }
                }
                if (sampleHeight >= 9.0f) return 9.0f;
                if (sampleHeight >= 0.0f) {
                    float sampleWeight = sampleHeight >= 7.2f ? 10.0f : 1.0f;
                    weightedHeight += sampleHeight * sampleWeight;
                    weight += sampleWeight;
                }
            }
        }
        return weight > 0.0f ? weightedHeight / weight : effectiveFluidHeight(voxelIndex);
    }

    private static int encodeFluidCornerHeight(float height) {
        if (height <= 0.01f) return 0;
        if (height >= 8.5f) return 7;
        if (height >= 6.5f) return 6;
        return Math.clamp(Math.round(height) - 1, 1, 5);
    }

    private static int packFluidCorners(float c0, float c1, float c2, float c3) {
        return encodeFluidCornerHeight(c0)
                | (encodeFluidCornerHeight(c1) << 3)
                | (encodeFluidCornerHeight(c2) << 6)
                | (encodeFluidCornerHeight(c3) << 9);
    }

    private long encodeFluidStep(long quad, int lowerHeight, int voxelIndex) {
        if (lowerHeight <= 0) return quad;
        quad &= ~((0xFL << 42) | (0x1FFL << 46) | (1L << 63));
        quad |= ((long) lowerHeight) << 42;
        quad |= ((long) this.biomeIds[voxelIndex] & 0x1FFL) << 46;
        return quad;
    }

    private long encodeFluidShape(long quad, int payload, int voxelIndex) {
        quad &= ~((0xFL << 42) | (0x1FFL << 46) | (1L << 63));
        quad |= ((long) ((payload >>> 8) & 0xF)) << 42;
        quad |= ((long) this.biomeIds[voxelIndex] & 0x1FFL) << 46;
        return quad;
    }

    private static boolean shouldMeshNonOpaqueBlockFace(int face, long quad, long meta, long neighborQuad, long neighborMeta) {
        if (((quad^neighborQuad)&(0xFFFFL<<26))==0 && (DISABLE_CULL_SAME_OCCLUDES || ModelQueries.cullsSame(meta))) return false;//This is a hack, if the neigbor and this are the same, dont mesh the face// TODO: FIXME
        if (!ModelQueries.faceExists(meta, face)) return false;//Dont mesh if no face
        if (ModelQueries.faceCanBeOccluded(meta, face)) //TODO: maybe enable this
          if (ModelQueries.faceOccludes(neighborMeta, face^1)) return false;
        return true;
    }

    private static void meshNonOpaqueFace(int face, long quad, long meta, long neighborQuad, long neighborMeta, Mesher mesher) {
        if (shouldMeshNonOpaqueBlockFace(face, quad, meta, neighborQuad, neighborMeta)) {
            mesher.putNext(applyQuadLight(
                    (long) (face&1) |
                    (quad&~LM) |
                    ((ModelQueries.faceUsesSelfLighting(meta, face)?quad:neighborQuad) & LM),
                    meta));
        } else {
            mesher.skip(1);
        }
    }

    private static long applyQuadLight(long quad, long selfmeta) {
        final long BLMSK = 0xFL<<(55+4);//block light mask
        long bl = quad&BLMSK;
        bl = Math.max(bl, ModelQueries.lightEmission(selfmeta)<<(55+4));
        quad &= ~(BLMSK);
        quad |= bl;
        return quad;
    }

    //Per-nibble max of two quad-position light bytes (sky bits 55-58, block 59-62). Fluid faces
    //light from their neighbor, but above-surface air can be stored with zero sky light; taking the
    //max with the fluid's own light keeps caves dark while fixing zero-lit neighbors.
    private static long maxQuadLight(long a, long b) {
        final long SKYMSK = 0xFL<<55;
        final long BLKMSK = 0xFL<<59;
        return Math.max(a&SKYMSK, b&SKYMSK) | Math.max(a&BLKMSK, b&BLKMSK);
    }

    private void generateYZOpaqueInnerGeometry(int axis) {
        for (int layer = 0; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                int current = this.opaqueMasks[pidx];
                int next = this.opaqueMasks[pidx + skipAmount];

                int msk = current ^ next;
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int faceForwardMsk = msk & current;
                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);
                        int shift = skipAmount * 32 * 2;

                        //Flip data with respect to facing direction
                        int iA = idx * 2 + (facingForward == 1 ? 0 : shift);
                        int iB = idx * 2 + (facingForward == 1 ? shift : 0);

                        long selfModel = this.sectionData[iA];
                        long selfMeta  = this.sectionData[iA+1];
                        long nextModel = this.sectionData[iB];

                        //Check if next culls this face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            long neighbor = this.sectionData[iB + 1];
                            boolean culls = false;
                            culls |= ((selfModel^nextModel)&(0xFFFFL<<26))==0&&ModelQueries.cullsSame(neighbor);
                            culls |= ModelQueries.faceOccludes(neighbor, (axis << 1) | (1 - facingForward));
                            if (culls) {
                                this.blockMesher.skip(1);
                                continue;
                            }
                        }

                        this.blockMesher.putNext(
                            applyQuadLight(
                                ((long) facingForward) |//Facing
                                (selfModel&~LM) |
                                (nextModel&LM),//Apply lighting
                                selfMeta
                            ));
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZOpaqueOuterGeometry(int axis) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        //Hacky generate section side faces (without check neighbor section)
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.opaqueMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];
                        long A = this.sectionData[idx * 2];
                        long selfMeta = this.sectionData[idx * 2 +1];

                        int nib = Mapper.getBlockId(neighborId);
                        if (nib != 0) {//Not air
                            //FIXME need to use selfMeta to check for if it can be culled against this block

                            int cid = this.modelMan.getModelId(nib);
                            long meta = this.modelMan.getModelMetadataFromClientId(cid);
                            if (ModelQueries.isFullyOpaque(meta)) {//Dont mesh this face
                                this.blockMesher.skip(1);
                                continue;
                            }

                            //This very funnily causes issues when not combined with meshing non full opaque geometry
                            //TODO:FIXME, when non opaque geometry is added
                            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                                boolean culls = false;
                                culls |= cid==((A>>26)&0xFFFF)&&ModelQueries.cullsSame(meta);
                                culls |= ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side));
                                if (culls) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }
                        }



                        this.blockMesher.putNext(applyQuadLight(
                                ((side == 0) ? 0L : 1L) |
                                (A&~LM) |
                                ((neighborId & (0xFFL << 56)) >>> 1),
                                selfMeta
                                )
                        );
                    }
                }
                this.blockMesher.endRow();
            }

            this.blockMesher.finish();
        }
        this.blockMesher.doAuxiliaryFaceOffset = true;
    }

    private void generateYZFluidInnerGeometry(int axis) {
        for (int layer = 0; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                //TODO: this needs to take into account opaqueMasks to not mesh any faces with it set
                int current = this.fluidMasks[pidx];
                int next = this.fluidMasks[pidx + skipAmount];

                int msk = (current | this.opaqueMasks[pidx]) ^ (next | this.opaqueMasks[pidx + skipAmount]);
                msk &= current|next;
                int differentFluidMsk = 0;
                int higherCurrentMsk = 0;
                int shared = current & next;
                while (shared != 0) {
                    int index = Integer.numberOfTrailingZeros(shared);
                    shared &= ~Integer.lowestOneBit(shared);
                    int aIndex = index + pidx * 32;
                    int bIndex = aIndex + skipAmount * 32;
                    int aFluid = fluidDescriptor(aIndex);
                    int bFluid = fluidDescriptor(bIndex);
                    if (axis != 0 && aFluid != bFluid) {
                        differentFluidMsk |= 1 << index;
                        int aHeight = aFluid & 31;
                        int bHeight = bFluid & 31;
                        if (aHeight >= bHeight) higherCurrentMsk |= 1 << index;
                    }
                }
                msk |= differentFluidMsk;
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int faceForwardMsk = (msk & current & ~differentFluidMsk) | higherCurrentMsk;
                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);

                        int a = idx*2;
                        int b = (idx + skipAmount * 32) * 2;

                        //Flip data with respect to facing direction
                        int ai = facingForward == 1 ? a : b;
                        int bi = facingForward == 1 ? b : a;

                        //TODO: check if must cull against next entries face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {//TODO:SELF OCCLUSION
                            if (ModelQueries.faceOccludes(this.sectionData[bi + 1], (axis << 1) | (1 - facingForward))) {
                                this.blockMesher.skip(1);
                                continue;
                            }
                        }

                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];
                        //If it isnt a fluid but contains one,
                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            //Update quad typing info
                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        if (axis == 0 && facingForward == 1 && this.modelMan.getVanillaFluidKind(
                                (int) ((A >>> 26) & 0xFFFF)) != 0) {
                            this.blockMesher.skip(1);
                            continue;
                        }

                        if ((differentFluidMsk & (1 << index)) != 0) {
                            int selfFluid = fluidDescriptor(ai / 2);
                            int neighborFluid = fluidDescriptor(bi / 2);
                            if ((selfFluid & (1 << 5)) == (neighborFluid & (1 << 5))) {
                                A = encodeFluidStep(A, neighborFluid & 31, ai / 2);
                            }
                        }

                        long lighter = this.sectionData[bi];
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        this.blockMesher.putNext(applyQuadLight(
                                ((long) facingForward) |//Facing
                                (A&~LM) |
                                maxQuadLight(A, lighter),//Apply lighting
                                Am)
                        );
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZFluidOuterGeometry(int axis, int unavailableNeighborMsk) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        //Hacky generate section side faces (without check neighbor section)
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            boolean suppressUnknownEdge = axis == 1 && (unavailableNeighborMsk & (16 << side)) != 0;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = suppressUnknownEdge ? 0 : this.fluidMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        long A = this.sectionData[idx * 2];
                        long Am = this.sectionData[idx * 2 + 1];

                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            //We need to update the typing info for A
                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        if (((axis == 0 && side == 1) || axis == 1)
                                && this.modelMan.getVanillaFluidKind((int) ((A >>> 26) & 0xFFFF)) != 0) {
                            this.blockMesher.skip(1);
                            continue;
                        }

                        //Check and test if can cull W.R.T neighbor
                        if (Mapper.getBlockId(neighborId) != 0) {//Not air
                            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                            long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                            if (ModelQueries.containsFluid(meta)) {
                                modelId = this.modelMan.getFluidClientStateId(modelId);
                            }
                            if (ModelQueries.cullsSame(Am)) {
                                if (modelId == ((A>>26)&0xFFFF)) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }

                            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }
                        }

                        this.blockMesher.putNext(applyQuadLight(
                                (side == 0 ? 0L : 1L) |
                                (A&~LM) |
                                maxQuadLight(A, (neighborId&(0xFFL<<56))>>>1),
                                Am)
                        );
                    }
                }
                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
        this.blockMesher.doAuxiliaryFaceOffset = true;
    }

    private void generateYZNonOpaqueInnerGeometry(int axis) {
        //Note: think is ok to just reuse.. blockMesher
        this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
        this.blockMesher.axis = axis;
        this.seondaryblockMesher.axis = axis;
        for (int layer = 1; layer < 31; layer++) {//(should be 1->31, then have outer face mesher)
            this.blockMesher.auxiliaryPosition = layer;
            this.seondaryblockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {//TODO: need to do the faces that border sections
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int skipAmount = axis==0?32*32:32;

                int msk = this.nonOpaqueMasks[pidx];

                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                this.seondaryblockMesher.skip(cSkip);
                cSkip = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) {
                        this.blockMesher.skip(delta);
                        this.seondaryblockMesher.skip(delta);
                    }
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);

                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[idx * 2+1];

                        meshNonOpaqueFace((axis<<1)|0, A, B, this.sectionData[(idx-skipAmount)*2], this.sectionData[(idx-skipAmount)*2+1], this.seondaryblockMesher);//-
                        meshNonOpaqueFace((axis<<1)|1, A, B, this.sectionData[(idx+skipAmount)*2], this.sectionData[(idx+skipAmount)*2+1], this.blockMesher);//+
                    }
                }
                this.blockMesher.endRow();
                this.seondaryblockMesher.endRow();
            }
            this.blockMesher.finish();
            this.seondaryblockMesher.finish();
        }
    }

    private void generateYZNonOpaqueOuterGeometry(int axis) {
        //Note: think is ok to just reuse.. blockMesher
        this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
        this.blockMesher.axis = axis;
        this.seondaryblockMesher.axis = axis;
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            int skipAmount = (axis==0?32*32:32) * (1-(side*2));
            this.blockMesher.auxiliaryPosition = layer;
            this.seondaryblockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.nonOpaqueMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                this.seondaryblockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) {
                        this.blockMesher.skip(delta);
                        this.seondaryblockMesher.skip(delta);
                    }
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        long A = this.sectionData[idx * 2];
                        long Am = this.sectionData[idx * 2 + 1];

                        boolean fail = false;
                        //Check and test if can cull W.R.T neighbor
                        if (Mapper.getBlockId(neighborId) != 0) {//Not air
                            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));


                            if (ModelQueries.cullsSame(Am) && modelId == ((A>>26)&0xFFFF)) {//TODO: FIXME, this technically isnt correct as need to check self occulsion, thinks?
                                //TODO: check self occlsuion in the if statment
                                fail = true;
                            } else {
                                long meta = this.modelMan.getModelMetadataFromClientId(modelId);

                                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                                    fail = true;
                                }
                            }
                        }

                        long nA = this.sectionData[(idx+skipAmount) * 2];
                        long nB = this.sectionData[(idx+skipAmount) * 2 + 1];
                        boolean failB = false;
                        //TODO: check self occlusion

                        if (ModelQueries.cullsSame(nB) && (nA&(0xFFFFL<<26)) == (A&(0xFFFFL<<26))) {//TODO: FIXME, this technically isnt correct as need to check self occulsion, thinks?
                            //TODO: check self occlsuion in the if statment
                            failB = true;
                        } else {
                            if (ModelQueries.faceOccludes(nB, (axis << 1) | (side))) {
                                failB = true;
                            }
                        }


                        //TODO: LIGHTING
                        if (ModelQueries.faceExists(Am, (axis<<1)|1) && ((side==1&&!fail) || (side==0&&!failB))) {
                            this.blockMesher.putNext(applyQuadLight(
                                    (long) (false ? 0L : 1L) |
                                    A |
                                    0,//((ModelQueries.faceUsesSelfLighting(B, (axis<<1)|1)?A:) & (0xFFL << 55))//TODO:THIS
                                    Am)
                            );
                        } else {
                            this.blockMesher.skip(1);
                        }

                        if (ModelQueries.faceExists(Am, (axis<<1)|0) && ((side==0&&!fail) || (side==1&&!failB))) {
                            this.seondaryblockMesher.putNext(applyQuadLight(
                                    (long) (true ? 0L : 1L) |
                                    A |
                                    0,//(((0xFFL) & 0xFF) << 55)//TODO:THIS
                                    Am)
                            );
                        } else {
                            this.seondaryblockMesher.skip(1);
                        }
                    }
                }
                this.blockMesher.endRow();
                this.seondaryblockMesher.endRow();
            }
            this.blockMesher.finish();
            this.seondaryblockMesher.finish();
        }
    }

    private void generateYZFaces() {
        for (int axis = 0; axis < 2; axis++) {//Y then Z
            this.blockMesher.axis = axis;

            this.generateYZOpaqueInnerGeometry(axis);
            this.generateYZOpaqueOuterGeometry(axis);

            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                this.generateYZNonOpaqueInnerGeometry(axis);
                this.generateYZNonOpaqueOuterGeometry(axis);
            }
        }
    }


    private final Mesher[] xAxisMeshers = new Mesher[32];
    private final Mesher[] secondaryXAxisMeshers = new Mesher[32];
    {
        for (int i = 0; i < 32; i++) {
            var mesher = new Mesher();
            mesher.auxiliaryPosition = i;
            mesher.axis = 2;//X axis
            this.xAxisMeshers[i] = mesher;
        }
        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            for (int i = 0; i < 32; i++) {
                var mesher = new Mesher();
                mesher.auxiliaryPosition = i;
                mesher.axis = 2;//X axis
                mesher.doAuxiliaryFaceOffset = false;
                this.secondaryXAxisMeshers[i] = mesher;
            }
        }
    }

    private static final long X_I_MSK = 0x4210842108421L;

    private void generateXOpaqueInnerGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int lMsk = this.opaqueMasks[y*32+z];
                msk = (lMsk^(lMsk>>>1));
                //TODO: fixme? doesnt this generate extra geometry??
                msk &= -1>>>1;//Remove top bit as we dont actually know/have the data for that slice

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {//Hackfix for incremental count overflow issue
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        //TODO: fixme! check this is correct or if should be 30
                        this.xAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                /*
                {//Dont need this as can just increment everything then -1 in mask
                    //Compute and increment skips for indexes
                    long imsk = Integer.toUnsignedLong(~msk);// we only want to increment where there isnt a face
                    sumA += Long.expand(imsk, X_I_MSK);
                    sumB += Long.expand(imsk>>11, X_I_MSK);
                    sumC += Long.expand(imsk>>22, X_I_MSK);
                }*/

                int faceForwardMsk = msk&lMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;//Compute the skip count
                    {//TODO: Branch-less
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);
                        //TODO: swap this out for something not getting the next entry

                        //Flip data with respect to facing direction
                        int iA = idx * 2 + (facingForward == 1 ? 0 : 2);
                        int iB = idx * 2 + (facingForward == 1 ? 2 : 0);

                        //Check if next culls this face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            //TODO: check self occlsuion
                            if (ModelQueries.faceOccludes(this.sectionData[iB + 1], (2 << 1) | (1 - facingForward))) {
                                mesher.skip(1);
                                continue;
                            }
                        }

                        long selfModel = this.sectionData[iA];
                        long selfMeta  = this.sectionData[iA+1];
                        long nextModel = this.sectionData[iB];

                        //Example thing thats just wrong but as example
                        mesher.putNext(applyQuadLight(
                                ((long) facingForward) |//Facing
                                (selfModel&~LM) |
                                (nextModel&LM),//TODO FIX THIS (self lighting)
                                selfMeta
                                )
                        );
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterOpaqueGeometry() {
        //Generate the side faces, hackily, using 0 and 31 mesher

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.opaqueMasks[i];
                if ((msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        } else if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 0))) {
                            //TODO check self occlsion
                            oki = false;
                        }
                    }
                    if (oki) {
                        ma.skip(skipA); skipA = 0;
                        long A = this.sectionData[(i<<5) * 2];
                        long Am = this.sectionData[(i<<5) * 2+1];
                        ma.putNext(applyQuadLight(
                                0L |
                                (A&~LM) |
                                ((neighborId&(0xFFL<<56))>>>1),
                                Am
                                )
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        } else if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 1))) {
                            //TODO check self occlsion
                            oki = false;
                        }
                    }
                    if (oki) {
                        mb.skip(skipB); skipB = 0;
                        long A = this.sectionData[(i*32+31) * 2];
                        long Am = this.sectionData[(i*32+31) * 2+1];
                        mb.putNext(applyQuadLight(
                                1L |
                                (A&~LM) |
                                ((neighborId&(0xFFL<<56))>>>1),
                                Am
                                )
                        );
                    } else {skipB++;}
                } else {skipB++;}
            }
            ma.skip(skipA);
            mb.skip(skipB);
        }

        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = true;
        mb.doAuxiliaryFaceOffset = true;
    }

    private void generateXInnerFluidGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int oMsk = this.opaqueMasks[y*32+z];
                int fMsk = this.fluidMasks[y*32+z];
                int lMsk = oMsk|fMsk;
                msk = (lMsk^(lMsk>>>1));
                //TODO: fixme? doesnt this generate extra geometry??
                msk &= -1>>>1;//Remove top bit as we dont actually know/have the data for that slice

                //Dont generate geometry for opaque faces
                msk &= fMsk|(fMsk>>1);

                int differentFluidMsk = 0;
                int higherCurrentMsk = 0;
                int shared = fMsk & (fMsk >>> 1);
                while (shared != 0) {
                    int index = Integer.numberOfTrailingZeros(shared);
                    shared &= ~Integer.lowestOneBit(shared);
                    int aIndex = index + z * 32 + y * 32 * 32;
                    int aFluid = fluidDescriptor(aIndex);
                    int bFluid = fluidDescriptor(aIndex + 1);
                    if (aFluid != bFluid) {
                        differentFluidMsk |= 1 << index;
                        int aHeight = aFluid & 31;
                        int bHeight = bFluid & 31;
                        if (aHeight >= bHeight) higherCurrentMsk |= 1 << index;
                    }
                }
                msk |= differentFluidMsk;

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {//Hackfix for incremental count overflow issue
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        //TODO: fixme! check this is correct or if should be 30
                        this.xAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int faceForwardMsk = (msk & lMsk & ~differentFluidMsk) | higherCurrentMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;//Compute the skip count
                    {//TODO: Branch-less
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        //The facingForward thing is to get next entry automajicly
                        int ai = (idx+(1-facingForward))*2;
                        int bi = (idx+facingForward)*2;

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(this.sectionData[bi + 1], (2 << 1) | (1 - facingForward))) {
                                //TODO check self occlsion
                                mesher.skip(1);
                                continue;
                            }
                        }

                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];

                        //TODO: check if must cull against next entries face
                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            //Update quad typing info to be the fluid type
                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        if ((differentFluidMsk & (1 << index)) != 0) {
                            int selfFluid = fluidDescriptor(ai / 2);
                            int neighborFluid = fluidDescriptor(bi / 2);
                            if ((selfFluid & (1 << 5)) == (neighborFluid & (1 << 5))) {
                                A = encodeFluidStep(A, neighborFluid & 31, ai / 2);
                            }
                        }

                        long lighter = this.sectionData[bi];
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        //Example thing thats just wrong but as example
                        mesher.putNext(applyQuadLight(
                                ((long) facingForward) |//Facing
                                (A&~LM) |
                                maxQuadLight(A, lighter),//Lighting
                                Am
                                )
                        );
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterFluidGeometry(int unavailableNeighborMsk) {
        //Generate the side faces, hackily, using 0 and 31 mesher

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.fluidMasks[i];
                if ((unavailableNeighborMsk & 1) == 0 && (msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    boolean oki = true;

                    int sidx = (i<<5) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                        //Update quad typing info to be the fluid type
                        A &= ~0b110L; A |= getQuadTyping(Am);
                    }
                    if (this.modelMan.getVanillaFluidKind((int) ((A >>> 26) & 0xFFFF)) != 0) {
                        oki = false;
                    }


                    if (Mapper.getBlockId(neighborId) != 0) {//Not air

                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        //Check neighbor face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(meta, (2 << 1) | (1-0))) {
                                oki = false;
                            }
                        }

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                oki = false;
                            }
                        }
                    }

                    if (oki) {
                        ma.skip(skipA); skipA = 0;

                        //TODO: LIGHTING
                        long lightData = maxQuadLight(A, (neighborId&(0xFFL<<56))>>>1);
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        ma.putNext(applyQuadLight(
                                0L |
                                (A&~LM) |
                                lightData,
                                Am
                                )
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((unavailableNeighborMsk & 2) == 0 && (msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    boolean oki = true;


                    int sidx = (i*32+31) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    //TODO: check if must cull against next entries face
                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                    }
                    if (this.modelMan.getVanillaFluidKind((int) ((A >>> 26) & 0xFFFF)) != 0) {
                        oki = false;
                    }



                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        //Check neighbor face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(meta, (2 << 1) | (1-1))) {
                                oki = false;
                            }
                        }

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                oki = false;
                            }
                        }
                    }

                    if (oki) {
                        mb.skip(skipB); skipB = 0;

                        //TODO: LIGHTING
                        long lightData = maxQuadLight(A, (neighborId&(0xFFL<<56))>>>1);
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        mb.putNext(applyQuadLight(
                                1L |
                                (A&~LM) |
                                lightData,
                                Am
                                )
                        );
                    } else {skipB++;}
                } else {skipB++;}
            }
            ma.skip(skipA);
            mb.skip(skipB);
        }

        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = true;
        mb.doAuxiliaryFaceOffset = true;
    }

    private boolean hasFluid(int voxelIndex) {
        long metadata = this.sectionData[voxelIndex * 2 + 1];
        return ModelQueries.isFluid(metadata) || ModelQueries.containsFluid(metadata);
    }

    private long makeDirectFluidFace(int fluidIndex, int neighborIndex, int facingForward, int lowerHeight, int shapePayload) {
        long quad = this.sectionData[fluidIndex * 2];
        long metadata = this.sectionData[fluidIndex * 2 + 1];
        if (ModelQueries.containsFluid(metadata)) {
            int modelId = (int) ((quad >>> 26) & 0xFFFF);
            int fluidId = this.modelMan.getFluidClientStateId(modelId);
            quad = (quad & ~(0xFFFFL << 26)) | (Integer.toUnsignedLong(fluidId) << 26);
            metadata = this.modelMan.getModelMetadataFromClientId(fluidId);
            quad = (quad & ~0b110L) | getQuadTyping(metadata);
        }

        long neighborQuad = this.sectionData[neighborIndex * 2];
        quad = shapePayload >= 0
                ? encodeFluidShape(quad, shapePayload, fluidIndex)
                : encodeFluidStep(quad, lowerHeight, fluidIndex);
        return applyQuadLight(
                facingForward | (quad & ~LM) | maxQuadLight(quad, neighborQuad),
                metadata);
    }

    private long makeDirectFluidTop(int fluidIndex, long neighborRaw, int shapePayload) {
        long quad = this.sectionData[fluidIndex * 2];
        long metadata = this.sectionData[fluidIndex * 2 + 1];
        if (ModelQueries.containsFluid(metadata)) {
            int fluidId = this.modelMan.getFluidClientStateId((int) ((quad >>> 26) & 0xFFFF));
            quad = (quad & ~(0xFFFFL << 26)) | (Integer.toUnsignedLong(fluidId) << 26);
            metadata = this.modelMan.getModelMetadataFromClientId(fluidId);
            quad = (quad & ~0b110L) | getQuadTyping(metadata);
        }
        quad = encodeFluidShape(quad, shapePayload, fluidIndex);
        long neighborLight = (neighborRaw & (0xFFL << 56)) >>> 1;
        return applyQuadLight(1L | (quad & ~LM) | maxQuadLight(quad, neighborLight), metadata);
    }

    private long makeDirectOuterFluidFace(int fluidIndex, long neighborRaw, int facingForward, int shapePayload) {
        long quad = this.sectionData[fluidIndex * 2];
        long metadata = this.sectionData[fluidIndex * 2 + 1];
        if (ModelQueries.containsFluid(metadata)) {
            int fluidId = this.modelMan.getFluidClientStateId((int) ((quad >>> 26) & 0xFFFF));
            quad = (quad & ~(0xFFFFL << 26)) | (Integer.toUnsignedLong(fluidId) << 26);
            metadata = this.modelMan.getModelMetadataFromClientId(fluidId);
            quad = (quad & ~0b110L) | getQuadTyping(metadata);
        }
        quad = encodeFluidShape(quad, shapePayload, fluidIndex);
        long neighborLight = (neighborRaw & (0xFFL << 56)) >>> 1;
        return applyQuadLight(facingForward | (quad & ~LM) | maxQuadLight(quad, neighborLight), metadata);
    }

    private int fluidSidePayload(int fluidIndex, int neighborIndex, int axis, int facingForward) {
        int kind = fluidKind(fluidIndex);
        if (kind == 0) return -1;

        float top0;
        float top1;
        float bottom0 = 0.0f;
        float bottom1 = 0.0f;
        if (axis == 2) {
            int edge = facingForward == 1 ? 1 : 0;
            top0 = fluidCornerHeight(fluidIndex, edge, 0);
            top1 = fluidCornerHeight(fluidIndex, edge, 1);
            if (fluidKind(neighborIndex) == kind) {
                int neighborEdge = 1 - edge;
                bottom0 = fluidCornerHeight(neighborIndex, neighborEdge, 0);
                bottom1 = fluidCornerHeight(neighborIndex, neighborEdge, 1);
            }
            return packFluidCorners(bottom0, bottom1, top0, top1);
        }

        int edge = facingForward == 1 ? 1 : 0;
        top0 = fluidCornerHeight(fluidIndex, 0, edge);
        top1 = fluidCornerHeight(fluidIndex, 1, edge);
        if (fluidKind(neighborIndex) == kind) {
            int neighborEdge = 1 - edge;
            bottom0 = fluidCornerHeight(neighborIndex, 0, neighborEdge);
            bottom1 = fluidCornerHeight(neighborIndex, 1, neighborEdge);
        }
        return packFluidCorners(bottom0, top0, bottom1, top1);
    }

    private int outerFluidSidePayload(int fluidIndex, int axis, int facingForward) {
        if (axis == 2) {
            int edge = facingForward == 1 ? 1 : 0;
            return packFluidCorners(0.0f, 0.0f,
                    fluidCornerHeight(fluidIndex, edge, 0),
                    fluidCornerHeight(fluidIndex, edge, 1));
        }
        int edge = facingForward == 1 ? 1 : 0;
        return packFluidCorners(0.0f,
                fluidCornerHeight(fluidIndex, 0, edge),
                0.0f,
                fluidCornerHeight(fluidIndex, 1, edge));
    }

    private void emitDirectOuterFluidBoundary(int fluidIndex, long neighborRaw, int axis,
                                               int plane, int firstCoord, int secondCoord,
                                               int facingForward) {
        int kind = fluidKind(fluidIndex);
        if (kind == 0 || rawFluidKind(neighborRaw) == kind) return;
        if (CHECK_NEIGHBOR_FACE_OCCLUSION
                && ModelQueries.faceOccludes(rawModelMetadata(neighborRaw),
                (axis << 1) | (1 - facingForward))) {
            return;
        }

        int payload = outerFluidSidePayload(fluidIndex, axis, facingForward);
        long quad = makeDirectOuterFluidFace(fluidIndex, neighborRaw, facingForward, payload);
        this.blockMesher.axis = axis;
        this.blockMesher.auxiliaryPosition = plane;
        this.blockMesher.doAuxiliaryFaceOffset = true;
        this.blockMesher.emitShapedFluidQuad(firstCoord, secondCoord, payload, quad);
    }

    private void emitDirectFluidBoundary(int firstIndex, int secondIndex, int axis, int plane, int firstCoord, int secondCoord) {
        boolean firstFluid = hasFluid(firstIndex);
        boolean secondFluid = hasFluid(secondIndex);
        if (!firstFluid && !secondFluid) return;

        int fluidIndex;
        int neighborIndex;
        int facingForward;
        int lowerHeight = 0;
        int shapePayload;

        if (firstFluid && secondFluid) {
            int firstDescriptor = fluidDescriptor(firstIndex);
            int secondDescriptor = fluidDescriptor(secondIndex);
            int firstKind = fluidKind(firstIndex);
            int secondKind = fluidKind(secondIndex);
            int firstHeight = firstKind != 0 ? effectiveFluidHeight(firstIndex) : firstDescriptor & 31;
            int secondHeight = secondKind != 0 ? effectiveFluidHeight(secondIndex) : secondDescriptor & 31;
            if (firstKind != 0 && firstKind == secondKind) return;
            if (firstKind == 0 && firstDescriptor == secondDescriptor) return;

            if ((firstKind != 0 && firstKind == secondKind)
                    || (firstKind == 0 && secondKind == 0
                    && (firstDescriptor & (1 << 5)) == (secondDescriptor & (1 << 5)))) {
                if (firstHeight == secondHeight) return;
                boolean firstHigher = firstHeight > secondHeight;
                fluidIndex = firstHigher ? firstIndex : secondIndex;
                neighborIndex = firstHigher ? secondIndex : firstIndex;
                facingForward = firstHigher ? 1 : 0;
                lowerHeight = Math.min(firstHeight, secondHeight);
            } else {
                fluidIndex = firstIndex;
                neighborIndex = secondIndex;
                facingForward = 1;
            }
        } else {
            boolean firstIsFluid = firstFluid;
            fluidIndex = firstIsFluid ? firstIndex : secondIndex;
            neighborIndex = firstIsFluid ? secondIndex : firstIndex;
            facingForward = firstIsFluid ? 1 : 0;

            long neighborMetadata = this.sectionData[neighborIndex * 2 + 1];
            if (CHECK_NEIGHBOR_FACE_OCCLUSION
                    && ModelQueries.faceOccludes(neighborMetadata, (axis << 1) | (1 - facingForward))) {
                return;
            }
        }

        this.blockMesher.axis = axis;
        this.blockMesher.auxiliaryPosition = plane;
        this.blockMesher.doAuxiliaryFaceOffset = true;
        shapePayload = fluidSidePayload(fluidIndex, neighborIndex, axis, facingForward);
        long quad = makeDirectFluidFace(fluidIndex, neighborIndex, facingForward, lowerHeight, shapePayload);
        if (shapePayload >= 0) {
            this.blockMesher.emitShapedFluidQuad(firstCoord, secondCoord, shapePayload, quad);
        } else {
            this.blockMesher.emitQuad(firstCoord, secondCoord, 1, 1, quad);
        }
    }

    private void generateDirectHorizontalFluidGeometry() {
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int index = x | (z << 5) | (y << 10);
                    if (x < 31) {
                        emitDirectFluidBoundary(index, index + 1, 2, x, z, y);
                    }
                    if (z < 31) {
                        emitDirectFluidBoundary(index, index + 32, 1, z, x, y);
                    }
                }
            }
        }
    }

    private void generateDirectOuterHorizontalFluidGeometry(int unavailableNeighborMsk) {
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                int sliceIndex = z + y * 32;
                if ((unavailableNeighborMsk & 1) == 0) {
                    int index = (z << 5) | (y << 10);
                    if (fluidKind(index) != 0) {
                        emitDirectOuterFluidBoundary(index, this.neighboringFaces[sliceIndex],
                                2, -1, z, y, 0);
                    }
                }
                if ((unavailableNeighborMsk & 2) == 0) {
                    int index = 31 | (z << 5) | (y << 10);
                    if (fluidKind(index) != 0) {
                        emitDirectOuterFluidBoundary(index, this.neighboringFaces[32 * 32 + sliceIndex],
                                2, 31, z, y, 1);
                    }
                }
            }
            for (int x = 0; x < 32; x++) {
                int sliceIndex = x + y * 32;
                if ((unavailableNeighborMsk & 16) == 0) {
                    int index = x | (y << 10);
                    if (fluidKind(index) != 0) {
                        emitDirectOuterFluidBoundary(index, this.neighboringFaces[4 * 32 * 32 + sliceIndex],
                                1, -1, x, y, 0);
                    }
                }
                if ((unavailableNeighborMsk & 32) == 0) {
                    int index = x | (31 << 5) | (y << 10);
                    if (fluidKind(index) != 0) {
                        emitDirectOuterFluidBoundary(index, this.neighboringFaces[5 * 32 * 32 + sliceIndex],
                                1, 31, x, y, 1);
                    }
                }
            }
        }
    }

    private void generateDirectFluidTops() {
        this.blockMesher.axis = 0;
        this.blockMesher.doAuxiliaryFaceOffset = true;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int index = x | (z << 5) | (y << 10);
                    int kind = fluidKind(index);
                    if (kind == 0) continue;

                    int above = y < 31 ? index + 1024 : -1;
                    long aboveRaw = y == 31
                            ? this.neighboringFaces[3 * 32 * 32 + x + z * 32]
                            : 0;
                    int aboveKind = above >= 0 ? fluidKind(above) : rawFluidKind(aboveRaw);
                    if (aboveKind == kind) continue;

                    long aboveMetadata = above >= 0
                            ? this.sectionData[above * 2 + 1]
                            : rawModelMetadata(aboveRaw);
                    if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(aboveMetadata, 0)) continue;

                    int payload = packFluidCorners(
                            fluidCornerHeight(index, 0, 0),
                            fluidCornerHeight(index, 0, 1),
                            fluidCornerHeight(index, 1, 0),
                            fluidCornerHeight(index, 1, 1));
                    long quad = above >= 0
                            ? makeDirectFluidFace(index, above, 1, 0, payload)
                            : makeDirectFluidTop(index, aboveRaw, payload);
                    this.blockMesher.auxiliaryPosition = y;
                    this.blockMesher.emitShapedFluidQuad(x, z, payload, quad);
                }
            }
        }
    }

    private void generateXNonOpaqueInnerGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                msk = this.nonOpaqueMasks[y*32+z]&(~0x80000001);//Dont mesh the outer layer

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {//Hackfix for incremental count overflow issue
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);

                        this.xAxisMeshers[index].skip(31);
                        this.secondaryXAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);


                    int skipCount;//Compute the skip count
                    {//TODO: Branch-less
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    var mesherA = this.xAxisMeshers[index];
                    var mesherB = this.secondaryXAxisMeshers[index];
                    if (skipCount != 0) {
                        mesherA.skip(skipCount);
                        mesherB.skip(skipCount);
                    }

                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        long A = this.sectionData[idx*2];
                        long Am = this.sectionData[idx*2+1];

                        //Check and generate the mesh for both + and - faces
                        meshNonOpaqueFace(2<<1, A, Am, this.sectionData[(idx-1)*2], this.sectionData[(idx-1)*2+1], mesherB);//-
                        meshNonOpaqueFace((2<<1)|1, A, Am, this.sectionData[(idx+1)*2], this.sectionData[(idx+1)*2+1], mesherA);//+
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                        this.secondaryXAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }



    private static void dualMeshNonOpaqueOuterX(int side, long quad, long meta, int neighborAId, int neighborLight, long neighborAMeta, long neighborBQuad, long neighborBMeta, Mesher ma, Mesher mb) {
        //side == 0 if is on 0 side and 1 if on 31 side

        //TODO: Check (neighborAId!=0) && works oki
        if ((neighborAId==0 && ModelQueries.faceExists(meta, ((2<<1)|0)^side))||(neighborAId!=0&&shouldMeshNonOpaqueBlockFace(((2<<1)|0)^side, quad, meta, ((long)neighborAId)<<26, neighborAMeta))) {
            ma.putNext(applyQuadLight(
                    ((long)side)|
                    (quad&~LM) |
                    (ModelQueries.faceUsesSelfLighting(meta, ((2<<1)|0)^side)?quad:(((long)neighborLight)<<55)),
                    meta
                    )
            );
        } else {
            ma.skip(1);
        }

        if (shouldMeshNonOpaqueBlockFace(((2<<1)|1)^side, quad, meta, neighborBQuad, neighborBMeta)) {
            mb.putNext(applyQuadLight(
                    ((long)(side^1))|
                    (quad&~LM) |
                    ((ModelQueries.faceUsesSelfLighting(meta, ((2<<1)|1)^side)?quad:neighborBQuad)&(0xFFL<<55)),
                    meta
                    )
            );
        } else {
            mb.skip(1);
        }
    }

    private void generateXNonOpaqueOuterGeometry() {
        var npx = this.xAxisMeshers[0]; npx.finish();
        var nnx = this.secondaryXAxisMeshers[0]; nnx.finish();
        var ppx = this.xAxisMeshers[31]; ppx.finish();
        var pnx = this.secondaryXAxisMeshers[31]; pnx.finish();

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.nonOpaqueMasks[i];
                if ((msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    //TODO also check self occlusion

                    int sidx = (i<<5) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    int modelId = 0;
                    long nM = 0;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        nM = this.modelMan.getModelMetadataFromClientId(modelId);
                    }

                    nnx.skip(skipA);
                    npx.skip(skipA); skipA = 0;

                    dualMeshNonOpaqueOuterX(0, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx+2], this.sectionData[sidx+3], nnx, npx);
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    //TODO also check self occlusion


                    int sidx = (i*32+31) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    int modelId = 0;
                    long nM = 0;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        nM = this.modelMan.getModelMetadataFromClientId(modelId);
                    }

                    pnx.skip(skipB);
                    ppx.skip(skipB); skipB = 0;

                    dualMeshNonOpaqueOuterX(1, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx-2], this.sectionData[sidx-1], ppx, pnx);
                } else {skipB++;}
            }
            nnx.skip(skipA);
            npx.skip(skipA);
            pnx.skip(skipB);
            ppx.skip(skipB);
        }
    }

    private void generateXFaces() {
        this.generateXOpaqueInnerGeometry();
        this.generateXOuterOpaqueGeometry();

        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }

        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            this.generateXNonOpaqueInnerGeometry();
            this.generateXNonOpaqueOuterGeometry();

            for (var mesher : this.xAxisMeshers) {
                mesher.finish();
            }
            for (var mesher : this.secondaryXAxisMeshers) {
                mesher.finish();
            }
        }
    }

    private void generateFluidFaces(int unavailableNeighborMsk) {
        // The translucent bucket is order-sensitive. Submit side walls first and
        // horizontal surfaces last so water tops do not hide shoreline geometry.
        this.generateDirectHorizontalFluidGeometry();

        this.blockMesher.axis = 1;
        this.generateYZFluidOuterGeometry(1, unavailableNeighborMsk);

        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }
        this.generateXOuterFluidGeometry(unavailableNeighborMsk);
        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }
        this.generateDirectOuterHorizontalFluidGeometry(unavailableNeighborMsk);

        this.blockMesher.axis = 0;
        this.generateYZFluidInnerGeometry(0);
        this.generateYZFluidOuterGeometry(0, unavailableNeighborMsk);
        this.generateDirectFluidTops();
    }

    private final int occupancyBarrier(int index) {
        int occ = 0;
        int msk = this.opaqueMasks[index];
        //x
        occ |= msk^(msk>>1);
        occ |= msk^(msk<<1);
        //y
        occ |= index<32*31?msk^this.opaqueMasks[index+32]:0;
        occ |= 31<index   ?msk^this.opaqueMasks[index-32]:0;
        //z
        occ |= (index&31)<31?msk^this.opaqueMasks[index+1]:0;
        occ |= 0< (index&31)?msk^this.opaqueMasks[index-1]:0;
        return occ;
    }

    //Build the occupancy set (used for AO) from the set of fully opaque blocks (atm, this can change in the future if needed to a special occupancy bitset)
    private final void buildOccupancy() {
        //We basicly want to record all the points where we go from air to solid or solid to air (this is to just get better compression)
        for (int i = 0; i < 32*32; i++) {
            int occ = this.occupancyBarrier(i);
            //We now have our occlusion mask, fill in our occupancy set
            for (;occ!=0;occ&=~Integer.lowestOneBit(occ)) {
                this.occupancy.set(i*32+Integer.numberOfTrailingZeros(occ));
            }
        }
    }

    private final void buildOccupancy16() {
        //We basicly want to record all the points where we go from air to solid or solid to air (this is to just get better compression)
        for (int i = 0; i < 16*16; i++) {
            int x = (i&15)*2;
            int y = (i>>4)*2;
            int A = this.occupancyBarrier(y*32+x); A = (A|(A>>16))&0xFFFF;
            int B = this.occupancyBarrier(y*32+x+1); B = (B|(B>>16))&0xFFFF;
            int C = this.occupancyBarrier((y+1)*32+x); C = (C|(C>>16))&0xFFFF;
            int D = this.occupancyBarrier((y+1)*32+x+1); D = (D|(D>>16))&0xFFFF;
            int occ = A|B|C|D;

            //Shink to 16 bit
            //We now have our occlusion mask, fill in our occupancy set
            for (;occ!=0;occ&=~Integer.lowestOneBit(occ)) {
                this.occupancy.set(i*16+Integer.numberOfTrailingZeros(occ));
            }
        }
    }

    //section is already acquired and gets released by the parent
    public BuiltSection generateMesh(WorldSection section) {
        //A uniform air section can only ever mesh to nothing: faces are emitted for its own solid
        //voxels and it has none (neighbour data only culls faces, it never creates them). Answering
        //that directly avoids materialising 256KiB and scanning all 32768 voxels to reach quadCount 0 -
        //and keeps the section uniform, so the neighbours that read its faces take the uniform path too.
        if (section.isUniform() && Mapper.isAir(section.getUniformValue())) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
        }
        //TODO: FIXME: because of the exceptions that are thrown when aquiring modelId
        // this can result in the state of all block meshes and well _everything_ from being incorrect
        //THE EXCEPTION THAT THIS THROWS CAUSES MAJOR ISSUES

        //Copy section data to end of array so that can mutate array while reading safely
        //section.copyDataTo(this.sectionData, 32*32*32);

        //We must reset _everything_ that could have changed as we dont exactly know the state due to how the model id exception
        // throwing system works
        if (this.bucketOverflowed) {
            me.cortex.voxy.commonImpl.PerfStats.quadBucketOverflow.increment();
            this.bucketOverflowed = false;
        }
        this.quadCount = 0;

        {//Reset all the block meshes
            this.blockMesher.reset();
            this.blockMesher.doAuxiliaryFaceOffset = true;
            this.seondaryblockMesher.reset();
            this.seondaryblockMesher.doAuxiliaryFaceOffset = true;
            for (var mesher : this.xAxisMeshers) {
                mesher.reset();
                mesher.doAuxiliaryFaceOffset = true;
            }
            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                for (var mesher : this.secondaryXAxisMeshers) {
                    mesher.reset();
                    mesher.doAuxiliaryFaceOffset = false;
                }
            }
        }
        if (this.occupancy != null) {
            this.occupancy.reset();
        }

        this.minX = Integer.MAX_VALUE;
        this.minY = Integer.MAX_VALUE;
        this.minZ = Integer.MAX_VALUE;
        this.maxX = Integer.MIN_VALUE;
        this.maxY = Integer.MIN_VALUE;
        this.maxZ = Integer.MIN_VALUE;

        Arrays.fill(this.quadCounters,0);
        Arrays.fill(this.opaqueMasks, 0);
        Arrays.fill(this.nonOpaqueMasks, 0);
        Arrays.fill(this.fluidMasks, 0);

        //Prepare everything
        long[] rawSection = section.materialize();
        int neighborMskAndFlags = this.prepareSectionData(rawSection);
        if ((neighborMskAndFlags&(1<<31))!=0) {//We failed to get everything so throw exception
            throw new IdNotYetComputedException(neighborMskAndFlags&((1<<20)-1), true);
        }
        int neighborMsk = neighborMskAndFlags&0b11_11_11;
        int flags = neighborMskAndFlags>>>6;
        int unavailableNeighborMsk = CHECK_NEIGHBOR_FACE_OCCLUSION
                ? this.acquireNeighborData(section, neighborMsk)
                : 0;

        try {
            this.applyBiomeBlend(rawSection, neighborMsk, CHECK_NEIGHBOR_FACE_OCCLUSION);
            this.generateYZFaces();
            this.generateXFaces();
            this.generateFluidFaces(unavailableNeighborMsk);
        } catch (IdNotYetComputedException e) {
            e.auxBitMsk = neighborMsk;
            e.auxData = this.neighboringFaces;
            throw e;
        }

        //We only care if we have quads
        if (this.occupancy != null && section.lvl == 0 /*only generate occupancy for lowest lod level*/ && this.quadCount != 0 && (flags&1) != 0) {
            this.buildOccupancy();
        }

        //TODO:NOTE! when doing face culling of translucent blocks,
        // if the connecting type of the translucent block is the same AND the face is full, discard it
        // this stops e.g. multiple layers of glass (and ocean) from having 3000 layers of quads etc
        if (this.quadCount == 0) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
        }

        if (this.quadCount >= 1<<16) {
            Logger.warn("Large quad count for section " + WorldEngine.pprintPos(section.key) + " is " + this.quadCount);
        }

        if (this.minX<0 || this.minY<0 || this.minZ<0 || 32<this.maxX || 32<this.maxY || 32<this.maxZ) {
            throw new IllegalStateException();
        }

        int[] offsets = new int[8];
        var buff = new MemoryBuffer(this.quadCount * 8L);
        long ptr = buff.address;
        int coff = 0;
        for (int buffer = 0; buffer < 8; buffer++) {// translucent, double sided quads, 6 faces
            offsets[buffer] = coff;
            int size = this.quadCounters[buffer];
            UnsafeUtil.memcpy(this.quadBufferPtr + (buffer*(8*(1<<16))), ptr + coff*8L, (size* 8L));
            coff += size;
        }



        int aabb = 0;
        aabb |= this.minX;
        aabb |= this.minY<<5;
        aabb |= this.minZ<<10;
        //Feel like a clown for missing the Math.max
        aabb |= Math.max(0,this.maxX-this.minX-1)<<15;
        aabb |= Math.max(0,this.maxY-this.minY-1)<<20;
        aabb |= Math.max(0,this.maxZ-this.minZ-1)<<25;

        //if (this.maxX<=this.minX||this.maxY<=this.minY||this.maxZ<=this.minZ) {
        //    throw new IllegalStateException("AABB bounds are not valid");
        //}

        MemoryBuffer occupancy = null;
        if (this.occupancy != null && !this.occupancy.isEmpty()) {
            occupancy = new MemoryBuffer(this.occupancy.writeSize());
            this.occupancy.write(occupancy.address, false);
        }

        return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets, occupancy);
    }

    public void free() {
        this.quadBuffer.free();
    }
}
