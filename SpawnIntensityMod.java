package com.spawnintensity;

import com.mojang.serialization.MapCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@Mod(SpawnIntensityMod.MODID)
public class SpawnIntensityMod {
    public static final String MODID = "spawnintensity";

    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MODID);

    static {
        LOOT_MODIFIERS.register("multiplier", () -> MultiplierModifier.CODEC);
    }

    public SpawnIntensityMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        LOOT_MODIFIERS.register(modBus);
        // Re-apply settings whenever config/spawnintensity-common.toml is edited while the game runs
        modBus.addListener((ModConfigEvent.Reloading ev) -> {
            if (ev.getConfig().getSpec() == Config.SPEC) Handlers.onConfigChanged();
        });
        NeoForge.EVENT_BUS.register(new Handlers());
    }
}
