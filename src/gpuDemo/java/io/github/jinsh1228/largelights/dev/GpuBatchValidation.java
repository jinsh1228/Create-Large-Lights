package io.github.jinsh1228.largelights.dev;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.mojang.logging.LogUtils;
import io.github.jinsh1228.largelights.CreateLargeLights;
import io.github.jinsh1228.largelights.client.GpuLightGeometry;
import io.github.jinsh1228.largelights.client.GpuLightProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Opt-in floating-point framebuffer comparison with the previous, independent single-light shaders. */
public final class GpuBatchValidation {
    private static final boolean ENABLED = Boolean.getBoolean("createLargeLights.gpuDemo.checkBatch");
    private static final int BATCH = 4;
    private static List<?> pending;
    private static int checked;
    private record Light(Object request, Object shadow) { }

    public static void before() {
        if (!ENABLED || pending != null) return;
        try {
            int phase = (int) get(GpuProjectionDemo.class, "phase");
            if (phase < 0 || (checked & 1 << phase) != 0 || (int) get(GpuProjectionDemo.class, "frameCount") < 60) return;
            pending = new ArrayList<>(((Map<?, ?>) get(GpuLightProjection.class, "queued")).values());
        } catch (Exception e) { fail(e); }
    }

    public static void after(RenderLevelStageEvent event) {
        if (pending == null) return;
        List<?> requests = pending;
        pending = null;
        try {
            int phase = (int) get(GpuProjectionDemo.class, "phase");
            Map<?, ?> shadows = (Map<?, ?>) get(GpuLightProjection.class, "shadows");
            List<Light> lights = new ArrayList<>();
            for (Object request : requests) {
                Object shadow = shadows.get(((BlockPos) get(request, "anchor")).asLong());
                if (shadow == null || !(boolean) method(shadow.getClass(), "matches").invoke(shadow, request)) continue;
                lights.add(new Light(varied(request, lights.size()), shadow));
            }
            if (lights.size() < 4) return;
            checked |= 1 << phase;
            long draws = (long) get(GpuLightProjection.class, "surfaceDraws");
            int count = (int) get(GpuLightProjection.class, "lastLights");
            try { verify(event, lights, phase); }
            finally {
                field(GpuLightProjection.class, "surfaceDraws").setLong(null, draws);
                field(GpuLightProjection.class, "lastLights").setInt(null, count);
            }
        } catch (Exception e) { fail(e); }
    }

    private static Object varied(Object request, int index) throws Exception {
        var components = request.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        int[] palette = {0xFF4C19, 0x22DD55, 0x1155FF, 0xFFAAEE, 0xFFFFFF};
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = get(request, components[i].getName());
            if (components[i].getName().equals("color")) values[i] = palette[index % palette.length];
            if (components[i].getName().equals("strength")) values[i] = .02f + (index % 7) * .12f;
        }
        Constructor<?> constructor = request.getClass().getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static void verify(RenderLevelStageEvent event, List<Light> lights, int phase) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getMainRenderTarget().width, height = mc.getMainRenderTarget().height;
        int[] textures = new int[2], fbos = new int[2];
        FloatBuffer reference = MemoryUtil.memAllocFloat(width * height * 4), actual = MemoryUtil.memAllocFloat(reference.capacity());
        float[] clear = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING), program = 0;
        try (AutoCloseable outer = guard()) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            if (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects)
                for (int i = 0; i <= BATCH; i++) GL33.glBindSampler(i, 0);
            program = referenceProgram();
            Matrix4f camera = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
            for (int mode = 0; mode < 2; mode++) {
                textures[mode] = GL11.glGenTextures();
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[mode]);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, width, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                fbos[mode] = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbos[mode]);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textures[mode], 0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D,
                        mc.getMainRenderTarget().getDepthTextureId(), 0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Comparison framebuffer incomplete");
                GL11.glViewport(0, 0, width, height);
                GL11.glColorMask(true, true, true, true);
                GL11.glClearColor(0, 0, 0, 0);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                if (mode == 0) reference(lights, event, camera, program);
                else {
                    try (AutoCloseable saved = guard()) {
                        Method surfaces = method(GpuLightProjection.class, "surfaces");
                        for (int at = 0; at < lights.size(); at += BATCH) {
                            Object requests = Array.newInstance(lights.getFirst().request.getClass(), BATCH);
                            Object maps = Array.newInstance(lights.getFirst().shadow.getClass(), BATCH);
                            int count = Math.min(BATCH, lights.size() - at);
                            for (int i = 0; i < count; i++) {
                                Array.set(requests, i, lights.get(at + i).request);
                                Array.set(maps, i, lights.get(at + i).shadow);
                            }
                            surfaces.invoke(null, requests, maps, count, saved, camera, event.getCamera().getPosition(), event.getFrustum());
                        }
                    }
                }
                GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, mode == 0 ? reference : actual);
            }
            int bad = 0, lit = 0;
            double max = 0, sum = 0;
            for (int i = 0; i < reference.capacity(); i++) {
                float a = reference.get(i), b = actual.get(i);
                double error = Math.abs(a - b);
                if (a != 0 || b != 0) lit++;
                if (!Float.isFinite(a + b) || error > 1e-4 + Math.abs(a) * 2e-5) bad++;
                max = Math.max(max, error); sum += error;
            }
            int error = GL11.glGetError();
            LogUtils.getLogger().info("GPU_BATCH CHECK {}: view={} lights={} components={} lit={} bad={} max_abs={} mean_abs={} gl_error={} reference=previous_single_light_RGBA32F",
                    bad == 0 && lit > 0 && error == 0 ? "PASS" : "FAIL", phase, lights.size(), reference.capacity(), lit, bad, max, sum / reference.capacity(), error);
        } finally {
            GL11.glClearColor(clear[0], clear[1], clear[2], clear[3]);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            for (int fbo : fbos) if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            for (int texture : textures) if (texture != 0) GL11.glDeleteTextures(texture);
            if (program != 0) GL20.glDeleteProgram(program);
            MemoryUtil.memFree(reference); MemoryUtil.memFree(actual);
        }
    }

    private static void reference(List<Light> lights, RenderLevelStageEvent event, Matrix4f camera, int program) throws Exception {
        GL20.glUseProgram(program);
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LEQUAL); GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND); GL14.glBlendEquation(GL14.GL_FUNC_ADD); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL); GL11.glPolygonOffset(-1, -1);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId());
        GL20.glUniform1i(location(program, "Atlas"), 0); GL20.glUniform1i(location(program, "Shadow"), 1);
        GL20.glUniform1f(location(program, "Near"), .25f);
        Matrix4f view = new Matrix4f();
        var eye = event.getCamera().getPosition();
        for (Light light : lights) {
            Object request = light.request, shadow = light.shadow;
            BlockPos anchor = (BlockPos) get(request, "anchor");
            matrix(program, "LightProjection", (Matrix4f) get(shadow, "projection"));
            for (String[] pair : new String[][]{{"Origin", "origin"}, {"Direction", "direction"}, {"LightRight", "right"}, {"LightUp", "up"}}) {
                Vector3f v = (Vector3f) get(shadow, pair[1]);
                GL20.glUniform3f(location(program, pair[0]), v.x, v.y, v.z);
            }
            int color = (int) get(request, "color");
            GL20.glUniform3f(location(program, "LightColor"), (color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f);
            GL20.glUniform1f(location(program, "Tangent"), ((Number) get(request, "tangent")).floatValue());
            GL20.glUniform1f(location(program, "Range"), (float) get(request, "range"));
            GL20.glUniform1f(location(program, "Strength"), (float) get(request, "strength"));
            GL20.glUniform1f(location(program, "Radius"), (int) get(request, "radius"));
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, (int) get(shadow, "texture"));
            for (GpuLightGeometry.Mesh mesh : ((GpuLightGeometry.Scene) get(request, "scene")).meshes()) {
                if (!mesh.receives() || !event.getFrustum().isVisible(mesh.bounds())) continue;
                BlockPos origin = mesh.origin();
                matrix(program, "ViewProjection", view.set(camera).translate((float) (origin.getX() - eye.x), (float) (origin.getY() - eye.y), (float) (origin.getZ() - eye.z)));
                GL20.glUniform3f(location(program, "SectionOffset"), origin.getX() - anchor.getX(), origin.getY() - anchor.getY(), origin.getZ() - anchor.getZ());
                mesh.draw();
            }
        }
    }

    private static int referenceProgram() throws Exception {
        int program = GL20.glCreateProgram();
        for (String name : new String[]{"projector.vsh", "projector.fsh"}) {
            int shader = GL20.glCreateShader(name.endsWith("vsh") ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            try (var input = Minecraft.getInstance().getResourceManager().open(CreateLargeLights.id("shaders/reference/" + name))) {
                GL20.glShaderSource(shader, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            GL20.glAttachShader(program, shader); GL20.glDeleteShader(shader);
        }
        String[] attributes = {"Position", "UV", "Normal", "Tint", "GeomNormal"};
        for (int i = 0; i < attributes.length; i++) GL20.glBindAttribLocation(program, i, attributes[i]);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
        return program;
    }
    private static int location(int program, String name) { return GL20.glGetUniformLocation(program, name); }
    private static void matrix(int program, String name, Matrix4f value) { GL20.glUniformMatrix4fv(location(program, name), false, value.get(new float[16])); }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static Object get(Object target, String name) throws Exception { return field(target instanceof Class<?> c ? c : target.getClass(), name).get(target instanceof Class<?> ? null : target); }
    private static Method method(Class<?> type, String name) throws Exception {
        for (Method m : type.getDeclaredMethods()) if (m.getName().equals(name)) { m.setAccessible(true); return m; }
        throw new NoSuchMethodException(name);
    }
    private static AutoCloseable guard() throws Exception {
        Constructor<?> c = Class.forName("io.github.jinsh1228.largelights.client.GpuProjectionState").getDeclaredConstructor(int.class);
        c.setAccessible(true); return (AutoCloseable) c.newInstance(BATCH + 1);
    }
    private static void fail(Exception e) { LogUtils.getLogger().error("GPU_BATCH CHECK FAIL: comparison could not complete", e); }
}
