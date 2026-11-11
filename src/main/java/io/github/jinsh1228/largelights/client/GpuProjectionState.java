package io.github.jinsh1228.largelights.client;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

/** One guard per projection batch. Raw GL calls leave Minecraft's cached render state unchanged. */
final class GpuProjectionState implements AutoCloseable {
    final int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    final int[] viewport = new int[4];
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
    private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    private final int destRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    private final int destAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
    private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean polygon = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
    private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final float polygonFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
    private final float polygonUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
    private final boolean[] colors = new boolean[4];
    private final int[] textures, samplerBindings;
    private final boolean samplers = samplerObjects();

    static boolean supported() {
        return GL.getCapabilities().OpenGL32 && GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) >= 1024;
    }

    static boolean samplerObjects() {
        var capabilities = GL.getCapabilities();
        return capabilities.OpenGL33 || capabilities.GL_ARB_sampler_objects;
    }

    GpuProjectionState() { this(2); }

    GpuProjectionState(int units) {
        textures = new int[units];
        samplerBindings = new int[units];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, size);
            size.get(viewport);
            var mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) colors[i] = mask.get(i) != 0;
        }
        for (int i = 0; i < units; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (samplers) samplerBindings[i] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i);
        }
        GL13.glActiveTexture(activeTexture);
    }

    @Override
    public void close() {
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        for (int i = 0; i < textures.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
            if (samplers) GL33.glBindSampler(i, samplerBindings[i]);
        }
        GL13.glActiveTexture(activeTexture);
        GL11.glDepthFunc(depthFunction);
        GL11.glClearDepth(clearDepth);
        GL11.glDepthMask(depthMask);
        GL14.glBlendFuncSeparate(sourceRgb, destRgb, sourceAlpha, destAlpha);
        GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
        GL11.glPolygonOffset(polygonFactor, polygonUnits);
        GL11.glColorMask(colors[0], colors[1], colors[2], colors[3]);
        enabled(GL11.GL_DEPTH_TEST, depth);
        enabled(GL11.GL_BLEND, blend);
        enabled(GL11.GL_CULL_FACE, cull);
        enabled(GL11.GL_POLYGON_OFFSET_FILL, polygon);
        enabled(GL11.GL_STENCIL_TEST, stencil);
        enabled(GL11.GL_SCISSOR_TEST, scissor);
    }

    private static void enabled(int capability, boolean value) {
        if (value) GL11.glEnable(capability); else GL11.glDisable(capability);
    }
}
