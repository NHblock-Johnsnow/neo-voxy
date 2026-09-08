package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryManager;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.ExpandingObjectAllocationList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

public class NodeManager {
   private static final boolean VERIFY_NODE_MANAGER_OPERATIONS = true;
   public static final int NULL_GEOMETRY_ID = -1;
   public static final int EMPTY_GEOMETRY_ID = -2;
   public static final int NULL_REQUEST_ID = 524287;
   public static final int SENTINEL_EMPTY_CHILD_PTR = 16777214;
   public static final int NODE_ID_MSK = 16777215;
   private static final int NODE_TYPE_MSK = -1073741824;
   private static final int NODE_TYPE_LEAF = 0;
   private static final int NODE_TYPE_INNER = 1073741824;
   private static final int NODE_TYPE_REQUEST = Integer.MIN_VALUE;
   private static final int REQUEST_TYPE_SINGLE = 0;
   private static final int REQUEST_TYPE_CHILD = 536870912;
   private static final int REQUEST_TYPE_MSK = 536870912;
   private final ExpandingObjectAllocationList<SingleNodeRequest> singleRequests = new ExpandingObjectAllocationList<>(SingleNodeRequest[]::new, 524287);
   private final ExpandingObjectAllocationList<NodeChildRequest> childRequests = new ExpandingObjectAllocationList<>(NodeChildRequest[]::new, 524287);
   private final IntOpenHashSet nodeUpdates = new IntOpenHashSet();
   private final IGeometryManager geometryManager;
   private final ISectionWatcher watcher;
   private final Long2IntOpenHashMap activeSectionMap = new Long2IntOpenHashMap();
   private final NodeStore nodeData;
   public final int maxNodeCount;
   private final IntOpenHashSet topLevelNodeIds = new IntOpenHashSet();
   private final LongOpenHashSet topLevelNodes = new LongOpenHashSet();
   private int activeNodeRequestCount;
   private IntConsumer topLevelNodeIdAddedCallback;
   private IntConsumer topLevelNodeIdRemovedCallback;
   private NodeManager.ICleaner cleanerInterface;
   private int _nodeAlreadyInFlightDontSpam = 0;

   public void setClear(NodeManager.ICleaner callback) {
      this.cleanerInterface = callback;
   }

   private void clearAllocId(int id) {
      if (this.cleanerInterface != null) {
         this.cleanerInterface.alloc(id);
      }
   }

   private void clearMoveId(int from, int to) {
      if (this.cleanerInterface != null) {
         this.cleanerInterface.move(from, to);
      }
   }

   private void clearFreeId(int id) {
      if (this.cleanerInterface != null) {
         this.cleanerInterface.free(id);
      }
   }

   public void setTLNCallbacks(IntConsumer onAdd, IntConsumer onRemove) {
      this.topLevelNodeIdAddedCallback = onAdd;
      this.topLevelNodeIdRemovedCallback = onRemove;
   }

   public NodeManager(int maxNodeCount, IGeometryManager geometryManager, ISectionWatcher watcher) {
      if ((maxNodeCount & maxNodeCount - 1) != 0) {
         throw new IllegalArgumentException("Max node count must be a power of 2");
      } else if (maxNodeCount > 16777216) {
         throw new IllegalArgumentException("Max node count cannot exceed 2^24");
      } else {
         this.activeSectionMap.defaultReturnValue(-1);
         this.watcher = watcher;
         this.maxNodeCount = maxNodeCount;
         this.nodeData = new NodeStore(maxNodeCount);
         this.geometryManager = geometryManager;
      }
   }

   private static void assertPosValid(long pos) {
      int lvl = WorldEngine.getLevel(pos);
      int x = WorldEngine.getX(pos);
      int y = WorldEngine.getY(pos);
      int z = WorldEngine.getZ(pos);
      if (WorldEngine.getWorldSectionId(lvl, x, y, z) != pos) {
         throw new IllegalStateException("Reconstructed pos not same as original");
      } else {
         x <<= lvl;
         y <<= lvl;
         z <<= lvl;
         long p2 = WorldEngine.getWorldSectionId(0, x, y, z);
         if (WorldEngine.getLevel(p2) != 0 || WorldEngine.getX(p2) != x || WorldEngine.getY(p2) != y || WorldEngine.getZ(p2) != z) {
            throw new IllegalStateException("Position not valid at all levels: " + pos + "-" + WorldEngine.pprintPos(pos) + ":" + WorldEngine.pprintPos(p2));
         }
      }
   }

   public void insertTopLevelNode(long pos) {
      assertPosValid(pos);
      if (this.activeSectionMap.containsKey(pos)) {
         Logger.error("Tried inserting top level pos " + WorldEngine.pprintPos(pos) + " but it was in active map, discarding!");
      } else {
         SingleNodeRequest request = new SingleNodeRequest(pos);
         int id = this.singleRequests.put(request);
         this.watcher.watch(pos, 3);
         this.activeSectionMap.put(pos, id | -2147483648 | 0);
         this.topLevelNodes.add(pos);
      }
   }

   public void removeTopLevelNode(long pos) {
      if (!this.topLevelNodes.remove(pos)) {
         throw new IllegalStateException("Position not in top level map: " + WorldEngine.pprintPos(pos));
      } else {
         int nodeId = this.activeSectionMap.get(pos);
         if (nodeId == -1) {
            throw new IllegalStateException("Tried removing top level pos " + WorldEngine.pprintPos(pos) + " but it was not in active map, discarding!");
         } else {
            if ((nodeId & -1073741824) != Integer.MIN_VALUE) {
               int id = nodeId & 16777215;
               if (!this.topLevelNodeIds.remove(id)) {
                  throw new IllegalStateException("Node id was not in top level node ids: " + nodeId + " pos: " + WorldEngine.pprintPos(pos));
               }

               if (this.topLevelNodeIdRemovedCallback != null) {
                  this.topLevelNodeIdRemovedCallback.accept(id);
               }
            }

            this.recurseRemoveNode(pos);
         }
      }
   }

   IntOpenHashSet getTopLevelNodeIds() {
      return this.topLevelNodeIds;
   }

   public void processGeometryResult(BuiltSection sectionResult) {
      long pos = sectionResult.position;
      int nodeId = this.activeSectionMap.get(pos);
      if (nodeId == -1) {
         sectionResult.free();
      } else {
         if ((nodeId & -1073741824) == Integer.MIN_VALUE) {
            if ((nodeId & 536870912) == 0) {
               SingleNodeRequest request = this.singleRequests.get(nodeId & 16777215);
               request.setMesh(this.uploadReplaceSection(request.getMesh(), sectionResult));
               if (!request.hasChildExistenceSet()) {
                  request.setChildExistence(sectionResult.childExistence);
               }

               if (request.isSatisfied()) {
                  this.singleRequests.release(nodeId & 16777215);
                  this.finishRequest(request);
               }
            } else {
               if ((nodeId & 536870912) != 536870912) {
                  throw new IllegalStateException();
               }

               NodeChildRequest requestx = this.childRequests.get(nodeId & 16777215);
               int childId = getChildIdx(pos);
               requestx.setChildMesh(childId, this.uploadReplaceSection(requestx.getChildMesh(childId), sectionResult));
               if (!requestx.hasChildChildExistence(childId)) {
                  requestx.setChildChildExistence(childId, sectionResult.childExistence);
               }

               if (requestx.isSatisfied()) {
                  this.finishRequest(nodeId & 16777215, requestx);
               }
            }
         } else {
            if ((nodeId & -1073741824) != 1073741824 && (nodeId & -1073741824) != 0) {
               throw new IllegalStateException();
            }

            nodeId &= 16777215;
            if ((this.watcher.get(pos) & 1) == 0) {
               if (this.nodeData.isNodeGeometryInFlight(nodeId)) {
                  throw new IllegalStateException();
               }

               Logger.warn("Recieved geometry update but not watching it, discarding");
               sectionResult.free();
               return;
            }

            this.nodeData.unmarkNodeGeometryInFlight(nodeId);
            if (this.updateNodeGeometry(nodeId, sectionResult) != 0) {
               this.invalidateNode(nodeId);
            }
         }
      }
   }

   private void removeGeometryCached(long pos, int id) {
      this.geometryManager.removeSection(id);
   }

   private int uploadReplaceSection(int meshId, BuiltSection section) {
      if (section.isEmpty()) {
         if (meshId != -1 && meshId != -2) {
            this.geometryManager.removeSection(meshId);
         }

         section.free();
         return -2;
      } else {
         return meshId != -1 && meshId != -2 ? this.geometryManager.uploadReplaceSection(meshId, section) : this.geometryManager.uploadSection(section);
      }
   }

   private int updateNodeGeometry(int node, BuiltSection geometry) {
      int previousGeometry = this.nodeData.getNodeGeometry(node);
      int newGeometry = -2;
      if (previousGeometry != -2 && previousGeometry != -1) {
         if (!geometry.isEmpty()) {
            newGeometry = this.geometryManager.uploadReplaceSection(previousGeometry, geometry);
         } else {
            this.geometryManager.removeSection(previousGeometry);
         }
      } else if (!geometry.isEmpty()) {
         newGeometry = this.geometryManager.uploadSection(geometry);
      }

      if (previousGeometry != newGeometry) {
         this.nodeData.setNodeGeometry(node, newGeometry);
      }

      if (previousGeometry == newGeometry) {
         return 0;
      } else {
         return previousGeometry != -2 && previousGeometry != -1 ? 2 : 1;
      }
   }

   public void processChildChange(long pos, byte childExistence) {
      int nodeId = this.activeSectionMap.get(pos);
      if (nodeId == -1) {
         Logger.warn("Got child change for pos " + WorldEngine.pprintPos(pos) + " but it was not in active map, ignoring!");
      } else {
         if ((nodeId & -1073741824) == Integer.MIN_VALUE) {
            if ((nodeId & 536870912) == 0) {
               SingleNodeRequest request = this.singleRequests.get(nodeId & 16777215);
               request.setChildExistence(childExistence);
               if (request.isSatisfied()) {
                  this.singleRequests.release(nodeId & 16777215);
                  this.finishRequest(request);
               }
            } else {
               if ((nodeId & 536870912) != 536870912) {
                  throw new IllegalStateException();
               }

               NodeChildRequest request = this.childRequests.get(nodeId & 16777215);
               request.setChildChildExistence(getChildIdx(pos), childExistence);
               if (request.isSatisfied()) {
                  this.finishRequest(nodeId & 16777215, request);
               }
            }
         } else if ((nodeId & -1073741824) == 1073741824) {
            this.updateChildSectionsInner(pos, nodeId & 16777215, childExistence);
         } else if ((nodeId & -1073741824) == 0) {
            if (this.nodeData.isNodeRequestInFlight(nodeId & 16777215)) {
               int requestId = this.nodeData.getNodeRequest(nodeId);
               NodeChildRequest request = this.childRequests.get(requestId);
               if (request.getPosition() != pos) {
                  throw new IllegalStateException(
                     "Request is not at pos, got " + WorldEngine.pprintPos(request.getPosition()) + " expected " + WorldEngine.pprintPos(pos)
                  );
               }

               byte oldMsk = request.getMsk();
               byte change = (byte)(oldMsk ^ childExistence);
               byte rem = (byte)(change & oldMsk);

               for (int i = 0; i < 8; i++) {
                  if ((rem & 1 << i) != 0) {
                     long cPos = makeChildPos(pos, i);
                     int meshId = request.removeAndUnRequire(i);
                     if (meshId != -1 && meshId != -2) {
                        this.removeGeometryCached(cPos, meshId);
                     }

                     if (this.activeSectionMap.remove(cPos) == -1) {
                        throw new IllegalStateException("Child pos was in a request but not in active section map");
                     }

                     if (!this.watcher.unwatch(cPos, 3)) {
                        throw new IllegalStateException("Child pos was not being watched");
                     }
                  }
               }

               rem = (byte)(change & childExistence);

               for (int ix = 0; ix < 8; ix++) {
                  if ((rem & 1 << ix) != 0) {
                     request.addChildRequirement(ix);
                     long cPosx = makeChildPos(pos, ix);
                     if (this.activeSectionMap.put(cPosx, requestId | -2147483648 | 536870912) != -1) {
                        throw new IllegalStateException("Child pos was already in active section tracker but was part of a request");
                     }

                     if (!this.watcher.watch(cPosx, 3)) {
                        throw new IllegalStateException("Child pos update router issue");
                     }
                  }
               }

               if (request.isSatisfied()) {
                  this.finishRequest(requestId, request);
               }
            }

            this.nodeData.setNodeChildExistence(nodeId & 16777215, childExistence);
            this.invalidateNode(nodeId & 16777215);
         }
      }
   }

   private void updateChildSectionsInner(long pos, int nodeId, byte childExistence) {
      if (childExistence == 0) {
         Logger.warn("Inner node child existence is changing to 0, this is mild bad");
      }

      byte existence = this.nodeData.getNodeChildExistence(nodeId);
      byte add = (byte)((existence ^ childExistence) & childExistence);
      if (add != 0) {
         if (!this.nodeData.isNodeRequestInFlight(nodeId)) {
            NodeChildRequest request = new NodeChildRequest(pos);
            int requestId = this.childRequests.put(request);
            this.nodeData.markRequestInFlight(nodeId);
            this.nodeData.setNodeRequest(nodeId, requestId);
            this.activeNodeRequestCount++;
         }

         int requestId = this.nodeData.getNodeRequest(nodeId);
         NodeChildRequest request = this.childRequests.get(requestId);
         if (request.getPosition() != pos) {
            throw new IllegalStateException(
               "Request is not at pos: got " + WorldEngine.pprintPos(pos) + " expected: " + WorldEngine.pprintPos(request.getPosition())
            );
         }

         for (int i = 0; i < 8; i++) {
            if ((add & 1 << i) != 0) {
               request.addChildRequirement(i);
               long cPos = makeChildPos(pos, i);
               if (this.activeSectionMap.put(cPos, requestId | -2147483648 | 536870912) != -1) {
                  throw new IllegalStateException("Child pos was already in active section tracker but was part of a request");
               }

               if (!this.watcher.watch(cPos, 3)) {
                  throw new IllegalStateException("Child pos update router issue");
               }
            }
         }
      }

      this.nodeData.setNodeChildExistence(nodeId, childExistence);
      int rem = (existence ^ childExistence) & existence & 0xFF;
      if (rem != 0) {
         if (this.nodeData.isNodeRequestInFlight(nodeId)) {
            int requestId = this.nodeData.getNodeRequest(nodeId);
            NodeChildRequest request = this.childRequests.get(requestId);
            if (request.getPosition() != pos) {
               throw new IllegalStateException("Request is not at pos");
            }

            int reqRem = Byte.toUnsignedInt(request.getMsk()) & rem;
            if (reqRem != 0) {
               for (int ix = 0; ix < 8; ix++) {
                  if ((reqRem & 1 << ix) != 0) {
                     long cPosx = makeChildPos(pos, ix);
                     int meshId = request.removeAndUnRequire(ix);
                     if (meshId != -1 && meshId != -2) {
                        this.removeGeometryCached(cPosx, meshId);
                     }

                     int cnid = this.activeSectionMap.remove(cPosx);
                     if (cnid == -1 || (cnid & -1073741824) != Integer.MIN_VALUE) {
                        throw new IllegalStateException("Child pos was in a request but not in active section map");
                     }

                     if (!this.watcher.unwatch(cPosx, 3)) {
                        throw new IllegalStateException("Child pos was not being watched");
                     }
                  }
               }
            }

            rem ^= reqRem;
         }

         if (rem != 0) {
            int oldPtr = this.nodeData.getChildPtr(nodeId);
            int oldCount = this.nodeData.getChildPtrCount(nodeId);
            if (oldPtr == -1) {
               throw new IllegalStateException();
            }

            int oldExistence = 0;

            for (int ixx = 0; ixx < oldCount; ixx++) {
               if (!this.nodeData.nodeExists(ixx + oldPtr)) {
                  throw new IllegalStateException();
               }

               oldExistence |= 1 << getChildIdx(this.nodeData.nodePosition(ixx + oldPtr));
            }

            if ((rem & oldExistence) != rem) {
               throw new IllegalStateException();
            }

            int remaining = rem ^ oldExistence;
            if (remaining == 0) {
               if (childExistence != 0 && !this.nodeData.isNodeRequestInFlight(nodeId)) {
                  throw new IllegalStateException();
               }

               this.nodeData.setAllChildrenAreLeaf(nodeId, false);
               this.nodeData.setChildPtr(nodeId, 16777214);
               this.nodeData.setChildPtrCount(nodeId, 8);

               for (int ixx = 0; ixx < 8; ixx++) {
                  if ((rem & 1 << ixx) != 0) {
                     long cPosxx = makeChildPos(pos, ixx);
                     this.recurseRemoveNode(cPosxx);
                  }
               }
            } else {
               int newCnt = Integer.bitCount(remaining);
               int newPtr = this.nodeData.allocate(newCnt);
               int prevChildId = oldPtr - 1;
               int newChildId = newPtr - 1;
               boolean allChildNodesLeaf = true;

               for (int ixxx = 0; ixxx < 8; ixxx++) {
                  if ((oldExistence & 1 << ixxx) != 0) {
                     prevChildId++;
                     if ((rem & 1 << ixxx) != 0) {
                        long cPosxx = makeChildPos(pos, ixxx);
                        this.recurseRemoveNode(cPosxx);
                     } else {
                        newChildId++;
                        long cPosxx = this.nodeData.nodePosition(prevChildId);
                        if (cPosxx != makeChildPos(pos, ixxx)) {
                           throw new IllegalStateException();
                        }

                        this.nodeData.copyNode(prevChildId, newChildId);
                        this.clearAllocId(newChildId);
                        this.clearMoveId(prevChildId, newChildId);
                        this.clearFreeId(prevChildId);
                        int prevNodeId = this.activeSectionMap.get(cPosxx);
                        if ((prevNodeId & -1073741824) == Integer.MIN_VALUE) {
                           throw new IllegalStateException();
                        }

                        if ((prevNodeId & 16777215) != prevChildId) {
                           throw new IllegalStateException("State inconsistency");
                        }

                        allChildNodesLeaf &= (prevNodeId & -1073741824) == 0;
                        this.activeSectionMap.put(cPosxx, prevNodeId & -1073741824 | newChildId);
                        this.nodeData.free(prevChildId);
                        this.invalidateNode(prevChildId);
                        this.invalidateNode(newChildId);
                     }
                  }
               }

               this.nodeData.setAllChildrenAreLeaf(nodeId, allChildNodesLeaf);
               this.nodeData.setChildPtr(nodeId, newPtr);
               this.nodeData.setChildPtrCount(nodeId, newCnt);

               for (int ixxxx = 0; ixxxx < oldCount; ixxxx++) {
                  if (this.nodeData.nodeExists(ixxxx + oldPtr)) {
                     throw new IllegalStateException();
                  }
               }
            }

            this.invalidateNode(nodeId);
         }

         if (this.nodeData.isNodeRequestInFlight(nodeId)) {
            int requestIdx = this.nodeData.getNodeRequest(nodeId);
            NodeChildRequest requestx = this.childRequests.get(requestIdx);
            if (requestx.getPosition() != pos) {
               throw new IllegalStateException("Request is not at pos");
            }

            if (requestx.isSatisfied()) {
               this.finishRequest(requestIdx, requestx);
            }
         }
      }

      if (childExistence == 0) {
         if (this.nodeData.isNodeRequestInFlight(nodeId)) {
            throw new IllegalStateException();
         }

         if (this.nodeData.getNodeGeometry(nodeId) == -1) {
            Logger.error("Transforming inner node to leaf node while it has null geometry");
            if (!this.nodeData.isNodeGeometryInFlight(nodeId)) {
               if ((this.watcher.get(pos) & 1) != 0) {
                  throw new IllegalStateException("Watcher was already watching for geometry update, but geometry was null");
               }

               this.processRequest(pos);
               if ((this.watcher.get(pos) & 1) == 0 || !this.nodeData.isNodeGeometryInFlight(nodeId)) {
                  throw new IllegalStateException("Watcher must be watching for geometry update");
               }
            }

            Logger.error("Setting geometry to EMPTY while request is inflight");
            this.nodeData.setNodeGeometry(nodeId, -2);
         }

         if (this.nodeData.getChildPtr(nodeId) != 16777214) {
            throw new IllegalStateException();
         }

         this.nodeData.setChildPtr(nodeId, -1);
         int old = this.activeSectionMap.put(pos, 0 | nodeId);
         this.nodeData.setAllChildrenAreLeaf(nodeId, false);
         this.invalidateNode(nodeId);
      }
   }

   private void recurseRemoveChildNodes(long pos) {
      this._recurseRemoveNode(pos, true);
   }

   private void recurseRemoveNode(long pos) {
      this._recurseRemoveNode(pos, false);
   }

   private void _removeRequest(int reqId, NodeChildRequest req, long pos) {
      for (int i = 0; i < 8; i++) {
         if ((req.getMsk() & 1 << i) != 0) {
            long childPos = makeChildPos(pos, i);
            int meshId = req.getChildMesh(i);
            if (meshId != -2 && meshId != -1) {
               this.removeGeometryCached(childPos, meshId);
            }

            int cId = this.activeSectionMap.remove(childPos);
            if (cId == -1) {
               throw new IllegalStateException("Child not in activeMap");
            }

            if ((cId & -1073741824) != Integer.MIN_VALUE || (cId & 536870912) != 536870912 || (cId & 16777215) != reqId) {
               throw new IllegalStateException("Invalid child active state map: " + cId);
            }

            if (!this.watcher.unwatch(childPos, 3)) {
               throw new IllegalStateException("Pos was not being watched");
            }
         }
      }

      this.childRequests.release(reqId);
      this.activeNodeRequestCount--;
   }

   private void _recurseRemoveNode(long pos, boolean onlyRemoveChildren) {
      int nodeId;
      if (onlyRemoveChildren) {
         nodeId = this.activeSectionMap.get(pos);
      } else {
         nodeId = this.activeSectionMap.remove(pos);
      }

      if (nodeId == -1) {
         throw new IllegalStateException("Cannot remove pos that doesnt exist");
      } else {
         int type = nodeId & -1073741824;
         if (type != 1073741824 && type != 0) {
            if (type != Integer.MIN_VALUE) {
               throw new IllegalStateException();
            }

            if (!this.watcher.unwatch(pos, 3)) {
               throw new IllegalStateException("Pos was not being watched");
            }

            if ((nodeId & 536870912) == 0) {
               nodeId &= 16777215;
               SingleNodeRequest req = this.singleRequests.get(nodeId);
               if (req.getPosition() != pos) {
                  throw new IllegalStateException();
               }

               this.singleRequests.release(nodeId);
               if (req.hasMeshSet()) {
                  int meshId = req.getMesh();
                  if (meshId != -2 && meshId != -1) {
                     this.removeGeometryCached(pos, meshId);
                  }
               }
            } else {
               nodeId &= 16777215;
               NodeChildRequest reqx = this.childRequests.get(nodeId);
               if (reqx.getPosition() != pos) {
                  throw new IllegalStateException();
               }

               this._removeRequest(nodeId, reqx, pos);
            }
         } else {
            nodeId &= 16777215;
            if (!this.nodeData.nodeExists(nodeId)) {
               throw new IllegalStateException("Node exists in section map but not in nodeData");
            }

            byte childExistence = this.nodeData.getNodeChildExistence(nodeId);
            if (this.nodeData.isNodeRequestInFlight(nodeId)) {
               int reqId = this.nodeData.getNodeRequest(nodeId);
               NodeChildRequest reqx = this.childRequests.get(reqId);
               childExistence ^= reqx.getMsk();
               this._removeRequest(reqId, reqx, pos);
               if (onlyRemoveChildren) {
                  this.nodeData.unmarkRequestInFlight(nodeId);
                  this.nodeData.setNodeRequest(nodeId, 524287);
               }
            }

            if (type == 1073741824) {
               byte msk = (byte)0;
               int childPtr = this.nodeData.getChildPtr(nodeId);
               if (childPtr == -1) {
                  throw new IllegalStateException();
               }

               if (childPtr != 16777214) {
                  int childCnt = this.nodeData.getChildPtrCount(nodeId);
                  if (Integer.bitCount(Byte.toUnsignedInt(childExistence)) != childCnt) {
                     throw new IllegalStateException();
                  }

                  for (int i = 0; i < childCnt; i++) {
                     if (!this.nodeData.nodeExists(i + childPtr)) {
                        throw new IllegalStateException();
                     }

                     long cp = this.nodeData.nodePosition(i + childPtr);
                     if (makeParentPos(cp) != pos) {
                        throw new IllegalStateException();
                     }

                     msk |= (byte)(1 << getChildIdx(cp));
                  }
               }

               if (msk != childExistence) {
                  throw new IllegalStateException();
               }

               for (int i = 0; i < 8; i++) {
                  if ((childExistence & 1 << i) != 0) {
                     long childPos = makeChildPos(pos, i);
                     this.recurseRemoveNode(childPos);
                  }
               }

               int removedChildPtr = this.nodeData.getChildPtr(nodeId);
               if (removedChildPtr == -1) {
                  throw new IllegalStateException();
               }

               if (removedChildPtr != 16777214) {
                  int removedChildCount = this.nodeData.getChildPtrCount(nodeId);
                  if (Integer.bitCount(Byte.toUnsignedInt(childExistence)) != removedChildCount) {
                     throw new IllegalStateException();
                  }

                  for (int ix = 0; ix < removedChildCount; ix++) {
                     if (this.nodeData.nodeExists(ix + removedChildPtr)) {
                        throw new IllegalStateException();
                     }
                  }
               }

               if (onlyRemoveChildren) {
                  this.nodeData.setChildPtr(nodeId, -1);
               }
            }

            if (!onlyRemoveChildren) {
               int meshId = this.nodeData.getNodeGeometry(nodeId);
               if (meshId != -2 && meshId != -1) {
                  this.removeGeometryCached(pos, meshId);
               }

               this.nodeData.free(nodeId);
               this.clearFreeId(nodeId);
               this.invalidateNode(nodeId);
               if (!this.watcher.unwatch(pos, 3)) {
                  throw new IllegalStateException("Pos was not being watched");
               }
            } else {
               this.nodeData.setAllChildrenAreLeaf(nodeId, false);
               this.invalidateNode(nodeId);
            }
         }
      }
   }

   private void finishRequest(SingleNodeRequest request) {
      int id = this.nodeData.allocate();
      this.nodeData.setNodePosition(id, request.getPosition());
      this.nodeData.setNodeGeometry(id, request.getMesh());
      this.nodeData.setNodeChildExistence(id, request.getChildExistence());
      this.activeSectionMap.put(request.getPosition(), id | 0);
      this.invalidateNode(id);
      if (!this.topLevelNodeIds.add(id)) {
         throw new IllegalStateException();
      } else {
         this.clearAllocId(id);
         if (this.topLevelNodeIdAddedCallback != null) {
            this.topLevelNodeIdAddedCallback.accept(id);
         }
      }
   }

   private void finishRequest(int requestId, NodeChildRequest request) {
      int parentNodeId = this.activeSectionMap.get(request.getPosition());
      if (parentNodeId != -1 && (parentNodeId & -1073741824) != Integer.MIN_VALUE) {
         int parentNodeType = parentNodeId & -1073741824;
         parentNodeId &= 16777215;
         if (request.getMsk() == 0) {
            this.childRequests.release(requestId);
            this.nodeData.setNodeRequest(parentNodeId, 524287);
            this.nodeData.unmarkRequestInFlight(parentNodeId);
            this.activeNodeRequestCount--;
            this.invalidateNode(parentNodeId);
         } else {
            if (parentNodeType == 0) {
               int msk = Byte.toUnsignedInt(request.getMsk());
               if (msk == 0) {
                  throw new IllegalStateException();
               }

               int base = this.nodeData.allocate(Integer.bitCount(msk));
               int offset = -1;

               for (int childIdx = 0; childIdx < 8; childIdx++) {
                  if ((msk & 1 << childIdx) != 0) {
                     offset++;
                     long childPos = makeChildPos(request.getPosition(), childIdx);
                     int childNodeId = base + offset;
                     this.nodeData.setNodePosition(childNodeId, childPos);
                     byte childExistence = request.getChildChildExistence(childIdx);
                     if (childExistence == 0) {
                        Logger.warn("Request result with child existence of 0, for child pos " + WorldEngine.pprintPos(childPos));
                     }

                     this.nodeData.setNodeChildExistence(childNodeId, childExistence);
                     this.nodeData.setNodeGeometry(childNodeId, request.getChildMesh(childIdx));
                     this.invalidateNode(childNodeId);
                     int pid = this.activeSectionMap.put(childPos, childNodeId | 0);
                     if ((pid & -1073741824) != Integer.MIN_VALUE) {
                        throw new IllegalStateException("Put node in map from request but type was not request: " + pid + " " + WorldEngine.pprintPos(childPos));
                     }

                     this.clearAllocId(childNodeId);
                  }
               }

               this.childRequests.release(requestId);
               this.nodeData.setChildPtr(parentNodeId, base);
               this.nodeData.setChildPtrCount(parentNodeId, Integer.bitCount(msk));
               this.nodeData.setNodeRequest(parentNodeId, 524287);
               this.activeNodeRequestCount--;
               this.nodeData.unmarkRequestInFlight(parentNodeId);
               if ((this.activeSectionMap.put(request.getPosition(), 1073741824 | parentNodeId) & -1073741824) != 0) {
                  throw new IllegalStateException();
               }

               this.invalidateNode(parentNodeId);
               this.nodeData.setAllChildrenAreLeaf(parentNodeId, true);
               if (!this.topLevelNodes.contains(request.getPosition())) {
                  int ppnId = this.activeSectionMap.get(makeParentPos(request.getPosition()));
                  if ((ppnId & -1073741824) != 1073741824) {
                     throw new IllegalStateException();
                  }

                  this.nodeData.setAllChildrenAreLeaf(ppnId & 16777215, false);
               }
            } else {
               if (parentNodeType != 1073741824) {
                  throw new IllegalStateException();
               }

               int oldChildPtr = this.nodeData.getChildPtr(parentNodeId);
               int oldChildCnt = this.nodeData.getChildPtrCount(parentNodeId);
               if (oldChildPtr == -1) {
                  throw new IllegalStateException();
               }

               int existingChildMsk = 0;
               if (oldChildPtr != 16777214) {
                  for (int i = 0; i < oldChildCnt; i++) {
                     if (!this.nodeData.nodeExists(i + oldChildPtr)) {
                        throw new IllegalStateException();
                     }

                     existingChildMsk |= 1 << getChildIdx(this.nodeData.nodePosition(i + oldChildPtr));
                  }
               }

               int reqMsk = Byte.toUnsignedInt(request.getMsk());
               if ((byte)(existingChildMsk | reqMsk) != this.nodeData.getNodeChildExistence(parentNodeId)) {
                  throw new IllegalStateException("node data existence state does not match pointer mask");
               }

               if ((reqMsk & existingChildMsk) != 0) {
                  throw new IllegalStateException("Overlapping child data!!! BAD");
               }

               int newMsk = reqMsk | existingChildMsk;
               int newChildPtr = this.nodeData.allocate(Integer.bitCount(newMsk));
               int childId = newChildPtr - 1;
               int prevChildId = oldChildPtr - 1;

               for (int i = 0; i < 8; i++) {
                  if ((newMsk & 1 << i) != 0) {
                     childId++;
                     if ((reqMsk & 1 << i) != 0) {
                        long childPosx = makeChildPos(request.getPosition(), i);
                        this.nodeData.setNodePosition(childId, childPosx);
                        byte childExistencex = request.getChildChildExistence(i);
                        if (childExistencex == 0) {
                        }

                        this.nodeData.setNodeChildExistence(childId, childExistencex);
                        this.nodeData.setNodeGeometry(childId, request.getChildMesh(i));
                        this.invalidateNode(childId);
                        int pid = this.activeSectionMap.put(childPosx, childId | 0);
                        if ((pid & -1073741824) != Integer.MIN_VALUE) {
                           throw new IllegalStateException(
                              "Put node in map from request but type was not request: " + pid + " " + WorldEngine.pprintPos(childPosx)
                           );
                        }

                        this.clearAllocId(childId);
                     } else {
                        long pos = this.nodeData.nodePosition(++prevChildId);
                        this.nodeData.copyNode(prevChildId, childId);
                        this.clearAllocId(childId);
                        this.clearMoveId(prevChildId, childId);
                        this.clearFreeId(prevChildId);
                        int prevNodeId = this.activeSectionMap.get(pos);
                        if ((prevNodeId & -1073741824) == Integer.MIN_VALUE) {
                           throw new IllegalStateException();
                        }

                        if ((prevNodeId & 16777215) != prevChildId) {
                           throw new IllegalStateException("State inconsistency");
                        }

                        this.activeSectionMap.put(pos, prevNodeId & -1073741824 | childId);
                        this.invalidateNode(prevChildId);
                        this.invalidateNode(childId);
                     }
                  }
               }

               if (oldChildPtr != 16777214) {
                  this.nodeData.free(oldChildPtr, oldChildCnt);
               }

               if (oldChildPtr == 16777214) {
                  this.nodeData.setAllChildrenAreLeaf(parentNodeId, true);
               }

               this.childRequests.release(requestId);
               this.nodeData.setChildPtr(parentNodeId, newChildPtr);
               this.nodeData.setChildPtrCount(parentNodeId, Integer.bitCount(newMsk));
               this.nodeData.setNodeRequest(parentNodeId, 524287);
               this.activeNodeRequestCount--;
               this.nodeData.unmarkRequestInFlight(parentNodeId);
               this.invalidateNode(parentNodeId);
            }
         }
      } else {
         throw new IllegalStateException(
            "CRITICAL BAD STATE!!! finishRequest tried to finish for a node that no longer exists in the map or has become a request type somehow?!!?!!"
               + WorldEngine.pprintPos(request.getPosition())
               + " "
               + parentNodeId
         );
      }
   }

   public void processRequest(long pos) {
      int nodeId = this.activeSectionMap.get(pos);
      if (nodeId != -1) {
         int nodeType = nodeId & -1073741824;
         nodeId &= 16777215;
         if (nodeType == Integer.MIN_VALUE) {
            Logger.error("Tried processing request for pos: " + WorldEngine.pprintPos(pos) + " but its type was a request, ignoring!");
         } else if (nodeType != 0 && nodeType != 1073741824) {
            throw new IllegalStateException("Unknown node type: " + nodeType);
         } else if (WorldEngine.getLevel(pos) == 0) {
            Logger.error("Requests cannot exist for bottom level nodes. at: " + WorldEngine.pprintPos(pos) + ". Ignoring request");
         } else {
            if (nodeType == 0) {
               if (this.nodeData.getNodeGeometry(nodeId) == -1) {
                  Logger.warn("Got request for leaf that doesnt have geometry, this should not be possible at pos " + WorldEngine.pprintPos(pos));
                  if (!this.watcher.watch(pos, 1)) {
                     Logger.warn("Node: " + nodeId + " at pos: " + WorldEngine.pprintPos(pos) + " got update request, but geometry was already being watched");
                  }

                  return;
               }

               if (this.nodeData.isNodeRequestInFlight(nodeId)) {
                  if (this._nodeAlreadyInFlightDontSpam > 1 && this._nodeAlreadyInFlightDontSpam < 100) {
                     Logger.warn(
                        "Tried processing a node that already has a request in flight: " + nodeId + " pos: " + WorldEngine.pprintPos(pos) + " ignoring"
                     );
                     this._nodeAlreadyInFlightDontSpam++;
                  } else if (this._nodeAlreadyInFlightDontSpam == 100) {
                     Logger.warn("Suppressing \"Tried processing node\" warning ;-; (probably gonna regret this)");
                     this._nodeAlreadyInFlightDontSpam = 0;
                  }

                  return;
               }

               this.nodeData.markRequestInFlight(nodeId);
               this.makeLeafChildRequest(nodeId);
            } else {
               this.processInnerRequest(pos, nodeId);
            }
         }
      }
   }

   private void makeLeafChildRequest(int nodeId) {
      long pos = this.nodeData.nodePosition(nodeId);
      byte childExistence = this.nodeData.getNodeChildExistence(nodeId);
      NodeChildRequest request = new NodeChildRequest(pos);
      int requestId = this.childRequests.put(request);

      for (int i = 0; i < 8; i++) {
         if ((childExistence & 1 << i) != 0) {
            long childPos = makeChildPos(pos, i);
            request.addChildRequirement(i);
            int pid = this.activeSectionMap.put(childPos, requestId | -2147483648 | 536870912);
            if (pid != -1) {
               String extra = "";
               if ((pid & -1073741824) == 0) {
                  extra = " type leaf: pos "
                     + WorldEngine.pprintPos(this.nodeData.nodePosition(pid))
                     + " hasRequest: "
                     + this.nodeData.isNodeRequestInFlight(pid);
               }

               throw new IllegalStateException(
                  "Leaf request creation failed to insert child into map as a mapping already existed for the node! pos: "
                     + WorldEngine.pprintPos(childPos)
                     + " id: "
                     + pid
                     + " for parent "
                     + WorldEngine.pprintPos(pos)
                     + " extra "
                     + extra
               );
            }

            if (!this.watcher.watch(childPos, 3)) {
               throw new IllegalStateException("Failed to watch childPos");
            }
         }
      }

      this.nodeData.setNodeRequest(nodeId, requestId);
      this.activeNodeRequestCount++;
   }

   private void processInnerRequest(long pos, int nodeId) {
      int geo = this.nodeData.getNodeGeometry(nodeId);
      boolean isWatchingUpdate = (this.watcher.get(pos) & 1) != 0;
      boolean inflight = this.nodeData.isNodeGeometryInFlight(nodeId);
      if (inflight && !isWatchingUpdate) {
         throw new IllegalStateException();
      } else if (geo != -1 && inflight && geo != -2) {
         throw new IllegalStateException();
      } else {
         if (!this.nodeData.isNodeGeometryInFlight(nodeId)) {
            if (!this.watcher.watch(pos, 1)) {
               this.invalidateNode(nodeId);
            } else {
               this.nodeData.markNodeGeometryInFlight(nodeId);
            }
         }
      }
   }

   public void removeNodeGeometry(long pos) {
      int nodeId = this.activeSectionMap.get(pos);
      if (nodeId != -1) {
         int nodeType = nodeId & -1073741824;
         nodeId &= 16777215;
         if (nodeType != Integer.MIN_VALUE) {
            if (nodeType == 1073741824) {
               this.clearGeometryInternal(pos, nodeId);
            } else if (this.topLevelNodes.contains(pos)) {
               int geo = this.nodeData.getNodeGeometry(nodeId);
               if (geo != -1 && geo != -2) {
                  Logger.warn("Tried removing geometry from top level node which is not allowed, disregarding request");
                  return;
               }
            } else {
               this.processLeafGeometryRemoval(pos);
            }
         }
      }
   }

   private void processLeafGeometryRemoval(long cPos) {
      long pPos = makeParentPos(cPos);
      int pId = this.activeSectionMap.get(pPos);
      if (pId == -1) {
         throw new IllegalStateException("Parent node must exist");
      } else if ((pId & -1073741824) != 1073741824) {
         throw new IllegalStateException("Parent node must be an inner node");
      } else {
         pId &= 16777215;
         int pGeo = this.nodeData.getNodeGeometry(pId);
         if (pGeo == -1) {
            this.processRequest(pPos);
         } else {
            this.recurseRemoveChildNodes(pPos);
            int old = this.activeSectionMap.put(pPos, 0 | pId);
            if (old == -1) {
               throw new IllegalStateException();
            }

            if ((old & -1073741824) != 1073741824 || (old & 16777215) != pId) {
               throw new IllegalStateException();
            }

            this.nodeData.setAllChildrenAreLeaf(pId, false);
         }
      }
   }

   private void clearGeometryInternal(long pos, int nodeId) {
      int meshId = this.nodeData.getNodeGeometry(nodeId);
      if (meshId != -1 && meshId != -2) {
         if (this.watcher.unwatch(pos, 1)) {
            throw new IllegalStateException("Unwatching position for geometry removal at: " + WorldEngine.pprintPos(pos) + " resulted in full removal");
         }

         this.removeGeometryCached(pos, meshId);
         this.nodeData.setNodeGeometry(nodeId, -1);
         this.invalidateNode(nodeId);
         this.nodeData.unmarkNodeGeometryInFlight(nodeId);
      } else if (meshId == -1) {
      }
   }

   public boolean writeChanges(GlBuffer nodeBuffer) {
      if (this.nodeUpdates.isEmpty()) {
         return false;
      } else {
         this.nodeUpdates.forEach(i -> this.nodeData.writeNode(UploadStream.INSTANCE.upload(nodeBuffer, i * 16L, 16L), i));
         this.nodeUpdates.clear();
         return true;
      }
   }

   IntOpenHashSet getNodeUpdates() {
      return this.nodeUpdates;
   }

   void writeNode(int node, long address) {
      this.nodeData.writeNode(address, node);
   }

   public MemoryBuffer _generateChangeList() {
      if (this.nodeUpdates.isEmpty()) {
         return null;
      } else {
         MemoryBuffer buff = new MemoryBuffer(this.nodeUpdates.size() * 20L);
         int c = 0;
         IntIterator var3 = this.nodeUpdates.iterator();

         while (var3.hasNext()) {
            int i = (Integer)var3.next();
            long addr = buff.address + 20L * c++;
            MemoryUtil.memPutInt(addr, i);
            this.nodeData.writeNode(addr + 4L, i);
         }

         this.nodeUpdates.clear();
         return buff;
      }
   }

   private void invalidateNode(int nodeId) {
      this.nodeUpdates.add(nodeId);
   }

   private static int getChildIdx(long pos) {
      int x = WorldEngine.getX(pos);
      int y = WorldEngine.getY(pos);
      int z = WorldEngine.getZ(pos);
      return x & 1 | (y & 1) << 2 | (z & 1) << 1;
   }

   private static long makeChildPos(long basePos, int addin) {
      int lvl = WorldEngine.getLevel(basePos);
      if (lvl == 0) {
         throw new IllegalArgumentException("Cannot create a child lower than lod level 0");
      } else {
         return WorldEngine.getWorldSectionId(
            lvl - 1,
            WorldEngine.getX(basePos) << 1 | addin & 1,
            WorldEngine.getY(basePos) << 1 | addin >> 2 & 1,
            WorldEngine.getZ(basePos) << 1 | addin >> 1 & 1
         );
      }
   }

   private static long makeParentPos(long pos) {
      int lvl = WorldEngine.getLevel(pos);
      if (lvl == 4) {
         throw new IllegalArgumentException("Cannot create a parent higher than LoD 4");
      } else {
         return WorldEngine.getWorldSectionId(lvl + 1, WorldEngine.getX(pos) >> 1, WorldEngine.getY(pos) >> 1, WorldEngine.getZ(pos) >> 1);
      }
   }

   public void addDebug(List<String> debug) {
      debug.add("NC/IF: " + this.activeSectionMap.size() + "/" + (this.singleRequests.count() + this.childRequests.count()));
   }

   public int getCurrentMaxNodeId() {
      return this.nodeData.getEndNodeId();
   }

   private int verifyRequest(long pos, int node, int cActiveExistence, LongOpenHashSet seenPositions, IntOpenHashSet seenNodes) {
      if (this.nodeData.isNodeRequestInFlight(node)) {
         int requestId = this.nodeData.getNodeRequest(node);
         NodeChildRequest request = this.childRequests.get(requestId);
         if (request.getPosition() != pos) {
            throw new IllegalStateException();
         } else {
            int reqMsk = Byte.toUnsignedInt(request.getMsk());
            if ((cActiveExistence & reqMsk) != 0) {
               throw new IllegalStateException();
            } else {
               for (int i = 0; i < 8; i++) {
                  if ((reqMsk & 1 << i) != 0) {
                     long cPos = makeChildPos(pos, i);
                     int cNode = this.activeSectionMap.get(cPos);
                     if (cNode == -1) {
                        throw new IllegalStateException();
                     }

                     if ((cNode & -1073741824) != Integer.MIN_VALUE) {
                        throw new IllegalStateException();
                     }

                     if ((cNode & 536870912) != 536870912) {
                        throw new IllegalStateException();
                     }

                     if ((cNode & 16777215) != requestId) {
                        throw new IllegalStateException();
                     }

                     this.verifyNode(cPos, seenPositions, seenNodes);
                  }
               }

               return reqMsk;
            }
         }
      } else {
         return 0;
      }
   }

   private void verifyNode(long pos, LongOpenHashSet seenPositions, IntOpenHashSet seenNodes) {
      int node = this.activeSectionMap.get(pos);
      if (node == -1) {
         throw new IllegalStateException();
      } else if (this.watcher.get(pos) == 0) {
         throw new IllegalStateException();
      } else if (!seenPositions.add(pos)) {
         throw new IllegalStateException();
      } else {
         int type = node & -1073741824;
         if (type == Integer.MIN_VALUE) {
            if ((node & 536870912) == 0) {
               if (!this.topLevelNodes.contains(pos)) {
                  throw new IllegalStateException();
               }

               int id = node & 16777215;
               SingleNodeRequest req = this.singleRequests.get(id);
               if (req.getPosition() != pos) {
                  throw new IllegalStateException();
               }
            } else {
               int id = node & 16777215;
               NodeChildRequest req = this.childRequests.get(id);
               if (req.getPosition() != makeParentPos(pos)) {
                  throw new IllegalStateException();
               }
            }
         } else {
            node &= 16777215;
            if (!this.nodeData.nodeExists(node)) {
               throw new IllegalStateException();
            }

            if (this.nodeData.nodePosition(node) != pos) {
               throw new IllegalStateException();
            }

            if (this.nodeData.getNodeRequest(node) != 524287 != this.nodeData.isNodeRequestInFlight(node)) {
               throw new IllegalStateException();
            }

            if (this.nodeData.isNodeRequestInFlight(node)) {
               NodeChildRequest req = this.childRequests.get(this.nodeData.getNodeRequest(node));
               if (req == null) {
                  throw new IllegalStateException();
               }

               if (req.getPosition() != pos) {
                  throw new IllegalStateException();
               }

               if (req.isSatisfied()) {
                  boolean dormantEmptyLeaf = type == 0
                     && this.nodeData.getNodeChildExistence(node) == 0
                     && req.getMsk() == 0;
                  if (!dormantEmptyLeaf && (type != 0 || !this.topLevelNodes.contains(pos))) {
                     throw new IllegalStateException();
                  }
               }
            }

            boolean hasGeometry = this.nodeData.getNodeGeometry(node) != -1;
            boolean watchingGeo = (this.watcher.get(pos) & 1) != 0;
            boolean awaitingGeo = this.nodeData.isNodeGeometryInFlight(node);
            if ((hasGeometry || awaitingGeo) != watchingGeo) {
               throw new IllegalStateException();
            }

            if (hasGeometry && awaitingGeo && this.nodeData.getNodeGeometry(node) != -2) {
               throw new IllegalStateException();
            }

            if (!seenNodes.add(node)) {
               throw new IllegalStateException();
            }

            if (type == 1073741824) {
               int childPtr = this.nodeData.getChildPtr(node);
               int childCount = this.nodeData.getChildPtrCount(node);
               int activeChildExistence = 0;
               if (childPtr == -1) {
                  throw new IllegalStateException();
               }

               if (childPtr == 16777214) {
                  if (this.nodeData.getAllChildrenAreLeaf(node)) {
                     throw new IllegalStateException();
                  }
               } else {
                  boolean allChildrenLeaf = true;

                  for (int i = 0; i < childCount; i++) {
                     if (!this.nodeData.nodeExists(i + childPtr)) {
                        throw new IllegalStateException();
                     }

                     long cPos = this.nodeData.nodePosition(i + childPtr);
                     if (makeParentPos(cPos) != pos) {
                        throw new IllegalStateException();
                     }

                     activeChildExistence |= 1 << getChildIdx(cPos);
                     int cNode = this.activeSectionMap.get(cPos);
                     if (cNode == -1) {
                        throw new IllegalStateException();
                     }

                     if ((cNode & -1073741824) != 0) {
                        allChildrenLeaf = false;
                     }

                     this.verifyNode(cPos, seenPositions, seenNodes);
                  }

                  if (this.nodeData.getAllChildrenAreLeaf(node) != allChildrenLeaf) {
                     throw new IllegalStateException();
                  }
               }

               int childExistence = activeChildExistence | this.verifyRequest(pos, node, activeChildExistence, seenPositions, seenNodes);
               if (childExistence != Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(node))) {
                  throw new IllegalStateException();
               }

               if (childExistence == 0) {
                  throw new IllegalStateException();
               }
            } else {
               if (type != 0) {
                  throw new IllegalStateException();
               }

               if (this.nodeData.getAllChildrenAreLeaf(node)) {
                  throw new IllegalStateException();
               }

               if (this.nodeData.getChildPtr(node) != -1) {
                  throw new IllegalStateException();
               }

               if (this.nodeData.getNodeGeometry(node) == -1) {
                  throw new IllegalStateException();
               }

               if (WorldEngine.getLevel(pos) == 0) {
                  if (this.nodeData.isNodeRequestInFlight(node)) {
                     throw new IllegalStateException();
                  }
               } else if (this.nodeData.isNodeRequestInFlight(node)) {
                  int childExistence = this.verifyRequest(pos, node, 0, seenPositions, seenNodes);
                  if (childExistence != Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(node))) {
                     throw new IllegalStateException();
                  }
               }
            }
         }
      }
   }

   public void verifyIntegrity() {
      this.verifyIntegrity(null, null);
   }

   public void verifyIntegrity(LongSet watchingPosSet, IntSet nodes) {
      LongOpenHashSet seenPositions = new LongOpenHashSet();
      IntOpenHashSet seenNodes = new IntOpenHashSet();
      LongIterator thisMap = this.topLevelNodes.iterator();

      while (thisMap.hasNext()) {
         long pos = (Long)thisMap.next();
         this.verifyNode(pos, seenPositions, seenNodes);
      }

      LongSet thisMapx = this.activeSectionMap.keySet();
      if (seenPositions.containsAll(thisMapx) && thisMapx.containsAll(seenPositions)) {
         if (seenNodes.size() != this.nodeData.getNodeCount()) {
            throw new IllegalStateException();
         } else {
            IntIterator seenNodeIterator = seenNodes.iterator();

            while (seenNodeIterator.hasNext()) {
               int i = (Integer)seenNodeIterator.next();
               if (!this.nodeData.nodeExists(i)) {
                  throw new IllegalStateException();
               }
            }

            if (this.activeNodeRequestCount != this.childRequests.count()) {
               throw new IllegalStateException();
            } else {
               if (watchingPosSet != null) {
                  if (!watchingPosSet.containsAll(thisMapx)) {
                     throw new IllegalStateException();
                  }

                  if (!thisMapx.containsAll(watchingPosSet)) {
                     throw new IllegalStateException();
                  }
               }

               if (nodes != null) {
                  if (!nodes.containsAll(seenNodes)) {
                     throw new IllegalStateException();
                  }

                  if (!seenNodes.containsAll(nodes)) {
                     throw new IllegalStateException();
                  }
               }

               IntOpenHashSet tln = new IntOpenHashSet(this.topLevelNodeIds.size());
               LongIterator var14 = this.topLevelNodes.iterator();

               while (var14.hasNext()) {
                  long p = (Long)var14.next();
                  int n = this.activeSectionMap.get(p);
                  if (n == -1) {
                     throw new IllegalStateException();
                  }

                  if ((n & -1073741824) != Integer.MIN_VALUE && !tln.add(n & 16777215)) {
                     throw new IllegalStateException();
                  }
               }

               if (!this.topLevelNodeIds.containsAll(tln)) {
                  throw new IllegalStateException();
               } else if (!tln.containsAll(this.topLevelNodeIds)) {
                  throw new IllegalStateException();
               }
            }
         }
      } else {
         throw new IllegalStateException();
      }
   }

   public interface ICleaner {
      void alloc(int var1);

      void move(int var1, int var2);

      void free(int var1);
   }
}
