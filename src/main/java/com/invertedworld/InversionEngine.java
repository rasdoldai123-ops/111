package com.invertedworld;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weight;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.storage.loot.LootDataManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceWithLootingCondition;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public class InversionEngine {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Unsafe UNSAFE;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            LOGGER.error("[InvertedWorld] Could not acquire Unsafe instance", e);
        }
    }

    // =========================================================================
    // SNAPSHOTS OF ORIGINAL VANILLA VALUES (Never overwritten once captured)
    // =========================================================================
    private static final Map<LootPoolSingletonContainer, Integer> ORIGINAL_ENTRY_WEIGHTS = new IdentityHashMap<>();
    private static final Map<LootPoolSingletonContainer, Integer> CUSTOM_USER_WEIGHTS = new IdentityHashMap<>();
    private static final Map<LootItemCondition, Float> ORIGINAL_CONDITION_CHANCES = new IdentityHashMap<>();
    private static final Map<ResourceLocation, Enchantment.Rarity> ORIGINAL_ENCHANT_RARITIES = new HashMap<>();
    private static final Map<ResourceLocation, Map<MobCategory, List<MobSpawnSettings.SpawnerData>>> ORIGINAL_BIOME_SPAWNS = new HashMap<>();
    private static final Set<LootPool> VILLAGE_ENRICHED_POOLS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<LootPoolSingletonContainer> INJECTED_ENTRIES = Collections.newSetFromMap(new IdentityHashMap<>());

    // =========================================================================
    // LIVE UI RECORDS FOR THE IN-GAME MINECRAFT MENU
    // =========================================================================
    public static class LootEntryView {
        public final LootPoolSingletonContainer container;
        public final ItemStack icon;
        public final String displayName;
        public final boolean isEmptyEntry;
        public final boolean isInjected;
        public final int vanillaWeight;
        public int currentWeight;
        public double vanillaPercent;
        public double currentPercent;

        public LootEntryView(LootPoolSingletonContainer container, ItemStack icon, String displayName,
                             boolean isEmptyEntry, boolean isInjected, int vanillaWeight, int currentWeight) {
            this.container = container;
            this.icon = icon;
            this.displayName = displayName;
            this.isEmptyEntry = isEmptyEntry;
            this.isInjected = isInjected;
            this.vanillaWeight = vanillaWeight;
            this.currentWeight = currentWeight;
        }
    }

    public static class LootPoolView {
        public final int poolIndex;
        public final List<LootEntryView> entries = new ArrayList<>();
        public Float vanillaConditionChance;
        public Float currentConditionChance;

        public LootPoolView(int poolIndex) {
            this.poolIndex = poolIndex;
        }

        public void recomputePercentages() {
            int sumVanilla = 0;
            int sumCurrent = 0;
            for (LootEntryView e : entries) {
                sumVanilla += Math.max(0, e.vanillaWeight);
                sumCurrent += Math.max(0, e.currentWeight);
            }
            for (LootEntryView e : entries) {
                e.vanillaPercent = sumVanilla > 0 ? (e.vanillaWeight * 100.0) / sumVanilla : 0.0;
                e.currentPercent = sumCurrent > 0 ? (e.currentWeight * 100.0) / sumCurrent : 0.0;
            }
        }
    }

    public static class LootTableView {
        public final ResourceLocation id;
        public final String category; // "chests", "village", "entities", "blocks", "gameplay"
        public final List<LootPoolView> pools = new ArrayList<>();

        public LootTableView(ResourceLocation id) {
            this.id = id;
            String path = id.getPath();
            if (path.startsWith("chests/village")) {
                this.category = "village";
            } else if (path.startsWith("chests/")) {
                this.category = "chests";
            } else if (path.startsWith("entities/")) {
                this.category = "entities";
            } else if (path.startsWith("blocks/")) {
                this.category = "blocks";
            } else {
                this.category = "gameplay";
            }
        }

        public int getTotalEntries() {
            int count = 0;
            for (LootPoolView p : pools) count += p.entries.size();
            return count;
        }
    }

    public static class MobSpawnEntryView {
        public final EntityType<?> entityType;
        public final String mobName;
        public final MobCategory category;
        public final int vanillaWeight;
        public final int vanillaMinGroup;
        public final int vanillaMaxGroup;
        public int currentWeight;
        public int currentMinGroup;
        public int currentMaxGroup;
        public double vanillaPercent;
        public double currentPercent;

        public MobSpawnEntryView(EntityType<?> entityType, MobCategory category,
                                 int vanillaWeight, int vanillaMinGroup, int vanillaMaxGroup,
                                 int currentWeight, int currentMinGroup, int currentMaxGroup) {
            this.entityType = entityType;
            this.mobName = entityType.getDescription().getString();
            this.category = category;
            this.vanillaWeight = vanillaWeight;
            this.vanillaMinGroup = vanillaMinGroup;
            this.vanillaMaxGroup = vanillaMaxGroup;
            this.currentWeight = currentWeight;
            this.currentMinGroup = currentMinGroup;
            this.currentMaxGroup = currentMaxGroup;
        }
    }

    public static class BiomeSpawnView {
        public final ResourceLocation biomeId;
        public final List<MobSpawnEntryView> spawns = new ArrayList<>();

        public BiomeSpawnView(ResourceLocation biomeId) {
            this.biomeId = biomeId;
        }
    }

    public static class EventChanceView {
        public final String category;
        public final String name;
        public final ItemStack icon;
        public final double vanillaPercent;
        public final double currentPercent;

        public EventChanceView(String category, String name, ItemStack icon, double vanillaPercent, double currentPercent) {
            this.category = category;
            this.name = name;
            this.icon = icon;
            this.vanillaPercent = vanillaPercent;
            this.currentPercent = currentPercent;
        }
    }

    private static final List<LootTableView> LOOT_TABLE_VIEWS = new ArrayList<>();
    private static final List<BiomeSpawnView> BIOME_SPAWN_VIEWS = new ArrayList<>();
    private static final List<EventChanceView> EVENT_CHANCE_VIEWS = new ArrayList<>();

    public static List<LootTableView> getLootTableViews() {
        return LOOT_TABLE_VIEWS;
    }

    public static List<BiomeSpawnView> getBiomeSpawnViews() {
        return BIOME_SPAWN_VIEWS;
    }

    public static List<EventChanceView> getEventChanceViews() {
        return EVENT_CHANCE_VIEWS;
    }

    // =========================================================================
    // MASTER RECALCULATION ENTRYPOINT (Called on server start or GUI "Apply")
    // =========================================================================
    public static synchronized void recalculateAll(MinecraftServer server) {
        if (server == null) {
            server = ServerLifecycleHooks.getCurrentServer();
        }
        applyEnchantmentInversion();
        rebuildEventChanceViews();

        if (server != null) {
            applyBiomeSpawnInversion(server);
            applyLootTableInversion(server.getLootData());
        }
    }

    /**
     * Allows manual adjustment of an entry's weight directly from the GUI!
     */
    public static synchronized void adjustEntryWeight(LootPoolView poolView, LootEntryView entryView, int delta) {
        int newWeight = Math.max(1, entryView.currentWeight + delta);
        entryView.currentWeight = newWeight;
        CUSTOM_USER_WEIGHTS.put(entryView.container, newWeight);
        setSingletonWeight(entryView.container, newWeight);
        poolView.recomputePercentages();
    }

    public static synchronized void clearCustomWeights() {
        CUSTOM_USER_WEIGHTS.clear();
    }

    // =========================================================================
    // MATHEMATICAL WEIGHT & PROBABILITY INVERSION FORMULAS
    // =========================================================================

    /**
     * Given an array of positive weights [w_0, w_1, ..., w_{n-1}], computes the truly inverted weights
     * according to the user's selected InversionFormula and inversionStrength.
     */
    public static int[] computeInvertedWeights(int[] originalWeights, int[] intrinsicPriorityBonus) {
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        int n = originalWeights.length;
        int[] result = new int[n];
        if (n == 0) return result;
        if (!cfg.modEnabled || cfg.inversionStrength <= 0.01f || n == 1) {
            System.arraycopy(originalWeights, 0, result, 0, n);
            return result;
        }

        int minW = Integer.MAX_VALUE;
        int maxW = Integer.MIN_VALUE;
        for (int w : originalWeights) {
            if (w > 0) {
                minW = Math.min(minW, w);
                maxW = Math.max(maxW, w);
            }
        }
        if (minW == Integer.MAX_VALUE || maxW <= minW) {
            // If all items in the pool had the exact same weight, differentiate by intrinsic rarity bonus if available
            boolean hasDiffBonus = false;
            if (intrinsicPriorityBonus != null) {
                for (int b : intrinsicPriorityBonus) {
                    if (b != intrinsicPriorityBonus[0]) {
                        hasDiffBonus = true;
                        break;
                    }
                }
            }
            if (!hasDiffBonus) {
                System.arraycopy(originalWeights, 0, result, 0, n);
                return result;
            }
            for (int i = 0; i < n; i++) {
                result[i] = Math.max(1, originalWeights[i] + intrinsicPriorityBonus[i]);
            }
            return result;
        }

        float alpha = cfg.inversionStrength;

        switch (cfg.formula) {
            case RANK_SWAP -> {
                // Collect sorted unique weights: u_0 < u_1 < ... < u_{k-1}
                // Map u_j -> u_{k - 1 - j}, then blend with alpha and apply intrinsic rarity tie-breaker
                TreeSet<Integer> uniqueSet = new TreeSet<>();
                for (int w : originalWeights) {
                    if (w > 0) uniqueSet.add(w);
                }
                List<Integer> unique = new ArrayList<>(uniqueSet);
                int k = unique.size();
                Map<Integer, Integer> swappedMap = new HashMap<>();
                for (int j = 0; j < k; j++) {
                    swappedMap.put(unique.get(j), unique.get(k - 1 - j));
                }
                for (int i = 0; i < n; i++) {
                    int orig = Math.max(1, originalWeights[i]);
                    int swapped = swappedMap.getOrDefault(orig, orig);
                    double blended;
                    if (alpha <= 1.0f) {
                        blended = orig * (1.0 - alpha) + swapped * alpha;
                    } else {
                        // Above 100% strength: amplify the ratio further
                        double ratio = (double) swapped / orig;
                        blended = orig * Math.pow(ratio, alpha);
                    }
                    if (intrinsicPriorityBonus != null && intrinsicPriorityBonus[i] > 0) {
                        blended *= (1.0 + 0.25 * intrinsicPriorityBonus[i]);
                    }
                    result[i] = Math.max(1, (int) Math.round(blended));
                }
            }
            case RECIPROCAL -> {
                // True hyperbolic inversion: w' = ((maxW * minW) / w)^alpha
                double product = (double) maxW * (double) minW;
                for (int i = 0; i < n; i++) {
                    int orig = Math.max(1, originalWeights[i]);
                    double invBase = product / orig;
                    double val = orig * Math.pow(invBase / orig, alpha);
                    if (intrinsicPriorityBonus != null && intrinsicPriorityBonus[i] > 0) {
                        val *= (1.0 + 0.25 * intrinsicPriorityBonus[i]);
                    }
                    result[i] = Math.max(1, (int) Math.round(val));
                }
            }
            case LINEAR_MIRROR -> {
                // Linear mirror: w' = (maxW + minW) - w
                int mirrorSum = maxW + minW;
                for (int i = 0; i < n; i++) {
                    int orig = Math.max(1, originalWeights[i]);
                    int mirrored = Math.max(1, mirrorSum - orig);
                    double blended = orig * (1.0 - Math.min(1.0, alpha)) + mirrored * alpha;
                    if (intrinsicPriorityBonus != null && intrinsicPriorityBonus[i] > 0) {
                        blended += intrinsicPriorityBonus[i] * 2;
                    }
                    result[i] = Math.max(1, (int) Math.round(blended));
                }
            }
        }
        return result;
    }

    /**
     * Inverts a single probability p in (0, 1) according to current config.
     */
    public static float invertSingleProbability(float originalP) {
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        if (!cfg.modEnabled) return originalP;
        float clamped = Math.max(0.0005f, Math.min(0.9995f, originalP));
        float inverted = 1.0f - clamped;
        float alpha = Math.min(1.0f, cfg.inversionStrength);
        float blended = clamped * (1.0f - alpha) + inverted * alpha;
        if (cfg.inversionStrength > 1.0f && inverted > clamped) {
            // Push rare probabilities even closer to 1.0 when strength > 100%
            float extra = Math.min(1.0f, (cfg.inversionStrength - 1.0f) / 1.5f);
            blended = blended + (0.995f - blended) * extra;
        }
        return Math.max(0.001f, Math.min(0.999f, blended));
    }

    // =========================================================================
    // 1. LOOT TABLE INVERSION (Chests, Blocks, Entities, Fishing, Archaeology)
    // =========================================================================
    public static void applyLootTableInversion(LootDataManager lootDataManager) {
        if (lootDataManager == null) return;
        LOOT_TABLE_VIEWS.clear();
        InvertedWorldConfig cfg = InvertedWorldConfig.get();

        Map<ResourceLocation, LootTable> tables = collectAllLootTables(lootDataManager);
        List<ResourceLocation> sortedKeys = new ArrayList<>(tables.keySet());
        sortedKeys.sort(Comparator.comparing(ResourceLocation::toString));

        int invertedPoolsCount = 0;

        for (ResourceLocation id : sortedKeys) {
            LootTable table = tables.get(id);
            if (table == null) continue;
            List<LootPool> pools = getLootPools(table);
            if (pools == null || pools.isEmpty()) continue;

            LootTableView tableView = new LootTableView(id);
            boolean isVillageChest = id.getPath().startsWith("chests/village/");

            for (int poolIdx = 0; poolIdx < pools.size(); poolIdx++) {
                LootPool pool = pools.get(poolIdx);

                // Optional enrichment of simple village chests so they contain rare treasures with vanilla weight 1
                if (isVillageChest && poolIdx == 0) {
                    ensureVillageChestTreasures(pool, cfg.modEnabled && cfg.invertLootTables && cfg.enrichVillageChests);
                }

                LootPoolView poolView = new LootPoolView(poolIdx + 1);
                List<LootPoolSingletonContainer> singletons = new ArrayList<>();
                LootPoolEntryContainer[] entries = getPoolEntries(pool);
                if (entries != null) {
                    collectSingletonsRecursive(entries, singletons);
                }

                // Snapshot original weights on first encounter
                for (LootPoolSingletonContainer s : singletons) {
                    ORIGINAL_ENTRY_WEIGHTS.computeIfAbsent(s, InversionEngine::getSingletonWeight);
                }

                // Filter active singletons (if injected entry and enrichVillageChests is off, set weight 0)
                List<LootPoolSingletonContainer> activeSingletons = new ArrayList<>();
                for (LootPoolSingletonContainer s : singletons) {
                    if (INJECTED_ENTRIES.contains(s) && !(cfg.modEnabled && cfg.invertLootTables && cfg.enrichVillageChests)) {
                        setSingletonWeight(s, 0);
                    } else {
                        activeSingletons.add(s);
                    }
                }

                int n = activeSingletons.size();
                int[] origWeights = new int[n];
                int[] priorityBonus = new int[n];
                boolean[] isEmptyFlags = new boolean[n];

                for (int i = 0; i < n; i++) {
                    LootPoolSingletonContainer s = activeSingletons.get(i);
                    origWeights[i] = ORIGINAL_ENTRY_WEIGHTS.getOrDefault(s, 1);
                    isEmptyFlags[i] = (s instanceof EmptyLootItem);
                    ItemStack stack = extractEntryItemStack(s);
                    priorityBonus[i] = getIntrinsicItemRarityScore(stack, isEmptyFlags[i]);
                }

                int[] newWeights;
                if (cfg.modEnabled && cfg.invertLootTables) {
                    newWeights = computeInvertedWeights(origWeights, priorityBonus);
                    for (int i = 0; i < n; i++) {
                        LootPoolSingletonContainer s = activeSingletons.get(i);
                        if (isEmptyFlags[i] && cfg.reduceEmptySlotWeight) {
                            newWeights[i] = 1;
                        }
                        if (CUSTOM_USER_WEIGHTS.containsKey(s)) {
                            newWeights[i] = CUSTOM_USER_WEIGHTS.get(s);
                        }
                        setSingletonWeight(s, newWeights[i]);
                    }
                    invertedPoolsCount++;
                } else {
                    newWeights = Arrays.copyOf(origWeights, n);
                    for (int i = 0; i < n; i++) {
                        setSingletonWeight(activeSingletons.get(i), origWeights[i]);
                    }
                }

                // Invert conditions on pool and entries
                LootItemCondition[] poolConds = getPoolConditions(pool);
                if (poolConds != null) {
                    for (LootItemCondition cond : poolConds) {
                        Float origChance = processCondition(cond, cfg.modEnabled && cfg.invertLootTables);
                        if (origChance != null) {
                            poolView.vanillaConditionChance = origChance * 100.0f;
                            poolView.currentConditionChance = getConditionChance(cond) * 100.0f;
                        }
                    }
                }
                if (entries != null) {
                    invertEntryConditionsRecursive(entries, cfg.modEnabled && cfg.invertLootTables);
                }

                // Populate UI view
                for (int i = 0; i < n; i++) {
                    LootPoolSingletonContainer s = activeSingletons.get(i);
                    ItemStack icon = extractEntryItemStack(s);
                    String name = extractEntryDisplayName(s, icon);
                    poolView.entries.add(new LootEntryView(
                            s,
                            icon,
                            name,
                            isEmptyFlags[i],
                            INJECTED_ENTRIES.contains(s),
                            origWeights[i],
                            newWeights[i]
                    ));
                }
                poolView.recomputePercentages();
                if (!poolView.entries.isEmpty()) {
                    tableView.pools.add(poolView);
                }
            }

            if (!tableView.pools.isEmpty()) {
                LOOT_TABLE_VIEWS.add(tableView);
            }
        }

        LOGGER.info("[InvertedWorld] Processed {} loot tables ({} pools inverted).", LOOT_TABLE_VIEWS.size(), invertedPoolsCount);
    }

    /**
     * Injects rare treasures (with vanilla weight 1) into poor village house chests so that
     * inverting a village house chest gives genuine rare loot instead of only poppies/feathers.
     */
    private static void ensureVillageChestTreasures(LootPool pool, boolean enableEnrichment) {
        if (!VILLAGE_ENRICHED_POOLS.contains(pool)) {
            if (!enableEnrichment) return;
            LootPoolEntryContainer[] existing = getPoolEntries(pool);
            if (existing == null || existing.length == 0) return;

            // Check if diamond or golden apple is already in this pool
            boolean hasDiamond = false;
            for (LootPoolEntryContainer c : existing) {
                if (c instanceof LootItem li) {
                    ItemStack st = extractEntryItemStack(li);
                    if (st.is(Items.DIAMOND) || st.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                        hasDiamond = true;
                        break;
                    }
                }
            }

            if (!hasDiamond) {
                List<LootPoolEntryContainer> newList = new ArrayList<>(Arrays.asList(existing));
                Item[] treasures = new Item[]{
                        Items.DIAMOND,
                        Items.EMERALD,
                        Items.GOLDEN_APPLE,
                        Items.IRON_INGOT,
                        Items.GOLD_INGOT,
                        Items.NAME_TAG
                };
                int[] baseRareWeights = new int[]{1, 2, 1, 3, 2, 1};

                for (int i = 0; i < treasures.length; i++) {
                    LootPoolSingletonContainer built = LootItem.lootTableItem(treasures[i]).setWeight(baseRareWeights[i]).build();
                    ORIGINAL_ENTRY_WEIGHTS.put(built, baseRareWeights[i]);
                    INJECTED_ENTRIES.add(built);
                    newList.add(built);
                }
                setPoolEntries(pool, newList.toArray(new LootPoolEntryContainer[0]));
            }
            VILLAGE_ENRICHED_POOLS.add(pool);
        }
    }

    private static int getIntrinsicItemRarityScore(ItemStack stack, boolean isEmpty) {
        if (isEmpty || stack == null || stack.isEmpty()) return -2;
        Rarity r = stack.getRarity();
        if (r == Rarity.EPIC) return 5;
        if (r == Rarity.RARE) return 4;
        if (r == Rarity.UNCOMMON) return 3;

        Item item = stack.getItem();
        if (item == Items.DIAMOND || item == Items.NETHERITE_INGOT || item == Items.NETHERITE_SCRAP
                || item == Items.ANCIENT_DEBRIS || item == Items.DIAMOND_HORSE_ARMOR
                || item == Items.DIAMOND_SWORD || item == Items.DIAMOND_PICKAXE
                || item == Items.DIAMOND_CHESTPLATE || item == Items.DIAMOND_HELMET
                || item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) {
            return 4;
        }
        if (item == Items.EMERALD || item == Items.GOLDEN_APPLE || item == Items.GOLD_INGOT
                || item == Items.GOLDEN_HORSE_ARMOR || item == Items.SADDLE
                || item == Items.NAME_TAG || item == Items.LEAD || item == Items.BOOK) {
            return 2;
        }
        if (item == Items.IRON_INGOT || item == Items.IRON_HORSE_ARMOR || item == Items.IRON_PICKAXE
                || item == Items.IRON_SWORD || item == Items.OBSIDIAN) {
            return 1;
        }
        if (item == Items.ROTTEN_FLESH || item == Items.BONE || item == Items.STRING
                || item == Items.SPIDER_EYE || item == Items.WHEAT || item == Items.BREAD
                || item == Items.POTATO || item == Items.POPPY || item == Items.DANDELION
                || item == Items.FEATHER || item == Items.STICK || item == Items.WHEAT_SEEDS) {
            return -1;
        }
        return 0;
    }

    // =========================================================================
    // 2. BIOME MOB SPAWNS INVERSION
    // =========================================================================
    public static void applyBiomeSpawnInversion(MinecraftServer server) {
        BIOME_SPAWN_VIEWS.clear();
        Registry<Biome> biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
        InvertedWorldConfig cfg = InvertedWorldConfig.get();

        List<ResourceLocation> biomeIds = new ArrayList<>(biomeRegistry.keySet());
        biomeIds.sort(Comparator.comparing(ResourceLocation::toString));

        for (ResourceLocation biomeId : biomeIds) {
            Biome biome = biomeRegistry.get(biomeId);
            if (biome == null) continue;
            MobSpawnSettings settings = biome.getMobSettings();
            Map<MobCategory, WeightedRandomList<MobSpawnSettings.SpawnerData>> currentMap = getBiomeSpawnersMap(settings);
            if (currentMap == null) continue;

            // Snapshot original biome spawns on first encounter
            Map<MobCategory, List<MobSpawnSettings.SpawnerData>> origCategoryMap =
                    ORIGINAL_BIOME_SPAWNS.computeIfAbsent(biomeId, k -> {
                        Map<MobCategory, List<MobSpawnSettings.SpawnerData>> snap = new EnumMap<>(MobCategory.class);
                        for (Map.Entry<MobCategory, WeightedRandomList<MobSpawnSettings.SpawnerData>> e : currentMap.entrySet()) {
                            snap.put(e.getKey(), new ArrayList<>(e.getValue().unwrap()));
                        }
                        return snap;
                    });

            BiomeSpawnView biomeView = new BiomeSpawnView(biomeId);
            Map<MobCategory, WeightedRandomList<MobSpawnSettings.SpawnerData>> updatedMap = new EnumMap<>(MobCategory.class);

            for (MobCategory category : MobCategory.values()) {
                List<MobSpawnSettings.SpawnerData> origList = origCategoryMap.getOrDefault(category, Collections.emptyList());
                if (origList.isEmpty()) {
                    updatedMap.put(category, WeightedRandomList.create());
                    continue;
                }

                int n = origList.size();
                int[] origWeights = new int[n];
                int minGroup = Integer.MAX_VALUE;
                int maxGroup = Integer.MIN_VALUE;

                for (int i = 0; i < n; i++) {
                    MobSpawnSettings.SpawnerData d = origList.get(i);
                    origWeights[i] = d.getWeight().asInt();
                    minGroup = Math.min(minGroup, d.minCount);
                    maxGroup = Math.max(maxGroup, d.maxCount);
                }

                int[] newWeights = (cfg.modEnabled && cfg.invertBiomeSpawns)
                        ? computeInvertedWeights(origWeights, null)
                        : Arrays.copyOf(origWeights, n);

                int sumOrig = 0;
                int sumNew = 0;
                for (int i = 0; i < n; i++) {
                    sumOrig += origWeights[i];
                    sumNew += newWeights[i];
                }

                List<MobSpawnSettings.SpawnerData> newList = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    MobSpawnSettings.SpawnerData d = origList.get(i);
                    int newMin = d.minCount;
                    int newMax = d.maxCount;
                    if (cfg.modEnabled && cfg.invertBiomeSpawns && cfg.invertMobGroupSizes && maxGroup > minGroup) {
                        newMin = Math.max(1, (maxGroup + minGroup) - d.maxCount);
                        newMax = Math.max(newMin, (maxGroup + minGroup) - d.minCount);
                    }

                    newList.add(new MobSpawnSettings.SpawnerData(d.type, Weight.of(newWeights[i]), newMin, newMax));

                    MobSpawnEntryView entryView = new MobSpawnEntryView(
                            d.type, category,
                            origWeights[i], d.minCount, d.maxCount,
                            newWeights[i], newMin, newMax
                    );
                    entryView.vanillaPercent = sumOrig > 0 ? (origWeights[i] * 100.0) / sumOrig : 0.0;
                    entryView.currentPercent = sumNew > 0 ? (newWeights[i] * 100.0) / sumNew : 0.0;
                    biomeView.spawns.add(entryView);
                }

                updatedMap.put(category, WeightedRandomList.create(newList));
            }

            setFieldByType(settings, MobSpawnSettings.class, Map.class, Collections.unmodifiableMap(updatedMap));
            if (!biomeView.spawns.isEmpty()) {
                BIOME_SPAWN_VIEWS.add(biomeView);
            }
        }
    }

    // =========================================================================
    // 3. ENCHANTMENT RARITY INVERSION
    // =========================================================================
    public static void applyEnchantmentInversion() {
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        for (Map.Entry<net.minecraft.resources.ResourceKey<Enchantment>, Enchantment> entry : ForgeRegistries.ENCHANTMENTS.getEntries()) {
            ResourceLocation id = entry.getKey().location();
            Enchantment ench = entry.getValue();
            Enchantment.Rarity orig = ORIGINAL_ENCHANT_RARITIES.computeIfAbsent(id, k -> ench.getRarity());

            Enchantment.Rarity target = orig;
            if (cfg.modEnabled && cfg.invertEnchantments) {
                target = switch (orig) {
                    case COMMON -> Enchantment.Rarity.VERY_RARE;
                    case UNCOMMON -> Enchantment.Rarity.RARE;
                    case RARE -> Enchantment.Rarity.UNCOMMON;
                    case VERY_RARE -> Enchantment.Rarity.COMMON;
                };
            }
            setFieldByType(ench, Enchantment.class, Enchantment.Rarity.class, target);
        }
    }

    // =========================================================================
    // 4. DISCRETE PROBABILITY DISTRIBUTIONS FOR MOB VARIANTS & WORLD EVENTS
    // =========================================================================

    // Vanilla Sheep Colors: White (81836), Light Gray (5000), Gray (5000), Black (5000), Brown (3000), Pink (164) -> Total 100,000
    private static final DyeColor[] SHEEP_COLORS = {
            DyeColor.WHITE, DyeColor.LIGHT_GRAY, DyeColor.GRAY, DyeColor.BLACK, DyeColor.BROWN, DyeColor.PINK
    };
    private static final String[] SHEEP_COLOR_NAMES = {
            "Белая овца (White)", "Светло-серая овца (Light Gray)", "Серая овца (Gray)",
            "Чёрная овца (Black)", "Коричневая овца (Brown)", "Розовая овца (Pink)"
    };
    private static final Item[] SHEEP_WOOL_ICONS = {
            Items.WHITE_WOOL, Items.LIGHT_GRAY_WOOL, Items.GRAY_WOOL,
            Items.BLACK_WOOL, Items.BROWN_WOOL, Items.PINK_WOOL
    };
    private static final int[] SHEEP_VANILLA_WEIGHTS = {81836, 5000, 5000, 5000, 3000, 164};

    // Vanilla Mob Armor Tiers: None (8500), Leather (556), Gold (454), Chain (386), Iron (95), Diamond (9) -> Total 10,000
    private static final String[] ARMOR_TIER_NAMES = {
            "Моб без брони", "Кожаная броня", "Золотая броня", "Кольчужная броня", "Железная броня", "Алмазная броня"
    };
    private static final Item[] ARMOR_TIER_ICONS = {
            Items.ROTTEN_FLESH, Items.LEATHER_CHESTPLATE, Items.GOLDEN_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE
    };
    private static final int[] ARMOR_VANILLA_WEIGHTS = {8500, 556, 454, 386, 95, 9};

    public static DyeColor rollInvertedSheepColor(RandomSource random) {
        int[] weights = (InvertedWorldConfig.get().modEnabled && InvertedWorldConfig.get().invertMobVariants)
                ? computeInvertedWeights(SHEEP_VANILLA_WEIGHTS, new int[]{-1, 0, 0, 0, 1, 3})
                : SHEEP_VANILLA_WEIGHTS;
        int idx = sampleWeightedIndex(weights, random);
        return SHEEP_COLORS[idx];
    }

    /**
     * Returns 0 = No Armor, 1 = Leather, 2 = Gold, 3 = Chainmail, 4 = Iron, 5 = Diamond
     */
    public static int rollInvertedArmorTier(RandomSource random) {
        int[] weights = (InvertedWorldConfig.get().modEnabled && InvertedWorldConfig.get().invertMobVariants)
                ? computeInvertedWeights(ARMOR_VANILLA_WEIGHTS, new int[]{-1, 0, 1, 1, 2, 4})
                : ARMOR_VANILLA_WEIGHTS;
        return sampleWeightedIndex(weights, random);
    }

    private static int sampleWeightedIndex(int[] weights, RandomSource random) {
        long total = 0;
        for (int w : weights) total += Math.max(0, w);
        if (total <= 0) return 0;
        long r = (long) (random.nextDouble() * total);
        long acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += Math.max(0, weights[i]);
            if (r < acc) return i;
        }
        return weights.length - 1;
    }

    public static void rebuildEventChanceViews() {
        EVENT_CHANCE_VIEWS.clear();
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        boolean active = cfg.modEnabled && cfg.invertMobVariants;

        // 1. Sheep color distribution
        int[] sheepW = active
                ? computeInvertedWeights(SHEEP_VANILLA_WEIGHTS, new int[]{-1, 0, 0, 0, 1, 3})
                : SHEEP_VANILLA_WEIGHTS;
        double sumSheepOrig = Arrays.stream(SHEEP_VANILLA_WEIGHTS).sum();
        double sumSheepNew = Arrays.stream(sheepW).sum();
        for (int i = 0; i < SHEEP_COLORS.length; i++) {
            EVENT_CHANCE_VIEWS.add(new EventChanceView(
                    "Овцы",
                    SHEEP_COLOR_NAMES[i],
                    new ItemStack(SHEEP_WOOL_ICONS[i]),
                    (SHEEP_VANILLA_WEIGHTS[i] * 100.0) / sumSheepOrig,
                    (sheepW[i] * 100.0) / sumSheepNew
            ));
        }

        // 2. Mob armor distribution
        int[] armorW = active
                ? computeInvertedWeights(ARMOR_VANILLA_WEIGHTS, new int[]{-1, 0, 1, 1, 2, 4})
                : ARMOR_VANILLA_WEIGHTS;
        double sumArmorOrig = Arrays.stream(ARMOR_VANILLA_WEIGHTS).sum();
        double sumArmorNew = Arrays.stream(armorW).sum();
        for (int i = 0; i < ARMOR_TIER_NAMES.length; i++) {
            EVENT_CHANCE_VIEWS.add(new EventChanceView(
                    "Экипировка",
                    ARMOR_TIER_NAMES[i],
                    new ItemStack(ARMOR_TIER_ICONS[i]),
                    (ARMOR_VANILLA_WEIGHTS[i] * 100.0) / sumArmorOrig,
                    (armorW[i] * 100.0) / sumArmorNew
            ));
        }

        // 3. Special Mob Variants (Binary probabilities)
        addBinaryEventView("Особые мобы", "Маленький зомби (Baby Zombie)", Items.ZOMBIE_HEAD, 0.05f, active);
        addBinaryEventView("Особые мобы", "Паучий наездник (Spider Jockey)", Items.BOW, 0.01f, active);
        addBinaryEventView("Особые мобы", "Заряженный крипер (Charged Creeper)", Items.CREEPER_HEAD, 0.01f, active);
        addBinaryEventView("Особые мобы", "Синий аксолотль (Blue Axolotl)", Items.AXOLOTL_BUCKET, 0.00083f, active);
        addBinaryEventView("Особые мобы", "Кричащая коза (Screaming Goat)", Items.GOAT_HORN, 0.02f, active);
        addBinaryEventView("Особые мобы", "Коричневая панда (Brown Panda)", Items.BAMBOO, 0.0204f, active);

        // 4. Enchantments summary
        boolean enchActive = cfg.modEnabled && cfg.invertEnchantments;
        for (Map.Entry<net.minecraft.resources.ResourceKey<Enchantment>, Enchantment> entry : ForgeRegistries.ENCHANTMENTS.getEntries()) {
            ResourceLocation id = entry.getKey().location();
            Enchantment ench = entry.getValue();
            Enchantment.Rarity orig = ORIGINAL_ENCHANT_RARITIES.getOrDefault(id, ench.getRarity());
            Enchantment.Rarity curr = ench.getRarity();
            // Convert rarity weight (10, 5, 2, 1) to relative weight % out of 18
            double origPct = (orig.getWeight() * 100.0) / 18.0;
            double currPct = ((enchActive ? curr.getWeight() : orig.getWeight()) * 100.0) / 18.0;
            String name = Component.translatable(ench.getDescriptionId()).getString() + " (" + orig.name() + " → " + curr.name() + ")";
            EVENT_CHANCE_VIEWS.add(new EventChanceView(
                    "Зачарования",
                    name,
                    new ItemStack(Items.ENCHANTED_BOOK),
                    origPct,
                    currPct
            ));
        }
    }

    private static void addBinaryEventView(String category, String name, Item icon, float vanillaProb, boolean active) {
        double origPct = vanillaProb * 100.0;
        double newPct = (active ? invertSingleProbability(vanillaProb) : vanillaProb) * 100.0;
        EVENT_CHANCE_VIEWS.add(new EventChanceView(category, name, new ItemStack(icon), origPct, newPct));
    }

    // =========================================================================
    // REFLECTION & UNSAFE ACCESSORS (100% Obfuscation-Safe for Dev & Prod SRG)
    // =========================================================================

    private static Map<ResourceLocation, LootTable> collectAllLootTables(LootDataManager manager) {
        Map<ResourceLocation, LootTable> result = new HashMap<>();
        try {
            Collection<ResourceLocation> keys = manager.getKeys(LootDataType.TABLE);
            for (ResourceLocation key : keys) {
                LootTable table = manager.getLootTable(key);
                if (table != null && table != LootTable.EMPTY) {
                    result.put(key, table);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Throwable ignored) {
        }

        // Fallback via reflection on LootDataManager.elements
        try {
            for (Field f : LootDataManager.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(manager);
                    if (val instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            if (e.getValue() instanceof LootTable lt && lt != LootTable.EMPTY) {
                                ResourceLocation loc = extractResourceLocationFromKey(e.getKey());
                                if (loc != null) {
                                    result.put(loc, lt);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[InvertedWorld] Failed to collect loot tables", e);
        }
        return result;
    }

    private static ResourceLocation extractResourceLocationFromKey(Object key) {
        if (key instanceof ResourceLocation rl) return rl;
        if (key == null) return null;
        try {
            for (Field f : key.getClass().getDeclaredFields()) {
                if (ResourceLocation.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (ResourceLocation) f.get(key);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void collectSingletonsRecursive(LootPoolEntryContainer[] entries, List<LootPoolSingletonContainer> out) {
        for (LootPoolEntryContainer entry : entries) {
            if (entry instanceof LootPoolSingletonContainer singleton) {
                out.add(singleton);
            } else if (entry instanceof CompositeEntryBase composite) {
                LootPoolEntryContainer[] children = getFieldByType(composite, CompositeEntryBase.class, LootPoolEntryContainer[].class);
                if (children != null) {
                    collectSingletonsRecursive(children, out);
                }
            }
        }
    }

    private static void invertEntryConditionsRecursive(LootPoolEntryContainer[] entries, boolean invert) {
        for (LootPoolEntryContainer entry : entries) {
            LootItemCondition[] conds = getFieldByType(entry, LootPoolEntryContainer.class, LootItemCondition[].class);
            if (conds != null) {
                for (LootItemCondition c : conds) {
                    processCondition(c, invert);
                }
            }
            if (entry instanceof CompositeEntryBase composite) {
                LootPoolEntryContainer[] children = getFieldByType(composite, CompositeEntryBase.class, LootPoolEntryContainer[].class);
                if (children != null) {
                    invertEntryConditionsRecursive(children, invert);
                }
            }
        }
    }

    private static Float processCondition(LootItemCondition condition, boolean invert) {
        if (condition instanceof LootItemRandomChanceCondition rc) {
            Float orig = ORIGINAL_CONDITION_CHANCES.computeIfAbsent(rc, k -> getFieldByType(rc, LootItemRandomChanceCondition.class, float.class));
            if (orig != null) {
                float target = invert ? invertSingleProbability(orig) : orig;
                setFloatField(rc, LootItemRandomChanceCondition.class, 0, target);
                return orig;
            }
        } else if (condition instanceof LootItemRandomChanceWithLootingCondition rcl) {
            Float orig = ORIGINAL_CONDITION_CHANCES.computeIfAbsent(rcl, k -> getFloatField(rcl, LootItemRandomChanceWithLootingCondition.class, 0));
            if (orig != null) {
                float target = invert ? invertSingleProbability(orig) : orig;
                setFloatField(rcl, LootItemRandomChanceWithLootingCondition.class, 0, target);
                return orig;
            }
        }
        return null;
    }

    private static float getConditionChance(LootItemCondition condition) {
        if (condition instanceof LootItemRandomChanceCondition rc) {
            Float v = getFieldByType(rc, LootItemRandomChanceCondition.class, float.class);
            return v != null ? v : 1.0f;
        } else if (condition instanceof LootItemRandomChanceWithLootingCondition rcl) {
            Float v = getFloatField(rcl, LootItemRandomChanceWithLootingCondition.class, 0);
            return v != null ? v : 1.0f;
        }
        return 1.0f;
    }

    private static ItemStack extractEntryItemStack(LootPoolSingletonContainer singleton) {
        if (singleton instanceof LootItem lootItem) {
            Item item = getFieldByType(lootItem, LootItem.class, Item.class);
            if (item != null && item != Items.AIR) {
                return new ItemStack(item);
            }
        } else if (singleton instanceof EmptyLootItem) {
            return new ItemStack(Items.BARRIER);
        }
        return new ItemStack(Items.CHEST);
    }

    private static String extractEntryDisplayName(LootPoolSingletonContainer singleton, ItemStack icon) {
        if (singleton instanceof EmptyLootItem) {
            return "Пустой слот (minecraft:empty)";
        }
        if (singleton instanceof LootItem && !icon.isEmpty() && !icon.is(Items.CHEST)) {
            String baseName = icon.getHoverName().getString();
            if (INJECTED_ENTRIES.contains(singleton)) {
                return baseName + " ★";
            }
            return baseName;
        }
        ResourceLocation subTable = getFieldByType(singleton, singleton.getClass(), ResourceLocation.class);
        if (subTable != null) {
            return "Таблица: " + subTable.getPath();
        }
        return singleton.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static List<LootPool> getLootPools(LootTable table) {
        try {
            for (Field f : LootTable.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val = f.get(table);
                if (val instanceof List<?> list) {
                    return (List<LootPool>) list;
                } else if (val instanceof LootPool[] arr) {
                    return new ArrayList<>(Arrays.asList(arr));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static LootPoolEntryContainer[] getPoolEntries(LootPool pool) {
        try {
            for (Field f : LootPool.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val = f.get(pool);
                if (val instanceof LootPoolEntryContainer[] arr) {
                    return arr;
                } else if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof LootPoolEntryContainer) {
                    return list.toArray(new LootPoolEntryContainer[0]);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void setPoolEntries(LootPool pool, LootPoolEntryContainer[] newEntries) {
        try {
            for (Field f : LootPool.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val = f.get(pool);
                if (val instanceof LootPoolEntryContainer[]) {
                    if (UNSAFE != null) {
                        UNSAFE.putObject(pool, UNSAFE.objectFieldOffset(f), newEntries);
                    } else {
                        f.set(pool, newEntries);
                    }
                    return;
                } else if (val instanceof List<?> list) {
                    if (list.isEmpty() || list.get(0) instanceof LootPoolEntryContainer) {
                        List<LootPoolEntryContainer> mutable = new ArrayList<>(Arrays.asList(newEntries));
                        if (UNSAFE != null) {
                            UNSAFE.putObject(pool, UNSAFE.objectFieldOffset(f), mutable);
                        } else {
                            f.set(pool, mutable);
                        }
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static LootItemCondition[] getPoolConditions(LootPool pool) {
        return getFieldByType(pool, LootPool.class, LootItemCondition[].class);
    }

    public static int getSingletonWeight(LootPoolSingletonContainer singleton) {
        try {
            for (Field f : LootPoolSingletonContainer.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                    f.setAccessible(true);
                    return f.getInt(singleton);
                }
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    public static void setSingletonWeight(LootPoolSingletonContainer singleton, int newWeight) {
        try {
            for (Field f : LootPoolSingletonContainer.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                    f.setAccessible(true);
                    if (UNSAFE != null) {
                        UNSAFE.putInt(singleton, UNSAFE.objectFieldOffset(f), newWeight);
                    } else {
                        f.setInt(singleton, newWeight);
                    }
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<MobCategory, WeightedRandomList<MobSpawnSettings.SpawnerData>> getBiomeSpawnersMap(MobSpawnSettings settings) {
        try {
            for (Field f : MobSpawnSettings.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(settings);
                    if (val instanceof Map<?, ?> map && !map.isEmpty()) {
                        Object firstKey = map.keySet().iterator().next();
                        if (firstKey instanceof MobCategory) {
                            return (Map<MobCategory, WeightedRandomList<MobSpawnSettings.SpawnerData>>) map;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Float getFloatField(Object instance, Class<?> clazz, int index) {
        try {
            int cur = 0;
            for (Field f : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == float.class) {
                    if (cur == index) {
                        f.setAccessible(true);
                        return f.getFloat(instance);
                    }
                    cur++;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void setFloatField(Object instance, Class<?> clazz, int index, float newValue) {
        try {
            int cur = 0;
            for (Field f : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == float.class) {
                    if (cur == index) {
                        f.setAccessible(true);
                        if (UNSAFE != null) {
                            UNSAFE.putFloat(instance, UNSAFE.objectFieldOffset(f), newValue);
                        } else {
                            f.setFloat(instance, newValue);
                        }
                        return;
                    }
                    cur++;
                }
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getFieldByType(Object instance, Class<?> clazz, Class<T> fieldType) {
        try {
            for (Field f : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && fieldType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (T) f.get(instance);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean setFieldByType(Object instance, Class<?> clazz, Class<?> fieldType, Object newValue) {
        try {
            for (Field f : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && fieldType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    if (UNSAFE != null) {
                        UNSAFE.putObject(instance, UNSAFE.objectFieldOffset(f), newValue);
                    } else {
                        f.set(instance, newValue);
                    }
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
