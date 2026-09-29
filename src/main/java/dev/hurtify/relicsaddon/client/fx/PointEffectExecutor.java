package dev.hurtify.relicsaddon.client.fx;

import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXEffectExecutor;
import com.lowdragmc.photon.client.fx.FXRuntime;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Plays an FX at a free world point: optionally oriented (local +Y onto a direction), moved linearly
 * from→to over N ticks, or pinned to an entity's body center. Photon's own executors only anchor to
 * blocks and entities and keep static per-anchor caches; this one keeps no static state, so a
 * finished instance is garbage as soon as the particle engine drops its objects.
 */
public final class PointEffectExecutor extends FXEffectExecutor {
    /** Hard cap so a looping emitter in an artist override can never pin a runtime (and a budget slot) forever. */
    private static final int MAX_AGE = 20 * 15;
    private final Vec3 from, to;
    private final int travelTicks;
    @Nullable private Entity anchor;
    @Nullable private Consumer<FXRuntime> onStarted;
    private float density = 1;
    private int age;

    public PointEffectExecutor(FX fx, Level level, Vec3 at) {
        this(fx, level, at, at, 0);
    }

    public PointEffectExecutor(FX fx, Level level, Vec3 from, Vec3 to, int travelTicks) {
        super(fx, level);
        this.from = from;
        this.to = to;
        this.travelTicks = Math.max(0, travelTicks);
        setAllowMulti(true);
    }

    /** Rotates the root so the emitters' local +Y points along {@code direction}; zero vectors are ignored. */
    public PointEffectExecutor orient(Vec3 direction) {
        setRotation(alignY(direction));
        return this;
    }

    /** Follows the entity's body center each frame; the FX drains in place once the entity is gone. */
    public PointEffectExecutor follow(Entity entity) {
        this.anchor = entity;
        return this;
    }

    /** Runs right after emit, where per-instance runtime overrides (lengths, lifetimes) survive the emit reset. */
    public PointEffectExecutor onStarted(Consumer<FXRuntime> hook) {
        this.onStarted = hook;
        return this;
    }

    /** Scales every emitter's particle budget (distance LOD); single-particle emitters are left intact. */
    public PointEffectExecutor density(float density) {
        this.density = density;
        return this;
    }

    @Override
    public void start() {
        resetFinishedNotification();
        age = 0;
        runtime = fx.createRuntime();
        var root = runtime.getRoot();
        root.updatePos(position(0).toVector3f().add(offset));
        root.updateRotation(rotation);
        root.updateScale(scale);
        runtime.emit(this, delay);
        if (onStarted != null) onStarted.accept(runtime);
        if (density < 1) {
            for (IFXObject object : runtime.objects.values()) {
                if (!(object instanceof ParticleEmitter emitter)) continue;
                var max = emitter.runtime().maxParticles;
                int count = max.get();
                if (count > 3) max.set(Math.max(2, Math.round(count * density)));
            }
        }
    }

    @Override
    public void updateFXObjectTick(IFXObject fxObject) {
        if (runtime == null || fxObject != runtime.root) return;
        age++;
        if (anchor != null && !anchor.isAlive()) {
            anchor = null;
            runtime.destroy(false);
        }
        // The root ticks before its children, so emitters sample this exact tick-time position.
        if (moves()) runtime.root.updatePos(position(age).toVector3f().add(offset));
        if (age > MAX_AGE && !runtime.isFinished()) runtime.destroy(true);
        if (runtimeEnded()) notifyFinished();
    }

    @Override
    public void updateFXObjectFrame(IFXObject fxObject, float partialTicks) {
        if (runtime != null && fxObject == runtime.root && moves()) {
            runtime.root.updatePos(position(age + partialTicks).toVector3f().add(offset));
        }
    }

    /** Whether the runtime finished or was silently discarded by the particle engine (level change, clear). */
    public boolean isDone() {
        return runtime != null && runtimeEnded();
    }

    /** Stops emission; {@code force} also drops the particles already alive. */
    public void stop(boolean force) {
        if (runtime != null) runtime.destroy(force);
    }

    private boolean moves() {
        return anchor != null || travelTicks > 0;
    }

    private Vec3 position(float ticks) {
        if (anchor != null) {
            float partial = ticks - (float) Math.floor(ticks);
            return anchor.getPosition(partial == 0 ? 1 : partial).add(0, anchor.getBbHeight() * .5, 0);
        }
        if (travelTicks <= 0) return from;
        return from.lerp(to, Math.min(1, ticks / travelTicks));
    }

    static Quaternionf alignY(Vec3 direction) {
        double length = direction.length();
        if (!(length > 1e-6)) return new Quaternionf();
        Vector3f target = direction.scale(1 / length).toVector3f();
        return new Quaternionf().rotationTo(0, 1, 0, target.x, target.y, target.z);
    }
}
