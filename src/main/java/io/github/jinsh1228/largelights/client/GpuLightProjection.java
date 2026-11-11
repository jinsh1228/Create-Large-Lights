package io.github.jinsh1228.largelights.client;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

import com.mojang.logging.LogUtils;

import io.github.jinsh1228.largelights.CreateLargeLights;
import io.github.jinsh1228.largelights.FloodlightBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Shared terrain meshes and cached, per-light shadow maps; colour and intensity remain current every frame. */
public final class GpuLightProjection {
    private static final int SHADOW_SIZE = 1024;
    static final int LIGHT_BATCH = 4;
    private static final int CACHED_SHADOWS = 128;
    private static final int FORGET_FRAMES = 600;
    private static final float NEAR = .25f;
    private static final Map<Long, Request> queued = new LinkedHashMap<>();
    private static final Map<Long, Shadow> shadows = new LinkedHashMap<>(16, .75f, true);
    private static ClientLevel knownLevel;
    private static Program depth, surface;
    private static boolean unavailable;
    private static boolean requested;
    private static long frame, shadowUpdates, surfaceDraws;
    private static int lastLights;

    private record Request(BlockPos anchor, Vec3 origin, Vec3 direction, Vec3 up, double tangent,
                           float range, float strength, int color, int radius, GpuLightGeometry.Scene scene) {
    }

    private static final class Shadow {
        final int texture, framebuffer;
        Request pose;
        long lastFrame;
        final Matrix4f projection = new Matrix4f();
        final Vector3f origin = new Vector3f();
        final Vector3f direction = new Vector3f();
        final Vector3f right = new Vector3f();
        final Vector3f up = new Vector3f();

        Shadow() {
            texture = GL11.glGenTextures();
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, SHADOW_SIZE, SHADOW_SIZE, 0,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (FloatBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                close();
                throw new IllegalStateException("Incomplete floodlight shadow framebuffer");
            }
        }

        boolean matches(Request request) {
            return pose != null && pose.anchor.equals(request.anchor)
                    && pose.origin.equals(request.origin) && pose.direction.equals(request.direction)
                    && pose.up.equals(request.up) && pose.tangent == request.tangent && pose.range == request.range
                    && pose.radius == request.radius && pose.scene.revision() == request.scene.revision();
        }

        void position(Request request) {
            origin.set((float) request.origin.x, (float) request.origin.y, (float) request.origin.z);
            direction.set((float) request.direction.x, (float) request.direction.y, (float) request.direction.z);
            up.set((float) request.up.x, (float) request.up.y, (float) request.up.z);
            right.set(direction).cross(up).normalize();
            up.set(right).cross(direction).normalize();
            Matrix4f view = new Matrix4f().lookAt(origin, new Vector3f(origin).add(direction), up);
            // Reuse the actual projection basis: lookAt's subtraction/normalization can change a few float bits.
            right.set(view.m00(), view.m10(), view.m20());
            up.set(view.m01(), view.m11(), view.m21());
            direction.set(-view.m02(), -view.m12(), -view.m22());
            projection.identity().perspective((float) (2 * Math.atan(request.tangent)), 1, NEAR, request.range).mul(view);
            pose = request;
        }

        void close() {
            GL30.glDeleteFramebuffers(framebuffer);
            GL11.glDeleteTextures(texture);
        }
    }

    /** False retains CMS rendering for incompatible worlds and unsupported geometry. */
    public static boolean queue(FloodlightBlock.Entity lamp, Vec3 origin, Vec3 direction, Vec3 up,
                                double halfAngle, float range, float strength, int color, int radius) {
        if (unavailable || !GpuProjectionLifecycle.allow(lamp.getLevel(), lamp.getBlockPos())
                || !(range > NEAR) || !(strength > 0) || !(halfAngle > 0) || halfAngle >= Math.PI / 2) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != knownLevel) return false;
        BlockPos anchor = lamp.getBlockPos();
        Vec3 worldOrigin = origin.add(anchor.getX(), anchor.getY(), anchor.getZ());
        direction = direction.normalize();
        double tangent = Math.tan(halfAngle);
        double width = Math.min(radius, Math.max(.08, range * tangent));
        Vec3 end = worldOrigin.add(direction.scale(range));
        AABB bounds = new AABB(worldOrigin, end).inflate(width);
        if (!GpuProjectionLifecycle.staticGeometry(mc.level, bounds)) return false;
        requested = true;
        try {
            GpuLightGeometry.Scene scene = GpuLightGeometry.select(mc.level, bounds, worldOrigin, direction, tangent, range, width);
            if (!scene.ready()) return scene.pending();
            if (depth == null) {
                try (GpuProjectionState ignored = new GpuProjectionState()) {
                    depth = new Program("depth.fsh");
                    surface = new Program("projector.fsh");
                }
                LogUtils.getLogger().info("GPU floodlight projection ready: shadow={} cached_maps={} max_depth_bytes={} light_batch={}",
                        SHADOW_SIZE, CACHED_SHADOWS, (long) CACHED_SHADOWS * SHADOW_SIZE * SHADOW_SIZE * 4, LIGHT_BATCH);
            }
            queued.put(anchor.asLong(), new Request(anchor.immutable(), origin, direction, up.normalize(), tangent,
                    range, strength, color, radius, scene));
            return true;
        } catch (RuntimeException error) {
            disable(error);
            return false;
        }
    }

    /** Called before block-entity rendering, so prepared meshes are stable until this frame is drawn. */
    public static void beginFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != knownLevel) {
            clear();
            knownLevel = mc.level;
        }
        frame++;
        queued.clear();
        lastLights = 0;
        boolean work = requested || !shadows.isEmpty();
        requested = false;
        if (knownLevel == null || unavailable || !work) return;
        try (GpuProjectionState ignored = new GpuProjectionState()) {
            shadows.values().removeIf(shadow -> {
                if (frame - shadow.lastFrame <= FORGET_FRAMES) return false;
                shadow.close();
                return true;
            });
            GpuLightGeometry.rebuild(frame);
        } catch (RuntimeException error) {
            disable(error);
        }
    }

    /** Runs before CMS flushes its lens and haze buffers, on the main world's terrain framebuffer. */
    public static void render(RenderLevelStageEvent event) {
        if (queued.isEmpty() || unavailable) return;
        queued.values().removeIf(request -> !visible(request, event.getFrustum()));
        if (queued.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Set<Long> pinned = queued.size() > CACHED_SHADOWS ? new HashSet<>() : Set.of();
        if (queued.size() > CACHED_SHADOWS) {
            for (long id : queued.keySet()) {
                if (pinned.size() == CACHED_SHADOWS - 1) break;
                pinned.add(id);
            }
        }
        Shadow scratch = null;
        long scratchId = 0;
        try (GpuProjectionState saved = new GpuProjectionState(LIGHT_BATCH + 1)) {
            if (GpuProjectionState.samplerObjects()) {
                for (int i = 0; i <= LIGHT_BATCH; i++) GL33.glBindSampler(i, 0);
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            Vec3 camera = event.getCamera().getPosition();
            Matrix4f cameraProjection = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
            if (!pinned.isEmpty()) {
                for (long id : shadows.keySet()) {
                    if (!pinned.contains(id)) {
                        scratch = shadows.remove(id);
                        break;
                    }
                }
            }
            Request[] requests = new Request[LIGHT_BATCH];
            Shadow[] maps = new Shadow[LIGHT_BATCH];
            int count = 0;
            for (Map.Entry<Long, Request> entry : queued.entrySet()) {
                Request request = entry.getValue();
                boolean transientMap = !pinned.isEmpty() && !pinned.contains(entry.getKey());
                if (transientMap && count != 0) {
                    surfaces(requests, maps, count, saved, cameraProjection, camera, event.getFrustum());
                    count = 0;
                }
                Shadow shadow;
                if (!transientMap) {
                    shadow = shadow(entry.getKey(), pinned);
                } else {
                    if (scratch == null) scratch = new Shadow();
                    shadow = scratch;
                    shadow.lastFrame = frame;
                    scratchId = entry.getKey();
                }
                if (!shadow.matches(request)) {
                    shadow.position(request);
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId());
                    shadow(request, shadow);
                }
                requests[count] = request;
                maps[count++] = shadow;
                if (count == LIGHT_BATCH || transientMap) {
                    surfaces(requests, maps, count, saved, cameraProjection, camera, event.getFrustum());
                    count = 0;
                }
            }
            if (count != 0) surfaces(requests, maps, count, saved, cameraProjection, camera, event.getFrustum());
            if (scratch != null) {
                shadows.put(scratchId, scratch);
                scratch = null;
            }
        } catch (RuntimeException error) {
            if (scratch != null) scratch.close();
            disable(error);
        } finally {
            queued.clear();
        }
    }

    /** Rasterize shared receiver geometry once for up to four independent lights. */
    private static void surfaces(Request[] requests, Shadow[] maps, int count, GpuProjectionState saved,
                                 Matrix4f cameraProjection, Vec3 camera, Frustum frustum) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, saved.framebuffer);
        GL11.glViewport(saved.viewport[0], saved.viewport[1], saved.viewport[2], saved.viewport[3]);
        GL11.glColorMask(true, true, true, true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1, -1);
        GL20.glUseProgram(surface.id);
        GL20.glUniform1f(surface.near, NEAR);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId());
        Map<GpuLightGeometry.Mesh, Integer> meshes = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Request request = requests[i];
            Shadow shadow = maps[i];
            LightUniforms uniforms = surface.lights[i];
            matrix(uniforms.projection, shadow.projection);
            vector(uniforms.origin, shadow.origin);
            vector(uniforms.direction, shadow.direction);
            vector(uniforms.right, shadow.right);
            vector(uniforms.up, shadow.up);
            GL20.glUniform3f(uniforms.color, (request.color >> 16 & 255) / 255f,
                    (request.color >> 8 & 255) / 255f, (request.color & 255) / 255f);
            GL20.glUniform1f(uniforms.tangent, (float) request.tangent);
            GL20.glUniform1f(uniforms.range, request.range);
            GL20.glUniform1f(uniforms.strength, request.strength);
            GL20.glUniform1f(uniforms.radius, request.radius);
            GL13.glActiveTexture(GL13.GL_TEXTURE1 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadow.texture);
            for (GpuLightGeometry.Mesh mesh : request.scene.meshes()) {
                if (mesh.receives() && frustum.isVisible(mesh.bounds())) meshes.merge(mesh, 1 << i, (a, b) -> a | b);
            }
        }
        Matrix4f view = new Matrix4f();
        for (var entry : meshes.entrySet()) {
            GpuLightGeometry.Mesh mesh = entry.getKey();
            BlockPos origin = mesh.origin();
            matrix(surface.viewProjection, view.set(cameraProjection).translate((float) (origin.getX() - camera.x),
                    (float) (origin.getY() - camera.y), (float) (origin.getZ() - camera.z)));
            GL20.glUniform1i(surface.activeMask, entry.getValue());
            for (int i = 0; i < count; i++) {
                BlockPos anchor = requests[i].anchor;
                GL20.glUniform3f(surface.lights[i].offset, origin.getX() - anchor.getX(),
                        origin.getY() - anchor.getY(), origin.getZ() - anchor.getZ());
            }
            mesh.draw();
            surfaceDraws++;
        }
        lastLights += count;
    }

    private static Shadow shadow(long id, Set<Long> pinned) {
        Shadow shadow = shadows.get(id);
        if (shadow == null) {
            if (shadows.size() >= (pinned.isEmpty() ? CACHED_SHADOWS : CACHED_SHADOWS - 1)) {
                long oldest = shadows.keySet().stream().filter(key -> !pinned.contains(key)).findFirst().orElseThrow();
                shadow = shadows.remove(oldest);
                shadow.pose = null;
            } else {
                shadow = new Shadow();
            }
            shadows.put(id, shadow);
        }
        shadow.lastFrame = frame;
        return shadow;
    }

    private static boolean visible(Request request, Frustum frustum) {
        for (GpuLightGeometry.Mesh mesh : request.scene.meshes()) {
            if (mesh.receives() && frustum.isVisible(mesh.bounds())) return true;
        }
        return false;
    }

    private static void shadow(Request request, Shadow shadow) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, shadow.framebuffer);
        GL11.glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_GREATER);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColorMask(false, false, false, false);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glClearDepth(0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL20.glUseProgram(depth.id);
        matrix(depth.viewProjection, shadow.projection);
        matrix(depth.lightProjection, shadow.projection);
        vector(depth.origin, shadow.origin);
        vector(depth.direction, shadow.direction);
        vector(depth.right, shadow.right);
        vector(depth.up, shadow.up);
        GL20.glUniform1f(depth.tangent, (float) request.tangent);
        GL20.glUniform1f(depth.shadowSize, SHADOW_SIZE);
        GL20.glUniform1f(depth.near, NEAR);
        drawShadow(depth, request);
        shadowUpdates++;
    }

    private static void drawShadow(Program program, Request request) {
        for (GpuLightGeometry.Mesh mesh : request.scene.meshes()) {
            GL20.glUniform1i(program.opaqueGeometry, mesh.receives() ? 0 : 1);
            BlockPos origin = mesh.origin();
            GL20.glUniform3f(program.sectionOffset, origin.getX() - request.anchor.getX(),
                    origin.getY() - request.anchor.getY(), origin.getZ() - request.anchor.getZ());
            mesh.draw(request.anchor);
        }
    }

    private static final class Program {
        final int id, viewProjection, lightProjection, sectionOffset, origin, direction, right, up,
                tangent, near, opaqueGeometry, shadowSize, activeMask;
        final LightUniforms[] lights;

        Program(String fragment) {
            boolean batch = fragment.equals("projector.fsh");
            int vertex = shader(GL20.GL_VERTEX_SHADER, "projector.vsh", batch);
            int pixel = 0;
            id = GL20.glCreateProgram();
            try {
                pixel = shader(GL20.GL_FRAGMENT_SHADER, fragment, batch);
                GL20.glAttachShader(id, vertex);
                GL20.glAttachShader(id, pixel);
                String[] attributes = {"Position", "UV", "Normal", "Tint", "GeomNormal"};
                for (int i = 0; i < attributes.length; i++) GL20.glBindAttribLocation(id, i, attributes[i]);
                GL20.glLinkProgram(id);
                if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == 0) {
                    throw new IllegalStateException(GL20.glGetProgramInfoLog(id));
                }
            } catch (RuntimeException error) {
                GL20.glDeleteProgram(id);
                throw error;
            } finally {
                GL20.glDeleteShader(vertex);
                if (pixel != 0) GL20.glDeleteShader(pixel);
            }
            viewProjection = location("ViewProjection"); lightProjection = location("LightProjection");
            sectionOffset = location("SectionOffset"); origin = location("Origin"); direction = location("Direction");
            right = location("LightRight"); up = location("LightUp"); tangent = location("Tangent");
            near = location("Near"); opaqueGeometry = location("OpaqueGeometry");
            shadowSize = location("ShadowSize");
            activeMask = location("ActiveMask");
            lights = batch ? new LightUniforms[LIGHT_BATCH] : new LightUniforms[0];
            GL20.glUseProgram(id);
            GL20.glUniform1i(location("Atlas"), 0);
            if (batch) {
                for (int i = 0; i < LIGHT_BATCH; i++) {
                    lights[i] = new LightUniforms(this, i);
                    GL20.glUniform1i(location("Shadow" + i), 1 + i);
                }
            }
        }

        int location(String name) {
            return GL20.glGetUniformLocation(id, name);
        }
    }

    private static final class LightUniforms {
        final int projection, offset, origin, direction, right, up, color, tangent, range, strength, radius;
        LightUniforms(Program program, int index) {
            String at = "[" + index + "]";
            projection = program.location("LightProjections" + at); offset = program.location("SectionOffsets" + at);
            origin = program.location("Origins" + at); direction = program.location("Directions" + at);
            right = program.location("LightRights" + at); up = program.location("LightUps" + at);
            color = program.location("LightColors" + at); tangent = program.location("Tangents" + at);
            range = program.location("Ranges" + at); strength = program.location("Strengths" + at); radius = program.location("Radii" + at);
        }
    }

    private static int shader(int type, String name, boolean batch) {
        var resource = CreateLargeLights.id("shaders/projection/" + name);
        int shader = GL20.glCreateShader(type);
        try (var input = Minecraft.getInstance().getResourceManager().open(resource)) {
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (batch) source = source.replace("#version 150", "#version 150\n#define LIGHT_BATCH " + LIGHT_BATCH);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            }
            return shader;
        } catch (IOException | RuntimeException error) {
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Cannot load floodlight shader " + resource, error);
        }
    }

    private static void matrix(int location, Matrix4f value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(location, false, value.get(stack.mallocFloat(16)));
        }
    }

    private static void vector(int location, Vector3f value) {
        GL20.glUniform3f(location, value.x, value.y, value.z);
    }

    private static void disable(RuntimeException error) {
        ClientLevel level = knownLevel;
        clear();
        knownLevel = level;
        unavailable = true;
        LogUtils.getLogger().error("GPU floodlight projection failed; retaining CMS rendering until resources reload", error);
    }

    public static void clear() {
        queued.clear();
        shadows.values().forEach(Shadow::close);
        shadows.clear();
        if (depth != null) GL20.glDeleteProgram(depth.id);
        if (surface != null) GL20.glDeleteProgram(surface.id);
        depth = surface = null;
        knownLevel = null;
        unavailable = false;
        requested = false;
        shadowUpdates = surfaceDraws = 0;
        lastLights = 0;
    }

    public record Metrics(int drawnLights, int cachedShadows, long shadowUpdates, long surfaceDraws, long depthBytes) {
    }

    public static Metrics metrics() {
        return new Metrics(lastLights, shadows.size(), shadowUpdates, surfaceDraws,
                (long) shadows.size() * SHADOW_SIZE * SHADOW_SIZE * 4);
    }
}
