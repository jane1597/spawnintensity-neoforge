package com.spawnintensity;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

public class Handlers {
    private static final String CONTROLLER_NAME = "Spawn Sit";

    private record Pending(ServerLevel level, EntityType<?> type, double x, double y, double z) {}

    private final Deque<Pending> queue = new ArrayDeque<>();
    private int tick = 0;

    // Counts of existing mobs, refreshed once a second (used by the safety cap)
    private int countAll = 0;
    private int countHostile = 0;
    private int countAnimal = 0;

    /**
     * Every mob that already went through the spawn event, or that this mod created itself.
     * The join handler skips these so nothing is ever multiplied twice.
     */
    private final Set<Entity> seen = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    // ---------------------------------------------------------------- helpers

    public static boolean isAllowedZombie(Entity e) {
        return e instanceof Zombie && !(e instanceof ZombifiedPiglin);
    }

    private static boolean isBoss(Entity e) {
        return e instanceof EnderDragon || e instanceof WitherBoss || e.getType().is(Tags.EntityTypes.BOSSES);
    }

    private static boolean idMatches(Entity e, List<String> list) {
        if (list.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        if (id == null) return false;
        for (String s : list) {
            if (s.equals(id.toString())) return true;
            if (s.endsWith(":*") && id.getNamespace().equals(s.substring(0, s.length() - 2))) return true;
        }
        return false;
    }

    /**
     * Hostile = vanilla monsters AND any modded mob that implements Enemy, uses the MONSTER
     * category, or is listed in the extraHostile config.
     */
    public static boolean isHostile(Entity e) {
        return e instanceof Enemy
                || e.getType().getCategory() == MobCategory.MONSTER
                || idMatches(e, Config.extraHostile());
    }

    private static boolean isAnimal(Entity e) {
        return !isHostile(e) && (e instanceof Animal || e.getType().getCategory() == MobCategory.CREATURE);
    }

    private static boolean isHostileNonZombie(Entity e) {
        return isHostile(e) && !isAllowedZombie(e) && !isBoss(e) && !blacklisted(e);
    }

    /** config: spawnBlacklist, exact ids or "modid:*" */
    private static boolean blacklisted(Entity e) {
        return idMatches(e, Config.blacklist());
    }

    /** Mobs that are never duplicated. */
    private static boolean neverClone(Entity e) {
        return isBoss(e) || e instanceof Warden || e instanceof ElderGuardian
                || e instanceof AbstractVillager || e instanceof AbstractGolem || blacklisted(e);
    }

    private static boolean matchesFilter(Entity e) {
        switch (Config.filter()) {
            case HOSTILE: return isHostile(e);
            case ANIMALS: return isAnimal(e);
            default: return true;
        }
    }

    /** Number of existing mobs of the kind the filter targets. */
    private int currentCount() {
        switch (Config.filter()) {
            case HOSTILE: return countHostile;
            case ANIMALS: return countAnimal;
            default: return countAll;
        }
    }

    private static boolean isController(ItemStack s) {
        return s.is(Items.CLOCK) && s.has(DataComponents.CUSTOM_NAME)
                && s.getHoverName().getString().equals(CONTROLLER_NAME);
    }

    private static void giveController(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isController(inv.getItem(i))) return;
        }
        ItemStack clock = new ItemStack(Items.CLOCK);
        clock.set(DataComponents.CUSTOM_NAME, Component.literal(CONTROLLER_NAME).withStyle(ChatFormatting.YELLOW));
        inv.add(clock);
    }

    private static String status() {
        return "Mobs x" + Config.spawnMult() + " (" + Config.filter().name().toLowerCase(Locale.ROOT)
                + ", cap " + Config.mobCap() + ") | Growth x" + Config.growth()
                + " | Loot x" + Config.lootMult() + " | Blocks x" + Config.blockMult()
                + " | Chests x" + Config.chestMult() + " | Item despawn " + Config.despawnSeconds()
                + "s | Breeding cooldown " + Config.breedCooldown() + "s | Baby growth " + Config.babyGrowth()
                + "s | Apocalypse " + (Config.apocalypse() ? "ON" : "off");
    }

    private static boolean lastApocalypse = false;

    /** Called (from the file-watcher thread) after the .toml is edited: apply it on the server thread. */
    public static void onConfigChanged() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            applyGrowth(server);
            boolean now = Config.apocalypse();
            if (now != lastApocalypse) announceApocalypse(server, now);
        });
    }

    private static void applyGrowth(MinecraftServer server) {
        int g = Config.growth();
        // vanilla randomTickSpeed is 3
        server.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(g <= 1 ? 3 : g, server);
    }

    private static void setApocalypse(MinecraftServer server, boolean on) {
        Config.setApocalypse(on);
        announceApocalypse(server, on);
    }

    private static void announceApocalypse(MinecraftServer server, boolean on) {
        lastApocalypse = on;
        if (on) {
            List<Entity> kill = new ArrayList<>();
            for (ServerLevel l : server.getAllLevels()) {
                for (Entity e : l.getAllEntities()) {
                    if (isHostileNonZombie(e)) kill.add(e);
                }
            }
            for (Entity e : kill) e.discard();
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("APOCALYPSE MODE ON - only the dead walk now. Zombies spawn day and night.")
                            .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        } else {
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("Apocalypse mode off. Normal spawns and loot are back.")
                            .withStyle(ChatFormatting.GREEN), false);
        }
    }

    // ----------------------------------------------------------- mob spawning

    private void queueClones(ServerLevel level, Mob mob, double x, double y, double z) {
        int mult = Config.spawnMult();
        if (mult <= 1) return;
        if (neverClone(mob) || !matchesFilter(mob)) return;
        int cap = Config.mobCap();
        for (int i = 0; i < mult - 1; i++) {
            if (currentCount() + queue.size() >= cap) break;
            queue.add(new Pending(level, mob.getType(), x, y, z));
        }
    }

    /**
     * Mobs announced through the normal spawn event (vanilla and most modded mobs).
     * Spawn types listed in "spawnTypes" are multiplied here; everything else is left alone.
     */
    @SubscribeEvent
    public void onFinalizeSpawn(FinalizeSpawnEvent e) {
        Mob mob = e.getEntity();
        seen.add(mob);
        if (Config.apocalypse() && isHostileNonZombie(mob)) {
            e.setSpawnCancelled(true);
            return;
        }
        if (Config.spawnMult() <= 1) return;
        MobSpawnType t = e.getSpawnType();
        if (!Config.spawnTypes().contains(t.name())) return;
        queueClones(e.getLevel().getLevel(), mob, e.getX(), e.getY(), e.getZ());
    }

    /**
     * Catches every mob that enters the world WITHOUT firing the spawn event
     * (many modded mobs are spawned this way). Also blocks hostiles in apocalypse mode.
     */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        Entity en = e.getEntity();

        if (Config.apocalypse() && isHostileNonZombie(en)) {
            e.setCanceled(true);
            return;
        }

        if (!(en instanceof Mob mob)) return;
        if (e.loadedFromDisk()) return;
        if (seen.contains(mob)) return;            // already handled by the spawn event / created by us
        seen.add(mob);
        if (!Config.multiplyUntracked() || Config.spawnMult() <= 1) return;
        if (mob.isBaby() || mob.isPassenger() || mob.isVehicle() || mob instanceof Slime) return; // splits, breeding, riders
        if (mob.tickCount > 0) return;             // moved between dimensions, not newly spawned
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        queueClones(level, mob, mob.getX(), mob.getY(), mob.getZ());
    }

    // Zombies don't burn in sunlight during apocalypse
    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post e) {
        if (e.getEntity() instanceof Zombie z && isAllowedZombie(z) && z.isOnFire()
                && !z.level().isClientSide() && Config.apocalypse()) {
            z.clearFire();
        }
    }

    @SubscribeEvent
    public void onDamage(LivingIncomingDamageEvent e) {
        if (e.getEntity() instanceof Zombie z && isAllowedZombie(z)
                && (e.getSource().is(DamageTypes.ON_FIRE) || e.getSource().is(DamageTypes.IN_FIRE))
                && Config.apocalypse()) {
            e.setCanceled(true);
        }
    }

    // ------------------------------------------------------------ server tick

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        tick++;

        spawnQueued();
        if (tick % 20 == 0) scan(server);
        if (tick % 40 == 0 && Config.apocalypse()) apocalypseSpawner(server);
    }

    private void spawnQueued() {
        int n = 0;
        while (!queue.isEmpty() && n < 15) {
            Pending p = queue.poll();
            n++;
            try {
                BlockPos pos = BlockPos.containing(p.x(), p.y(), p.z());
                if (!p.level().hasChunkAt(pos)) continue;
                Entity ent = p.type().create(p.level());
                if (!(ent instanceof Mob m)) continue;

                // Try a few spots around the original (big modded mobs often don't fit the first one)
                boolean placed = false;
                for (int attempt = 0; attempt < 10 && !placed; attempt++) {
                    double radius = 1.5 + attempt * 0.8;
                    double ang = p.level().random.nextDouble() * Math.PI * 2;
                    double yo = attempt >= 6 ? 1.0 : 0.0;
                    m.moveTo(p.x() + Math.cos(ang) * radius, p.y() + yo, p.z() + Math.sin(ang) * radius,
                            p.level().random.nextFloat() * 360f, 0f);
                    placed = p.level().noCollision(m);
                }
                if (!placed) {
                    // still no room: put it on top of the original, the mobs push apart by themselves
                    m.moveTo(p.x(), p.y(), p.z(), p.level().random.nextFloat() * 360f, 0f);
                }

                seen.add(m); // ours: never multiply it again
                m.finalizeSpawn(p.level(), p.level().getCurrentDifficultyAt(m.blockPosition()),
                        MobSpawnType.EVENT, null);
                p.level().addFreshEntity(m);
            } catch (Exception ignored) {
            }
        }
    }

    /** Once a second: count mobs for the safety cap and remove old dropped items. */
    private void scan(MinecraftServer server) {
        int all = 0, hostile = 0, animal = 0;
        int despawnTicks = Config.despawnSeconds() * 20;
        List<ItemEntity> expired = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity en : level.getAllEntities()) {
                if (en instanceof AgeableMob am) adjustAge(am);
                if (en instanceof Mob) {
                    all++;
                    if (isHostile(en)) hostile++;
                    else if (isAnimal(en)) animal++;
                } else if (despawnTicks < 6000 && en instanceof ItemEntity ie && ie.getAge() >= despawnTicks) {
                    expired.add(ie);
                }
            }
        }
        countAll = all;
        countHostile = hostile;
        countAnimal = animal;
        for (ItemEntity ie : expired) ie.discard();
    }

    /**
     * Runs once a second per animal. Adults: shorten the breeding cooldown. Babies: skip ahead so they
     * grow up in the chosen number of seconds. Vanilla values: cooldown 300 s, growing up 1200 s.
     */
    private static void adjustAge(AgeableMob am) {
        int age = am.getAge();
        if (age > 0) {
            int maxCooldown = Config.breedCooldown() * 20;
            if (maxCooldown < 6000 && age > maxCooldown) am.setAge(maxCooldown);
        } else if (age < 0) {
            int growSeconds = Config.babyGrowth();
            if (growSeconds < 1200) {
                double factor = 1200.0 / growSeconds;
                int extra = (int) Math.round((factor - 1.0) * 20.0);
                am.setAge(Math.min(0, age + extra));
            }
        }
    }

    private void apocalypseSpawner(MinecraftServer server) {
        int mult = Config.spawnMult();
        int wave = Math.min(2 + mult / 5, 20);
        int localCap = Math.min(24 + mult, 120);
        int globalCap = Config.mobCap();

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerLevel level = p.serverLevel();
            if (level.dimension() != Level.OVERWORLD) continue;
            int near = level.getEntitiesOfClass(Zombie.class, p.getBoundingBox().inflate(48)).size();
            if (near >= localCap) continue;

            for (int i = 0; i < wave; i++) {
                if (countAll + queue.size() + i >= globalCap) break;
                double ang = level.random.nextDouble() * Math.PI * 2;
                double dist = 18 + level.random.nextDouble() * 16;
                int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist);
                int zc = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
                if (!level.hasChunk(x >> 4, zc >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zc);
                BlockPos ground = new BlockPos(x, y - 1, zc);
                BlockState bs = level.getBlockState(ground);
                if (!bs.blocksMotion() || !level.getFluidState(ground.above()).isEmpty()) continue;

                Zombie zombie = EntityType.ZOMBIE.create(level);
                if (zombie == null) continue;
                zombie.moveTo(x + 0.5, y, zc + 0.5, level.random.nextFloat() * 360f, 0f);
                seen.add(zombie);
                zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(zombie.blockPosition()),
                        MobSpawnType.EVENT, null);
                level.addFreshEntity(zombie);
            }
        }
    }

    // ------------------------------------------------ controller clock / join

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        Player p = e.getEntity();
        if (p.level().isClientSide()) return;
        giveController(p);
        p.sendSystemMessage(Component.literal("Spawn Intensity active. Use your Spawn Sit clock to see settings, /spawnintensity to change them.")
                .withStyle(ChatFormatting.AQUA));
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        Player p = e.getEntity();
        if (!p.level().isClientSide()) giveController(p);
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getLevel().isClientSide()) return;
        if (!isController(e.getItemStack())) return;
        e.getEntity().sendSystemMessage(Component.literal(status()).withStyle(ChatFormatting.YELLOW));
        e.getEntity().sendSystemMessage(Component.literal(
                "Change with: /spawnintensity mobs|growth|loot|blocks|chests|despawn|mobcap|breeding|babygrowth <n>, filter <all|hostile|animals>, apocalypse <true|false>")
                .withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent e) {
        lastApocalypse = Config.apocalypse();
        if (Config.growth() > 1) applyGrowth(e.getServer());
        if (lastApocalypse) {
            // clean up hostile mobs that were already loaded
            e.getServer().execute(() -> announceApocalypse(e.getServer(), true));
        }
    }

    // ---------------------------------------------------------------- commands

    private interface Apply {
        void run(MinecraftServer server, int value);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> intCmd(String name, int min, int max, Apply apply) {
        return Commands.literal(name).then(
                Commands.argument("value", IntegerArgumentType.integer(min, max)).executes(ctx -> {
                    int v = IntegerArgumentType.getInteger(ctx, "value");
                    apply.run(ctx.getSource().getServer(), v);
                    ctx.getSource().sendSuccess(() -> Component.literal(name + " set to " + v), true);
                    return 1;
                }));
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("spawnintensity").requires(s -> s.hasPermission(2));

        root.then(intCmd("mobs", 1, 100, (s, v) -> Config.set(Config.SPAWN_MULT, v)));
        root.then(intCmd("growth", 1, 100, (s, v) -> {
            Config.set(Config.GROWTH, v);
            applyGrowth(s);
        }));
        root.then(intCmd("loot", 1, 100, (s, v) -> Config.set(Config.LOOT_MULT, v)));
        root.then(intCmd("blocks", 1, 100, (s, v) -> Config.set(Config.BLOCK_MULT, v)));
        root.then(intCmd("chests", 1, 100, (s, v) -> Config.set(Config.CHEST_MULT, v)));
        root.then(intCmd("despawn", 5, 300, (s, v) -> Config.set(Config.DESPAWN_SECONDS, v)));
        root.then(intCmd("mobcap", 100, 1000, (s, v) -> Config.set(Config.MOB_CAP, v)));
        root.then(intCmd("breeding", 0, 300, (s, v) -> Config.set(Config.BREED_COOLDOWN, v)));
        root.then(intCmd("babygrowth", 10, 1200, (s, v) -> Config.set(Config.BABY_GROWTH, v)));

        LiteralArgumentBuilder<CommandSourceStack> filter = Commands.literal("filter");
        for (Config.SpawnFilter sf : Config.SpawnFilter.values()) {
            filter.then(Commands.literal(sf.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
                Config.setFilter(sf);
                ctx.getSource().sendSuccess(() -> Component.literal("Spawn filter: " + sf.name().toLowerCase(Locale.ROOT)), true);
                return 1;
            }));
        }
        root.then(filter);

        root.then(Commands.literal("apocalypse").then(
                Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> {
                    setApocalypse(ctx.getSource().getServer(), BoolArgumentType.getBool(ctx, "on"));
                    return 1;
                })));

        root.then(Commands.literal("status").executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal(status()), false);
            return 1;
        }));

        e.getDispatcher().register(root);
    }
}
