package dev.hurtify.relicsaddon.client;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33.*;

/** Optional GPU regression check: hidden OpenGL context, no Minecraft client or screenshots. */
public final class PostEffectGpuCheck {
    private static final int WIDTH = 96, HEIGHT = 54;
    private static final Path SHADERS = Path.of("src/main/resources/assets/relics_addon/shaders/core");
    private static final Matrix4f PROJECTION = new Matrix4f().perspective((float) Math.toRadians(70), WIDTH / (float) HEIGHT, .05F, 4096);
    private static final Matrix4f INVERSE = new Matrix4f(PROJECTION).invert();
    private static int vertexBuffer;

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new AssertionError("Cannot initialise hidden GLFW context");
        long window = 0;
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "Post-effect regression", 0, 0);
            if (window == 0) throw new AssertionError("Cannot create hidden OpenGL window");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GPU: " + glGetString(GL_RENDERER));
            glBindVertexArray(glGenVertexArrays());
            vertexBuffer = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            int target = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, WIDTH, HEIGHT, 0, GL_RGBA, GL_FLOAT, 0L);
            int framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Incomplete framebuffer");
            glViewport(0, 0, WIDTH, HEIGHT);
            texture(GL_TEXTURE1, 1, 1, new float[]{1, 1, 1, 1});
            float[] scene = new float[WIDTH * HEIGHT * 4];
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                int at = (y * WIDTH + x) * 4;
                scene[at] = x / (float) WIDTH; scene[at + 1] = y / (float) HEIGHT;
                scene[at + 2] = .5F; scene[at + 3] = 1;
            }
            texture(GL_TEXTURE0, WIDTH, HEIGHT, scene);
            String vertex = Files.readString(SHADERS.resolve("armageddon_volume.vsh"));
            int compared = 0;
            for (String name : new String[]{"armageddon_volume", "mana_volume"}) {
                String optimized = Files.readString(SHADERS.resolve(name + ".fsh"));
                String reference = optimized.replace(" && Front - r > -22.0 && Front - r < 84.0", "");
                if (reference.equals(optimized)) throw new AssertionError("Missing radial optimization in " + name);
                int before = program(vertex, reference), after = program(vertex, optimized);
                for (float front : new float[]{10, 80, 135, 256, 500}) {
                    for (Vector3f centre : new Vector3f[]{new Vector3f(0, -10, -150), new Vector3f(80, -25, -250), new Vector3f(0, -1, -5)}) {
                        compare(render(before, front, centre, null), render(after, front, centre, null), name);
                        compared++;
                    }
                }
                glDeleteProgram(before); glDeleteProgram(after);
            }
            int lens = program(vertex, Files.readString(SHADERS.resolve("black_hole.fsh")));
            for (float reach : new float[]{8, 30, 90}) {
                for (Vector3f centre : new Vector3f[]{new Vector3f(0, 0, -150), new Vector3f(80, 0, -150), new Vector3f(300, 0, -150), new Vector3f(0, 0, -5)}) {
                    LensScreenBounds.Bounds bounds = LensScreenBounds.of(centre, reach, PROJECTION, WIDTH, HEIGHT);
                    compare(render(lens, reach, centre, null), render(lens, reach, centre, bounds), "black_hole rectangle");
                    compared++;
                }
            }
            glDeleteProgram(lens);
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("OpenGL error");
            System.out.println("Post-effect GPU check: " + compared + " full/optimized float frame pairs match (tolerance 0.00001)");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void texture(int unit, int width, int height, float[] data) {
        glActiveTexture(unit);
        glBindTexture(GL_TEXTURE_2D, glGenTextures());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, data);
    }

    private static float[] render(int program, float front, Vector3f centre, LensScreenBounds.Bounds bounds) {
        glUseProgram(program);
        glUniformMatrix4fv(glGetUniformLocation(program, "InverseProj"), false, INVERSE.get(new float[16]));
        glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false, PROJECTION.get(new float[16]));
        glUniform2f(glGetUniformLocation(program, "TargetSize"), WIDTH, HEIGHT);
        glUniform2f(glGetUniformLocation(program, "ScreenSize"), WIDTH, HEIGHT);
        glUniform3f(glGetUniformLocation(program, "Centre"), centre.x, centre.y, centre.z);
        glUniform3f(glGetUniformLocation(program, "Up"), 0, 1, 0);
        glUniform3f(glGetUniformLocation(program, "East"), 1, 0, 0);
        glUniform3f(glGetUniformLocation(program, "North"), 0, 0, 1);
        glUniform3f(glGetUniformLocation(program, "Seam"), 1, 0, 0);
        glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
        glUniform1i(glGetUniformLocation(program, "Sampler1"), 1);
        for (String name : new String[]{"Wave", "Glare", "Magenta", "PillarGlow", "Dust", "Vortex", "SphereFog", "SunGlow", "Shock", "DarkCore", "ColumnFog", "GroundHaze"}) scalar(program, name, 1);
        scalar(program, "Time", 150); scalar(program, "Front", front); scalar(program, "Reach", front);
        scalar(program, "Pillar", 25); scalar(program, "Core", 8); scalar(program, "Spheres", 3.5F);
        scalar(program, "SphereRadius", 40); scalar(program, "SunRadius", 12); scalar(program, "SunLift", 6);
        scalar(program, "ShockRadius", 90); scalar(program, "ColumnRadius", 25); scalar(program, "ColumnTop", 400); scalar(program, "SealRadius", 90);
        scalar(program, "Horizon", 3); scalar(program, "Einstein", 10); scalar(program, "Darkness", 1); scalar(program, "Halo", 20);
        scalar(program, "Ring", front * .6F); scalar(program, "RingWidth", 2); scalar(program, "RingStrength", .5F);
        glClearColor(.1F, .2F, .3F, 1);
        glDisable(GL_SCISSOR_TEST);
        glClear(GL_COLOR_BUFFER_BIT);
        if (bounds == null || !bounds.empty()) {
            if (bounds != null) {
                glEnable(GL_SCISSOR_TEST);
                glScissor(bounds.leftPixel(WIDTH), bounds.bottomPixel(HEIGHT),
                        bounds.rightPixel(WIDTH) - bounds.leftPixel(WIDTH), bounds.topPixel(HEIGHT) - bounds.bottomPixel(HEIGHT));
            }
            glBufferData(GL_ARRAY_BUFFER, new float[]{-1,-1,0, 1,-1,0, 1,1,0, -1,-1,0, 1,1,0, -1,1,0}, GL_STREAM_DRAW);
            int position = glGetAttribLocation(program, "Position");
            glEnableVertexAttribArray(position);
            glVertexAttribPointer(position, 3, GL_FLOAT, false, 0, 0L);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glDisableVertexAttribArray(position);
        }
        glDisable(GL_SCISSOR_TEST);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(WIDTH * HEIGHT * 4);
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_FLOAT, pixels);
        float[] output = new float[pixels.remaining()];
        pixels.get(output);
        return output;
    }

    private static void scalar(int program, String name, float value) { glUniform1f(glGetUniformLocation(program, name), value); }

    private static void compare(float[] before, float[] after, String name) {
        for (int i = 0; i < before.length; i++) {
            if (!Float.isFinite(before[i]) || !Float.isFinite(after[i]) || Math.abs(before[i] - after[i]) > .00001F)
                throw new AssertionError(name + " pixel channel " + i + ": " + before[i] + " != " + after[i]);
        }
    }

    private static int program(String vertex, String fragment) {
        int vertexShader = shader(GL_VERTEX_SHADER, vertex), fragmentShader = shader(GL_FRAGMENT_SHADER, fragment);
        int program = glCreateProgram();
        glAttachShader(program, vertexShader); glAttachShader(program, fragmentShader); glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new AssertionError(glGetProgramInfoLog(program));
        glDeleteShader(vertexShader); glDeleteShader(fragmentShader);
        return program;
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new AssertionError(glGetShaderInfoLog(shader));
        return shader;
    }
}
