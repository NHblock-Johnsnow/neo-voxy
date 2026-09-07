package me.cortex.voxy.common.world;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;

import static me.cortex.voxy.common.world.WorldEngine.*;

public class WorldUpdater {
    //Executes an update to the world and automatically updates all the parent mip layers up to level 4 (e.g. where 1 chunk section is 1 block big)

    //NOTE: THIS RUNS ON THE THREAD IT WAS EXECUTED ON, when this method exits, the calling method may assume that VoxelizedSection is no longer needed
    public static void insertUpdate(WorldEngine into, VoxelizedSection section) {//TODO: add a bitset of levels to update and if it should force update

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (section.x+512)>>10;
            section.setPosition(section.x-(sector<<10), section.y+16+(256-32-sector*30), section.z);//Note sector size mult is 30 because the top chunk is replicated (and so is bottom chunk)
        }

        if (!into.isLive) throw new IllegalStateException("World is not live");
        boolean shouldCheckEmptiness = false;
        WorldSection previousSection = null;
        for (int lvl = 0; lvl <= MAX_LOD_LAYER; lvl++) {
            var worldSection = into.acquire(lvl, section.x >> (lvl + 1), section.y >> (lvl + 1), section.z >> (lvl + 1));

            int emptinessStateChange = 0;
            //Propagate the child existence state of the previous iteration to this section
            if (lvl != 0 && shouldCheckEmptiness) {
                emptinessStateChange = worldSection.updateEmptyChildState(previousSection);
                //We kept the previous section acquired, so we need to release it
                previousSection.release();
                previousSection = null;
            }

            long status = insertSectionLvlIntoWorld(into, section, worldSection);
            boolean didStateChange = (status&1)==1;
            int nonAirCount = (int) ((status>>1)&0x1FFF);


            if (lvl == 0) {
                int nonAirCountDelta = section.lvl0NonAirCount-nonAirCount;
                if (nonAirCountDelta != 0) {
                    worldSection.addNonEmptyBlockCount(nonAirCountDelta);
                    emptinessStateChange = worldSection.updateLvl0State() ? 2 : 0;
                }
            }

            if (didStateChange||(emptinessStateChange!=0)) {
                //TODO: somehow foward the neighbors that are facing the updated area, this allows forwarding to the dirty consumer
                // which can decide wether to dispatch mesh rebuilds to the surounding sections
                //Bitmask of neighboring sections
                //Note, this may be zero (this is more likely to occure at higher lod levels) if it doesnt face any neighbors
                int neighbors = 0;
                if (didStateChange) {
                    neighbors |= ((section.y^(section.y-1))>>(lvl+1))==0?0:1<<0;//Down
                    neighbors |= ((section.y^(section.y+1))>>(lvl+1))==0?0:1<<1;//Up
                    neighbors |= ((section.x^(section.x-1))>>(lvl+1))==0?0:1<<2;//-x
                    neighbors |= ((section.x^(section.x+1))>>(lvl+1))==0?0:1<<3;//+x
                    neighbors |= ((section.z^(section.z-1))>>(lvl+1))==0?0:1<<4;//-z
                    neighbors |= ((section.z^(section.z+1))>>(lvl+1))==0?0:1<<5;//+z
                }

                into.markDirty(worldSection, (didStateChange?UPDATE_TYPE_BLOCK_BIT:0)|(emptinessStateChange!=0?UPDATE_TYPE_CHILD_EXISTENCE_BIT:0), neighbors);
            }

            //Need to release the section after using it
            if (didStateChange||(emptinessStateChange==2)) {
                if (emptinessStateChange==2) {
                    //Major state emptiness change, bubble up
                    shouldCheckEmptiness = true;
                    //Dont release the section, it will be released on the next loop
                    previousSection = worldSection;
                } else {
                    //Propagate up without state change
                    shouldCheckEmptiness = false;
                    previousSection = null;
                    worldSection.release();
                }
            } else {
                //Keep walking the remaining mip levels even when nothing changed here: each level compares the
                //fresh mip against storage, so stale parents get detected and rewritten. Unchanged levels write
                //identical data and mark nothing dirty.
                shouldCheckEmptiness = false;
                previousSection = null;
                worldSection.release();
            }
        }

        if (previousSection != null) {
            previousSection.release();
        }
    }


    private static long insertSectionLvlIntoWorld(WorldEngine into, VoxelizedSection section, WorldSection worldSection) {
        final long[] vdat = section.section;
        final int lvl = worldSection.lvl;

        final int msk = (1<<(lvl+1))-1;
        final int bx = (section.x&msk)<<(4-lvl);
        final int by = (section.y&msk)<<(4-lvl);
        final int bz = (section.z&msk)<<(4-lvl);

        int nonAirCount = 0;
        boolean didStateChange = false;
        WorldSection belowWorldSection = null;
        boolean belowDidStateChange = false;


        //TODO: remove the nonAirCountDelta stuff if level != 0

        //A uniform section being written with values that all equal its uniform value changes nothing,
        //so it can stay uniform and skip both the 256KiB materialise and the write loop. This is the
        //common ingest case: whole sections of air above the terrain (the ingest service even has a
        //uniformAir fast path feeding straight into here). Compare first, materialise only if needed.
        {
            long[] existing = worldSection._rawOrNull();
            if (existing == null) {
                long uniform = worldSection.getUniformValue();
                boolean allSame = true;
                if (lvl == 0) {
                    for (int i = 0; i <= 0xFFF; i++) {
                        if (vdat[i] != uniform) { allSame = false; break; }
                    }
                } else {
                    int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);
                    for (int i = baseVIdx; i <= (0xFFF >> (lvl * 3)) + baseVIdx; i++) {
                        if (Mapper.isSurfaceCarrier(vdat[i]) || vdat[i] != uniform) { allSame = false; break; }
                    }
                }
                if (allSame) {
                    long unchangedStatus = 0;//didStateChange = false
                    if (lvl == 0 && !Mapper.isAir(uniform)) {
                        unchangedStatus |= Integer.toUnsignedLong(4096) << 1;
                    }
                    me.cortex.voxy.commonImpl.PerfStats.sectionUniformWriteSkipped.increment();
                    return unchangedStatus;
                }
            }
        }

        {//Do a bunch of funny math
            //Writing differing voxels, so a real array is needed. materialize() fills it with the
            //uniform value first, so the pre-existing contents are preserved exactly and the
            //nonEmptyBlockCount bookkeeping below is unchanged. Must re-read: never reuse a hoisted
            //null, or every write would land in an orphan array and the whole ingest would vanish.
            var secD = worldSection.materialize();
            int baseSec = bx | (bz << 5) | (by << 10);
            if (lvl == 0) {
                final int secMsk = 0b1100|(0xf << 5) | (0xf << 10);
                final int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;

                //TODO rotate the loop parralelization
                // i.e. instead of doing 4 consecutive blocks, which would all be in the same cache line
                // do 4 seperate rows so they are in different cache lines, should allow
                // more instruction pipelining (in theory)
                for (int i = 0; i <= 0xFFF; i+=4) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;

                    long oldId0 = secD[cSecIdx+0]; secD[cSecIdx+0] = vdat[i+0];
                    long oldId1 = secD[cSecIdx+1]; secD[cSecIdx+1] = vdat[i+1];
                    long oldId2 = secD[cSecIdx+2]; secD[cSecIdx+2] = vdat[i+2];
                    long oldId3 = secD[cSecIdx+3]; secD[cSecIdx+3] = vdat[i+3];

                    nonAirCount += Mapper.isNotAirInt(oldId0); didStateChange |= vdat[i+0] != oldId0;
                    nonAirCount += Mapper.isNotAirInt(oldId1); didStateChange |= vdat[i+1] != oldId1;
                    nonAirCount += Mapper.isNotAirInt(oldId2); didStateChange |= vdat[i+2] != oldId2;
                    nonAirCount += Mapper.isNotAirInt(oldId3); didStateChange |= vdat[i+3] != oldId3;
                }
            } else {
                int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);

                int secMsk = 0xF >> lvl;
                secMsk |= (secMsk << 5) | (secMsk << 10);
                int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;
                //TODO: manually unroll and do e.g. 4 iterations per loop
                for (int i = baseVIdx; i <= (0xFFF >> (lvl * 3)) + baseVIdx; i++) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;
                    long newId = vdat[i];
                    if (Mapper.isSurfaceCarrier(newId)) {
                        if (((cSecIdx >> 10) & 31) != 0 && !Mapper.isAir(secD[cSecIdx - (1 << 10)])) {
                            int belowIndex = cSecIdx - (1 << 10);
                            long below = Mapper.applySurfaceCarrier(secD[belowIndex], newId);
                            didStateChange |= below != secD[belowIndex];
                            secD[belowIndex] = below;
                            newId = Mapper.clearSurfaceCarrier(newId);
                        } else if (((cSecIdx >> 10) & 31) == 0) {
                            if (belowWorldSection == null) {
                                belowWorldSection = into.acquire(lvl, worldSection.x, worldSection.y - 1, worldSection.z);
                            }
                            int belowIndex = (31 << 10) | (cSecIdx & 0x3FF);
                            long oldBelow = belowWorldSection.get(belowIndex);
                            if (!Mapper.isAir(oldBelow)) {
                                long below = Mapper.applySurfaceCarrier(oldBelow, newId);
                                if (below != oldBelow) {
                                    belowWorldSection.materialize()[belowIndex] = below;
                                    belowDidStateChange = true;
                                }
                                newId = Mapper.clearSurfaceCarrier(newId);
                            } else {
                                newId = Mapper.restoreSurfaceCarrier(newId);
                            }
                        } else {
                            newId = Mapper.restoreSurfaceCarrier(newId);
                        }
                    }
                    long oldId = secD[cSecIdx];
                    didStateChange |= newId != oldId;
                    secD[cSecIdx] = newId;
                }
            }
        }

        if (belowWorldSection != null) {
            if (belowDidStateChange) {
                into.markDirty(belowWorldSection, UPDATE_TYPE_BLOCK_BIT, 1 << 1);
            }
            belowWorldSection.release();
        }

        long status = 0;
        status |= didStateChange?1:0;
        status |= Integer.toUnsignedLong(nonAirCount)<<1;//13 bits are required because the value can be 4096
        return status;
    }
}
