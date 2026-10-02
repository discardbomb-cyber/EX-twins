package dev.hurtify.relicsaddon.client.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hurtify.relicsaddon.RelicsAddon;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Client-side mesh/pose adapter for the same numeric clips used by the Blockbench source. */
public final class RfWorkbenchModel {
    private static final ResourceLocation RESOURCE = ResourceLocation.fromNamespaceAndPath(
            RelicsAddon.MOD_ID, "workbench/rf_workbench.json");
    private static final int[] PALETTE = {0x101820, 0x20313E, 0x536674, 0x142835, 0x087E96, 0x38E8FF, 0xD8FCFF, 0xFFB347};
    private static final RenderType SOLID = type("rf_workbench_solid", false);
    private static final RenderType GLASS = type("rf_workbench_glass", true);
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 20));
    private static RfWorkbenchModel cached;
    private final List<Bone> roots = new ArrayList<>();
    private final Map<String, Bone> bones = new LinkedHashMap<>();
    private final Map<String, Bone> byName = new HashMap<>();
    private final List<Mesh> meshes = new ArrayList<>();
    private final Map<String, Clip> clips = new HashMap<>();

    private record Key(float time, Vector3f value) { }
    private record Clip(float length, boolean loop, Map<String, Map<String, List<Key>>> tracks) { }
    private record Face(int[] indices) { }
    private record Mesh(Bone bone, Vector3f[] vertices, List<Face> faces, int color, float opacity, boolean emissive) { }
    private static final class Bone {
        final String uuid, name;
        final Vector3f origin;
        final List<Bone> children = new ArrayList<>();
        Bone(String uuid, String name, Vector3f origin) { this.uuid = uuid; this.name = name; this.origin = origin; }
    }
    public record Pose(Map<String, Matrix4f> transforms, String clip, float time) { }

    public static RfWorkbenchModel get() throws IOException {
        if (cached == null) {
            try (var reader = Minecraft.getInstance().getResourceManager().openAsReader(RESOURCE)) {
                cached = new RfWorkbenchModel(JsonParser.parseReader(reader).getAsJsonObject());
            }
        }
        return cached;
    }

    public static void clear() { cached = null; }

    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> clear());
    }

    private RfWorkbenchModel(JsonObject data) {
        Map<String, Bone> owners = new HashMap<>();
        parseBones(data.getAsJsonArray("outliner"), null, owners);
        for (JsonElement element : data.getAsJsonArray("elements")) {
            JsonObject raw = element.getAsJsonObject();
            if (!"mesh".equals(raw.get("type").getAsString())) throw new IllegalArgumentException("Workbench requires mesh elements");
            var vertices = raw.getAsJsonObject("vertices");
            Map<String, Integer> indices = new HashMap<>();
            List<Vector3f> points = new ArrayList<>();
            for (var entry : vertices.entrySet()) { indices.put(entry.getKey(), points.size()); points.add(vector(entry.getValue().getAsJsonArray())); }
            List<Face> faces = new ArrayList<>();
            for (var entry : raw.getAsJsonObject("faces").entrySet()) {
                var ids = entry.getValue().getAsJsonObject().getAsJsonArray("vertices");
                int[] face = new int[ids.size()];
                for (int i = 0; i < ids.size(); i++) face[i] = indices.get(ids.get(i).getAsString());
                faces.add(new Face(face));
            }
            int band = raw.get("color").getAsInt();
            float opacity = raw.has("opacity") ? raw.get("opacity").getAsFloat() : 1;
            String name = raw.get("name").getAsString();
            boolean emissive = (band >= 4 && band <= 6) && !name.startsWith("terminal_inset");
            meshes.add(new Mesh(owners.get(raw.get("uuid").getAsString()), points.toArray(Vector3f[]::new), faces,
                    PALETTE[band], opacity, emissive));
        }
        for (JsonElement element : data.getAsJsonArray("animations")) {
            var raw = element.getAsJsonObject();
            Map<String, Map<String, List<Key>>> tracks = new HashMap<>();
            for (var entry : raw.getAsJsonObject("animators").entrySet()) {
                Map<String, List<Key>> channels = new HashMap<>();
                for (var item : entry.getValue().getAsJsonObject().getAsJsonArray("keyframes")) {
                    var key = item.getAsJsonObject();
                    var point = key.getAsJsonArray("data_points").get(0).getAsJsonObject();
                    var value = new Vector3f(point.get("x").getAsFloat(), point.get("y").getAsFloat(), point.get("z").getAsFloat());
                    channels.computeIfAbsent(key.get("channel").getAsString(), ignored -> new ArrayList<>())
                            .add(new Key(key.get("time").getAsFloat(), value));
                }
                channels.values().forEach(keys -> keys.sort(Comparator.comparingDouble(Key::time)));
                tracks.put(entry.getKey(), channels);
            }
            clips.put(raw.get("name").getAsString(), new Clip(raw.get("length").getAsFloat(),
                    "loop".equals(raw.get("loop").getAsString()), tracks));
        }
    }

    private void parseBones(JsonArray nodes, Bone parent, Map<String, Bone> owners) {
        for (var element : nodes) {
            if (element.isJsonPrimitive()) { owners.put(element.getAsString(), parent); continue; }
            var raw = element.getAsJsonObject();
            var bone = new Bone(raw.get("uuid").getAsString(), raw.get("name").getAsString(), vector(raw.getAsJsonArray("origin")));
            bones.put(bone.uuid, bone); byName.put(bone.name, bone);
            if (parent == null) roots.add(bone); else parent.children.add(bone);
            parseBones(raw.getAsJsonArray("children"), bone, owners);
        }
    }

    public Pose pose(String name, float seconds) {
        Clip clip = clips.get(name);
        if (clip == null) throw new IllegalArgumentException("Unknown workbench clip " + name);
        float time = clip.loop ? Math.max(0, seconds) % clip.length : Math.max(0, Math.min(seconds, clip.length));
        Map<String, Matrix4f> transforms = new HashMap<>();
        for (var root : roots) transform(root, new Matrix4f(), clip, time, transforms);
        return new Pose(transforms, name, time);
    }

    private void transform(Bone bone, Matrix4f parent, Clip clip, float time, Map<String, Matrix4f> transforms) {
        var channels = clip.tracks.getOrDefault(bone.uuid, Map.of());
        Vector3f position = sample(channels.get("position"), time, false);
        Vector3f rotation = sample(channels.get("rotation"), time, false).mul((float) (Math.PI / 180));
        Vector3f scale = sample(channels.get("scale"), time, true);
        var o = bone.origin;
        // Vertices and bone origins are absolute in bbmodel; apply child transform before parent.
        Matrix4f matrix = new Matrix4f(parent).translate(o.x + position.x, o.y + position.y, o.z + position.z)
                .rotateZ(rotation.z).rotateY(rotation.y).rotateX(rotation.x).scale(scale).translate(-o.x, -o.y, -o.z);
        transforms.put(bone.uuid, matrix);
        for (var child : bone.children) transform(child, matrix, clip, time, transforms);
    }

    private static Vector3f sample(List<Key> keys, float time, boolean scale) {
        if (keys == null || keys.isEmpty()) return new Vector3f(scale ? 1 : 0);
        Key left = keys.getFirst();
        for (var right : keys) {
            if (right.time >= time) {
                float t = right.time <= left.time ? 0 : Math.max(0, Math.min(1, (time - left.time) / (right.time - left.time)));
                return new Vector3f(left.value).lerp(right.value, t);
            }
            left = right;
        }
        return new Vector3f(left.value);
    }

    public Vec3 point(Pose pose, String boneName, Vec3 origin) {
        Bone bone = byName.get(boneName);
        if (bone == null) throw new IllegalArgumentException("Unknown bone " + boneName);
        Vector3f result = pose.transforms.get(bone.uuid).transformPosition(new Vector3f(bone.origin));
        return origin.add(result.x / 16, result.y / 16, result.z / 16);
    }

    /** Must run before Photon's AFTER_BLOCK_ENTITIES depth capture. */
    public void renderOpaque(Pose pose, PoseStack stack, Vec3 origin, Vec3 camera) {
        renderPass(pose, stack, origin, camera, false);
    }

    public void renderGlass(Pose pose, PoseStack stack, Vec3 origin, Vec3 camera) {
        renderPass(pose, stack, origin, camera, true);
    }

    private void renderPass(Pose pose, PoseStack stack, Vec3 origin, Vec3 camera, boolean glassPass) {
        stack.pushPose();
        Vec3 relative = origin.subtract(camera);
        stack.translate(relative.x, relative.y, relative.z);
        stack.scale(1f / 16, 1f / 16, 1f / 16);
        Matrix4f matrix = stack.last().pose();
        RenderType type = glassPass ? GLASS : SOLID;
        VertexConsumer consumer = BUFFERS.getBuffer(type);
        for (var mesh : meshes) {
            if ((mesh.opacity < 1) == glassPass) draw(mesh, pose, matrix, consumer);
        }
        BUFFERS.endBatch(type);
        stack.popPose();
    }

    private static void draw(Mesh mesh, Pose pose, Matrix4f view, VertexConsumer consumer) {
        Matrix4f transform = pose.transforms.get(mesh.bone.uuid);
        // A hidden scaled group contributes no triangles, avoiding degenerate depth writes.
        if (Math.abs(transform.determinant()) < 1e-7f) return;
        Vector3f[] points = new Vector3f[mesh.vertices.length];
        for (int i = 0; i < points.length; i++) points[i] = transform.transformPosition(new Vector3f(mesh.vertices[i]));
        for (Face face : mesh.faces) {
            int[] ids = face.indices;
            if (ids.length < 3) continue;
            Vector3f a = points[ids[0]], b = points[ids[1]], c = points[ids[2]];
            Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            float shade = 1;
            if (!mesh.emissive && normal.lengthSquared() > 1e-12f) {
                normal.normalize(); shade = .72f + .38f * Math.abs(normal.y * .8f + normal.x * -.4f + normal.z * -.2f);
            }
            int r = Math.min(255, Math.round(((mesh.color >>> 16) & 255) * shade));
            int g = Math.min(255, Math.round(((mesh.color >>> 8) & 255) * shade));
            int blue = Math.min(255, Math.round((mesh.color & 255) * shade));
            int alpha = Math.round(mesh.opacity * 255);
            for (int i = 1; i + 1 < ids.length; i++) {
                vertex(consumer, view, a, r, g, blue, alpha);
                vertex(consumer, view, points[ids[i]], r, g, blue, alpha);
                vertex(consumer, view, points[ids[i + 1]], r, g, blue, alpha);
            }
        }
    }

    private static void vertex(VertexConsumer c, Matrix4f matrix, Vector3f p, int r, int g, int b, int a) {
        c.addVertex(matrix, p.x, p.y, p.z).setColor(r, g, b, a);
    }

    private static Vector3f vector(JsonArray values) {
        return new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat());
    }

    private static RenderType type(String name, boolean glass) {
        return RenderType.create(name, DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, 1 << 20, false, glass,
                RenderType.CompositeState.builder().setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setTransparencyState(glass ? RenderStateShard.TRANSLUCENT_TRANSPARENCY : RenderStateShard.NO_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(glass ? RenderStateShard.COLOR_WRITE : RenderStateShard.COLOR_DEPTH_WRITE)
                        .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));
    }
}
