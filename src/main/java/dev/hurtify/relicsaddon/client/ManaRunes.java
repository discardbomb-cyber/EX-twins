package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hurtify.relicsaddon.RelicsAddon;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The Mana runes (drawn by {@code tools/draw_rune_atlas.mjs}): 28 glyphs of the Mana script and four
 * ornaments in an 8x4 atlas, white with a soft glow, laid into the world as light tinted by the vertex
 * colour, and read by the Mana Armageddon's shaders for its sphere, seal and column. The atlas is loaded
 * with mipmaps of its own, so glyphs far off stay smooth instead of sparkling.
 */
public final class ManaRunes {
    public static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "textures/misc/mana_runes.png");
    public static final int GLYPHS = 28, RINGS = 28, GEAR = 29, CURL = 30, BEAD = 31;
    private static final int COLUMNS = 8, ROWS = 4, MIPMAPS = 4;
    /** Texels kept clear at the edge of each cell when sampling, so no neighbour bleeds in. */
    private static final double INSET = .5 / 512;
    private static final RenderStateShard.ShaderStateShard SHADER = new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader);
    static final RenderType TYPE = RenderType.create("relic_mana_runes", DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.TRIANGLES, 1 << 16, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(ATLAS, true, true))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));
    private static AtlasTexture texture;

    /** Where runes are drawn this frame; flushed after the constructs' light by {@link #flush}. */
    public static VertexConsumer consumer() {
        load();
        return BUFFERS.getBuffer(TYPE);
    }

    public static void flush() {
        BUFFERS.endBatch(TYPE);
    }

    /** The atlas's texture, loaded (with its mipmaps) the first time it is asked for. */
    public static int textureId() {
        load();
        return texture.getId();
    }

    private static void load() {
        if (texture != null) return;
        texture = new AtlasTexture();
        Minecraft.getInstance().getTextureManager().register(ATLAS, texture);
    }

    /** Glyph {@code cell} of the atlas on a quad across {@code right} and {@code up} round {@code centre}, {@code size} blocks tall. */
    public static void glyph(VertexConsumer c, Matrix4f m, Vec3 centre, Vec3 right, Vec3 up, double size, int cell, int color, double alpha) {
        glyph(c, m, centre, right, up, size, cell, color, alpha, 1);
    }

    /**
     * As above, only as far as {@code written} (0..1) across it from its left edge: a glyph being written, the
     * rest still bare.
     */
    public static void glyph(VertexConsumer c, Matrix4f m, Vec3 centre, Vec3 right, Vec3 up, double size, int cell, int color, double alpha, double written) {
        if (alpha < 1 || written <= 0) return;
        written = Math.min(1, written);
        int column = Math.floorMod(cell, COLUMNS * ROWS) % COLUMNS, row = Math.floorMod(cell, COLUMNS * ROWS) / COLUMNS;
        double u0 = (double) column / COLUMNS + INSET, u1 = (column + 1.0) / COLUMNS - INSET, v0 = (double) row / ROWS + INSET * 2, v1 = (row + 1.0) / ROWS - INSET * 2;
        double half = size / 2, reach = -half + size * written;
        Vec3 bottomLeft = centre.subtract(right.scale(half)).subtract(up.scale(half)), topLeft = centre.subtract(right.scale(half)).add(up.scale(half));
        Vec3 bottomRight = centre.add(right.scale(reach)).subtract(up.scale(half)), topRight = centre.add(right.scale(reach)).add(up.scale(half));
        float ur = (float) (u0 + (u1 - u0) * written);
        int a = (int) Math.clamp(alpha, 0, 255), r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        vertex(c, m, bottomLeft, (float) u0, (float) v1, r, g, b, a);
        vertex(c, m, bottomRight, ur, (float) v1, r, g, b, a);
        vertex(c, m, topRight, ur, (float) v0, r, g, b, a);
        vertex(c, m, bottomLeft, (float) u0, (float) v1, r, g, b, a);
        vertex(c, m, topRight, ur, (float) v0, r, g, b, a);
        vertex(c, m, topLeft, (float) u0, (float) v0, r, g, b, a);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, float u, float v, int r, int g, int b, int a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setUv(u, v).setColor(r, g, b, a);
    }

    /** A stable choice of glyph for rune {@code index} of set {@code set}. */
    public static int pick(int set, int index) {
        long h = (set * 0x9E3779B97F4A7C15L) ^ (index * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (int) Math.floorMod(h, (long) GLYPHS);
    }

    /** The atlas, uploaded with mipmaps averaged down from it. */
    private static final class AtlasTexture extends AbstractTexture {
        @Override
        public void load(ResourceManager resources) throws IOException {
            NativeImage image;
            try (InputStream in = resources.open(ATLAS)) {
                image = NativeImage.read(in);
            }
            NativeImage[] levels = new NativeImage[MIPMAPS + 1];
            levels[0] = image;
            for (int level = 1; level <= MIPMAPS; level++) levels[level] = half(levels[level - 1]);
            Runnable upload = () -> {
                TextureUtil.prepareImage(getId(), MIPMAPS, image.getWidth(), image.getHeight());
                for (int level = 0; level <= MIPMAPS; level++) {
                    levels[level].upload(level, 0, 0, 0, 0, levels[level].getWidth(), levels[level].getHeight(), true, true, true, true);
                }
            };
            if (RenderSystem.isOnRenderThreadOrInit()) upload.run();
            else RenderSystem.recordRenderCall(upload::run);
        }

        /** Half the size, each texel the average of the four under it. */
        private static NativeImage half(NativeImage image) {
            int width = Math.max(1, image.getWidth() / 2), height = Math.max(1, image.getHeight() / 2);
            NativeImage smaller = new NativeImage(width, height, false);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int[] sum = new int[4];
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                    int pixel = image.getPixelRGBA(Math.min(image.getWidth() - 1, x * 2 + dx), Math.min(image.getHeight() - 1, y * 2 + dy));
                    for (int channel = 0; channel < 4; channel++) sum[channel] += pixel >>> channel * 8 & 255;
                }
                int averaged = 0;
                for (int channel = 0; channel < 4; channel++) averaged |= (sum[channel] + 2) / 4 << channel * 8;
                smaller.setPixelRGBA(x, y, averaged);
            }
            return smaller;
        }
    }

    private ManaRunes() {
    }
}
