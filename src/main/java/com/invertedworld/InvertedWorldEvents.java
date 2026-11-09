package com.invertedworld;

import com.mojang.brigadier.CommandDispatcher;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityStruckByLightningEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;

import java.util.ArrayList;
import java.util.List;

public class InvertedWorldEvents {

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        InversionEngine.applyBiomeSpawnInversion(event.getServer());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        InversionEngine.applyLootTableInversion(event.getServer().getLootData());
        InversionEngine.rebuildEventChanceViews();
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Component openCmd = Component.literal("[Открыть меню шансов (/iw)]")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/iw"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Нажмите, чтобы открыть GUI инспектора шансов и настроек (или нажмите клавишу I)"))));

        event.getEntity().sendSystemMessage(
                Component.literal("§d§l[Inverted World] §eМатематическая инверсия шансов активна! Нажмите клавишу §b§l[I]§e или ")
                        .append(openCmd)
        );
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        var cmd = Commands.literal("iw")
                .executes(ctx -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientSetup::openScreenDelayed);
                    return 1;
                })
                .then(Commands.literal("gui").executes(ctx -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientSetup::openScreenDelayed);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    InversionEngine.recalculateAll(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§d[Inverted World] §aВсе шансы сундуков, биомов и событий успешно пересчитаны! ("
                                    + InversionEngine.getLootTableViews().size() + " таблиц лута, "
                                    + InversionEngine.getBiomeSpawnViews().size() + " биомов)"), true);
                    return 1;
                }));
        dispatcher.register(cmd);
        dispatcher.register(Commands.literal("invertedworld").redirect(dispatcher.getRoot().getChild("iw")));
    }

    @SubscribeEvent
    public void onVillagerTrades(VillagerTradesEvent event) {
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        if (!cfg.modEnabled || !cfg.invertVillagerTrades) return;

        Int2ObjectMap<List<VillagerTrades.ItemListing>> trades = event.getTrades();
        List<VillagerTrades.ItemListing> lvl1 = trades.get(1);
        List<VillagerTrades.ItemListing> lvl2 = trades.get(2);
        List<VillagerTrades.ItemListing> lvl4 = trades.get(4);
        List<VillagerTrades.ItemListing> lvl5 = trades.get(5);

        if (lvl1 != null && lvl5 != null) {
            trades.put(1, lvl5);
            trades.put(5, lvl1);
        }
        if (lvl2 != null && lvl4 != null) {
            trades.put(2, lvl4);
            trades.put(4, lvl2);
        }
    }

    @SubscribeEvent
    public void onWandererTrades(WandererTradesEvent event) {
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        if (!cfg.modEnabled || !cfg.invertVillagerTrades) return;

        List<VillagerTrades.ItemListing> generic = new ArrayList<>(event.getGenericTrades());
        List<VillagerTrades.ItemListing> rare = new ArrayList<>(event.getRareTrades());
        event.getGenericTrades().clear();
        event.getRareTrades().clear();
        event.getGenericTrades().addAll(rare);
        event.getRareTrades().addAll(generic);
    }

    @SubscribeEvent
    public void onMobFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        if (!cfg.modEnabled || !cfg.invertMobVariants) return;

        var entity = event.getEntity();
        ServerLevelAccessor level = event.getLevel();
        RandomSource random = level.getRandom();

        // 1. Овцы: точное вероятностное распределение по 6 цветам (Белая, Светло-серая, Серая, Чёрная, Коричневая, Розовая)
        if (entity instanceof Sheep sheep) {
            sheep.setColor(InversionEngine.rollInvertedSheepColor(random));
        }

        // 2. Криперы: инверсия вероятности 1% -> (1 - p) с учётом ползунка силы
        if (entity instanceof Creeper creeper) {
            float chargedChance = InversionEngine.invertSingleProbability(0.01f);
            boolean powered = random.nextFloat() < chargedChance;
            CompoundTag tag = new CompoundTag();
            creeper.saveWithoutId(tag);
            tag.putBoolean("powered", powered);
            creeper.load(tag);
        }

        // 3. Зомби: вероятностный расчёт шанса детёныша (ванилла 5%) и распределения тиров брони
        if (entity instanceof Zombie zombie) {
            float babyChance = InversionEngine.invertSingleProbability(0.05f);
            zombie.setBaby(random.nextFloat() < babyChance);
            applyProbabilisticEquipment(zombie, random);
        }

        // 4. Скелеты: вероятностное распределение тиров брони
        if (entity instanceof Skeleton skeleton) {
            applyProbabilisticEquipment(skeleton, random);
        }

        // 5. Пауки: вероятностный расчёт шанса паучьего наездника (ванилла 1%)
        if (entity instanceof Spider spider && spider.getPassengers().isEmpty()) {
            float jockeyChance = InversionEngine.invertSingleProbability(0.01f);
            if (random.nextFloat() < jockeyChance && level.getServer() != null) {
                Skeleton jockey = EntityType.SKELETON.create(spider.level());
                if (jockey != null) {
                    jockey.moveTo(spider.getX(), spider.getY(), spider.getZ(), spider.getYRot(), 0.0F);
                    jockey.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                    applyProbabilisticEquipment(jockey, random);
                    level.addFreshEntity(jockey);
                    jockey.startRiding(spider);
                }
            }
        }

        // 6. Синий аксолотль (ванилла 1/1200 = 0.083%)
        if (entity instanceof Axolotl axolotl) {
            float blueChance = InversionEngine.invertSingleProbability(0.00083f);
            if (random.nextFloat() < blueChance) {
                axolotl.setVariant(Axolotl.Variant.BLUE);
            }
        }

        // 7. Кричащая коза (ванилла 2%)
        if (entity instanceof Goat goat) {
            float screamingChance = InversionEngine.invertSingleProbability(0.02f);
            goat.setScreamingGoat(random.nextFloat() < screamingChance);
        }

        // 8. Коричневая панда (ванилла ~2.04%)
        if (entity instanceof Panda panda) {
            float brownChance = InversionEngine.invertSingleProbability(0.0204f);
            if (random.nextFloat() < brownChance) {
                panda.setMainGene(Panda.Gene.BROWN);
                panda.setHiddenGene(Panda.Gene.BROWN);
            }
        }
    }

    @SubscribeEvent
    public void onLightningStrike(EntityStruckByLightningEvent event) {
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        if (!cfg.modEnabled || !cfg.invertMobVariants) return;

        if (event.getEntity() instanceof Creeper creeper && creeper.isPowered()) {
            event.setCanceled(true);
            CompoundTag tag = new CompoundTag();
            creeper.saveWithoutId(tag);
            tag.putBoolean("powered", false);
            creeper.load(tag);
        }
    }

    /**
     * Equips armor according to the exact discrete probability distribution over the 6 armor tiers:
     * 0 = None, 1 = Leather, 2 = Gold, 3 = Chainmail, 4 = Iron, 5 = Diamond.
     * Also probabilistically rolls how many armor pieces (1 to 4) are equipped.
     */
    private void applyProbabilisticEquipment(Mob mob, RandomSource random) {
        int tier = InversionEngine.rollInvertedArmorTier(random);
        if (tier <= 0) {
            mob.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            mob.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            mob.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            mob.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            return;
        }

        Item helmet = switch (tier) {
            case 1 -> Items.LEATHER_HELMET;
            case 2 -> Items.GOLDEN_HELMET;
            case 3 -> Items.CHAINMAIL_HELMET;
            case 4 -> Items.IRON_HELMET;
            default -> Items.DIAMOND_HELMET;
        };
        Item chest = switch (tier) {
            case 1 -> Items.LEATHER_CHESTPLATE;
            case 2 -> Items.GOLDEN_CHESTPLATE;
            case 3 -> Items.CHAINMAIL_CHESTPLATE;
            case 4 -> Items.IRON_CHESTPLATE;
            default -> Items.DIAMOND_CHESTPLATE;
        };
        Item legs = switch (tier) {
            case 1 -> Items.LEATHER_LEGGINGS;
            case 2 -> Items.GOLDEN_LEGGINGS;
            case 3 -> Items.CHAINMAIL_LEGGINGS;
            case 4 -> Items.IRON_LEGGINGS;
            default -> Items.DIAMOND_LEGGINGS;
        };
        Item boots = switch (tier) {
            case 1 -> Items.LEATHER_BOOTS;
            case 2 -> Items.GOLDEN_BOOTS;
            case 3 -> Items.CHAINMAIL_BOOTS;
            case 4 -> Items.IRON_BOOTS;
            default -> Items.DIAMOND_BOOTS;
        };

        // In vanilla, each additional armor slot has a 90% continuation chance (so full 4-piece set is rarest among armored).
        // Inverted: full 4-piece set is most common among armored, but 1-3 pieces also occur probabilistically!
        float pieceChance = InversionEngine.invertSingleProbability(0.25f);
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(helmet));
        if (random.nextFloat() < pieceChance) mob.setItemSlot(EquipmentSlot.CHEST, new ItemStack(chest));
        if (random.nextFloat() < pieceChance) mob.setItemSlot(EquipmentSlot.LEGS, new ItemStack(legs));
        if (random.nextFloat() < pieceChance) mob.setItemSlot(EquipmentSlot.FEET, new ItemStack(boots));

        if (mob instanceof Zombie && random.nextFloat() < InversionEngine.invertSingleProbability(0.05f)) {
            Item weapon = (tier >= 5) ? Items.DIAMOND_SWORD : (tier == 4 ? Items.IRON_SWORD : Items.GOLDEN_SWORD);
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
        }
    }
}
