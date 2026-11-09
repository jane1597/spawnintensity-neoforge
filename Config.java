package com.spawnintensity;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Settings, saved in config/spawnintensity-common.toml. */
public final class Config {
    public enum SpawnFilter { ALL, HOSTILE, ANIMALS }

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue SPAWN_MULT =
            B.comment("Mob spawn multiplier (1 = normal)").defineInRange("spawnMultiplier", 1, 1, 100);
    public static final ModConfigSpec.EnumValue<SpawnFilter> FILTER =
            B.comment("Which mobs the spawn multiplier affects. HOSTILE includes every modded hostile mob.")
                    .defineEnum("spawnFilter", SpawnFilter.ALL);
    public static final ModConfigSpec.IntValue MOB_CAP =
            B.comment("Safety cap: no extra spawns once this many mobs (of the filtered kind) exist")
                    .defineInRange("mobCap", 400, 100, 1000);
    public static final ModConfigSpec.IntValue GROWTH =
            B.comment("Growth speed (sets randomTickSpeed). 1 = vanilla").defineInRange("growthSpeed", 1, 1, 100);
    public static final ModConfigSpec.IntValue LOOT_MULT =
            B.comment("Mob loot drop multiplier").defineInRange("lootMultiplier", 1, 1, 100);
    public static final ModConfigSpec.IntValue BLOCK_MULT =
            B.comment("Block drop multiplier").defineInRange("blockMultiplier", 1, 1, 100);
    public static final ModConfigSpec.IntValue CHEST_MULT =
            B.comment("Chest loot multiplier (new loot chests)").defineInRange("chestMultiplier", 1, 1, 100);
    public static final ModConfigSpec.IntValue DESPAWN_SECONDS =
            B.comment("Dropped items despawn after this many seconds (300 = vanilla)")
                    .defineInRange("itemDespawnSeconds", 300, 5, 300);
    public static final ModConfigSpec.IntValue BREED_COOLDOWN =
            B.comment("Seconds before animals can breed again after breeding (300 = vanilla, 0 = no cooldown)")
                    .defineInRange("breedingCooldownSeconds", 300, 0, 300);
    public static final ModConfigSpec.IntValue BABY_GROWTH =
            B.comment("Seconds for a baby animal to grow up (1200 = vanilla, 10 = very fast)")
                    .defineInRange("babyGrowthSeconds", 1200, 10, 1200);
    public static final ModConfigSpec.BooleanValue APOCALYPSE =
            B.comment("Apocalypse mode: only zombies, they spawn by day, new zombie loot").define("apocalypse", false);

    public static final ModConfigSpec.ConfigValue<String> SPAWN_TYPES =
            B.comment("Spawn types that get multiplied (comma separated). Options: NATURAL, CHUNK_GENERATION, STRUCTURE,",
                            "SPAWNER, TRIGGERED, EVENT, PATROL, REINFORCEMENT, MOB_SUMMONED, JOCKEY, BREEDING, CONVERSION, COMMAND, SPAWN_EGG ...",
                            "Default only multiplies the normal world spawns.")
                    .define("spawnTypes", "NATURAL,CHUNK_GENERATION,STRUCTURE");
    public static final ModConfigSpec.BooleanValue MULTIPLY_UNTRACKED =
            B.comment("Some modded mobs are put into the world by their mod WITHOUT going through the normal spawn event.",
                            "true = multiply those too (this is what makes modded enemies work). Babies and riders are skipped.")
                    .define("multiplyUntracked", true);
    public static final ModConfigSpec.ConfigValue<String> EXTRA_HOSTILE =
            B.comment("Extra mobs to treat as HOSTILE (comma separated). Use exact ids like \"somemod:thing\" or a whole mod like \"somemod:*\".",
                            "Only needed for a modded enemy that is not marked as a monster by its author.")
                    .define("extraHostile", "");
    public static final ModConfigSpec.ConfigValue<String> SPAWN_BLACKLIST =
            B.comment("Mobs that are never duplicated or removed (comma separated). Exact ids like \"somemod:big_boss\" or \"somemod:*\"")
                    .define("spawnBlacklist", "");

    public static final ModConfigSpec SPEC = B.build();

    private Config() {}

    private static int get(ModConfigSpec.IntValue v, int def) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return def;
        }
    }

    private static String str(ModConfigSpec.ConfigValue<String> v, String def) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return def;
        }
    }

    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static int spawnMult() { return get(SPAWN_MULT, 1); }
    public static int mobCap() { return get(MOB_CAP, 400); }
    public static int growth() { return get(GROWTH, 1); }
    public static int lootMult() { return get(LOOT_MULT, 1); }
    public static int blockMult() { return get(BLOCK_MULT, 1); }
    public static int chestMult() { return get(CHEST_MULT, 1); }
    public static int breedCooldown() { return get(BREED_COOLDOWN, 300); }
    public static int babyGrowth() { return get(BABY_GROWTH, 1200); }
    public static int despawnSeconds() { return get(DESPAWN_SECONDS, 300); }

    public static List<String> blacklist() { return split(str(SPAWN_BLACKLIST, "")); }
    public static List<String> extraHostile() { return split(str(EXTRA_HOSTILE, "")); }

    public static List<String> spawnTypes() {
        List<String> l = new ArrayList<>();
        for (String s : split(str(SPAWN_TYPES, "NATURAL,CHUNK_GENERATION,STRUCTURE"))) l.add(s.toUpperCase(Locale.ROOT));
        return l;
    }

    public static boolean multiplyUntracked() {
        try {
            return MULTIPLY_UNTRACKED.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static SpawnFilter filter() {
        try {
            return FILTER.get();
        } catch (IllegalStateException e) {
            return SpawnFilter.ALL;
        }
    }

    public static boolean apocalypse() {
        try {
            return APOCALYPSE.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static void set(ModConfigSpec.IntValue v, int value) {
        v.set(value);
        v.save();
    }

    public static void setFilter(SpawnFilter f) {
        FILTER.set(f);
        FILTER.save();
    }

    public static void setApocalypse(boolean on) {
        APOCALYPSE.set(on);
        APOCALYPSE.save();
    }
}
