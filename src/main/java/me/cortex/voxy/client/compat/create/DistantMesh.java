package me.cortex.voxy.client.compat.create;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11C.glDrawElements;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45C.glCreateBuffers;
import static org.lwjgl.opengl.GL45C.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45C.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45C.glNamedBufferData;
import static org.lwjgl.opengl.GL45C.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45C.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45C.glVertexArrayAttribIFormat;
import static org.lwjgl.opengl.GL45C.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45C.glVertexArrayVertexBuffer;

//Quad mesh in the distant-render vertex format, fully self-managed GL (own VBO+VAO, shared quad
//index buffer). Layout per vertex (32 bytes, see STRIDE):
//  0  vec3  position (relative to the mesh origin)
//  12 vec2  block atlas uv
//  20 2x u8 lightmap uv (normalized)
//  22 u8    shade (normalized)
//  23 u8    face index (read as an integer attribute, not normalized)
//  24 4x u8 rgba tint (normalized; white for untinted)
//  28 u32   shader-pack material/block ID (zero when unknown)
public final class DistantMesh {
    public static final int STRIDE = 32;

    private static int sharedIndexBuffer;
    private static int sharedIndexQuadCapacity;

    private final int vao;
    private final int vbo;
    private final int quadCount;

    public int quadCount() {
        return this.quadCount;
    }

    //Vertex bytes held on the GPU. The shared index buffer is not counted - it is one allocation for
    //every mesh in the game, so charging it per mesh would say the wrong thing about what a mesh costs.
    public long gpuByteSize() {
        return (long) this.quadCount * 4L * STRIDE;
    }

    //Mesh-local extent, so a draw can frustum-test without the caller having to know what went in
    public float minX, minY, minZ, maxX, maxY, maxZ;

    DistantMesh(ByteBuffer vertexData, int quadCount) {
        this.quadCount = quadCount;
        ensureIndexCapacity(quadCount);

        this.vbo = glCreateBuffers();
        glNamedBufferData(this.vbo, vertexData, GL_STATIC_DRAW);

        this.vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(this.vao, 0, this.vbo, 0, STRIDE);
        glVertexArrayElementBuffer(this.vao, sharedIndexBuffer);

        glEnableVertexArrayAttrib(this.vao, 0);
        glVertexArrayAttribFormat(this.vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(this.vao, 0, 0);

        glEnableVertexArrayAttrib(this.vao, 1);
        glVertexArrayAttribFormat(this.vao, 1, 2, GL_FLOAT, false, 12);
        glVertexArrayAttribBinding(this.vao, 1, 0);

        glEnableVertexArrayAttrib(this.vao, 2);
        glVertexArrayAttribFormat(this.vao, 2, 2, GL_UNSIGNED_BYTE, true, 20);
        glVertexArrayAttribBinding(this.vao, 2, 0);

        glEnableVertexArrayAttrib(this.vao, 3);
        glVertexArrayAttribFormat(this.vao, 3, 1, GL_UNSIGNED_BYTE, true, 22);
        glVertexArrayAttribBinding(this.vao, 3, 0);

        glEnableVertexArrayAttrib(this.vao, 4);
        glVertexArrayAttribIFormat(this.vao, 4, 1, GL_UNSIGNED_BYTE, 23);
        glVertexArrayAttribBinding(this.vao, 4, 0);

        glEnableVertexArrayAttrib(this.vao, 5);
        glVertexArrayAttribFormat(this.vao, 5, 4, GL_UNSIGNED_BYTE, true, 24);
        glVertexArrayAttribBinding(this.vao, 5, 0);

        glEnableVertexArrayAttrib(this.vao, 6);
        glVertexArrayAttribIFormat(this.vao, 6, 1, GL_UNSIGNED_INT, 28);
        glVertexArrayAttribBinding(this.vao, 6, 0);
    }

    //Grows the shared quad->triangles index buffer (0,1,2, 2,3,0 per quad)
    private static void ensureIndexCapacity(int quads) {
        if (quads <= sharedIndexQuadCapacity) {
            return;
        }
        int capacity = Math.max(quads, Math.max(sharedIndexQuadCapacity * 2, 4096));
        if (sharedIndexBuffer != 0) {
            glDeleteBuffers(sharedIndexBuffer);
        }
        int[] indices = new int[capacity * 6];
        for (int q = 0; q < capacity; q++) {
            int base = q * 4, i = q * 6;
            indices[i] = base;
            indices[i + 1] = base + 1;
            indices[i + 2] = base + 2;
            indices[i + 3] = base + 2;
            indices[i + 4] = base + 3;
            indices[i + 5] = base;
        }
        sharedIndexBuffer = glCreateBuffers();
        glNamedBufferData(sharedIndexBuffer, indices, GL_STATIC_DRAW);
        sharedIndexQuadCapacity = capacity;
    }

    //Caller is responsible for program, uniforms, textures and depth state
    public void draw() {
        glBindVertexArray(this.vao);
        glDrawElements(GL_TRIANGLES, this.quadCount * 6, GL_UNSIGNED_INT, 0);
    }

    public void free() {
        glDeleteVertexArrays(this.vao);
        glDeleteBuffers(this.vbo);
    }
}
