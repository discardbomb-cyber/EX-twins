package dev.hurtify.relicsaddon.client.fx;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction3;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Circle;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Dot;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting;
import com.mojang.blaze3d.platform.GlStateManager;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * Code-built default effects. Emitter names double as the hooks {@link ExFx} tweaks per instance
 * (lengths, lifetimes, radii), so an artist override in {@code assets/relics_addon/fx/} that reuses
 * the same names keeps those behaviours. Burst-only emitters live for two ticks; their particles drain.
 */
final class ExFxLibrary {
    static final int LIGHTNING_SEGMENTS = 4;
    private static final ResourceLocation TAIL = ResourceLocation.fromNamespaceAndPath("photon", "textures/particle/kila_tail.png");
    private static final ResourceLocation RING = ResourceLocation.fromNamespaceAndPath("photon", "textures/particle/ring.png");

    static FX shieldAbsorb(int color) {
        return fx(
                streaks(glow("sparks", color, 16, circle(.06f, 1), 3, 7, 6, 12, .03f, .06f, .95f), 12, .82f),
                flash("flash", color, .55f, 4),
                shrink(glow("glints", color, 5, sphere(.18f, 1), .1f, .4f, 10, 16, .03f, .05f, .9f), 0, 1, 0));
    }

    static FX shieldCellBreak(int color) {
        var shards = streaks(glow("shards", color, 18, cone(50, .12f), 4, 9, 14, 24, .05f, .11f, 1), 6, .95f);
        shards.config.physics.setGravity(NumberFunction.constant(.5f));
        return fx(shards,
                flash("flash", color, .8f, 5),
                shrink(glow("dust", color, 8, sphere(.25f, 1), .3f, 1.2f, 18, 30, .1f, .2f, .45f), .5f, 1.4f));
    }

    /** Unit-radius shapes; ExFx scales them to the shield radius per instance. */
    static FX shieldCollapse(int color) {
        var nova = flash("nova", color, 2, 7);
        nova.config.renderer.getMaterials().set(0, additive(new MaterialSetting(new TextureMaterial(RING))));
        shrink(nova, .3f, 1.25f);
        return fx(
                streaks(glow("shell", color, 72, sphere(1, 0), 3, 5, 12, 18, .08f, .16f, .95f), 8, .9f),
                drag(glow("ring", color, 48, circle(1, 0), 6, 8, 10, 14, .1f, .14f, 1), .88f),
                shrink(glow("dust", color, 40, sphere(1, 0), -1, -2, 16, 24, .12f, .25f, .5f), .6f, 1.2f, .2f),
                flash("flash", color, 2, 6),
                nova);
    }

    /** RF instant beam: a unit-length box along local +Y, stretched to the shot length per instance. */
    static FX hiveBeam(int color) {
        var core = glow("core", color, 48, new Box(), -.2f, .2f, 2, 3, .1f, .14f, 1);
        core.config.colorOverLifetime.setColor(fade(color, 1, .65f));
        return fx(glow("beam", color, 96, new Box(), -.4f, .4f, 3, 6, .05f, .09f, .9f), core);
    }

    /** Travelling bolt: a head with a ribbon trail plus world-space sparkles left behind. */
    static FX hiveBolt(int color) {
        var head = glow("head", color, 1, new Dot(), 0, 0, 20, 20, .3f, .3f, 1);
        head.config.setMaxParticles(2);
        head.config.colorOverLifetime.setColor(hold(color));
        var trails = head.config.trails;
        trails.setEnable(true);
        trails.setLifetime(NumberFunction.constant(.25f));
        trails.setInheritParticleColor(false);
        trails.setColorOverLifetime(NumberFunction.color(-1));
        trails.config.setWidthOverTrail(curve(0, .6f, 1));
        trails.config.setColorOverTrail(trail(color));
        trails.config.renderer.getMaterials().set(0, additive(new MaterialSetting(new TextureMaterial(TAIL))));
        var halo = glow("halo", color, 1, new Dot(), 0, 0, 20, 20, .75f, .75f, .35f);
        halo.config.colorOverLifetime.setColor(hold(color));
        var sparkle = glow("sparkle", color, 0, sphere(.08f, 1), .2f, .6f, 8, 14, .03f, .06f, .9f);
        sparkle.config.setSimulationSpace(ParticleConfig.Space.World);
        sparkle.config.emission.setEmissionRate(NumberFunction.constant(2.5f));
        sparkle.config.setMaxParticles(64);
        sparkle.config.setDuration(20);
        return fx(head, halo, sparkle);
    }

    /** Lightning: segment emitters seg0..segN placed along a jagged polyline per instance. */
    static FX hiveLightning(int color) {
        var objects = new ParticleEmitter[LIGHTNING_SEGMENTS + 1];
        for (int index = 0; index < LIGHTNING_SEGMENTS; index++) {
            objects[index] = glow("seg" + index, color, 24, new Box(), -.2f, .2f, 2, 5, .05f, .1f, 1);
            objects[index].config.colorOverLifetime.setColor(fade(color, 1, .5f));
        }
        objects[LIGHTNING_SEGMENTS] = flash("flash", color, .45f, 3);
        return fx(objects);
    }

    static FX hiveImpact(int color, boolean bolt) {
        var sparks = streaks(glow("sparks", color, bolt ? 14 : 18, sphere(.05f, 1),
                bolt ? 2 : 3, bolt ? 5 : 7, bolt ? 5 : 3, bolt ? 10 : 7, .03f, .06f, 1), 10, .85f);
        if (!bolt) return fx(flash("flash", color, .6f, 3), sparks);
        return fx(flash("flash", color, .9f, 5), sparks,
                drag(glow("ring", color, 16, circle(.1f, 1), 2, 3, 8, 12, .06f, .1f, .8f), .9f));
    }

    /** Inward spiral that closes on the owner, capped by a flash as the swarm locks in. */
    static FX hiveSummon(int color) {
        var swirl = orbit(glow("swirl", color, 0, sphere(1.6f, 0), -1.6f, -2.4f, 12, 16, .05f, .09f, 1), 4);
        swirl.config.emission.setEmissionRate(NumberFunction.constant(4));
        swirl.config.setMaxParticles(64);
        swirl.config.setDuration(12);
        var flash = flash("flash", color, .9f, 6);
        flash.config.emission.getBursts().getFirst().time = 12;
        flash.config.setDuration(14);
        return fx(swirl, flash);
    }

    /** Outward scatter left in world space so it does not trail the owner. */
    static FX hiveDismiss(int color) {
        var scatter = drag(orbit(glow("scatter", color, 40, sphere(.5f, 1), 3, 6, 12, 20, .05f, .09f, 1), 3), .9f);
        scatter.config.setSimulationSpace(ParticleConfig.Space.World);
        return fx(scatter, flash("flash", color, .8f, 5));
    }

    /** Applies {@code action} to every particle emitter of the runtime with the given name. */
    static void each(FXRuntime runtime, String name, Consumer<ParticleEmitter> action) {
        for (IFXObject object : runtime.objects.values()) {
            if (object instanceof ParticleEmitter emitter && name.equals(emitter.getName())) action.accept(emitter);
        }
    }

    static void scaleCount(ParticleEmitter emitter, float factor) {
        var max = emitter.runtime().maxParticles;
        max.set(Math.max(1, Math.round(max.get() * factor)));
    }

    static void size(ParticleEmitter emitter, float size) {
        emitter.runtime().startSize.set(new NumberFunction3(size, size, size));
    }

    static void shapeScale(ParticleEmitter emitter, float x, float y, float z) {
        emitter.runtime().shape.scale.set(new NumberFunction3(x, y, z));
    }

    static void speed(ParticleEmitter emitter, float min, float max) {
        emitter.runtime().startSpeed.set(new RandomConstant(min, max));
    }

    /** Lifetime and emitter duration together, so a travelling emitter stops exactly on arrival. */
    static void lifetime(ParticleEmitter emitter, int ticks) {
        emitter.runtime().startLifetime.set(NumberFunction.constant(ticks));
        emitter.runtime().duration.set(ticks);
    }

    /** Trail length as a fraction of the head's lifetime (Photon's trail lifetime unit). */
    static void trailFraction(ParticleEmitter emitter, float fraction) {
        emitter.runtime().trails.lifetime.set(NumberFunction.constant(fraction));
    }

    private static FX fx(ParticleEmitter... emitters) {
        var fx = new FX();
        for (var emitter : emitters) fx.getFxData().objects().add(emitter);
        return fx;
    }

    /** A burst-once, full-bright, additive emitter: white-hot core cooling to the palette and alpha 0. */
    private static ParticleEmitter glow(String name, int color, int count, IShape shape, float speedMin, float speedMax,
            int lifeMin, int lifeMax, float sizeMin, float sizeMax, float alpha) {
        var emitter = new ParticleEmitter();
        emitter.setName(name);
        var config = emitter.config;
        config.setDuration(2);
        config.setLooping(false);
        config.setMaxParticles(Math.max(1, count));
        config.setStartLifetime(new RandomConstant(lifeMin, lifeMax));
        config.setStartSpeed(new RandomConstant(speedMin, speedMax));
        config.setStartSize(new NumberFunction3(new RandomConstant(sizeMin, sizeMax), new RandomConstant(sizeMin, sizeMax),
                new RandomConstant(sizeMin, sizeMax)));
        config.setStartColor(NumberFunction.color(-1));
        config.shape.setShape(shape);
        config.emission.setEmissionRate(NumberFunction.constant(0));
        if (count > 0) {
            var burst = new EmissionSetting.Burst();
            burst.setCount(NumberFunction.constant(count));
            config.emission.getBursts().add(burst);
        }
        config.colorOverLifetime.setEnable(true);
        config.colorOverLifetime.setColor(fade(color, alpha, .3f));
        config.renderer.setShade(false);
        additive(config.renderer.getMaterials().getFirst());
        return emitter;
    }

    private static ParticleEmitter flash(String name, int color, float size, int life) {
        var flash = glow(name, color, 1, new Dot(), 0, 0, life, life, size, size, .9f);
        return shrink(flash, .6f, 1.2f, 0);
    }

    private static ParticleEmitter streaks(ParticleEmitter emitter, float velocityScale, float friction) {
        emitter.config.renderer.setRenderMode(ParticleRendererSetting.Mode.StretchedBillboard);
        emitter.config.renderer.setLengthScale(1.5f);
        emitter.config.renderer.setVelocityScale(velocityScale);
        return drag(emitter, friction);
    }

    private static ParticleEmitter drag(ParticleEmitter emitter, float friction) {
        var physics = emitter.config.physics;
        physics.setEnable(true);
        physics.setHasCollision(false);
        physics.setFriction(NumberFunction.constant(friction));
        return emitter;
    }

    private static ParticleEmitter orbit(ParticleEmitter emitter, float angular) {
        var velocity = emitter.config.velocityOverLifetime;
        velocity.setEnable(true);
        velocity.setOrbital(new NumberFunction3(0, angular, 0));
        return emitter;
    }

    private static ParticleEmitter shrink(ParticleEmitter emitter, float... sizes) {
        emitter.config.sizeOverLifetime.setEnable(true);
        var curve = curve(sizes);
        emitter.config.sizeOverLifetime.setSize(new NumberFunction3(curve, curve.copy(), curve.copy()));
        return emitter;
    }

    /** SRC_ALPHA, ONE: overlapping sparks add up to a glow instead of occluding each other. */
    private static MaterialSetting additive(MaterialSetting material) {
        material.getBlendMode().setSrcColorFactor(GlStateManager.SourceFactor.SRC_ALPHA);
        material.getBlendMode().setDstColorFactor(GlStateManager.DestFactor.ONE);
        material.setDepthMask(false);
        return material;
    }

    private static Sphere sphere(float radius, float thickness) {
        var sphere = new Sphere();
        sphere.setRadius(radius);
        sphere.setRadiusThickness(thickness);
        return sphere;
    }

    private static Circle circle(float radius, float thickness) {
        var circle = new Circle();
        circle.setRadius(radius);
        circle.setRadiusThickness(thickness);
        return circle;
    }

    private static Cone cone(float angle, float radius) {
        var cone = new Cone();
        cone.setAngle(angle);
        cone.setRadius(radius);
        return cone;
    }

    /** Evenly spaced keys joined by eased cubic segments; values are used as-is (lower 0, upper 1). */
    private static Curve curve(float... values) {
        var data = new float[(values.length - 1) * 8];
        for (int index = 0; index + 1 < values.length; index++) {
            float x0 = index / (values.length - 1f), x1 = (index + 1) / (values.length - 1f), third = (x1 - x0) / 3;
            System.arraycopy(new float[]{x0, values[index], x0 + third, values[index], x1 - third, values[index + 1],
                    x1, values[index + 1]}, 0, data, index * 8, 8);
        }
        return new Curve(0, Float.MAX_VALUE, 0, 1, "", "", new ECBCurves(data));
    }

    private static Gradient fade(int color, float alpha, float coreEnd) {
        var gradient = new GradientColor();
        gradient.getAP().clear();
        gradient.getRgbP().clear();
        gradient.getAP().add(new Vector2f(0, alpha));
        gradient.getAP().add(new Vector2f(.55f, alpha * .75f));
        gradient.getAP().add(new Vector2f(1, 0));
        gradient.getRgbP().add(rgb(0, core(color)));
        gradient.getRgbP().add(rgb(Math.min(coreEnd, .95f), color));
        gradient.getRgbP().add(rgb(1, color));
        return new Gradient(gradient);
    }

    /** Head colour: steady hot core for the whole flight, cut only in the last tenth. */
    private static Gradient hold(int color) {
        var gradient = new GradientColor();
        gradient.getAP().clear();
        gradient.getRgbP().clear();
        gradient.getAP().add(new Vector2f(0, 1));
        gradient.getAP().add(new Vector2f(.9f, 1));
        gradient.getAP().add(new Vector2f(1, 0));
        gradient.getRgbP().add(rgb(0, core(color)));
        gradient.getRgbP().add(rgb(1, core(color)));
        return new Gradient(gradient);
    }

    /** Trail t runs oldest (0) to head (1): transparent palette tail brightening into the head. */
    private static Gradient trail(int color) {
        var gradient = new GradientColor();
        gradient.getAP().clear();
        gradient.getRgbP().clear();
        gradient.getAP().add(new Vector2f(0, 0));
        gradient.getAP().add(new Vector2f(1, .85f));
        gradient.getRgbP().add(rgb(0, color));
        gradient.getRgbP().add(rgb(.8f, color));
        gradient.getRgbP().add(rgb(1, core(color)));
        return new Gradient(gradient);
    }

    private static int core(int color) {
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        return (255 - (255 - r) * 35 / 100) << 16 | (255 - (255 - g) * 35 / 100) << 8 | (255 - (255 - b) * 35 / 100);
    }

    private static Vector4f rgb(float t, int color) {
        return new Vector4f(t, (color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f);
    }

    private ExFxLibrary() { }
}
