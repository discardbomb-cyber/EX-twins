package dev.hurtify.relicsaddon.domain;

import dev.hurtify.relicsaddon.domain.combat.DamageKind;
import dev.hurtify.relicsaddon.domain.device.DeviceProgression;
import dev.hurtify.relicsaddon.domain.device.DeviceStat;
import dev.hurtify.relicsaddon.domain.device.DeviceStats;
import dev.hurtify.relicsaddon.domain.device.DeviceUpgrade;
import dev.hurtify.relicsaddon.domain.device.HiveWearRule;
import dev.hurtify.relicsaddon.domain.device.ProgressionRules;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.device.UpgradeRules;
import dev.hurtify.relicsaddon.domain.device.WornSlot;
import dev.hurtify.relicsaddon.domain.energy.BatteryRules;
import dev.hurtify.relicsaddon.domain.energy.DeviceEnergy;
import dev.hurtify.relicsaddon.domain.energy.EnergyCosts;
import dev.hurtify.relicsaddon.domain.energy.ExperienceCurve;
import dev.hurtify.relicsaddon.domain.hive.ChargeFlight;
import dev.hurtify.relicsaddon.domain.hive.DroneDamage;
import dev.hurtify.relicsaddon.domain.hive.HealingPolicy;
import dev.hurtify.relicsaddon.domain.hive.HiveCombatState;
import dev.hurtify.relicsaddon.domain.hive.HiveStackState;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import dev.hurtify.relicsaddon.domain.hive.HiveUpgradeEffects;
import dev.hurtify.relicsaddon.domain.hive.HoldPolicy;
import dev.hurtify.relicsaddon.domain.hive.ShotLog;
import dev.hurtify.relicsaddon.domain.hive.SwarmRules;
import dev.hurtify.relicsaddon.domain.hive.SwarmStrike;
import dev.hurtify.relicsaddon.domain.math.Vec3d;
import dev.hurtify.relicsaddon.domain.shield.BarrierPush;
import dev.hurtify.relicsaddon.domain.shield.CellSelection;
import dev.hurtify.relicsaddon.domain.shield.Coverage;
import dev.hurtify.relicsaddon.domain.shield.CoverageRule;
import dev.hurtify.relicsaddon.domain.shield.DamageFacts;
import dev.hurtify.relicsaddon.domain.shield.DamagePassPolicy;
import dev.hurtify.relicsaddon.domain.shield.EffectTrim;
import dev.hurtify.relicsaddon.domain.shield.HitImmunity;
import dev.hurtify.relicsaddon.domain.shield.ProjectileFacts;
import dev.hurtify.relicsaddon.domain.shield.ProjectilePolicy;
import dev.hurtify.relicsaddon.domain.shield.ShieldField;
import dev.hurtify.relicsaddon.domain.shield.ShieldSettings;
import dev.hurtify.relicsaddon.domain.shield.ShieldStats;
import dev.hurtify.relicsaddon.domain.shield.ShieldStatus;
import dev.hurtify.relicsaddon.domain.shield.ShieldTopology;
import dev.hurtify.relicsaddon.domain.shield.ShieldUpgradeEffects;
import dev.hurtify.relicsaddon.domain.shield.StrikeRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The pure rules the legacy handlers call since S6, pinned to the numbers the game has always used.
 * Doubles and floats are compared bit for bit (so association, float versus double and the ceil variant
 * all show), and lazy facts by the order they are asked in. Runs on the test runtime alone, without
 * Minecraft: a rule that reached the game would fail with NoClassDefFoundError.
 */
public final class DomainRulesCheck {
    private static int checks;

    public static void main(String[] args) {
        progression();
        stats();
        upgrades();
        batteries();
        shieldStats();
        shieldUpgrades();
        hiveUpgrades();
        passPolicy();
        immunity();
        effectTrim();
        projectiles();
        coverage();
        barrier();
        cells();
        strikes();
        swarm();
        drones();
        flights();
        healing();
        holds();
        status();
        wear();
        System.out.println("Domain rules: " + checks + " checks passed");
    }

    private static void progression() {
        require(ProgressionRules.experienceToNext(0) == 60 && ProgressionRules.experienceToNext(9) == 2040, "60 experience to level 1, 2 040 to level 10");
        int total = 0;
        for (int level = 0; level < DeviceProgression.MAX_LEVEL; level++) total += ProgressionRules.experienceToNext(level);
        require(total == 8100, "8 100 experience in total to reach level 10: " + total);
        require(ProgressionRules.gain(20) == 3 && ProgressionRules.gain(6) == 2 && ProgressionRules.gain(1) == 1 && ProgressionRules.gain(1000) == 3,
                "a blow earns a quarter of its damage, 1 to 3");
        require(ProgressionRules.addExperience(DeviceProgression.DEFAULT, 3).equals(new DeviceProgression(3, 0, 0, 0)), "experience accrues below a level");
        require(ProgressionRules.addExperience(new DeviceProgression(57, 0, 0, 5), 3).equals(new DeviceProgression(0, 1, 1, 5)),
                "reaching 60 gives level 1 and a point, keeping the upgrades");
        require(ProgressionRules.addExperience(new DeviceProgression(0, 0, 4, 9), 200).equals(new DeviceProgression(20, 2, 6, 9)),
                "one gain can cross several levels (60, then 120)");
        require(ProgressionRules.addExperience(new DeviceProgression(100, 10, 5, 0), 3).equals(new DeviceProgression(103, 10, 5, 0)),
                "experience keeps accruing at level 10 (Q17)");
    }

    private static void stats() {
        for (DeviceStat stat : DeviceStat.values()) require(DeviceStat.byId(stat.id()).orElseThrow() == stat, "stat id " + stat.id());
        require(DeviceStat.byId("buffer_capacity").orElseThrow() == DeviceStat.BUFFER_CAPACITY && DeviceStat.byId("drone_health").orElseThrow() == DeviceStat.DRONE_HEALTH
                && DeviceStat.byId("attack_interval_max").orElseThrow() == DeviceStat.ATTACK_INTERVAL_MAX, "stat ids are the saved ones");
        require(DeviceStat.byId("unknown").isEmpty() && DeviceStat.byId("RADIUS").isEmpty(), "an unknown stat id has no stat");
        throwsNull(() -> DeviceStat.byId(null), "a null stat id throws, as the switch over it did");
        int[] levels = {0, 5, 10};
        for (RelicRole role : RelicRole.values()) for (int index = 0; index < levels.length; index++) {
            int level = levels[index];
            Optional<RelicRole> device = Optional.of(role);
            same(DeviceStats.stat(device, level, DeviceStat.BUFFER_CAPACITY, 504, 504, 5500), new double[]{504, 2752, 5000}[index], role + " buffer at " + level);
            same(DeviceStats.stat(device, level, DeviceStat.RADIUS, 2, 2, 24), new double[]{2, 7, 12}[index], role + " radius at " + level);
            same(DeviceStats.stat(device, level, DeviceStat.DRONE_COUNT, 100, 100, 2000), new double[]{100, 1050, 2000}[index], role + " drones at " + level);
            same(DeviceStats.stat(device, level, DeviceStat.DRONE_HEALTH, 12, 1, 1000), 3, role + " drone health at " + level);
            same(DeviceStats.stat(device, level, DeviceStat.ATTACK_INTERVAL_MAX, 100, 20, 100), new double[]{100, 70, 40}[index], role + " interval at " + level);
            if (role.isHive()) {
                HiveType type = HiveType.of(role);
                double[] damage = type == HiveType.TWINS ? new double[]{3, 3.5, 4} : new double[]{2, 2.5, 3};
                double[] cooldown = switch (type) {
                    case RF -> new double[]{80, 60, 40};
                    case MANA -> new double[]{50, 35, 20};
                    case TWINS -> new double[]{120, 90, 60};
                };
                same(DeviceStats.stat(device, level, DeviceStat.ATTACK_DAMAGE, 2, 1, 100), damage[index], role + " damage at " + level);
                same(DeviceStats.stat(device, level, DeviceStat.COOLDOWN, type.initialCooldown, 10, 400), cooldown[index], role + " cooldown at " + level);
            } else {
                throwsIllegalArgument(() -> DeviceStats.stat(device, level, DeviceStat.ATTACK_DAMAGE, 2, 1, 100), role + " has no attack damage");
                throwsIllegalArgument(() -> DeviceStats.stat(device, level, DeviceStat.COOLDOWN, 80, 10, 400), role + " has no cooldown");
            }
        }
        same(DeviceStats.stat(Optional.empty(), 7, DeviceStat.ATTACK_DAMAGE, 2, 1, 100), 1, "no device: damage is 0, clamped");
        same(DeviceStats.stat(Optional.empty(), 7, DeviceStat.COOLDOWN, 80, 10, 400), 10, "no device: cooldown is 0, clamped");
        same(DeviceStats.stat(Optional.empty(), 30, DeviceStat.BUFFER_CAPACITY, 504, 504, 5500), 5500, "the buffer clamps at 5 500 (Q18)");
        same(DeviceStats.stat(Optional.empty(), -10, DeviceStat.RADIUS, 2, 2, 24), 2, "values clamp from below");
    }

    private static void upgrades() {
        DeviceProgression rich = new DeviceProgression(0, 10, 5, 0);
        for (RelicRole role : RelicRole.values()) for (DeviceUpgrade upgrade : DeviceUpgrade.values()) {
            boolean kind = role.isShield() == upgrade.shield();
            boolean expected = kind && !(upgrade == DeviceUpgrade.GATHER && role == RelicRole.TWINS_SHIELD);
            require(UpgradeRules.canPurchase(role, upgrade, rich) == expected, role + " buying " + upgrade);
        }
        require(UpgradeRules.canPurchase(RelicRole.RF_SHIELD, DeviceUpgrade.STABILIZATION, rich), "an RF shield may buy stabilization (Q5)");
        require(!UpgradeRules.canPurchase(RelicRole.RF_SHIELD, DeviceUpgrade.RESTORATION, new DeviceProgression(0, 2, 5, 0)), "restoration needs level 3");
        require(!UpgradeRules.canPurchase(RelicRole.RF_SHIELD, DeviceUpgrade.DISTRIBUTION, new DeviceProgression(0, 10, 0, 0)), "a purchase needs a point");
        DeviceProgression maxed = new DeviceProgression(0, 10, 5, 0).withRank(DeviceUpgrade.COMBAT.id(), 3);
        require(!UpgradeRules.canPurchase(RelicRole.RF_HIVE, DeviceUpgrade.COMBAT, maxed), "three ranks at most");
        DeviceProgression bought = UpgradeRules.afterPurchase(new DeviceProgression(40, 4, 2, 0), DeviceUpgrade.SUPPORT);
        require(bought.rank(DeviceUpgrade.SUPPORT.id()) == 1 && bought.points() == 1 && bought.level() == 4 && bought.experience() == 40,
                "a purchase adds a rank for a point");
        require(UpgradeRules.afterPurchase(bought, DeviceUpgrade.SUPPORT).rank(DeviceUpgrade.SUPPORT.id()) == 2, "ranks add up");
        require(UpgradeRules.canBuy(DeviceUpgrade.RECOVERY, new DeviceProgression(0, 4, 1, 0))
                && !UpgradeRules.canBuy(DeviceUpgrade.RECOVERY, new DeviceProgression(0, 3, 1, 0))
                && !UpgradeRules.canBuy(DeviceUpgrade.RECOVERY, new DeviceProgression(0, 4, 0, 0))
                && !UpgradeRules.canBuy(DeviceUpgrade.COMBAT, maxed), "the console offers what can be bought");
        require(UpgradeRules.button(DeviceUpgrade.COMBAT, new DeviceProgression(0, 0, 0, 0).withRank(DeviceUpgrade.COMBAT.id(), 3)) == UpgradeRules.Button.MAXED
                && UpgradeRules.button(DeviceUpgrade.RECOVERY, new DeviceProgression(0, 3, 0, 0)) == UpgradeRules.Button.NEEDS_LEVEL
                && UpgradeRules.button(DeviceUpgrade.RECOVERY, new DeviceProgression(0, 4, 0, 0)) == UpgradeRules.Button.NO_POINTS
                && UpgradeRules.button(DeviceUpgrade.RECOVERY, new DeviceProgression(0, 4, 1, 0)) == UpgradeRules.Button.BUY, "button precedence");
    }

    private static void batteries() {
        require(EnergyCosts.FE_PER_POINT == 10 && EnergyCosts.FE_TRANSFER_PER_TICK == 20_000 && EnergyCosts.SHIELD_UPKEEP == 20 && EnergyCosts.HIVE_UPKEEP == 20
                && EnergyCosts.ABSORB_PER_HP == 10 && EnergyCosts.REPAIR_PER_HP == 2 && EnergyCosts.SHOT == 3 && EnergyCosts.HEAL_PER_HP == 5
                && EnergyCosts.HIVE_REPAIR_PER_HP == 1 && EnergyCosts.STRIKE == 6 && EnergyCosts.MANA_CHARGE_PER_PULSE == 250, "energy costs");
        for (RelicRole role : RelicRole.values()) {
            String name = role.name();
            require(BatteryRules.hasRf(role) == (name.startsWith("RF_") && !role.equals(RelicRole.RF_DRONE) || name.equals("TWINS_SHIELD") || name.equals("TWINS_HIVE")),
                    role + " RF battery");
            require(BatteryRules.hasMana(role) == (name.startsWith("MANA_") && !role.equals(RelicRole.MANA_DRONE) || name.equals("TWINS_SHIELD") || name.equals("TWINS_HIVE")),
                    role + " mana battery");
        }
        require(BatteryRules.capacity(0) == 25_000 && BatteryRules.capacity(10) == 100_000 && BatteryRules.feCapacity(10) == 1_000_000, "battery capacity 25 000 to 100 000");
        require(BatteryRules.full(RelicRole.TWINS_HIVE, 2).equals(new DeviceEnergy(400_000, 40_000, true, true, DeviceEnergy.ManaSource.AUTO))
                && BatteryRules.full(RelicRole.MANA_SHIELD, 0).equals(new DeviceEnergy(0, 25_000, true, true, DeviceEnergy.ManaSource.AUTO)), "full batteries");
        requireSplit(100, 100, 20, 10, 10, "Twins share a cost evenly");
        requireSplit(100, 100, 21, 11, 10, "an odd point goes to the RF battery");
        requireSplit(3, 100, 20, 3, 17, "a short RF battery hands the rest to mana");
        requireSplit(100, 4, 20, 16, 4, "a short mana battery hands the rest to RF");
        requireSplit(50, 0, 20, 20, 0, "an RF-only device pays from RF");
        requireSplit(0, 50, 20, 0, 20, "a mana-only device pays from mana");
        DeviceEnergy twins = new DeviceEnergy(1_005, 100, true, true, DeviceEnergy.ManaSource.MAGIC);
        BatteryRules.Drain paid = BatteryRules.drain(RelicRole.TWINS_SHIELD, twins, 21);
        require(paid.paid() && paid.after().equals(new DeviceEnergy(895, 90, true, true, DeviceEnergy.ManaSource.MAGIC)), "a paid drain splits the cost");
        BatteryRules.Drain shortfall = BatteryRules.drain(RelicRole.TWINS_SHIELD, new DeviceEnergy(55, 3, true, true, DeviceEnergy.ManaSource.AUTO), 10);
        require(!shortfall.paid() && shortfall.after().equals(new DeviceEnergy(5, 0, true, true, DeviceEnergy.ManaSource.AUTO)),
                "a shortfall empties the usable batteries and keeps the RF below a point: " + shortfall);
        BatteryRules.Drain off = BatteryRules.drain(RelicRole.TWINS_HIVE, new DeviceEnergy(1_000, 50, false, true, DeviceEnergy.ManaSource.AUTO), 60);
        require(!off.paid() && off.after().equals(new DeviceEnergy(1_000, 0, false, true, DeviceEnergy.ManaSource.AUTO)), "a switched-off battery is never drained");
        require(BatteryRules.usableRf(RelicRole.MANA_HIVE, twins) == 0 && BatteryRules.usableRf(RelicRole.RF_HIVE, twins) == 100
                && BatteryRules.usableMana(RelicRole.RF_SHIELD, twins) == 0, "only a device's own batteries are usable");
        require(BatteryRules.acceptFe(RelicRole.RF_SHIELD, new DeviceEnergy(0, 0, true, true, null), 0, 50_000) == 20_000
                && BatteryRules.acceptFe(RelicRole.RF_SHIELD, new DeviceEnergy(249_990, 0, true, true, null), 0, 100) == 10
                && BatteryRules.acceptFe(RelicRole.RF_SHIELD, new DeviceEnergy(260_000, 0, true, true, null), 0, 100) == 0
                && BatteryRules.acceptFe(RelicRole.MANA_SHIELD, DeviceEnergy.EMPTY, 0, 100) == 0
                && BatteryRules.acceptFe(RelicRole.RF_SHIELD, DeviceEnergy.EMPTY, 0, 0) == 0, "FE insertion: 20 000 a tick, up to capacity, RF batteries only");
        require(ExperienceCurve.pointsForLevel(0) == 0 && ExperienceCurve.pointsForLevel(16) == 352 && ExperienceCurve.pointsForLevel(17) == 394
                && ExperienceCurve.pointsForLevel(30) == 1395 && ExperienceCurve.pointsForLevel(32) == 1628, "vanilla experience curve");
    }

    private static void requireSplit(int rf, int mana, int cost, int fromRf, int fromMana, String message) {
        int[] share = BatteryRules.split(rf, mana, cost);
        require(share[0] == fromRf && share[1] == fromMana, message + ": got " + share[0] + "/" + share[1]);
    }

    private static void shieldStats() {
        require(ShieldStats.capacity(0) == 504 && ShieldStats.capacity(5) == 2752 && ShieldStats.capacity(10) == 5000, "buffer capacity 504 to 5 000");
        require(ShieldStats.totalCapacity(0) == 504 + ShieldTopology.CELL_COUNT * 12 && ShieldStats.totalCapacity(10) == 10_040, "total capacity adds every cell");
        same(ShieldStats.radiusLimit(0), 2, "radius limit at level 0");
        same(ShieldStats.radiusLimit(10), 12, "radius limit at level 10");
        same(ShieldStats.maxRadius(8, 10), 8, "the server limit caps the radius");
        same(ShieldStats.maxRadius(24, 3), 5, "the level caps the radius");
        same(ShieldStats.radius(5, new ShieldSettings(9, "all")), 5, "a chosen radius beyond the maximum is cut");
        same(ShieldStats.radius(12, new ShieldSettings(9, "all")), 9, "the chosen radius");
        float[][] strike = {{3F, 5F, 7F}, {2.5F, 4.25F, 6F}, {4F, 6.5F, 9F}};
        RelicRole[] shields = RelicRole.shields();
        for (int role = 0; role < shields.length; role++) for (int index = 0; index < 3; index++) {
            int level = index * 5;
            same(ShieldStats.strikeDamage(Optional.of(shields[role]), level, 1), strike[role][index], shields[role] + " strike at " + level);
            same(ShieldStats.strikeDamage(Optional.of(shields[role]), level, 1.5), strike[role][index] * 1.5F, shields[role] + " strike at " + level + " times 1.5");
        }
        same(ShieldStats.strikeDamage(Optional.of(RelicRole.RF_HIVE), 10, 1), 0F, "a hive strikes nothing");
        same(ShieldStats.strikeDamage(Optional.empty(), 10, 1), 0F, "no device strikes nothing");
        same(ShieldStats.strikeKnockback(RelicRole.RF_SHIELD, 1), 1, "RF knockback");
        same(ShieldStats.strikeKnockback(RelicRole.MANA_SHIELD, 1), .8, "Mana knockback");
        same(ShieldStats.strikeKnockback(RelicRole.TWINS_SHIELD, .5), .6, "Twins knockback times the server multiplier");
    }

    private static void shieldUpgrades() {
        double[][] sharing = {{0, 0.31666666666666665, 0.3833333333333333, 0.45000000000000007}, {0, .25, .3, .35}, {0, 0.39999999999999997, .45, .5}};
        int[][] gathering = {{0, 1, 2, 2}, {0, 1, 2, 3}, {0, 0, 0, 0}};
        int[][] repair = {{1, 2, 2, 2}, {1, 2, 3, 3}, {1, 2, 2, 2}};
        int[][] quiet = {{40, 40, 40, 40}, {40, 40, 40, 40}, {40, 32, 24, 16}};
        RelicRole[] shields = RelicRole.shields();
        for (int role = 0; role < shields.length; role++) for (int rank = 0; rank <= 3; rank++) {
            same(ShieldUpgradeEffects.sharing(shields[role], rank), sharing[role][rank], shields[role] + " sharing at rank " + rank);
            require(ShieldUpgradeEffects.gathering(shields[role], rank) == gathering[role][rank], shields[role] + " gathering at rank " + rank);
            require(ShieldUpgradeEffects.repairSteps(shields[role], rank) == repair[role][rank], shields[role] + " repair steps at rank " + rank);
            require(ShieldUpgradeEffects.quietTicks(shields[role], rank) == quiet[role][rank], shields[role] + " quiet ticks at rank " + rank);
        }
        for (int rank = 0; rank <= 5; rank++) require(ShieldUpgradeEffects.gathering(RelicRole.TWINS_SHIELD, rank) == 0, "Twins never gather, rank " + rank);
    }

    private static void hiveUpgrades() {
        double[][] damage = {{1, 1.1, 1.2, 1.3}, {1, 1.08, 1.16, 1.24}, {1, 1.12, 1.24, 1.3599999999999999}};
        double[][] healing = {{1, 1.1666666666666667, 1.3333333333333333, 1.5}, {1, 1.3333333333333333, 1.6666666666666665, 2}, {1, 1.25, 1.5, 1.75}};
        double[][] rebuild = {{1, .9, .8, .7}, {1, 0.9333333333333333, 0.8666666666666667, .8}, {1, 0.8833333333333333, 0.7666666666666667, .65}};
        for (HiveType type : HiveType.values()) {
            for (int rank = 0; rank <= 3; rank++) {
                same(HiveUpgradeEffects.damage(type, rank), damage[type.ordinal()][rank], type + " damage at rank " + rank);
                same(HiveUpgradeEffects.healing(type, rank), healing[type.ordinal()][rank], type + " healing at rank " + rank);
                same(HiveUpgradeEffects.rebuild(type, rank), rebuild[type.ordinal()][rank], type + " rebuild at rank " + rank);
            }
            same(HiveUpgradeEffects.damage(type, 7), damage[type.ordinal()][3], type + " damage counts ranks above 3 as 3");
            same(HiveUpgradeEffects.rebuild(type, 7), rebuild[type.ordinal()][3], type + " rebuild counts ranks above 3 as 3");
        }
        // The code multiplies by (cap / 3D); r * cap / 3D would be 0.7999999999999999 and 0.6500000000000001.
        same(HiveUpgradeEffects.rebuild(HiveType.MANA, 3), 1 - 3 * (.20 / 3D), "Mana rebuild at rank 3 is r * (cap / 3D)");
        require(HiveUpgradeEffects.rebuild(HiveType.MANA, 3) != 1 - 3 * .20 / 3D, "Mana rebuild at rank 3 is not r * cap / 3D");
        same(HiveUpgradeEffects.rebuild(HiveType.TWINS, 3), 1 - 3 * (.35 / 3D), "Twins rebuild at rank 3 is r * (cap / 3D)");
        require(HiveUpgradeEffects.rebuild(HiveType.TWINS, 3) != 1 - 3 * .35 / 3D, "Twins rebuild at rank 3 is not r * cap / 3D");
    }

    private static void passPolicy() {
        for (int row = 0; row < 16; row++) {
            boolean pass = (row & 8) != 0, tag = (row & 4) != 0, absorb = (row & 2) != 0, strike = (row & 1) != 0;
            List<String> asked = new ArrayList<>();
            boolean passes = DamagePassPolicy.passesField(new DamageFacts() {
                @Override public boolean passListed() { asked.add("pass"); return pass; }
                @Override public boolean inPassTag() { asked.add("tag"); return tag; }
                @Override public boolean absorbListed() { asked.add("absorb"); return absorb; }
                @Override public boolean strike() { asked.add("strike"); return strike; }
            });
            boolean expected = pass || tag && (!absorb || strike);
            List<String> expectedAsked = pass ? List.of("pass") : !tag ? List.of("pass", "tag") : !absorb ? List.of("pass", "tag", "absorb")
                    : List.of("pass", "tag", "absorb", "strike");
            require(passes == expected, "pass policy row " + row + ": pass=" + pass + " tag=" + tag + " absorb=" + absorb + " strike=" + strike);
            require(asked.equals(expectedAsked), "pass policy row " + row + " asks " + expectedAsked + ", asked " + asked);
        }
    }

    private static void immunity() {
        HitImmunity first = HitImmunity.record(null, 100, 1);
        require(first.equals(new HitImmunity(100, 1)), "the first absorbed hit opens the window");
        HitImmunity second = HitImmunity.record(first, 104, 2);
        require(second.equals(new HitImmunity(104, 3)), "a hit within the window adds to it: " + second);
        HitImmunity third = HitImmunity.record(second, 113, 1);
        require(third.equals(new HitImmunity(113, 4)), "the window moves with each hit: " + third);
        require(third.covers(113) && third.covers(122) && !third.covers(123) && !third.covers(112), "the window lasts 10 ticks from the last hit");
        require(HitImmunity.record(third, 123, 5).equals(new HitImmunity(123, 5)), "after the window a hit starts afresh");
        long late = (1L << 24) + 1;
        HitImmunity rounded = HitImmunity.record(null, late, 1);
        require(rounded.tick() == 16_777_216F, "the tick is stored as a float and rounds at 2^24 + 1 (Q1)");
        require(rounded.covers(late) && rounded.covers(late + 8) && !rounded.covers(late + 9) && !rounded.covers(late - 2),
                "the window follows the rounded tick");
    }

    private static void effectTrim() {
        require(EffectTrim.kept(true, 6, 2, 200) == 50, "a hit that got a quarter through keeps a quarter of 200 ticks");
        require(EffectTrim.removed(false, 200, 50) == 150 && EffectTrim.removed(true, 200, 50) == 1200, "the rest is removed; an endless effect counts 1 200");
        require(EffectTrim.cost(150, 0) == 15 && EffectTrim.cost(1200, 1) == 240 && EffectTrim.cost(1, 0) == 1 && EffectTrim.cost(0, 0) == 1,
                "two points per second per level, at least one");
        require(EffectTrim.kept(false, 6, 2, 200) == 0 && EffectTrim.kept(true, 6, 0, 200) == 0, "an untouched or fully stopped hit keeps nothing");
        require(EffectTrim.kept(true, 0, 0.00048828125F, 1000) == 488, "the share's denominator is at least 1e-3");
    }

    private static void projectiles() {
        String[] names = {"removed", "ignored", "noPhysics", "intercept", "trident", "arrow", "tag"};
        for (int bits = 0; bits < 128; bits++) {
            Projectile facts = new Projectile(bits, false, 0, 0, ProjectileFacts.CostClass.OTHER_4);
            boolean expected;
            int asked;
            if ((bits & 1) != 0) { expected = false; asked = 1; }
            else if ((bits & 2) != 0) { expected = false; asked = 2; }
            else if ((bits & 4) != 0) { expected = false; asked = 3; }
            else if ((bits & 8) != 0) { expected = true; asked = 4; }
            else if ((bits & 16) != 0) { expected = false; asked = 5; }
            else if ((bits & 32) != 0) { expected = true; asked = 6; }
            else { expected = (bits & 64) != 0; asked = 7; }
            require(ProjectilePolicy.supported(facts) == expected, "supported projectile, facts " + Integer.toBinaryString(bits));
            require(facts.asked.equals(List.of(names).subList(0, asked)), "supported asks in order, facts " + Integer.toBinaryString(bits) + ": " + facts.asked);
        }
        int[] classCost = {8, 5, 1, 4};
        for (ProjectileFacts.CostClass costClass : ProjectileFacts.CostClass.values()) {
            require(ProjectilePolicy.impactCost(new Projectile(0, true, 9, 9, costClass)) == classCost[costClass.ordinal()], costClass + " costs " + classCost[costClass.ordinal()]);
        }
        require(arrowCost(2, 3, false) == 6 && arrowCost(2, 3, true) == 10, "an arrow costs its damage; a critical one ceil(d) * 1.5 + 1");
        require(arrowCost(2, 2.9, false) == 6 && arrowCost(2, 2.9, true) == 10, "arrow costs round up");
        require(arrowCost(1e-6, 1, false) == 1 && arrowCost(5000, 3, false) == 10_000 && arrowCost(Double.NaN, 1, false) == 10_000
                && arrowCost(Double.POSITIVE_INFINITY, 1, true) == 10_000, "arrow costs stay within 1 to 10 000");
    }

    private static int arrowCost(double base, double speed, boolean crit) {
        return ProjectilePolicy.impactCost(new Projectile(32, crit, base, speed, ProjectileFacts.CostClass.OTHER_4));
    }

    /** A projectile's facts from bits (removed, ignored, no physics, intercept listed, trident, arrow, tag), recording what is asked. */
    private static final class Projectile implements ProjectileFacts {
        final List<String> asked = new ArrayList<>();
        private final int bits;
        private final boolean crit;
        private final double base, speed;
        private final CostClass costClass;

        Projectile(int bits, boolean crit, double base, double speed, CostClass costClass) {
            this.bits = bits;
            this.crit = crit;
            this.base = base;
            this.speed = speed;
            this.costClass = costClass;
        }

        private boolean bit(int index, String name) { asked.add(name); return (bits & 1 << index) != 0; }
        @Override public boolean removed() { return bit(0, "removed"); }
        @Override public boolean ignoredListed() { return bit(1, "ignored"); }
        @Override public boolean noPhysicsArrow() { return bit(2, "noPhysics"); }
        @Override public boolean interceptListed() { return bit(3, "intercept"); }
        @Override public boolean trident() { return bit(4, "trident"); }
        @Override public boolean arrow() { return bit(5, "arrow"); }
        @Override public boolean inInterceptTag() { return bit(6, "tag"); }
        @Override public boolean crit() { return crit; }
        @Override public double baseDamage() { return base; }
        @Override public double speed() { return speed; }
        @Override public CostClass costClass() { return costClass; }
    }

    private static void coverage() {
        Vec3d feet = new Vec3d(10, 64, -3);
        require(CoverageRule.withinRadius(new Vec3d(13, 64 + ShieldField.CENTER_Y, -3), feet, 3), "a creature exactly at the radius is covered");
        require(!CoverageRule.withinRadius(new Vec3d(13.001, 64 + ShieldField.CENTER_Y, -3), feet, 3), "a creature beyond the radius is not");
        require(CoverageRule.withinRadius(new Vec3d(10, 66.9, -3), feet, 2) && !CoverageRule.withinRadius(new Vec3d(10, 62.9, -3), feet, 2),
                "the field is centred above the owner's feet");
        require(Coverage.of("owner") == Coverage.OWNER && Coverage.of("allies") == Coverage.ALLIES && Coverage.of("all") == Coverage.ALL
                && Coverage.of("bogus") == Coverage.ALLIES && Coverage.of(null) == Coverage.ALLIES, "coverage ids; unknown means allies");
    }

    private static void barrier() {
        require(BarrierPush.PUSH_COST == 1, "holding a mob out costs a point a tick");
        same(BarrierPush.reach(2, .6F), 2.300000011920929, "a mob touches the shell half its (float) width out");
        sameVector(BarrierPush.outward(new Vec3d(3, 4, 0), 5, new Vec3d(0, 0, 1)), new Vec3d(0.6000000000000001, .8, 0), "outward from the centre");
        sameVector(BarrierPush.outward(Vec3d.ZERO, 0, new Vec3d(.6, -.8, 0)), new Vec3d(1, -0.0, 0), "a mob on the centre goes along the owner's level look");
        sameVector(BarrierPush.outward(Vec3d.ZERO, 0, new Vec3d(0, 1, 0)), new Vec3d(1, 0, 0), "no direction at all falls back to +x");
        sameVector(BarrierPush.horizontal(new Vec3d(.6, .8, 0)), new Vec3d(1, 0, 0), "the ground-plane direction");
        sameVector(BarrierPush.horizontal(new Vec3d(0, 1, 0)), new Vec3d(1, 0, 0), "straight up falls back to +x");
        same(BarrierPush.step(.2), .2, "a shallow mob moves out all the way");
        same(BarrierPush.step(3), 1.5, "at most 1.5 blocks a tick");
        require(BarrierPush.needsDrift(new Vec3d(.3, 0, 0), new Vec3d(1, 0, 0)) && !BarrierPush.needsDrift(new Vec3d(.35, 0, 0), new Vec3d(1, 0, 0)),
                "a mob already drifting out at .35 is left alone");
        sameVector(BarrierPush.drift(new Vec3d(1, 0, 0), new Vec3d(0, -.2, 0)), new Vec3d(.35, .05, 0), "drift lifts a falling mob a little");
        sameVector(BarrierPush.drift(new Vec3d(0, 0, -1), new Vec3d(0, .3, 0)), new Vec3d(0, .3, -.35), "drift keeps a rising mob's lift");
    }

    private static void cells() {
        ShieldTopology topology = ShieldTopology.INSTANCE;
        require(topology.cells()[CellSelection.select(0, new Vec3d(0, 0, 1))].panel() == ShieldTopology.PANEL_FRONT
                && topology.cells()[CellSelection.select(0, new Vec3d(0, 0, -1))].panel() == ShieldTopology.PANEL_BACK
                && topology.cells()[CellSelection.select(0, new Vec3d(1, 0, 0))].panel() == ShieldTopology.PANEL_LEFT
                && topology.cells()[CellSelection.select(0, new Vec3d(-1, 0, 0))].panel() == ShieldTopology.PANEL_RIGHT, "hits land on the facing sectors");
        require(CellSelection.select(90, new Vec3d(-1, 0, 0)) == CellSelection.select(0, new Vec3d(0, 0, 1)), "the wearer's yaw turns the frame");
        require(CellSelection.select(0, new Vec3d(0, 0, 1)) == topology.nearest(0, 0, 1) && CellSelection.select(35, new Vec3d(0, 1, 0)) == topology.nearest(0, 1, 0),
                "the nearest cell in the wearer's frame");
    }

    private static void strikes() {
        require(StrikeRules.damageKind(RelicRole.RF_SHIELD) == DamageKind.SHIELD_DISCHARGE && StrikeRules.damageKind(RelicRole.MANA_SHIELD) == DamageKind.SHIELD_MANA_BURST
                && StrikeRules.damageKind(RelicRole.TWINS_SHIELD) == DamageKind.SHIELD_TWIN_SURGE, "each shell strikes with its own damage type");
        require(String.join(",", java.util.Arrays.stream(DamageKind.values()).map(DamageKind::id).toList())
                .equals("drone_shot,swarm_strike,swarm_void,swarm_reflect,shield_discharge,shield_mana_burst,shield_twin_surge"), "damage type ids");
    }

    private static void swarm() {
        for (HiveType type : HiveType.values()) {
            require(SwarmRules.capacity(type, 0) == 100 && SwarmRules.capacity(type, 5) == 1050 && SwarmRules.capacity(type, 10) == HiveType.MAX_DRONES, type + " swarm size");
            same(SwarmRules.attackDamageBase(type, 10), type == HiveType.TWINS ? 4 : 3, type + " damage at level 10");
            same(SwarmRules.cooldown(type, 0), type.initialCooldown, type + " cooldown at level 0");
        }
        require(SwarmRules.strikeInterval(0) == 100 && SwarmRules.strikeInterval(5) == 70 && SwarmRules.strikeInterval(10) == 40, "strike interval 5 s down to 2 s");
        require(SwarmRules.SETTLE_QUIET_TICKS == 120 && SwarmRules.SETTLE_PERIOD == 10, "settle rhythm");
        HiveStackState before = new HiveStackState(true, List.of(new HiveStackState.Unit(1, 0, -1, 0), new HiveStackState.Unit(3, 0, -1, 0)));
        HiveStackState after = new HiveStackState(true, List.of(HiveStackState.Unit.fresh(), new HiveStackState.Unit(2, 0, -1, 0), HiveStackState.Unit.fresh()));
        require(SwarmRules.restoredHealth(before, after) == 2, "only existing drones' regained HP is paid for");
        List<HiveCombatState.Shot> shots = new ArrayList<>();
        for (int n = 0; n < 130; n++) shots.add(new HiveCombatState.Shot(n % 7, 900 + n, HiveCombatState.BALL, 0, 0, 0, 1, 1, 1, 900 + n));
        List<HiveCombatState.Shot> recent = ShotLog.recent(shots, 1000);
        require(recent.size() == 70 && recent.get(0).impactAt() == 960 && recent.get(69).impactAt() == 1029, "events fade after 40 ticks: " + recent.size());
        List<HiveCombatState.Shot> burst = new ArrayList<>();
        for (int n = 0; n < 150; n++) burst.add(new HiveCombatState.Shot(0, 850 + n, HiveCombatState.ZAP, 0, 0, 0, 1, 1, 1, 1000));
        List<HiveCombatState.Shot> newest = ShotLog.recent(burst, 1000);
        require(newest.size() == HiveCombatState.MAX_SHOTS && newest.get(0).firedAt() == 900 && newest.get(99).firedAt() == 999,
                "at most the newest 100 events are kept");
    }

    private static void drones() {
        require(!DroneDamage.reeling(10) && DroneDamage.reeling(11), "a target reels while its hurt immunity is above 10");
        require(DroneDamage.swingDamage(5.99) == 1 && DroneDamage.swingDamage(6) == 2, "heavy hitters knock two HP off a drone");
        double radius = 2, reach = radius * 2;
        int[] expected = {3, 3, 2, 1, 0};
        for (int distance = 0; distance <= 4; distance++) {
            require(DroneDamage.explosionDamage(distance, reach) == expected[distance], "blast damage ceil(3(1 - d/2r)) at " + distance);
        }
        require(DroneDamage.away(0, 80, .9) == 72 && DroneDamage.away(-2, 80, .9) == 72 && DroneDamage.away(2, 80, .9) == 36 && DroneDamage.away(1, 80, 1) == 80,
                "a destroyed drone waits the cooldown, a damaged one 40 ticks per lost HP");
        require(DroneDamage.backAt(100, 36) == 160, "home after the 24-tick flight back and the time away");
        same(SwarmStrike.knockback(1), .38, "knockback of one drone");
        same(SwarmStrike.knockback(10), 0.6499999999999999, "knockback of ten drones");
        same(SwarmStrike.knockback(35), 1.4, "knockback caps at 1.4");
        same(SwarmStrike.knockback(500), 1.4, "knockback stays capped");
        require(SwarmStrike.containmentDrain(4) == 1 && SwarmStrike.containmentDrain(5) == 1 && SwarmStrike.containmentDrain(24) == 4
                && SwarmStrike.containmentDrain(250) == 50, "containment costs a point per five drones");
        require(SwarmStrike.COST_PER_DRONE == 1 && SwarmStrike.CONTAINMENT_DAMAGE == .12F && SwarmStrike.SPLASH_DAMAGE == .4F && SwarmStrike.SPLASH_INFLATE == 2
                && SwarmStrike.SPLASH_DISTANCE_SQR == 4 && SwarmStrike.SPLASH_KNOCKBACK == .5 && SwarmStrike.SWING_INFLATE == 1.5 && SwarmStrike.SWING_PERIOD == 20
                && SwarmStrike.SWING_PHASE == 7 && SwarmStrike.CONTAINMENT_PERIOD == 20 && SwarmStrike.WARD_PER_DRONE == .5 && SwarmStrike.WARD_HUM_PERIOD == 60
                && SwarmStrike.TARGET_REFRESH_PERIOD == 5 && SwarmStrike.TARGET_MEMORY_TICKS == 100 && SwarmStrike.EXPLOSION_OWNER_RANGE_SQR == 40_000, "swarm strike numbers");
    }

    private static void flights() {
        Vec3d start = Vec3d.ZERO, end = new Vec3d(3, 4, 0);
        sameVector(ChargeFlight.velocity(start, end), new Vec3d(.54, 0.7200000000000001, 0), "a charge flies at .9 a tick");
        require(ChargeFlight.flightTicks(start, end) == 6 && ChargeFlight.flightTicks(start, start) == 1 && ChargeFlight.flightTicks(start, new Vec3d(0, 0, .9)) == 1
                && ChargeFlight.flightTicks(start, new Vec3d(0, 0, .9000001)) == 2, "flight ticks round up, at least one");
        require(ChargeFlight.expiresAt(500, start, end) == 546 && ChargeFlight.arrival(500, start, end) == 506, "expiry 40 ticks after the arrival");
        sameVector(ChargeFlight.homing(new Vec3d(0, 0, 10), new Vec3d(0, 0, 5), new Vec3d(1, 0, 0)), new Vec3d(0, 0, .9), "a charge homes in on the target");
        Vec3d course = new Vec3d(.1, .2, .3);
        require(ChargeFlight.homing(new Vec3d(1, 1, 1), new Vec3d(1, 1, 1.0005), course) == course, "a charge at the target keeps its course");
    }

    private static void healing() {
        long[] firstMends = {1020, 1040, 1060, 1080, 1100, 1020, 1040};
        for (int index = 0; index < firstMends.length; index++) require(HealingPolicy.firstMendAt(1000, index) == firstMends[index], "healer " + index + " first mends in turn");
        same(HealingPolicy.request(1, 10, 5), .5F, "a healer mends half a point");
        same(HealingPolicy.request(1 + 1 * (.5 / 3D), 10, 5), 0.5833333F, "the support upgrade scales it");
        same(HealingPolicy.request(2, .3F, 5), .3F, "within the budget left");
        same(HealingPolicy.request(1, 10, .2F), .2F, "within what is missing");
        require(HealingPolicy.cost(.5F) == 3 && HealingPolicy.cost(1) == 5 && HealingPolicy.cost(0) == 0 && HealingPolicy.cost(0.5833333F) == 3, "five points per HP, rounded up");
        require(HealingPolicy.PERIOD == 20 && HealingPolicy.NEXT_MEND == 100, "healing rhythm");
    }

    private static void holds() {
        require(HoldPolicy.joinsOther(false, 10, 10) && HoldPolicy.joinsOther(false, 10, 11) && !HoldPolicy.joinsOther(false, 10, 12)
                && !HoldPolicy.joinsOther(true, 10, 11) && !HoldPolicy.joinsOther(false, 10, 9), "a fresh hold of another swarm is joined");
        require(!HoldPolicy.stale(true, 10, 12) && HoldPolicy.stale(true, 10, 13) && HoldPolicy.stale(false, 10, 10) && HoldPolicy.stale(true, 10, 9),
                "a hold not refreshed for two ticks, from another clock, or of a dead target is released");
        Vec3d anchor = new Vec3d(.5, 64, .5);
        sameVector(HoldPolicy.pinPoint(anchor, true, 1, 4, 100, 100), new Vec3d(.5, 65, .5), "the lift starts where the target was (t = 0)");
        sameVector(HoldPolicy.pinPoint(anchor, true, 1, 4, 100, 115), new Vec3d(.5, 66.5, .5), "half way through the easing (t = .5)");
        sameVector(HoldPolicy.pinPoint(anchor, true, 1, 4, 100, 130), new Vec3d(.5, 68, .5), "the lift ends at its height (t = 1)");
        sameVector(HoldPolicy.pinPoint(anchor, true, 1, 4, 100, 400), new Vec3d(.5, 68, .5), "and stays there");
        require(HoldPolicy.pinPoint(anchor, false, 1, 4, 100, 115) == anchor, "other holds keep the target where it stood");
        same(HoldPolicy.refill(3, 10, 4), 7, "the ward fills");
        same(HoldPolicy.refill(8, 10, 4), 10, "up to its capacity");
        same(HoldPolicy.refill(8, 10, -4), 8, "never down");
        require(HoldPolicy.wardMax(0) == 1 && HoldPolicy.wardMax(24) == 24, "one ward point per drone, at least one");
        require(HoldPolicy.LIFT == 4 && HoldPolicy.LIFT_TICKS == 30 && HoldPolicy.GROUND_PROBE == 6, "hold numbers");
    }

    private static void status() {
        for (int bits = 0; bits < 8; bits++) for (int integrity : new int[]{0, 5}) {
            boolean slotActive = (bits & 1) != 0, enabled = (bits & 2) != 0, active = (bits & 4) != 0;
            ShieldStatus expected = !slotActive ? ShieldStatus.INACTIVE_SLOT : !enabled ? ShieldStatus.DISABLED : !active ? ShieldStatus.PRIORITY
                    : integrity == 0 ? ShieldStatus.BROKEN : ShieldStatus.ACTIVE;
            require(ShieldStatus.of(slotActive, enabled, active, integrity) == expected, "status precedence " + bits + "/" + integrity);
        }
        require(String.join(",", java.util.Arrays.stream(ShieldStatus.values()).map(ShieldStatus::id).toList()).equals("inactive_slot,disabled,priority,broken,active"),
                "status ids (message keys)");
    }

    private static void wear() {
        require(HiveWearRule.allows(Optional.empty(), "charm", 0), "no curio inventory: anything goes");
        require(HiveWearRule.allows(Optional.of(List.of()), "charm", 1), "no hive worn yet");
        require(HiveWearRule.allows(Optional.of(List.of(new WornSlot("charm", 1))), "charm", 1), "the worn hive may stay in its slot");
        require(!HiveWearRule.allows(Optional.of(List.of(new WornSlot("charm", 0))), "charm", 1), "a second hive in another slot is refused");
        require(!HiveWearRule.allows(Optional.of(List.of(new WornSlot("ring", 1))), "charm", 1), "slots of another type count too");
        require(!HiveWearRule.allows(Optional.of(List.of(new WornSlot("charm", 1), new WornSlot("charm", 0))), "charm", 1), "every worn hive must be in the slot");
    }

    private static void same(double actual, double expected, String message) {
        require(Double.doubleToLongBits(actual) == Double.doubleToLongBits(expected), message + ": expected " + expected + ", got " + actual);
    }

    private static void same(float actual, float expected, String message) {
        require(Float.floatToIntBits(actual) == Float.floatToIntBits(expected), message + ": expected " + expected + ", got " + actual);
    }

    private static void sameVector(Vec3d actual, Vec3d expected, String message) {
        require(Double.doubleToLongBits(actual.x) == Double.doubleToLongBits(expected.x) && Double.doubleToLongBits(actual.y) == Double.doubleToLongBits(expected.y)
                && Double.doubleToLongBits(actual.z) == Double.doubleToLongBits(expected.z), message + ": expected " + expected + ", got " + actual);
    }

    private static void throwsIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }

    private static void throwsNull(Runnable action, String message) {
        try {
            action.run();
        } catch (NullPointerException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message + ": expected NullPointerException");
    }

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
