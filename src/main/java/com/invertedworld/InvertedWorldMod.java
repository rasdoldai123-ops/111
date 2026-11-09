package com.invertedworld;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(InvertedWorldMod.MODID)
public class InvertedWorldMod {
    public static final String MODID = "invertedworld";
    private static final Logger LOGGER = LogUtils.getLogger();

    public InvertedWorldMod() {
        InvertedWorldConfig.load();

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(new InvertedWorldEvents());

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSetup.init(modEventBus));
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            InversionEngine.applyEnchantmentInversion();
            InversionEngine.rebuildEventChanceViews();
            LOGGER.info("[InvertedWorld] Initialized mathematical rarity inversion engine.");
        });
    }
}
