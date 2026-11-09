package com.spawnintensity;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

import java.util.Set;

/**
 * One global loot modifier that handles everything that drops from a loot table:
 * mob loot, block drops and loot chests. Multiplying the generated list means
 * "2x" is always exactly 2x of what the game actually rolled.
 */
public class MultiplierModifier extends LootModifier {
    public static final MapCodec<MultiplierModifier> CODEC = RecordCodecBuilder.mapCodec(
            inst -> codecStart(inst).apply(inst, MultiplierModifier::new));

    // Apocalypse zombie loot: {item, chance, min, max}
    private static final Object[][] APOC_LOOT = {
            {Items.ROTTEN_FLESH, 0.10f, 1, 1}, // now rare
            {Items.IRON_INGOT, 0.40f, 1, 2},   // now likely
            {Items.CARROT, 0.30f, 1, 2},
            {Items.POTATO, 0.30f, 1, 2},
            {Items.GUNPOWDER, 0.55f, 1, 2},    // new drop
    };
    private static final Set<Item> VANILLA_ZOMBIE_LOOT =
            Set.of(Items.ROTTEN_FLESH, Items.IRON_INGOT, Items.CARROT, Items.POTATO);

    private static final int MAX_STACKS = 64;

    public MultiplierModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        String path = context.getQueriedLootTableId().getPath();

        if (path.startsWith("entities/")) {
            Entity dead = context.getParamOrNull(LootContextParams.THIS_ENTITY);
            if (Config.apocalypse() && dead != null && Handlers.isAllowedZombie(dead)) {
                loot.removeIf(s -> VANILLA_ZOMBIE_LOOT.contains(s.getItem()));
                for (Object[] row : APOC_LOOT) {
                    if (context.getRandom().nextFloat() < (float) row[1]) {
                        int min = (int) row[2];
                        int max = (int) row[3];
                        int n = min + context.getRandom().nextInt(max - min + 1);
                        loot.add(new ItemStack((Item) row[0], n));
                    }
                }
            }
            return multiply(loot, Config.lootMult());
        }
        if (path.startsWith("blocks/")) {
            return multiply(loot, Config.blockMult());
        }
        if (path.startsWith("chests/")) {
            return multiply(loot, Config.chestMult());
        }
        return loot;
    }

    private static ObjectArrayList<ItemStack> multiply(ObjectArrayList<ItemStack> in, int mult) {
        if (mult <= 1) return in;
        ObjectArrayList<ItemStack> out = new ObjectArrayList<>();
        for (ItemStack s : in) {
            if (s.isEmpty()) continue;
            // Items that cannot stack (tools, weapons, armor, books ...) stay as ONE copy.
            // Only stackable items get multiplied.
            if (s.getMaxStackSize() <= 1) {
                out.add(s.copy());
                continue;
            }
            long total = (long) s.getCount() * mult;
            int max = Math.max(1, s.getMaxStackSize());
            int made = 0;
            while (total > 0 && made < MAX_STACKS) {
                int n = (int) Math.min(total, max);
                ItemStack copy = s.copy();
                copy.setCount(n);
                out.add(copy);
                total -= n;
                made++;
            }
        }
        return out;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
