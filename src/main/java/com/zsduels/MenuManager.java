package com.zsduels;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MenuManager implements Listener {

    public static class MenuHolder implements InventoryHolder {
        final Map<Integer, String> actions = new HashMap<>();
        final Map<Integer, String> data = new HashMap<>();
        final Map<Integer, ConfigurationSection> sections = new HashMap<>();
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public static class EditorHolder implements InventoryHolder {
        final KitManager.Kit kit;
        final Map<Integer, String> buttons = new HashMap<>();
        private Inventory inv;

        EditorHolder(KitManager.Kit kit) {
            this.kit = kit;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final ZSDuels plugin;

    public MenuManager(ZSDuels plugin) {
        this.plugin = plugin;
    }

    // ---------------- Utilidades ----------------

    private ConfigurationSection menu(String name) {
        return plugin.getMenuConfig().getConfigurationSection("menus." + name);
    }

    private static int normalizeSize(int s) {
        return Math.max(9, Math.min(54, (s / 9) * 9));
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private static String time(long seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Los extras se reemplazan primero para que puedan pisar a los placeholders generales. */
    private String ph(Player p, String s, Map<String, String> extra) {
        if (extra != null) {
            for (Map.Entry<String, String> en : extra.entrySet()) s = s.replace(en.getKey(), en.getValue());
        }
        StatsManager.PlayerData d = plugin.getStats().get(p);
        s = s.replace("%player%", p.getName())
                .replace("%wins%", String.valueOf(d.wins))
                .replace("%losses%", String.valueOf(d.losses))
                .replace("%kills%", String.valueOf(d.kills))
                .replace("%winrate%", String.valueOf(d.winrate()))
                .replace("%streak%", String.valueOf(d.streak))
                .replace("%best_streak%", String.valueOf(d.bestStreak))
                .replace("%gems%", String.valueOf(d.gems))
                .replace("%state%", d.enabled ? "&aActivado" : "&cDesactivado")
                .replace("%queue_size%", String.valueOf(plugin.getDuels().queueSize()))
                .replace("%live_duels%", String.valueOf(plugin.getDuels().liveCount()));
        return Colors.color(s);
    }

    private ItemStack build(ConfigurationSection s, Player p, Map<String, String> extra, OfflinePlayer skull, Material override) {
        Material mat = override;
        if (mat == null) mat = Material.matchMaterial(s.getString("material", "STONE"));
        if (mat == null || mat.isAir()) mat = Material.STONE;
        ItemStack it = new ItemStack(mat, Math.max(1, Math.min(s.getInt("amount", 1), 64)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (s.contains("name")) meta.setDisplayName(ph(p, s.getString("name", ""), extra));
            List<String> lore = new ArrayList<>();
            for (String l : s.getStringList("lore")) lore.add(ph(p, l, extra));
            if (!lore.isEmpty()) meta.setLore(lore);
            if (s.getBoolean("hide-flags")) meta.addItemFlags(ItemFlag.values());
            if (meta instanceof SkullMeta sm) {
                if (skull != null) {
                    sm.setOwningPlayer(skull);
                } else if (s.contains("skull-owner")) {
                    String owner = Colors.strip(ph(p, s.getString("skull-owner", ""), extra));
                    sm.setOwningPlayer(owner.equalsIgnoreCase(p.getName()) ? p : Bukkit.getOfflinePlayer(owner));
                }
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private MenuHolder base(ConfigurationSection m, Player p) {
        MenuHolder h = new MenuHolder();
        Inventory inv = Bukkit.createInventory(h, normalizeSize(m.getInt("size", 45)), ph(p, m.getString("title", "Menu"), null));
        h.inv = inv;
        fill(inv, m.getConfigurationSection("filler"));
        return h;
    }

    private ItemStack fillerItem(ConfigurationSection f) {
        Material mat = Material.matchMaterial(f.getString("material", "GRAY_STAINED_GLASS_PANE"));
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Colors.color(f.getString("name", " ")));
            it.setItemMeta(meta);
        }
        return it;
    }

    private void fill(Inventory inv, ConfigurationSection f) {
        if (f == null || !f.getBoolean("enabled")) return;
        ItemStack it = fillerItem(f);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, it.clone());
    }

    private void addItems(MenuHolder h, ConfigurationSection items, Player p, Map<String, String> extra) {
        if (items == null) return;
        for (String key : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(key);
            if (s == null) continue;
            int slot = s.getInt("slot", -1);
            if (slot < 0 || slot >= h.inv.getSize()) continue;
            Map<String, String> ex = extra == null ? new HashMap<>() : new HashMap<>(extra);
            if (s.contains("price")) ex.put("%price%", String.valueOf(s.getInt("price")));
            h.inv.setItem(slot, build(s, p, ex, null, null));
            h.actions.put(slot, s.getString("action", "NONE").toUpperCase());
            h.sections.put(slot, s);
        }
    }

    // ---------------- Menús ----------------

    public void openMain(Player p) {
        ConfigurationSection m = menu("main");
        if (m == null) return;
        MenuHolder h = base(m, p);
        addItems(h, m.getConfigurationSection("items"), p, null);
        p.openInventory(h.inv);
    }

    public void openKitSelect(Player p) {
        ConfigurationSection m = menu("kit-select");
        if (m == null) return;
        MenuHolder h = base(m, p);
        addItems(h, m.getConfigurationSection("items"), p, null);
        List<Integer> slots = m.getIntegerList("kit-slots");
        ConfigurationSection tpl = m.getConfigurationSection("kit-item");
        int i = 0;
        for (KitManager.Kit kit : plugin.getKits().all()) {
            if (i >= slots.size() || tpl == null) break;
            Map<String, String> extra = new HashMap<>();
            extra.put("%kit_name%", kit.displayName);
            int slot = slots.get(i++);
            if (slot < 0 || slot >= h.inv.getSize()) continue;
            h.inv.setItem(slot, build(tpl, p, extra, null, kit.icon));
            h.actions.put(slot, "KIT");
            h.data.put(slot, kit.id);
        }
        p.openInventory(h.inv);
    }

    public void openEditor(Player p, KitManager.Kit kit) {
        ConfigurationSection e = plugin.getKits().editor();
        EditorHolder h = new EditorHolder(kit);
        int size = Math.max(45, normalizeSize(e.getInt("size", 54)));
        String title = Colors.color(e.getString("title", "Editor").replace("%kit_name%", kit.displayName));
        Inventory inv = Bukkit.createInventory(h, size, title);
        h.inv = inv;

        ItemStack[] layout = plugin.getKits().getLayout(p.getUniqueId(), kit);
        for (int i = 0; i < 36; i++) {
            ItemStack it = layout[i];
            if (it != null && !it.getType().isAir()) inv.setItem(i, it.clone());
        }
        String[] fixedKeys = {"helmet", "chestplate", "leggings", "boots", "offhand"};
        int[] defaults = {36, 37, 38, 39, 40};
        Set<Integer> fixed = new HashSet<>();
        for (int k = 0; k < 5; k++) {
            int gs = e.getInt("fixed-slots." + fixedKeys[k], defaults[k]);
            if (gs < 0 || gs >= size) continue;
            fixed.add(gs);
            ItemStack it = kit.items[36 + k];
            if (it != null && !it.getType().isAir()) inv.setItem(gs, it.clone());
        }
        for (String b : new String[]{"save", "reset", "back"}) {
            ConfigurationSection bs = e.getConfigurationSection("buttons." + b);
            if (bs == null) continue;
            int slot = bs.getInt("slot", -1);
            if (slot < 36 || slot >= size) continue;
            inv.setItem(slot, build(bs, p, null, null, null));
            h.buttons.put(slot, b);
        }
        ConfigurationSection f = e.getConfigurationSection("filler");
        if (f != null && f.getBoolean("enabled")) {
            ItemStack fi = fillerItem(f);
            for (int s = 36; s < size; s++) {
                if (inv.getItem(s) == null && !fixed.contains(s)) inv.setItem(s, fi.clone());
            }
        }
        p.openInventory(inv);
    }

    public void openTop(Player p) {
        ConfigurationSection m = menu("top");
        if (m == null) return;
        MenuHolder h = base(m, p);
        addItems(h, m.getConfigurationSection("items"), p, null);
        List<Integer> slots = m.getIntegerList("player-slots");
        ConfigurationSection tpl = m.getConfigurationSection("entry");
        List<StatsManager.PlayerData> top = plugin.getStats().getTopWins();
        for (int i = 0; i < slots.size() && i < top.size() && tpl != null; i++) {
            StatsManager.PlayerData d = top.get(i);
            Map<String, String> extra = new HashMap<>();
            extra.put("%pos%", String.valueOf(i + 1));
            extra.put("%name%", d.name);
            extra.put("%wins%", String.valueOf(d.wins));
            extra.put("%losses%", String.valueOf(d.losses));
            extra.put("%kills%", String.valueOf(d.kills));
            extra.put("%winrate%", String.valueOf(d.winrate()));
            extra.put("%best_streak%", String.valueOf(d.bestStreak));
            int slot = slots.get(i);
            if (slot < 0 || slot >= h.inv.getSize()) continue;
            h.inv.setItem(slot, build(tpl, p, extra, Bukkit.getOfflinePlayer(d.id), null));
        }
        p.openInventory(h.inv);
    }

    public void openSpectate(Player p) {
        ConfigurationSection m = menu("spectate");
        if (m == null) return;
        MenuHolder h = base(m, p);
        addItems(h, m.getConfigurationSection("items"), p, null);
        List<Integer> slots = m.getIntegerList("duel-slots");
        ConfigurationSection tpl = m.getConfigurationSection("duel-item");
        List<Duel> live = plugin.getDuels().liveDuels();
        for (int i = 0; i < slots.size() && i < live.size() && tpl != null; i++) {
            Duel d = live.get(i);
            Map<String, String> extra = new HashMap<>();
            extra.put("%player1%", d.n1);
            extra.put("%player2%", d.n2);
            extra.put("%kit%", d.kit.displayName);
            extra.put("%time%", time(d.elapsedSeconds()));
            extra.put("%spectators%", String.valueOf(d.spectators.size()));
            int slot = slots.get(i);
            if (slot < 0 || slot >= h.inv.getSize()) continue;
            h.inv.setItem(slot, build(tpl, p, extra, Bukkit.getOfflinePlayer(d.p1), null));
            h.actions.put(slot, "SPECTATE_DUEL");
            h.data.put(slot, d.p1.toString());
        }
        p.openInventory(h.inv);
    }

    public void openShop(Player p) {
        ConfigurationSection m = menu("shop");
        if (m == null) return;
        MenuHolder h = base(m, p);
        addItems(h, m.getConfigurationSection("items"), p, null);
        p.openInventory(h.inv);
    }

    // ---------------- Eventos ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof MenuHolder h) {
            handleMenuClick(e, h);
        } else if (holder instanceof EditorHolder eh) {
            handleEditorClick(e, eh);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof MenuHolder || holder instanceof EditorHolder) e.setCancelled(true);
    }

    /** Evita que un ítem en el cursor del editor pase al inventario real. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof EditorHolder) e.getPlayer().setItemOnCursor(null);
    }

    private void handleMenuClick(InventoryClickEvent e, MenuHolder h) {
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getInventory())) return;
        int slot = e.getRawSlot();
        String action = h.actions.get(slot);
        if (action == null) return;
        ConfigurationSection sec = h.sections.get(slot);
        plugin.sound(p, "click");
        later(() -> dispatch(p, h, slot, action, sec));
    }

    private void dispatch(Player p, MenuHolder h, int slot, String action, ConfigurationSection sec) {
        if (sec != null && sec.contains("price")) {
            buy(p, sec);
            return;
        }
        switch (action) {
            case "CLOSE" -> p.closeInventory();
            case "BACK" -> openMain(p);
            case "CHALLENGE" -> {
                p.closeInventory();
                plugin.send(p, "challenge-menu-info");
            }
            case "GUIDE" -> {
                p.closeInventory();
                plugin.sendList(p, "guide");
            }
            case "RULES" -> {
                p.closeInventory();
                plugin.sendList(p, "rules");
            }
            case "EDIT_KIT" -> openKitSelect(p);
            case "TOP" -> openTop(p);
            case "SPECTATE" -> openSpectate(p);
            case "REMATCH" -> {
                p.closeInventory();
                plugin.send(p, "rematch-menu-info");
            }
            case "RANDOM" -> {
                p.closeInventory();
                plugin.getDuels().joinQueue(p);
            }
            case "TOGGLE" -> {
                boolean on = plugin.getStats().toggle(p);
                plugin.send(p, on ? "duels-enabled" : "duels-disabled");
                openMain(p);
            }
            case "SHOP" -> openShop(p);
            case "KIT" -> {
                KitManager.Kit kit = plugin.getKits().get(h.data.get(slot));
                if (kit == null) return;
                if (!plugin.getKits().hasPermission(p, kit)) {
                    plugin.send(p, "no-permission");
                    return;
                }
                openEditor(p, kit);
            }
            case "SPECTATE_DUEL" -> {
                p.closeInventory();
                String raw = h.data.get(slot);
                if (raw == null) return;
                plugin.getDuels().spectate(p, plugin.getDuels().getDuel(UUID.fromString(raw)));
            }
            default -> {
            }
        }
        if (sec != null) {
            for (String c : sec.getStringList("commands")) plugin.runCommand(p, c);
        }
    }

    private void buy(Player p, ConfigurationSection sec) {
        int price = sec.getInt("price");
        StatsManager.PlayerData d = plugin.getStats().get(p);
        String name = Colors.strip(sec.getString("name", "item").replace("%price%", String.valueOf(price)));
        if (d.gems < price) {
            plugin.send(p, "shop-no-gems", "%price%", String.valueOf(price), "%gems%", String.valueOf(d.gems));
            plugin.sound(p, "error");
            return;
        }
        d.gems -= price;
        plugin.getStats().markDirty();
        plugin.getStats().save();
        for (String c : sec.getStringList("commands")) plugin.runCommand(p, c);
        plugin.send(p, "shop-bought", "%item%", name, "%price%", String.valueOf(price));
        plugin.sound(p, "purchase");
        openShop(p);
    }

    private void handleEditorClick(InventoryClickEvent e, EditorHolder h) {
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getInventory();
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= top.getSize()) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;

        String button = h.buttons.get(raw);
        if (button != null) {
            plugin.sound(p, "click");
            switch (button) {
                case "save" -> saveEditor(p, h, e);
                case "reset" -> {
                    plugin.getKits().resetLayout(p.getUniqueId(), h.kit);
                    plugin.send(p, "kit-reset", "%kit%", Colors.strip(h.kit.displayName));
                    later(() -> openEditor(p, h.kit));
                }
                case "back" -> later(() -> openKitSelect(p));
                default -> {
                }
            }
            return;
        }
        // Solo se pueden mover los ítems dentro de los slots 0-35 (clic izquierdo/derecho)
        if (raw < 36 && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)) {
            e.setCancelled(false);
        }
    }

    private void saveEditor(Player p, EditorHolder h, InventoryClickEvent e) {
        ItemStack cur = e.getCursor();
        if (cur != null && !cur.getType().isAir()) {
            plugin.send(p, "kit-editor-cursor");
            return;
        }
        Inventory top = e.getInventory();
        ItemStack[] arr = new ItemStack[41];
        for (int i = 0; i < 36; i++) {
            ItemStack it = top.getItem(i);
            arr[i] = (it == null || it.getType().isAir()) ? null : it.clone();
        }
        for (int i = 36; i < 41; i++) arr[i] = h.kit.items[i];
        if (plugin.getKits().saveLayout(p.getUniqueId(), h.kit, arr)) {
            plugin.send(p, "kit-saved", "%kit%", Colors.strip(h.kit.displayName));
        } else {
            plugin.send(p, "kit-editor-invalid");
            plugin.sound(p, "error");
        }
    }
              }
