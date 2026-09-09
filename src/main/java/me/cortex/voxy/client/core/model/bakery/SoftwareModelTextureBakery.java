package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.client.config.VoxyConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.compat.DomumOrnamentumCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.client.resources.model.BakedModel;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;

public class SoftwareModelTextureBakery {
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
    private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer();
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);
    private final Mapper mapper;

    public SoftwareModelTextureBakery(Mapper mapper) {
        this.mapper = mapper;
    }

    public void setupTexture() {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));

        int textureId = texture.getId();

        if (!RenderSystem.isOnRenderThread()) {
            CompletableFuture<Void> future = new CompletableFuture<>();

            RenderSystem.recordRenderCall(() -> {
                try {
                    _doSetupTexture(textureId);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            future.join();
        } else {
            _doSetupTexture(textureId);
        }
    }

    private void _doSetupTexture(int glId) {
        // This readback shares global GL state with custom renderers. In particular, Universal Mod
        // Core's OBJ renderer tracks its own texture binding and assumes foreign renderers return the
        // binding unchanged. Leaving the block atlas bound makes its next model sample grass/terrain
        // sprites instead of the OBJ material.
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousPackBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int previousRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int previousImageHeight = glGetInteger(GL_PACK_IMAGE_HEIGHT);
        int previousSkipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int previousSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        int previousAlignment = glGetInteger(GL_PACK_ALIGNMENT);

        int width;
        int height;
        int[] pixels;
        try {
            glBindTexture(GL_TEXTURE_2D, glId);
            width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            pixels = new int[width * height];

            // A pixel-pack buffer left bound redirects this readback into that buffer instead of the
            // Java array; stale row/skip state similarly corrupts the copied atlas.
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ROW_LENGTH, width);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            long byteSize = Math.multiplyExact((long) width, (long) height) * 4L;
            long address = MemoryUtil.nmemAlloc(byteSize);
            if (address == 0) {
                throw new OutOfMemoryError("atlas readback buffer: " + byteSize + " bytes");
            }
            try {
                nglGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, address);
                MemoryUtil.memIntBuffer(address, width * height).get(pixels);
            } finally {
                MemoryUtil.nmemFree(address);
            }
        } finally {
            glPixelStorei(GL_PACK_ROW_LENGTH, previousRowLength);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, previousImageHeight);
            glPixelStorei(GL_PACK_SKIP_ROWS, previousSkipRows);
            glPixelStorei(GL_PACK_SKIP_PIXELS, previousSkipPixels);
            glPixelStorei(GL_PACK_ALIGNMENT, previousAlignment);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            glBindTexture(GL_TEXTURE_2D, previousTexture);
        }

        this.rasterizer.setSamplerTexture(pixels, width, height);
    }

    public static final int FLAG_CENTERED_GROUND_CROSS = 1 << 4;

    private boolean bakeBlockModel(int blockId, BlockState state, RenderType layer, boolean forceSolidLeaves) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            //Vanilla only draws the json model for MODEL-shaped states. ENTITYBLOCK_ANIMATED blocks
            //(Create cogwheels and friends) still carry a full json the game never renders - baking it
            //shows up at LOD range as a boxy ghost of geometry that does not exist up close. Their
            //distant look is owned by the kinetic snapshots, which capture the real rendered parts.
            return false;
        }

        var plan = DomumOrnamentumCompat.getBakePlan(this.mapper, blockId);
        boolean domumModel = !plan.isEmpty();
        if (domumModel && plan.detailedMesh()) {
            return false;
        }
        if (!domumModel) {
            plan = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.getBakePlan(this.mapper, blockId, state);
        }
        if (plan.isEmpty()) {
            plan = me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.getBakePlan(this.mapper, blockId, state);
        }
        BlockState modelState = plan.modelState() == null ? state : plan.modelState();
        ModelData modelData = plan.modelData();
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(modelState);

        int forcedTint = plan.forceTint() ? plan.fallbackTintAbgr() : -1;
        this.opaqueVC.setFallbackTintColour(plan.fallbackTintAbgr()).setForcedTintColour(forcedTint);
        this.translucentVC.setFallbackTintColour(plan.fallbackTintAbgr()).setForcedTintColour(forcedTint);

        boolean crossCandidate = true;
        int diagonalFamilies = 0;
        int unculledQuads = 0;

        List<RenderType> layers = List.of(layer);
        if (plan.independentModel()) {
            try {
                var declaredLayers = model.getRenderTypes(
                        modelState, new SingleThreadedRandomSource(42L), modelData).asList();
                if (!declaredLayers.isEmpty()) {
                    layers = declaredLayers;
                }
            } catch (Throwable ignored) {
                // Keep the block state's normal layer when optional model data is malformed.
            }
        }

        var random = new SingleThreadedRandomSource(42L);
        for (RenderType renderLayer : layers) {
            // Copycat wrapper models gate per-layer queries on the material model's declared
            // render types. A null query asks the wrapper for all geometry.
            RenderType quadQueryLayer =
                    me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.isCopycatState(state)
                            ? null : resolveQueryLayer(
                                    model, modelState, modelData, renderLayer);

            for (Direction direction : new Direction[] { Direction.DOWN, Direction.UP,
                    Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null }) {
                random.setSeed(42L);
                var quads = model.getQuads(
                        modelState, direction, random, modelData, quadQueryLayer);

                if (direction != null && !quads.isEmpty()) {
                    crossCandidate = false;
                }

                for (var quad : quads) {
                    if (direction == null && crossCandidate) {
                        int family = classifyGroundCrossQuad(quad.getVertices());
                        if (family == 0) {
                            crossCandidate = false;
                        } else {
                            diagonalFamilies |= family;
                            unculledQuads++;
                        }
                    }

                    (renderLayer == RenderType.translucent() ? this.translucentVC : this.opaqueVC)
                            .quad(quad, forceSolidLeaves, renderLayer, modelState, domumModel);
                }
            }
        }

        return crossCandidate && unculledQuads >= 2 && diagonalFamilies == 0b11;
    }

    private static int classifyGroundCrossQuad(int[] vertices) {
        if (vertices.length < 16 || (vertices.length & 3) != 0) {
            return 0;
        }

        int stride = vertices.length / 4;
        float x0 = Float.intBitsToFloat(vertices[0]);
        float y0 = Float.intBitsToFloat(vertices[1]);
        float z0 = Float.intBitsToFloat(vertices[2]);
        float x1 = Float.intBitsToFloat(vertices[stride]);
        float y1 = Float.intBitsToFloat(vertices[stride + 1]);
        float z1 = Float.intBitsToFloat(vertices[stride + 2]);
        float x2 = Float.intBitsToFloat(vertices[stride * 2]);
        float y2 = Float.intBitsToFloat(vertices[stride * 2 + 1]);
        float z2 = Float.intBitsToFloat(vertices[stride * 2 + 2]);
        float x3 = Float.intBitsToFloat(vertices[stride * 3]);
        float y3 = Float.intBitsToFloat(vertices[stride * 3 + 1]);
        float z3 = Float.intBitsToFloat(vertices[stride * 3 + 2]);

        float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        float bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float normalLengthSq = nx * nx + ny * ny + nz * nz;
        if (normalLengthSq < 1.0e-8f) {
            return 0;
        }
        float normalLength = (float) Math.sqrt(normalLengthSq);

        //Lily pads and other horizontal cards have a mostly vertical normal
        if (Math.abs(ny) > normalLength * 0.12f) {
            return 0;
        }
        //Crops and vines use axis-aligned cards; ground crosses have balanced X/Z normals
        float major = Math.max(Math.abs(nx), Math.abs(nz));
        float minor = Math.min(Math.abs(nx), Math.abs(nz));
        if (major < 1.0e-5f || minor < major * 0.55f) {
            return 0;
        }
        //The plane must pass through the middle of the cell - rejects slanted decorative faces that
        //merely happen to have a diagonal normal
        float centerX = (x0 + x1 + x2 + x3) * 0.25f - 0.5f;
        float centerY = (y0 + y1 + y2 + y3) * 0.25f - 0.5f;
        float centerZ = (z0 + z1 + z2 + z3) * 0.25f - 0.5f;
        float planeDistance = Math.abs(nx * centerX + ny * centerY + nz * centerZ) / normalLength;
        if (planeDistance > 0.0625f) {
            return 0;
        }
        return nx * nz >= 0.0f ? 0b01 : 0b10;
    }

    private void bakeFluidState(BlockState state, int face, RenderType layer) {
        BlockAndTintGetter getter = new BlockAndTintGetter() {
            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                translucentVC.setDefaultMeta(translucentVC.getDefaultMeta() | 4);
                opaqueVC.setDefaultMeta(opaqueVC.getDefaultMeta() | 4);
                translucentVC.setVertexAlphaOnly(true);
                opaqueVC.setVertexAlphaOnly(true);
                return -1;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }

            @Override
            public float getShade(Direction direction, boolean bl) {
                return getVanillaLikeFluidShade(direction);
            }
        };

        VertexConsumer vc = layer == RenderType.translucent() ? this.translucentVC : this.opaqueVC;
        if (layer == RenderType.cutout()) {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta() | 1);
        } else {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta() & ~1);
        }
        try {
            Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, getter, vc, state, state.getFluidState());
        } finally {
            this.opaqueVC.setVertexAlphaOnly(false);
            this.translucentVC.setVertexAlphaOnly(false);
            this.translucentVC.setDefaultMeta(0);
            this.opaqueVC.setDefaultMeta(0);
        }
    }

    private static float getVanillaLikeFluidShade(Direction direction) {
        if (direction == null) {
            return 1.0f;
        }
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX() * pos.getX() + fv.getY() * pos.getY() + fv.getZ() * pos.getZ();
        return dot >= 1;
    }

    private static boolean isHorizontalFluidSideFace(int face) {
        Direction direction = Direction.from3DDataValue(face);
        return direction == Direction.NORTH || direction == Direction.SOUTH
                || direction == Direction.WEST || direction == Direction.EAST;
    }

    public void free() {
        this.opaqueVC.free();
        this.translucentVC.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE
            * ModelFactory.MODEL_TEXTURE_SIZE) * 8;
    // Faces are appended in direction order: down, up, north, south, west, east.

    //The layer to hand getQuads. It has to come from the MODEL's declared set, not from the block's
    //registered chunk render type: those disagree more often than one would hope, and the chunk mesher
    //only ever queries what the model declares, so a model is within its rights to assume it never
    //sees anything else. Immersive Engineering's conveyor is the case that found this - it declares
    //{cutout, translucent} and keys an internal per-layer cache on exactly those, so being asked for
    //the block's registered solid() handed it a null cache and it threw.
    //A copycat is the deliberate exception and never reaches here; its gate is bypassed with null.
    private static RenderType resolveQueryLayer(BakedModel model, BlockState modelState, ModelData modelData,
                                                RenderType layer) {
        ChunkRenderTypeSet declared;
        try {
            declared = model.getRenderTypes(modelState, new SingleThreadedRandomSource(42L), modelData);
        } catch (Throwable t) {
            //A model that cannot even report its layers is not going to survive being queried for one
            return layer;
        }
        if (declared == null || declared.isEmpty() || declared.contains(layer)) {
            return layer;
        }
        //Not declared: take the model at its word and ask for something it does claim to emit, rather
        //than nothing at all - the final opaque/cutout/translucent call is made from the baked pixels
        //afterwards, so querying a neighbouring layer costs correctness nothing here.
        for (RenderType candidate : declared) {
            return candidate;
        }
        return layer;
    }

    public int renderToOutput(int blockId, BlockState state, long outputBuffer) {
        MemoryUtil.memSet(outputBuffer, 0, 16 * 16 * 8 * 6);

        boolean isBlock = !ModelFactory.isFluidBlockState(state);

        RenderType blockRenderLayer;
        boolean forceSolidLeaves = false;
        if (!isBlock) {
            blockRenderLayer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
        } else if (ModelFactory.isLeafBlockState(state)) {
            var leafMode = VoxyConfig.CONFIG.getLeafLodMode();
            forceSolidLeaves = leafMode == VoxyConfig.LeafLodMode.FAST;
            blockRenderLayer = forceSolidLeaves ? RenderType.solid() : RenderType.cutout();
        } else {
            blockRenderLayer = ItemBlockRenderTypes.getChunkRenderType(state);
        }
        if (isBlock) {
            //Copycat wrapper models only emit quads when queried with their MATERIAL's chunk render
            //type, not the copycat block's own layer
            var copycatLayer = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.renderLayerOverride(this.mapper, blockId, state);
            if (copycatLayer != null) {
                blockRenderLayer = copycatLayer;
            }
        }

        boolean isAnyShaded = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;
        boolean centeredGroundCross = false;
        if (isBlock) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            centeredGroundCross = this.bakeBlockModel(blockId, state, blockRenderLayer, forceSolidLeaves);
            isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
            anyTranslucent |= !this.translucentVC.isEmpty();
            anyDiscard |= this.opaqueVC.anyDiscard;
            if (!(this.opaqueVC.isEmpty() && this.translucentVC.isEmpty())) {
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
                    this.rasterizer.clear();
                    this.rasterizer.setBlending(false);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setBlending(true);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(),
                            outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
                }
            }
        } else {
            if (!ModelFactory.isFluidBlockState(state)) {
                throw new IllegalStateException();
            }
            for (int i = 0; i < VIEWS.length; i++) {
                // Supplement's Lumisene Fluids use surface-only LOD geometry.
                if (ModelFactory.isLumiseneFluidBlockState(state) && isHorizontalFluidSideFace(i)) {
                    continue;
                }

                this.opaqueVC.reset();
                this.translucentVC.reset();
                this.bakeFluidState(state, i, blockRenderLayer);
                if (this.opaqueVC.isEmpty() && this.translucentVC.isEmpty()) {
                    continue;
                }
                isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
                anyTranslucent |= !this.translucentVC.isEmpty();
                anyDiscard |= this.opaqueVC.anyDiscard;

                this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);

                this.rasterizer.clear();
                this.rasterizer.setBlending(false);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                // Preserve straight alpha when opposite-winding fluid quads overlap.
                this.rasterizer.setBlending(true, true);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
            }
        }

        return (isAnyShaded ? 1 : 0) | (anyTranslucent ? 4 : 0) | (anyDiscard ? 8 : 0) | (centeredGroundCross ? FLAG_CENTERED_GROUND_CROSS : 0);
    }

    static {
        addView(0, -90, 0, 0, 0);
        addView(1, 90, 0, 0, 0b100);

        addView(2, 0, 180, 0, 0b001);
        addView(3, 0, 0, 0, 0);

        addView(4, 0, 90, 270, 0b100);
        addView(5, 0, 270, 270, 0);
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack.last().pose().mul(new Matrix4f().scale(1 - 2 * (flip & 1), 1 - (flip & 2), 1 - ((flip >> 1) & 2)));
        stack.translate(-0.5f, -0.5f, -0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                2, 0, 0, 0,
                0, 2, 0, 0,
                0, 0, -2, 0,
                -1, -1, 1, 1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
