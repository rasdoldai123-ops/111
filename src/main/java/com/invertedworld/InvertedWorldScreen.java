package com.invertedworld;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class InvertedWorldScreen extends Screen {

    private enum Tab {
        LOOT_TABLES("1. Сундуки и Лут"),
        BIOME_SPAWNS("2. Спавн Мобов"),
        WORLD_EVENTS("3. Шансы Событий"),
        SETTINGS("4. Настройки");

        final String title;
        Tab(String title) {
            this.title = title;
        }
    }

    private final Screen parent;
    private Tab currentTab = Tab.LOOT_TABLES;

    // Search & filter state
    private EditBox searchBox;
    private String searchText = "";
    private String lootCategoryFilter = "all"; // "all", "village", "chests", "entities", "blocks"
    private String eventCategoryFilter = "all"; // "all", "Овцы", "Экипировка", "Особые мобы", "Зачарования"

    // Selection & scroll state
    private int selectedTableIndex = 0;
    private int selectedPoolIndex = 0;
    private int selectedBiomeIndex = 0;
    private double leftScroll = 0;
    private double rightScroll = 0;

    // Filtered lists for rendering
    private final List<InversionEngine.LootTableView> filteredLootTables = new ArrayList<>();
    private final List<InversionEngine.BiomeSpawnView> filteredBiomes = new ArrayList<>();
    private final List<InversionEngine.EventChanceView> filteredEvents = new ArrayList<>();

    // Clickable inline regions for [-] and [+] weight buttons in Loot Inspector
    private record InlineWeightBtn(int x, int y, int w, int h, InversionEngine.LootPoolView pool, InversionEngine.LootEntryView entry, int delta) {
        boolean isHovered(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
    private final List<InlineWeightBtn> inlineButtons = new ArrayList<>();

    public InvertedWorldScreen(Screen parent) {
        super(Component.literal("Inverted World — Инспектор Шансов и Настройки"));
        this.parent = parent;
        if (InversionEngine.getLootTableViews().isEmpty() && ServerLifecycleHooks.getCurrentServer() != null) {
            InversionEngine.recalculateAll(ServerLifecycleHooks.getCurrentServer());
        } else if (InversionEngine.getEventChanceViews().isEmpty()) {
            InversionEngine.rebuildEventChanceViews();
        }
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        int topY = 26;
        int tabWidth = (this.width - 40) / 4;
        for (int i = 0; i < Tab.values().length; i++) {
            Tab tab = Tab.values()[i];
            int tabX = 20 + i * tabWidth;
            Button tabBtn = Button.builder(
                    Component.literal(currentTab == tab ? "§e§l" + tab.title : tab.title),
                    btn -> {
                        this.currentTab = tab;
                        this.leftScroll = 0;
                        this.rightScroll = 0;
                        this.init();
                    }
            ).bounds(tabX, topY, tabWidth - 4, 20).build();
            this.addRenderableWidget(tabBtn);
        }

        // Bottom bar: Quick Recalculate & Close buttons
        this.addRenderableWidget(Button.builder(
                Component.literal("§aПрименить и пересчитать шансы"),
                btn -> {
                    InvertedWorldConfig.save();
                    InversionEngine.recalculateAll(ServerLifecycleHooks.getCurrentServer());
                    refreshFilters();
                    this.init();
                }
        ).bounds(20, this.height - 26, 190, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Закрыть [ESC]"),
                btn -> this.onClose()
        ).bounds(this.width - 130, this.height - 26, 110, 20).build());

        if (currentTab == Tab.LOOT_TABLES) {
            initLootTablesTab();
        } else if (currentTab == Tab.BIOME_SPAWNS) {
            initBiomeSpawnsTab();
        } else if (currentTab == Tab.WORLD_EVENTS) {
            initWorldEventsTab();
        } else if (currentTab == Tab.SETTINGS) {
            initSettingsTab();
        }

        refreshFilters();
    }

    private void initLootTablesTab() {
        this.searchBox = new EditBox(this.font, 20, 52, 175, 18, Component.literal("Поиск..."));
        this.searchBox.setMaxLength(64);
        this.searchBox.setValue(this.searchText);
        this.searchBox.setHint(Component.literal("§7Поиск сундука/предмета..."));
        this.searchBox.setResponder(val -> {
            this.searchText = val;
            this.selectedTableIndex = 0;
            this.selectedPoolIndex = 0;
            this.leftScroll = 0;
            this.rightScroll = 0;
            refreshFilters();
        });
        this.addRenderableWidget(this.searchBox);

        String[][] cats = {
                {"all", "Все"},
                {"village", "Деревни"},
                {"chests", "Данжи"},
                {"entities", "Мобы"},
                {"blocks", "Блоки"}
        };
        int catX = 202;
        for (String[] c : cats) {
            String key = c[0];
            String label = lootCategoryFilter.equals(key) ? "§b§l" + c[1] : c[1];
            int w = this.font.width(c[1]) + 14;
            this.addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                this.lootCategoryFilter = key;
                this.selectedTableIndex = 0;
                this.selectedPoolIndex = 0;
                this.leftScroll = 0;
                this.rightScroll = 0;
                this.init();
            }).bounds(catX, 51, w, 20).build());
            catX += w + 3;
        }
    }

    private void initBiomeSpawnsTab() {
        this.searchBox = new EditBox(this.font, 20, 52, 240, 18, Component.literal("Поиск биома или моба..."));
        this.searchBox.setMaxLength(64);
        this.searchBox.setValue(this.searchText);
        this.searchBox.setHint(Component.literal("§7Поиск биома или моба (plains, enderman)..."));
        this.searchBox.setResponder(val -> {
            this.searchText = val;
            this.selectedBiomeIndex = 0;
            this.leftScroll = 0;
            this.rightScroll = 0;
            refreshFilters();
        });
        this.addRenderableWidget(this.searchBox);
    }

    private void initWorldEventsTab() {
        String[][] cats = {
                {"all", "Все события"},
                {"Овцы", "Цвета овец"},
                {"Экипировка", "Броня мобов"},
                {"Особые мобы", "Особые мобы"},
                {"Зачарования", "Зачарования"}
        };
        int catX = 20;
        for (String[] c : cats) {
            String key = c[0];
            String label = eventCategoryFilter.equals(key) ? "§b§l" + c[1] : c[1];
            int w = this.font.width(c[1]) + 18;
            this.addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                this.eventCategoryFilter = key;
                this.rightScroll = 0;
                this.init();
            }).bounds(catX, 51, w, 20).build());
            catX += w + 4;
        }
    }

    private void initSettingsTab() {
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        int col1X = 25;
        int col2X = this.width / 2 + 5;
        int colW = (this.width - 60) / 2;
        int startY = 56;
        int rowH = 24;

        // Row 0: Mod Enabled & Formula Selector
        this.addRenderableWidget(Button.builder(
                Component.literal("Мод активен: " + (cfg.modEnabled ? "§a§lВКЛ" : "§c§lВЫКЛ")),
                btn -> {
                    cfg.modEnabled = !cfg.modEnabled;
                    applyAndRefresh();
                }
        ).bounds(col1X, startY, colW, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Формула: §e" + cfg.formula.getTitle()),
                btn -> {
                    cfg.formula = cfg.formula.next();
                    applyAndRefresh();
                }
        ).bounds(col2X, startY, colW, 20).build());

        // Row 1: Inversion Strength [-25%] [Strength: 100%] [+25%]
        int strengthPct = Math.round(cfg.inversionStrength * 100);
        this.addRenderableWidget(Button.builder(
                Component.literal("§c-25%"),
                btn -> {
                    cfg.inversionStrength = Math.max(0.0f, Math.round((cfg.inversionStrength - 0.25f) * 100f) / 100f);
                    applyAndRefresh();
                }
        ).bounds(col1X, startY + rowH, 48, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Сила инверсии: §b§l" + strengthPct + "%"),
                btn -> {
                    cfg.inversionStrength = 1.0f;
                    applyAndRefresh();
                }
        ).bounds(col1X + 52, startY + rowH, colW - 104, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("§a+25%"),
                btn -> {
                    cfg.inversionStrength = Math.min(2.5f, Math.round((cfg.inversionStrength + 0.25f) * 100f) / 100f);
                    applyAndRefresh();
                }
        ).bounds(col1X + colW - 48, startY + rowH, 48, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Инверсия сундуков и лута: " + onOff(cfg.invertLootTables)),
                btn -> {
                    cfg.invertLootTables = !cfg.invertLootTables;
                    applyAndRefresh();
                }
        ).bounds(col2X, startY + rowH, colW, 20).build());

        // Row 2
        this.addRenderableWidget(Button.builder(
                Component.literal("Сокровища в сундуках деревень: " + onOff(cfg.enrichVillageChests)),
                btn -> {
                    cfg.enrichVillageChests = !cfg.enrichVillageChests;
                    applyAndRefresh();
                }
        ).bounds(col1X, startY + rowH * 2, colW, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Снижать шанс пустых слотов: " + onOff(cfg.reduceEmptySlotWeight)),
                btn -> {
                    cfg.reduceEmptySlotWeight = !cfg.reduceEmptySlotWeight;
                    applyAndRefresh();
                }
        ).bounds(col2X, startY + rowH * 2, colW, 20).build());

        // Row 3
        this.addRenderableWidget(Button.builder(
                Component.literal("Инверсия спавна мобов в биомах: " + onOff(cfg.invertBiomeSpawns)),
                btn -> {
                    cfg.invertBiomeSpawns = !cfg.invertBiomeSpawns;
                    applyAndRefresh();
                }
        ).bounds(col1X, startY + rowH * 3, colW, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Инверсия размера групп мобов: " + onOff(cfg.invertMobGroupSizes)),
                btn -> {
                    cfg.invertMobGroupSizes = !cfg.invertMobGroupSizes;
                    applyAndRefresh();
                }
        ).bounds(col2X, startY + rowH * 3, colW, 20).build());

        // Row 4
        this.addRenderableWidget(Button.builder(
                Component.literal("Инверсия редкости зачарований: " + onOff(cfg.invertEnchantments)),
                btn -> {
                    cfg.invertEnchantments = !cfg.invertEnchantments;
                    applyAndRefresh();
                }
        ).bounds(col1X, startY + rowH * 4, colW, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Варианты мобов (овцы/броня/криперы): " + onOff(cfg.invertMobVariants)),
                btn -> {
                    cfg.invertMobVariants = !cfg.invertMobVariants;
                    applyAndRefresh();
                }
        ).bounds(col2X, startY + rowH * 4, colW, 20).build());

        // Row 5
        this.addRenderableWidget(Button.builder(
                Component.literal("Торговля жителей (1 ур. ↔ 5 ур.): " + onOff(cfg.invertVillagerTrades)),
                btn -> {
                    cfg.invertVillagerTrades = !cfg.invertVillagerTrades;
                    applyAndRefresh();
                }
        ).bounds(col1X, startY + rowH * 5, colW, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("§eСбросить ручные правки и настройки"),
                btn -> {
                    InversionEngine.clearCustomWeights();
                    cfg.resetDefaults();
                    applyAndRefresh();
                }
        ).bounds(col2X, startY + rowH * 5, colW, 20).build());
    }

    private void applyAndRefresh() {
        InvertedWorldConfig.save();
        InversionEngine.recalculateAll(ServerLifecycleHooks.getCurrentServer());
        refreshFilters();
        this.init();
    }

    private static String onOff(boolean v) {
        return v ? "§aВКЛ" : "§cВЫКЛ";
    }

    private void refreshFilters() {
        String q = this.searchText == null ? "" : this.searchText.trim().toLowerCase(Locale.ROOT);

        // Filter Loot Tables
        filteredLootTables.clear();
        for (InversionEngine.LootTableView t : InversionEngine.getLootTableViews()) {
            if (!lootCategoryFilter.equals("all") && !t.category.equals(lootCategoryFilter)) {
                continue;
            }
            if (q.isEmpty()) {
                filteredLootTables.add(t);
                continue;
            }
            boolean match = t.id.toString().toLowerCase(Locale.ROOT).contains(q);
            if (!match) {
                for (InversionEngine.LootPoolView p : t.pools) {
                    for (InversionEngine.LootEntryView e : p.entries) {
                        if (e.displayName.toLowerCase(Locale.ROOT).contains(q)) {
                            match = true;
                            break;
                        }
                    }
                    if (match) break;
                }
            }
            if (match) {
                filteredLootTables.add(t);
            }
        }
        if (selectedTableIndex >= filteredLootTables.size()) {
            selectedTableIndex = Math.max(0, filteredLootTables.size() - 1);
        }

        // Filter Biomes
        filteredBiomes.clear();
        for (InversionEngine.BiomeSpawnView b : InversionEngine.getBiomeSpawnViews()) {
            if (q.isEmpty()) {
                filteredBiomes.add(b);
                continue;
            }
            boolean match = b.biomeId.toString().toLowerCase(Locale.ROOT).contains(q);
            if (!match) {
                for (InversionEngine.MobSpawnEntryView s : b.spawns) {
                    if (s.mobName.toLowerCase(Locale.ROOT).contains(q)) {
                        match = true;
                        break;
                    }
                }
            }
            if (match) {
                filteredBiomes.add(b);
            }
        }
        if (selectedBiomeIndex >= filteredBiomes.size()) {
            selectedBiomeIndex = Math.max(0, filteredBiomes.size() - 1);
        }

        // Filter World Events
        filteredEvents.clear();
        for (InversionEngine.EventChanceView ev : InversionEngine.getEventChanceViews()) {
            if (!eventCategoryFilter.equals("all") && !ev.category.equals(eventCategoryFilter)) {
                continue;
            }
            filteredEvents.add(ev);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        inlineButtons.clear();

        // Main outer panel
        g.fill(12, 8, this.width - 12, this.height - 6, 0xD0101016);
        g.renderOutline(12, 8, this.width - 24, this.height - 14, 0xFF604090);

        // Header title
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        String statusBadge = cfg.modEnabled
                ? "§a[АКТИВЕН | " + cfg.formula.getTitle() + " | Сила " + Math.round(cfg.inversionStrength * 100) + "%]"
                : "§c[МОД ВЫКЛЮЧЕН — ВАНИЛЬНЫЕ ШАНСЫ]";
        g.drawCenteredString(this.font, "§d§lInverted World §7— " + statusBadge, this.width / 2, 13, 0xFFFFFF);

        if (currentTab == Tab.LOOT_TABLES) {
            renderLootTablesTab(g, mouseX, mouseY);
        } else if (currentTab == Tab.BIOME_SPAWNS) {
            renderBiomeSpawnsTab(g, mouseX, mouseY);
        } else if (currentTab == Tab.WORLD_EVENTS) {
            renderWorldEventsTab(g, mouseX, mouseY);
        } else if (currentTab == Tab.SETTINGS) {
            renderSettingsTab(g, mouseX, mouseY);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderLootTablesTab(GuiGraphics g, int mouseX, int mouseY) {
        int topY = 74;
        int bottomY = this.height - 32;
        int leftX = 20;
        int leftW = Math.min(200, (this.width - 50) / 3);
        int rightX = leftX + leftW + 8;
        int rightW = this.width - 20 - rightX;

        // Left list panel (Loot Tables)
        g.fill(leftX, topY, leftX + leftW, bottomY, 0xB008080C);
        g.renderOutline(leftX, topY, leftW, bottomY - topY, 0xFF444455);

        // Right detail panel (Selected Table Pool & Entries)
        g.fill(rightX, topY, rightX + rightW, bottomY, 0xB008080C);
        g.renderOutline(rightX, topY, rightW, bottomY - topY, 0xFF444455);

        if (filteredLootTables.isEmpty()) {
            g.drawCenteredString(this.font, "§7Таблицы лута не найдены (зайдите в мир)", leftX + leftW / 2, topY + 30, 0xAAAAAA);
            return;
        }

        // Render left scrollable list of tables
        g.enableScissor(leftX + 1, topY + 1, leftX + leftW - 1, bottomY - 1);
        int itemH = 18;
        int startY = topY + 4 - (int) leftScroll;
        for (int i = 0; i < filteredLootTables.size(); i++) {
            int rowY = startY + i * itemH;
            if (rowY + itemH < topY || rowY > bottomY) continue;
            InversionEngine.LootTableView t = filteredLootTables.get(i);
            boolean selected = (i == selectedTableIndex);
            boolean hovered = mouseX >= leftX && mouseX < leftX + leftW && mouseY >= rowY && mouseY < rowY + itemH;

            if (selected) {
                g.fill(leftX + 2, rowY, leftX + leftW - 2, rowY + itemH - 1, 0xFF4A2A7A);
            } else if (hovered) {
                g.fill(leftX + 2, rowY, leftX + leftW - 2, rowY + itemH - 1, 0x60303045);
            }

            String shortPath = t.id.getPath()
                    .replace("chests/village/", "🏠 ")
                    .replace("chests/", "📦 ")
                    .replace("entities/", "⚔ ")
                    .replace("blocks/", "⛏ ");
            if (this.font.width(shortPath) > leftW - 10) {
                shortPath = this.font.plainSubstrByWidth(shortPath, leftW - 14) + "..";
            }
            g.drawString(this.font, shortPath, leftX + 6, rowY + 5, selected ? 0xFFFF55 : 0xDDDDDD, false);
        }
        g.disableScissor();

        // Render right panel: Selected Loot Table details
        InversionEngine.LootTableView selectedTable = filteredLootTables.get(selectedTableIndex);
        if (selectedPoolIndex >= selectedTable.pools.size()) {
            selectedPoolIndex = 0;
        }
        InversionEngine.LootPoolView pool = selectedTable.pools.get(selectedPoolIndex);

        // Header of right panel
        g.fill(rightX + 1, topY + 1, rightX + rightW - 1, topY + 22, 0xFF1E1A2E);
        String headerText = "§e" + selectedTable.id.toString() + " §7(Пул " + (selectedPoolIndex + 1) + "/" + selectedTable.pools.size() + ")";
        if (pool.vanillaConditionChance != null && pool.currentConditionChance != null) {
            headerText += String.format(Locale.ROOT, " §b[Шанс пула: %.1f%% → %.1f%%]", pool.vanillaConditionChance, pool.currentConditionChance);
        }
        g.drawString(this.font, headerText, rightX + 8, topY + 7, 0xFFFFFF, false);

        // Pool switcher hint if multiple pools exist
        if (selectedTable.pools.size() > 1) {
            String switchTxt = "§b[Сменить пул ⟳]";
            int swW = this.font.width(switchTxt) + 8;
            int swX = rightX + rightW - swW - 4;
            g.fill(swX, topY + 3, swX + swW, topY + 19, 0xFF3A3A5C);
            g.drawString(this.font, switchTxt, swX + 4, topY + 7, 0xFFFFFF, false);
        }

        // Render entries inside selected pool
        int listTop = topY + 24;
        g.enableScissor(rightX + 1, listTop, rightX + rightW - 1, bottomY - 1);
        int entryH = 26;
        int eStartY = listTop + 4 - (int) rightScroll;

        for (int i = 0; i < pool.entries.size(); i++) {
            int ey = eStartY + i * entryH;
            if (ey + entryH < listTop || ey > bottomY) continue;
            InversionEngine.LootEntryView e = pool.entries.get(i);

            g.fill(rightX + 4, ey, rightX + rightW - 4, ey + entryH - 2, (i % 2 == 0) ? 0x601A1A26 : 0x6012121A);

            // Item icon
            g.renderItem(e.icon, rightX + 8, ey + 4);

            // Item display name
            String itemName = e.displayName;
            int maxNameW = Math.max(70, rightW - 220);
            if (this.font.width(itemName) > maxNameW) {
                itemName = this.font.plainSubstrByWidth(itemName, maxNameW - 6) + "..";
            }
            g.drawString(this.font, itemName, rightX + 28, ey + 4, e.isEmptyEntry ? 0x999999 : 0xFFFFFF, false);

            // Vanilla % -> Inverted %
            boolean increased = e.currentPercent > e.vanillaPercent + 0.05;
            boolean decreased = e.currentPercent < e.vanillaPercent - 0.05;
            String arrow = increased ? "§a▲ " : (decreased ? "§c▼ " : "§7= ");
            String stats = String.format(Locale.ROOT,
                    "§7Ванилла: §f%d (%.1f%%) §7→ Инверсия: %s§l%d (%.1f%%)",
                    e.vanillaWeight, e.vanillaPercent,
                    increased ? "§a" : (decreased ? "§c" : "§e"),
                    e.currentWeight, e.currentPercent);
            g.drawString(this.font, arrow + stats, rightX + 28, ey + 14, 0xCCCCCC, false);

            // Visual dual bar on the right + [-] [+] manual buttons
            int btnPlusX = rightX + rightW - 22;
            int btnMinusX = rightX + rightW - 40;
            int btnY = ey + 5;

            boolean hovMinus = mouseX >= btnMinusX && mouseX < btnMinusX + 16 && mouseY >= btnY && mouseY < btnY + 14;
            boolean hovPlus = mouseX >= btnPlusX && mouseX < btnPlusX + 16 && mouseY >= btnY && mouseY < btnY + 14;

            g.fill(btnMinusX, btnY, btnMinusX + 16, btnY + 14, hovMinus ? 0xFF773333 : 0xFF442222);
            g.drawCenteredString(this.font, "-", btnMinusX + 8, btnY + 3, 0xFFAAAA);
            inlineButtons.add(new InlineWeightBtn(btnMinusX, btnY, 16, 14, pool, e, -Math.max(1, e.currentWeight / 5)));

            g.fill(btnPlusX, btnY, btnPlusX + 16, btnY + 14, hovPlus ? 0xFF337733 : 0xFF224422);
            g.drawCenteredString(this.font, "+", btnPlusX + 8, btnY + 3, 0xAAFFAA);
            inlineButtons.add(new InlineWeightBtn(btnPlusX, btnY, 16, 14, pool, e, Math.max(1, e.currentWeight / 4)));
        }
        g.disableScissor();
    }

    private void renderBiomeSpawnsTab(GuiGraphics g, int mouseX, int mouseY) {
        int topY = 74;
        int bottomY = this.height - 32;
        int leftX = 20;
        int leftW = Math.min(195, (this.width - 50) / 3);
        int rightX = leftX + leftW + 8;
        int rightW = this.width - 20 - rightX;

        g.fill(leftX, topY, leftX + leftW, bottomY, 0xB008080C);
        g.renderOutline(leftX, topY, leftW, bottomY - topY, 0xFF444455);

        g.fill(rightX, topY, rightX + rightW, bottomY, 0xB008080C);
        g.renderOutline(rightX, topY, rightW, bottomY - topY, 0xFF444455);

        if (filteredBiomes.isEmpty()) {
            g.drawCenteredString(this.font, "§7Биомы не найдены (зайдите в мир)", leftX + leftW / 2, topY + 30, 0xAAAAAA);
            return;
        }

        // Left: Biomes list
        g.enableScissor(leftX + 1, topY + 1, leftX + leftW - 1, bottomY - 1);
        int itemH = 18;
        int startY = topY + 4 - (int) leftScroll;
        for (int i = 0; i < filteredBiomes.size(); i++) {
            int rowY = startY + i * itemH;
            if (rowY + itemH < topY || rowY > bottomY) continue;
            InversionEngine.BiomeSpawnView b = filteredBiomes.get(i);
            boolean selected = (i == selectedBiomeIndex);
            boolean hovered = mouseX >= leftX && mouseX < leftX + leftW && mouseY >= rowY && mouseY < rowY + itemH;

            if (selected) {
                g.fill(leftX + 2, rowY, leftX + leftW - 2, rowY + itemH - 1, 0xFF2A5A4A);
            } else if (hovered) {
                g.fill(leftX + 2, rowY, leftX + leftW - 2, rowY + itemH - 1, 0x60304538);
            }
            String label = "🌍 " + b.biomeId.getPath();
            if (this.font.width(label) > leftW - 10) {
                label = this.font.plainSubstrByWidth(label, leftW - 14) + "..";
            }
            g.drawString(this.font, label, leftX + 6, rowY + 5, selected ? 0x55FFAA : 0xDDDDDD, false);
        }
        g.disableScissor();

        // Right: Mobs inside selected biome
        InversionEngine.BiomeSpawnView selectedBiome = filteredBiomes.get(selectedBiomeIndex);
        g.fill(rightX + 1, topY + 1, rightX + rightW - 1, topY + 22, 0xFF1A2E26);
        g.drawString(this.font, "§aБиом: §f" + selectedBiome.biomeId + " §7(Мобов: " + selectedBiome.spawns.size() + ")", rightX + 8, topY + 7, 0xFFFFFF, false);

        int listTop = topY + 24;
        g.enableScissor(rightX + 1, listTop, rightX + rightW - 1, bottomY - 1);
        int entryH = 26;
        int eStartY = listTop + 4 - (int) rightScroll;

        for (int i = 0; i < selectedBiome.spawns.size(); i++) {
            int ey = eStartY + i * entryH;
            if (ey + entryH < listTop || ey > bottomY) continue;
            InversionEngine.MobSpawnEntryView s = selectedBiome.spawns.get(i);

            g.fill(rightX + 4, ey, rightX + rightW - 4, ey + entryH - 2, (i % 2 == 0) ? 0x601A2422 : 0x60121816);

            String title = "§e" + s.mobName + " §8[" + s.category.getName() + "]";
            g.drawString(this.font, title, rightX + 8, ey + 3, 0xFFFFFF, false);

            boolean up = s.currentPercent > s.vanillaPercent + 0.05;
            boolean down = s.currentPercent < s.vanillaPercent - 0.05;
            String color = up ? "§a" : (down ? "§c" : "§e");
            String line2 = String.format(Locale.ROOT,
                    "§7Ванилла: §fВес %d (%.1f%%) Гр.%d-%d §7→ Инверсия: %s§lВес %d (%.1f%%) Гр.%d-%d",
                    s.vanillaWeight, s.vanillaPercent, s.vanillaMinGroup, s.vanillaMaxGroup,
                    color, s.currentWeight, s.currentPercent, s.currentMinGroup, s.currentMaxGroup);
            g.drawString(this.font, line2, rightX + 8, ey + 14, 0xCCCCCC, false);
        }
        g.disableScissor();
    }

    private void renderWorldEventsTab(GuiGraphics g, int mouseX, int mouseY) {
        int topY = 74;
        int bottomY = this.height - 32;
        int panelX = 20;
        int panelW = this.width - 40;

        g.fill(panelX, topY, panelX + panelW, bottomY, 0xB008080C);
        g.renderOutline(panelX, topY, panelW, bottomY - topY, 0xFF444455);

        g.enableScissor(panelX + 1, topY + 1, panelX + panelW - 1, bottomY - 1);
        int rowH = 26;
        int startY = topY + 4 - (int) rightScroll;

        for (int i = 0; i < filteredEvents.size(); i++) {
            int ry = startY + i * rowH;
            if (ry + rowH < topY || ry > bottomY) continue;
            InversionEngine.EventChanceView ev = filteredEvents.get(i);

            g.fill(panelX + 4, ry, panelX + panelW - 4, ry + rowH - 2, (i % 2 == 0) ? 0x601C1826 : 0x6012101A);
            g.renderItem(ev.icon, panelX + 8, ry + 4);

            g.drawString(this.font, "§d[" + ev.category + "] §f" + ev.name, panelX + 28, ry + 4, 0xFFFFFF, false);

            boolean up = ev.currentPercent > ev.vanillaPercent + 0.01;
            boolean down = ev.currentPercent < ev.vanillaPercent - 0.01;
            String col = up ? "§a▲ " : (down ? "§c▼ " : "§e= ");
            String chanceText = String.format(Locale.ROOT,
                    "§7Шанс в ванилле: §f%.3f%%   §7→   После инверсии: %s§l%.3f%%",
                    ev.vanillaPercent, col, ev.currentPercent);
            g.drawString(this.font, chanceText, panelX + 28, ry + 14, 0xCCCCCC, false);

            // Probability bar on the right side
            int barW = 90;
            int barX = panelX + panelW - barW - 10;
            int barY = ry + 8;
            g.fill(barX, barY, barX + barW, barY + 10, 0xFF222228);
            int fillW = (int) Math.round(Mth.clamp(ev.currentPercent / 100.0, 0.0, 1.0) * barW);
            g.fill(barX, barY, barX + fillW, barY + 10, up ? 0xFF33AA55 : 0xFFAA4444);
            g.renderOutline(barX, barY, barW, 10, 0xFF555566);
        }
        g.disableScissor();
    }

    private void renderSettingsTab(GuiGraphics g, int mouseX, int mouseY) {
        InvertedWorldConfig cfg = InvertedWorldConfig.get();
        int boxY = this.height - 68;
        g.fill(25, boxY, this.width - 25, this.height - 32, 0x90181424);
        g.renderOutline(25, boxY, this.width - 50, 36, 0xFF554477);

        g.drawString(this.font, "§eОписание выбранной формулы (§f" + cfg.formula.getTitle() + "§e):", 32, boxY + 6, 0xFFFFFF, false);
        g.drawString(this.font, "§7" + cfg.formula.getDescription(), 32, boxY + 19, 0xCCCCCC, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Check inline [-] / [+] buttons in Loot Inspector
            for (InlineWeightBtn b : inlineButtons) {
                if (b.isHovered(mouseX, mouseY)) {
                    InversionEngine.adjustEntryWeight(b.pool, b.entry, b.delta);
                    return true;
                }
            }

            int topY = 74;
            int bottomY = this.height - 32;
            int leftX = 20;
            int leftW = Math.min(200, (this.width - 50) / 3);
            int rightX = leftX + leftW + 8;
            int rightW = this.width - 20 - rightX;

            if (currentTab == Tab.LOOT_TABLES && !filteredLootTables.isEmpty()) {
                // Click on left table list
                if (mouseX >= leftX && mouseX < leftX + leftW && mouseY >= topY && mouseY < bottomY) {
                    int idx = (int) ((mouseY - (topY + 4) + leftScroll) / 18);
                    if (idx >= 0 && idx < filteredLootTables.size()) {
                        selectedTableIndex = idx;
                        selectedPoolIndex = 0;
                        rightScroll = 0;
                        return true;
                    }
                }
                // Click on "[Сменить пул ⟳]" button in right header
                InversionEngine.LootTableView selectedTable = filteredLootTables.get(selectedTableIndex);
                if (selectedTable.pools.size() > 1 && mouseX >= rightX + rightW - 110 && mouseX < rightX + rightW && mouseY >= topY && mouseY < topY + 22) {
                    selectedPoolIndex = (selectedPoolIndex + 1) % selectedTable.pools.size();
                    rightScroll = 0;
                    return true;
                }
            } else if (currentTab == Tab.BIOME_SPAWNS && !filteredBiomes.isEmpty()) {
                if (mouseX >= leftX && mouseX < leftX + leftW && mouseY >= topY && mouseY < bottomY) {
                    int idx = (int) ((mouseY - (topY + 4) + leftScroll) / 18);
                    if (idx >= 0 && idx < filteredBiomes.size()) {
                        selectedBiomeIndex = idx;
                        rightScroll = 0;
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int leftW = Math.min(200, (this.width - 50) / 3);
        double step = delta * 22.0;

        if (currentTab == Tab.LOOT_TABLES || currentTab == Tab.BIOME_SPAWNS) {
            if (mouseX < 20 + leftW) {
                int count = (currentTab == Tab.LOOT_TABLES) ? filteredLootTables.size() : filteredBiomes.size();
                double maxScroll = Math.max(0, count * 18 - (this.height - 115));
                leftScroll = Mth.clamp(leftScroll - step, 0, maxScroll);
            } else {
                int count = 0;
                if (currentTab == Tab.LOOT_TABLES && !filteredLootTables.isEmpty()) {
                    var t = filteredLootTables.get(selectedTableIndex);
                    if (!t.pools.isEmpty()) count = t.pools.get(Math.min(selectedPoolIndex, t.pools.size() - 1)).entries.size();
                } else if (currentTab == Tab.BIOME_SPAWNS && !filteredBiomes.isEmpty()) {
                    count = filteredBiomes.get(selectedBiomeIndex).spawns.size();
                }
                double maxScroll = Math.max(0, count * 26 - (this.height - 140));
                rightScroll = Mth.clamp(rightScroll - step, 0, maxScroll);
            }
            return true;
        } else if (currentTab == Tab.WORLD_EVENTS) {
            double maxScroll = Math.max(0, filteredEvents.size() * 26 - (this.height - 115));
            rightScroll = Mth.clamp(rightScroll - step, 0, maxScroll);
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
