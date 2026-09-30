package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.Armageddon;
import dev.hurtify.relicsaddon.drone.ArmageddonState;
import dev.hurtify.relicsaddon.network.ArmageddonPayloads;
import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.hurtify.relicsaddon.AddonConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Armageddon, the ultimate of a fully upgraded Twins hive (see {@link Armageddon} for the cannon and
 * its timings). The owner asks for a shot at the point they look at; the hive must be at the top level
 * and fully charged. While the cannon charges, the hive's battery pours into it, and a worn Twins
 * shield feeds it too without letting its own field drop. Where the beam lands a blast front sweeps
 * out to {@link Armageddon#RADIUS} blocks and strikes every creature and every player not allied to
 * the owner as it passes them: hardest at the heart, still hard at the edge.
 */
public final class ArmageddonController {
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "armageddon"));
    /** Damage at the heart of the blast and at its very edge; it falls off with the square of the distance between. */
    static final double CORE_DAMAGE = 2000, EDGE_DAMAGE = 20;
    /** The most a Twins shield hands over, as a share of the hive's battery, and the share of its own battery it always keeps. */
    private static final double SHIELD_SHARE = .3, SHIELD_KEEPS = .5;
    /** A battery counts as full from this share up: a switched-on hive always spends a little on its upkeep. */
    static final double FULL = .98;
    /** What the hive keeps of its charge when the beam fires (the rest went into the cannon). */
    private static final double LEFT_AT_FIRE = .02;
    /** The most blocks the black hole takes in one tick; if it falls behind it catches up over the next few. */
    private static final int BITES = 12000;
    /** The most blocks the eruption's beam bores away in one tick. */
    private static final int BORES = 6000;
    /** Shots in progress, by owner. */
    private static final Map<UUID, Shot> SHOTS = new HashMap<>();
    /** Shots already fired whose owner has gone: they fly on, feed and burst where they were fired. */
    private static final List<Shot> LOOSE = new ArrayList<>();
    /** Blasts still sweeping out. */
    private static final List<Blast> BLASTS = new ArrayList<>();

    /** A shot under way: the hive it came from, what the hive and shield held at the start, and how far it has got. */
    private static final class Shot {
        final String hive;
        final ServerLevel level;
        final ServerPlayer owner;
        final ArmageddonState state;
        final DeviceEnergy hiveAtStart;
        final int shieldGives;
        int shieldGiven;
        boolean fired, arrived, landed;
        /** The columns the black hole will eat, nearest its target first (packed x and z), how far it has got, and how low it has cut in the one it is on. */
        long[] crater;
        int eaten, eatenTo = Integer.MAX_VALUE;
        /** The columns the beam will bore, nearest its axis first (packed x and z), how far it has got, and how low it has cut in the one it is on. */
        long[] shaft;
        int bored, boredTo = Integer.MAX_VALUE;

        Shot(String hive, ServerPlayer owner, ArmageddonState state, DeviceEnergy hiveAtStart, int shieldGives) {
            this.hive = hive;
            this.level = owner.serverLevel();
            this.owner = owner;
            this.state = state;
            this.hiveAtStart = hiveAtStart;
            this.shieldGives = shieldGives;
        }
    }

    /** A blast front sweeping out from {@code centre}, and every creature it has already reached. */
    private record Blast(ServerLevel level, Vec3 centre, long impactAt, ServerPlayer owner, java.util.Set<UUID> reached) { }

    /**
     * Why {@code owner}'s hive cannot fire Armageddon now, as a translation key, or null when it can.
     * Works on either side: the client asks before it offers the shot, and the server asks again.
     */
    public static String unavailable(Player owner, ItemStack hive, long now) {
        if (!(hive.getItem() instanceof AutonomousRelicItem item) || item.role() != RelicRole.TWINS_HIVE) return "message.relics_addon.armageddon.no_hive";
        if (!RelicRuntime.enabled(hive)) return "message.relics_addon.armageddon.no_hive";
        if (RelicRuntime.progression(hive).level() < DeviceProgression.MAX_LEVEL) return "message.relics_addon.armageddon.level";
        ArmageddonState running = hive.get(ModDataComponents.HIVE_ARMAGEDDON.get());
        if (running != null && running.running(now)) return "message.relics_addon.armageddon.running";
        if (!full(owner, hive)) return "message.relics_addon.armageddon.charge";
        return null;
    }

    /** Whether both of the hive's batteries are full, near enough (always, when power is off or in Creative). */
    static boolean full(Player owner, ItemStack hive) {
        if (!DevicePower.required() || owner.getAbilities().instabuild) return true;
        DeviceEnergy energy = DevicePower.energy(hive);
        return energy.rf() >= DevicePower.feCapacity(hive) * FULL && energy.mana() >= DevicePower.capacity(hive) * FULL;
    }

    /**
     * The owner asks to fire at {@code target}; everything is checked again here before the cannon starts
     * to gather. Returns null once it has, or the translation key of why not.
     */
    public static String request(ServerPlayer owner, Vec3 target) {
        return request(owner, target, 0);
    }

    /** As {@link #request(ServerPlayer, Vec3)}, but as if the cannon had already been charging for {@code headStart} ticks (for tests and films). */
    public static String request(ServerPlayer owner, Vec3 target, int headStart) {
        if (!owner.isAlive() || owner.isSpectator() || !EquippedRelicSetResolver.isRealPlayer(owner) || target == null) return "message.relics_addon.armageddon.no_hive";
        if (!Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)) return "message.relics_addon.armageddon.no_hive";
        if (SHOTS.containsKey(owner.getUUID())) return "message.relics_addon.armageddon.running";
        ItemStack hive = twinsHive(owner);
        long now = owner.level().getGameTime();
        String reason = hive.isEmpty() ? "message.relics_addon.armageddon.no_hive" : unavailable(owner, hive, now);
        if (reason != null) return reason;
        String identity = hive.get(ModDataComponents.INSTANCE_ID.get());
        if (identity == null) return "message.relics_addon.armageddon.no_hive";
        // The aim can be no further off than the reach.
        Vec3 eye = owner.getEyePosition(), aim = target.subtract(eye);
        if (aim.length() > Armageddon.REACH + 2) target = eye.add(aim.normalize().scale(Armageddon.REACH));
        if (aim.lengthSqr() < 1) target = eye.add(owner.getLookAngle().scale(Armageddon.REACH));
        ItemStack shield = twinsShield(owner);
        int shieldGives = shield.isEmpty() ? 0 : shieldGives(owner, shield, hive);
        ArmageddonState state = new ArmageddonState(now - Math.clamp(headStart, 0, Armageddon.IMPACT - 1), Armageddon.origin(eye, target), target, shieldGives > 0);
        hive.set(ModDataComponents.HIVE_ARMAGEDDON.get(), state);
        SHOTS.put(owner.getUUID(), new Shot(identity, owner, state, DevicePower.energy(hive), shieldGives));
        RelicSounds.armageddon(owner.serverLevel(), state.origin(), RelicSounds.Cannon.CHARGE);
        return null;
    }

    /**
     * Runs {@code owner}'s shot on their hive every server tick. Returns true while the shot holds the
     * swarm, so ordinary combat stands down.
     */
    public static boolean tick(ServerPlayer owner, ItemStack hive, long now) {
        Shot shot = SHOTS.get(owner.getUUID());
        if (shot == null) {
            // A shot this server does not know (the world was reloaded mid-shot): let it go.
            if (hive.has(ModDataComponents.HIVE_ARMAGEDDON.get())) hive.remove(ModDataComponents.HIVE_ARMAGEDDON.get());
            return false;
        }
        if (!shot.hive.equals(hive.get(ModDataComponents.INSTANCE_ID.get()))) {
            // Another Twins hive is worn now: the shot goes on (or ends) without the hive that fired it.
            abort(owner);
            return false;
        }
        long age = now - shot.state.startedAt();
        pour(owner, hive, shot, age);
        advance(shot, now);
        if (age >= Armageddon.END) {
            finish(owner, hive, shot);
            return false;
        }
        return true;
    }

    /**
     * Carries a shot on {@code now}, in the level it was fired in: the shot leaving, the black hole arriving and
     * feeding (through the first moments of the burst too, until it has had all it marked), the burst, and the
     * beam boring the land out.
     */
    private static void advance(Shot shot, long now) {
        long age = now - shot.state.startedAt();
        ServerLevel level = shot.level;
        if (!shot.fired && age >= Armageddon.FIRE) {
            shot.fired = true;
            RelicSounds.armageddon(level, Armageddon.muzzle(shot.state), RelicSounds.Cannon.FIRE);
        }
        if (!shot.arrived && age >= Armageddon.ARRIVE) {
            shot.arrived = true;
            RelicSounds.armageddon(level, shot.state.target(), RelicSounds.Cannon.DEVOUR);
            if (!safe()) shot.crater = columns(shot.state.target(), Armageddon.DEVOUR_RADIUS);
        }
        if (shot.arrived && age < Armageddon.IMPACT + Armageddon.CRUSHED) devour(level, shot, age);
        if (!shot.landed && age >= Armageddon.IMPACT) {
            shot.landed = true;
            detonate(level, shot.owner, shot.state.target(), shot.state.startedAt() + Armageddon.IMPACT);
        }
        if (shot.landed && !safe()) bore(level, shot, age - Armageddon.IMPACT);
    }

    /**
     * The charge flows: while the cannon charges the hive's battery drains into it down to a sliver, and
     * the shield's gift flows in as well; once the drones are home the hive keeps only what the shield gave.
     */
    private static void pour(ServerPlayer owner, ItemStack hive, Shot shot, long age) {
        if (!DevicePower.required() || owner.getAbilities().instabuild) return;
        double drained = Math.clamp((age - Armageddon.ASSEMBLED) / (double) (Armageddon.FIRE - Armageddon.ASSEMBLED), 0, 1);
        double left = 1 - drained * (1 - LEFT_AT_FIRE);
        DeviceEnergy start = shot.hiveAtStart, energy = DevicePower.energy(hive);
        int rf = (int) Math.round(start.rf() * left), mana = (int) Math.round(start.mana() * left);
        if (rf < energy.rf() || mana < energy.mana()) {
            hive.set(ModDataComponents.DEVICE_ENERGY.get(), energy.withRf(Math.min(energy.rf(), rf)).withMana(Math.min(energy.mana(), mana)));
        }
        int owed = (int) Math.round(shot.shieldGives * drained) - shot.shieldGiven;
        if (owed > 0) {
            ItemStack shield = twinsShield(owner);
            if (!shield.isEmpty() && DevicePower.drain(owner, shield, owed)) shot.shieldGiven += owed;
        }
    }

    /**
     * What a Twins shield can hand over, in points: never more than a share of the hive's battery, and
     * never out of the reserve it keeps for its own field. Without batteries in play it gives a token 1,
     * so the link still shows.
     */
    public static int shieldGives(Player owner, ItemStack shield, ItemStack hive) {
        if (!RelicRuntime.enabled(shield) || !DevicePower.required() || owner.getAbilities().instabuild) return RelicRuntime.enabled(shield) ? 1 : 0;
        DeviceEnergy energy = DevicePower.energy(shield);
        int held = energy.rf() / DevicePower.FE_PER_POINT + energy.mana();
        int spare = held - (int) Math.round(2 * DevicePower.capacity(shield) * SHIELD_KEEPS);
        return Math.max(0, Math.min(spare, (int) Math.round(2 * DevicePower.capacity(hive) * SHIELD_SHARE)));
    }

    private static void finish(ServerPlayer owner, ItemStack hive, Shot shot) {
        SHOTS.remove(owner.getUUID());
        hive.remove(ModDataComponents.HIVE_ARMAGEDDON.get());
        if (!DevicePower.required() || owner.getAbilities().instabuild) return;
        // The shot took the hive's whole charge; what the shield gave is all it has left.
        int kept = shot.shieldGiven;
        DeviceEnergy energy = DevicePower.energy(hive);
        hive.set(ModDataComponents.DEVICE_ENERGY.get(), energy.withRf(kept / 2 * DevicePower.FE_PER_POINT).withMana(kept - kept / 2));
    }

    /** Whether the server keeps Armageddon from breaking blocks. */
    static boolean safe() {
        return AddonConfig.SPEC.isLoaded() && AddonConfig.ARMAGEDDON_SAFE.get();
    }

    /** The same, as a client sees it (server settings reach the client when it joins). */
    public static boolean safeClient() {
        return safe();
    }

    /**
     * The black hole takes the land from the middle out: column by column, as its reach passes each, every
     * breakable block within the sphere of {@link Armageddon#DEVOUR_RADIUS} round its target, a few thousand a
     * tick, and drags every creature it would strike towards itself.
     */
    private static void devour(ServerLevel level, Shot shot, long age) {
        Vec3 centre = shot.state.target();
        double reach = Armageddon.devoured(age), most = Armageddon.DEVOUR_RADIUS * Armageddon.DEVOUR_RADIUS;
        int budget = BITES;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        while (shot.crater != null && budget > 0 && shot.eaten < shot.crater.length) {
            int x = (int) (shot.crater[shot.eaten] >> 32), z = (int) shot.crater[shot.eaten];
            double dx = x + .5 - centre.x, dz = z + .5 - centre.z, flat = dx * dx + dz * dz;
            if (flat > reach * reach) break;
            double span = Math.sqrt(Math.max(0, most - flat));
            int floor = Math.max(level.getMinBuildHeight(), (int) Math.ceil(centre.y - span));
            at.set(x, floor, z);
            if (level.isLoaded(at)) {
                int top = Math.min(shot.eatenTo, Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), (int) Math.floor(centre.y + span)));
                for (int y = top; y >= floor && budget > 0; y--) {
                    at.setY(y);
                    // Checked as it is taken: nothing unbreakable goes.
                    BlockState state = level.getBlockState(at);
                    shot.eatenTo = y - 1;
                    if (state.isAir() || state.getDestroySpeed(level, at) < 0) continue;
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                    budget--;
                }
                if (budget <= 0 && shot.eatenTo >= floor) break;
            }
            shot.eaten++;
            shot.eatenTo = Integer.MAX_VALUE;
        }
        // Creatures are dragged in once it starts to feed, and no longer once it has burst.
        if (age < Armageddon.HUNGER || age >= Armageddon.IMPACT) return;
        ServerPlayer owner = shot.owner;
        double pull = Armageddon.DEVOUR_RADIUS * 1.5;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(pull), LivingEntity::isAlive)) {
            if (spared(level, owner, entity)) continue;
            Vec3 in = centre.subtract(entity.position());
            double distance = in.length();
            if (distance < 1 || distance > pull) continue;
            entity.setDeltaMovement(entity.getDeltaMovement().scale(.6).add(in.scale(.25 * (1.2 - distance / pull) / distance)));
            entity.hurtMarked = true;
        }
    }

    /**
     * The eruption's beam bores the land out as it widens: column by column from its axis out, every breakable
     * block from the top of the world down to {@link Armageddon#BORE_DEPTH} below the burst, a few thousand a tick.
     */
    private static void bore(ServerLevel level, Shot shot, double sinceImpact) {
        double radius = Armageddon.beam(sinceImpact);
        if (radius <= 0) return;
        Vec3 centre = shot.state.target();
        if (shot.shaft == null) shot.shaft = columns(centre, Armageddon.BEAM);
        int floor = Math.max(level.getMinBuildHeight(), (int) Math.floor(centre.y - Armageddon.BORE_DEPTH));
        int budget = BORES;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        while (budget > 0 && shot.bored < shot.shaft.length) {
            int x = (int) (shot.shaft[shot.bored] >> 32), z = (int) shot.shaft[shot.bored];
            double dx = x + .5 - centre.x, dz = z + .5 - centre.z;
            if (dx * dx + dz * dz > radius * radius) break;
            at.set(x, floor, z);
            if (level.isLoaded(at)) {
                int top = Math.min(shot.boredTo, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
                for (int y = top; y >= floor && budget > 0; y--) {
                    at.setY(y);
                    BlockState state = level.getBlockState(at);
                    if (state.isAir() || state.getDestroySpeed(level, at) < 0) continue;
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                    budget--;
                    shot.boredTo = y - 1;
                }
                if (budget <= 0 && shot.boredTo >= floor) break;
            }
            shot.bored++;
            shot.boredTo = Integer.MAX_VALUE;
        }
    }

    /** Every column within {@code radius} of {@code centre}, nearest first, as x in the high and z in the low half of a long. */
    private static long[] columns(Vec3 centre, double radius) {
        int reach = (int) Math.ceil(radius), cx = (int) Math.floor(centre.x), cz = (int) Math.floor(centre.z);
        List<long[]> columns = new ArrayList<>();
        for (int x = cx - reach; x <= cx + reach; x++) for (int z = cz - reach; z <= cz + reach; z++) {
            double dx = x + .5 - centre.x, dz = z + .5 - centre.z, d = dx * dx + dz * dz;
            if (d <= radius * radius) columns.add(new long[]{(long) x << 32 | (z & 0xFFFFFFFFL), Double.doubleToLongBits(d)});
        }
        columns.sort(Comparator.comparingDouble(column -> Double.longBitsToDouble(column[1])));
        long[] shaft = new long[columns.size()];
        for (int index = 0; index < shaft.length; index++) shaft[index] = columns.get(index)[0];
        return shaft;
    }

    /** Called off when the owner leaves, dies or changes dimension before the beam fires; a shot already fired still lands. */
    public static void abort(Player owner) {
        Shot shot = SHOTS.remove(owner.getUUID());
        if (shot == null) return;
        ItemStack hive = twinsHive(owner);
        if (!hive.isEmpty() && shot.hive.equals(hive.get(ModDataComponents.INSTANCE_ID.get()))) hive.remove(ModDataComponents.HIVE_ARMAGEDDON.get());
        // A shot already fired flies on, feeds and bursts on time, where it was fired.
        if (shot.fired) LOOSE.add(shot);
    }

    /** The beam lands: every client near enough sees and hears the blast, and the front starts to sweep out. */
    static void detonate(ServerLevel level, ServerPlayer owner, Vec3 centre, long impactAt) {
        BLASTS.add(new Blast(level, centre, impactAt, owner, new java.util.HashSet<>()));
        ArmageddonPayloads.blast(level, centre, impactAt);
    }

    /** Drops every shot, loose shot and blast in {@code level} at once, so that a test leaves nothing behind it. */
    public static void forgetAll(ServerLevel level) {
        SHOTS.values().removeIf(shot -> shot.level == level);
        LOOSE.removeIf(shot -> shot.level == level);
        BLASTS.removeIf(blast -> blast.level == level);
    }

    /** Forgets every shot and blast when the server stops. */
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        SHOTS.clear();
        LOOSE.clear();
        BLASTS.clear();
    }

    /** The owner, their allies and pets, players who cannot be hurt, and every player when the server forbids fights. */
    static boolean spared(ServerLevel level, Player owner, LivingEntity entity) {
        if (owner != null && (entity == owner || ShieldCoverage.friendly(owner, entity))) return true;
        if (entity instanceof Player player) return player.isCreative() || player.isSpectator() || !level.getServer().isPvpAllowed();
        return false;
    }

    /**
     * Carries loose shots on, and sweeps every blast front on towards its edge, striking every creature it reaches
     * where it stands at that moment (whoever is spared is judged then too).
     */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || BLASTS.isEmpty() && LOOSE.isEmpty()) return;
        long now = level.getGameTime();
        for (Iterator<Shot> loose = LOOSE.iterator(); loose.hasNext(); ) {
            Shot shot = loose.next();
            if (shot.level != level) continue;
            advance(shot, now);
            if (now - shot.state.startedAt() >= Armageddon.END) loose.remove();
        }
        for (Iterator<Blast> blasts = BLASTS.iterator(); blasts.hasNext(); ) {
            Blast blast = blasts.next();
            if (blast.level != level) continue;
            double front = Armageddon.reach(now - blast.impactAt);
            if (front > 0) {
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(blast.centre, blast.centre).inflate(front),
                        entity -> entity.isAlive() && !blast.reached.contains(entity.getUUID()))) {
                    double distance = entity.position().add(0, entity.getBbHeight() * .5, 0).distanceTo(blast.centre);
                    if (distance > front) continue;
                    blast.reached.add(entity.getUUID());
                    if (!spared(level, blast.owner, entity)) strike(blast, entity, distance);
                }
            }
            if (now - blast.impactAt >= Armageddon.BALL + Armageddon.EXPAND) blasts.remove();
        }
    }

    private static void strike(Blast blast, LivingEntity entity, double distance) {
        double edge = distance / Armageddon.RADIUS, falloff = (1 - edge) * (1 - edge);
        float damage = (float) (EDGE_DAMAGE + (CORE_DAMAGE - EDGE_DAMAGE) * falloff);
        // Credited to the owner even when they have left, so the death message still names them.
        if (!entity.hurt(blast.level.damageSources().source(DAMAGE, blast.owner), damage)) return;
        // Flung away from the heart of it, hardest nearest.
        Vec3 away = entity.position().subtract(blast.centre);
        Vec3 push = new Vec3(away.x, 0, away.z);
        push = push.lengthSqr() < 1e-6 ? Vec3.ZERO : push.normalize().scale(.6 + 2.4 * falloff);
        entity.push(push.x, .35 + 1.1 * falloff, push.z);
        entity.hurtMarked = true;
    }

    /** The player's worn Twins hive, switched on or not, or an empty stack. */
    public static ItemStack twinsHive(Player player) {
        return worn(player, RelicRole.TWINS_HIVE);
    }

    public static ItemStack twinsShield(Player player) {
        return worn(player, RelicRole.TWINS_SHIELD);
    }

    private static ItemStack worn(Player player, RelicRole role) {
        return CuriosApi.getCuriosInventory(player).map(inventory -> inventory.getStacksHandler(RelicRole.EQUIPMENT_SLOT).map(handler -> {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStacks().getStackInSlot(slot);
                if (inventory.isSlotActive(RelicRole.EQUIPMENT_SLOT, slot) && stack.getItem() instanceof AutonomousRelicItem item && item.role() == role) {
                    return stack;
                }
            }
            return ItemStack.EMPTY;
        }).orElse(ItemStack.EMPTY)).orElse(ItemStack.EMPTY);
    }

    /** Whether {@code owner} has a shot under way (for tests and the renderer's server twin). */
    public static boolean shooting(Player owner) {
        return SHOTS.containsKey(owner.getUUID());
    }

    /** Blasts still sweeping out in {@code level} (for tests). */
    public static int blasts(ServerLevel level) {
        return (int) BLASTS.stream().filter(blast -> blast.level == level).count();
    }

    private ArmageddonController() {
    }
}
