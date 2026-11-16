package com.zsduels;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class KitManager {

    // Indices del array de items de un kit: 0-35 inventario, 36-40 armadura y mano secundaria
    public static final int HELMET = 36, CHESTPLATE = 37, LEGGINGS = 38, BOOTS = 39, OFFHAND = 40;

    public static class Kit {
        public final String id;
        public String displayName;
        public Material icon = Material.IRON_SWORD;
        public boolean random = true;
        public String permission = "";
        public final ItemStack[] items = new ItemStack[41];

        public Kit(String id) {
            this.id = id;
            this.displayName = id;
        }
    }

    private final ZSDuels plugin;
    private final File kitsFile;
    private final File layoutsFile;
    private YamlConfiguration kitsCfg;
    private YamlConfiguration layoutsCfg;
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final Map<UUID, String> editing = new HashMap<>();
    private final Random random = new Random();

    public KitManager(ZSDuels plugin) {
        this.plugin = plugin;
        this.kitsFile = new File(plugin.getDataFolder(), "kits.yml");
        this.layoutsFile = new File(plugin.getDataFolder(), "layouts.yml");
        load();
    }

    // ---------------- Carga y guardado ----------------

    public void load() {
        kits.clear();
        kitsCfg = YamlConfiguration.loadConfiguration(kitsFile);
        layoutsCfg = YamlConfiguration.loadConfiguration(layoutsFile);
        ConfigurationSection sec = kitsCfg.getConfigurationSection("kits");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection ks = sec.getConfigurationSection(id);
            if (ks == null) continue;
            Kit kit = new Kit(id);
            kit.displayName = ks.getString("display-name", id);
            Material icon = Material.matchMaterial(ks.getString("icon", "IRON_SWORD"));
            if (icon != null && !icon.isAir()) kit.icon = icon;
            kit.random = ks.getBoolean("random", true);
            kit.permission = ks.getString("permission", "");
            ConfigurationSection is = ks.getConfigurationSection("items");
            if (is != null) {
                for (String key : is.getKeys(false)) {
                    int idx = slotIndex(key);
                    if (idx < 0) {
                        plugin.getLogger().warning("kits.yml: slot inválido '" + key + "' en el kit " + id);
                        continue;
                    }
                    kit.items[idx] = readItem(is, key);
                }
            }
            kits.put(id.toLowerCase(), kit);
        }
    }

    private void saveKitsFile() {
        try {
            kitsCfg.save(kitsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar kits.yml: " + e.getMessage());
        }
    }

    private void saveLayoutsFile() {
        try {
            layoutsCfg.save(layoutsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar layouts.yml: " + e.getMessage());
        }
    }

    /** Escribe un kit en kits.yml (los ítems se guardan completos, con todos sus datos). */
    public void save(Kit kit) {
        String base = "kits." + kit.id;
        kitsCfg.set(base + ".display-name", kit.displayName);
        kitsCfg.set(base + ".icon", kit.icon.name());
        kitsCfg.set(base + ".random", kit.random);
        kitsCfg.set(base + ".permission", kit.permission);
        kitsCfg.set(base + ".items", null);
        for (int i = 0; i < 41; i++) {
            ItemStack it = kit.items[i];
            if (it != null && !it.getType().isAir()) kitsCfg.set(base + ".items." + keyFor(i), it);
        }
        saveKitsFile();
    }

    private static String keyFor(int i) {
        return switch (i) {
            case HELMET -> "helmet";
            case CHESTPLATE -> "chestplate";
            case LEGGINGS -> "leggings";
            case BOOTS -> "boots";
            case OFFHAND -> "offhand";
            default -> String.valueOf(i);
        };
    }

    private static int slotIndex(String key) {
        switch (key.toLowerCase()) {
            case "helmet": return HELMET;
            case "chestplate": return CHESTPLATE;
            case "leggings": return LEGGINGS;
            case "boots": return BOOTS;
            case "offhand": return OFFHAND;
            default:
                try {
                    int n = Integer.parseInt(key);
                    return n >= 0 && n <= 35 ? n : -1;
                } catch (NumberFormatException e) {
                    return -1;
                }
        }
    }

    // ---------------- Lectura de ítems ----------------

    private ItemStack readItem(ConfigurationSection parent, String key) {
        if (parent.isItemStack(key)) return parent.getItemStack(key);
        ConfigurationSection s = parent.getConfigurationSection(key);
        return s == null ? null : parseItem(s);
    }

    private ItemStack parseItem(ConfigurationSection s) {
        Material mat = Material.matchMaterial(s.getString("material", "AIR"));
        if (mat == null || mat.isAir()) {
            plugin.getLogger().warning("kits.yml: material inválido: " + s.getString("material"));
            return null;
        }
        int amount = Math.max(1, Math.min(s.getInt("amount", 1), mat.getMaxStackSize()));
        ItemStack it = new ItemStack(mat, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        if (s.contains("name")) meta.setDisplayName(Colors.color(s.getString("name")));
        List<String> lore = new ArrayList<>();
        for (String l : s.getStringList("lore")) lore.add(Colors.color(l));
        if (!lore.isEmpty()) meta.setLore(lore);
        for (String e : s.getStringList("enchantments")) {
            String[] p = e.split(":");
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(p[0].trim().toLowerCase()));
            if (ench == null) {
                plugin.getLogger().warning("kits.yml: encantamiento inválido: " + e);
                continue;
            }
            meta.addEnchant(ench, p.length > 1 ? parseInt(p[1], 1) : 1, true);
        }
        if (meta instanceof PotionMeta pm) {
            for (String e : s.getStringList("effects")) {
                String[] p = e.split(":");
                PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(p[0].trim().toLowerCase()));
                if (type == null) {
                    plugin.getLogger().warning("kits.yml: efecto inválido: " + e);
                    continue;
                }
                int seconds = p.length > 1 ? parseInt(p[1], 30) : 30;
                int level = p.length > 2 ? parseInt(p[2], 1) : 1;
                pm.addCustomEffect(new PotionEffect(type, seconds * 20, Math.max(0, level - 1)), true);
            }
        }
        if (s.getBoolean("unbreakable")) meta.setUnbreakable(true);
        if (s.getBoolean("hide-flags")) meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------------- Consulta de kits ----------------

    public Collection<Kit> all() {
        return kits.values();
    }

    public Kit get(String id) {
        return id == null ? null : kits.get(id.toLowerCase());
    }

    public boolean hasPermission(Player p, Kit kit) {
        return kit.permission == null || kit.permission.isEmpty() || p.hasPermission(kit.permission);
    }

    public boolean hasRandomKits() {
        for (Kit k : kits.values()) {
            if (k.random) return true;
        }
        return false;
    }

    /** Kit al azar que ambos jugadores puedan usar. */
    public Kit randomKit(Player a, Player b) {
        List<Kit> pool = new ArrayList<>();
        for (Kit k : kits.values()) {
            if (k.random && hasPermission(a, k) && hasPermission(b, k)) pool.add(k);
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    public ConfigurationSection editor() {
        ConfigurationSection s = kitsCfg.getConfigurationSection("editor");
        return s == null ? kitsCfg.createSection("editor") : s;
    }

    public static boolean isValidId(String id) {
        return id != null && id.matches("[a-z0-9_-]{1,32}");
    }

    // ---------------- Dar el kit ----------------

    private static ItemStack cl(ItemStack i) {
        return i == null || i.getType().isAir() ? null : i.clone();
    }

    private void applyTo(Player p, ItemStack[] arr) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] storage = new ItemStack[36];
        for (int i = 0; i < 36; i++) storage[i] = cl(arr[i]);
        inv.setStorageContents(storage);
        inv.setHelmet(cl(arr[HELMET]));
        inv.setChestplate(cl(arr[CHESTPLATE]));
        inv.setLeggings(cl(arr[LEGGINGS]));
        inv.setBoots(cl(arr[BOOTS]));
        ItemStack off = cl(arr[OFFHAND]);
        inv.setItemInOffHand(off == null ? new ItemStack(Material.AIR) : off);
        p.updateInventory();
    }

    /** Entrega el kit usando el orden personal del jugador si existe. */
    public void giveKit(Player p, Kit kit) {
        applyTo(p, getLayout(p.getUniqueId(), kit));
    }

    // ---------------- Orden personal (editar mi kit) ----------------

    private static Map<ItemStack, Integer> sig(ItemStack[] arr) {
        Map<ItemStack, Integer> m = new HashMap<>();
        for (int i = 0; i < 36 && i < arr.length; i++) {
            ItemStack it = arr[i];
            if (it == null || it.getType().isAir()) continue;
            ItemStack key = it.clone();
            key.setAmount(1);
            m.merge(key, it.getAmount(), Integer::sum);
        }
        return m;
    }

    /** Devuelve el orden personal si es válido; si no, el del kit. */
    public ItemStack[] getLayout(UUID id, Kit kit) {
        List<?> list = layoutsCfg.getList(id + "." + kit.id);
        if (list == null) return kit.items;
        ItemStack[] arr = new ItemStack[41];
        for (int i = 0; i < 36 && i < list.size(); i++) {
            if (list.get(i) instanceof ItemStack s) arr[i] = s;
        }
        for (int i = 36; i < 41; i++) arr[i] = kit.items[i];
        return sig(arr).equals(sig(kit.items)) ? arr : kit.items;
    }

    /** Guarda el orden solo si tiene exactamente los mismos ítems que el kit. */
    public boolean saveLayout(UUID id, Kit kit, ItemStack[] arr) {
        if (!sig(arr).equals(sig(kit.items))) return false;
        layoutsCfg.set(id + "." + kit.id, new ArrayList<>(Arrays.asList(Arrays.copyOf(arr, 36))));
        saveLayoutsFile();
        return true;
    }

    public void resetLayout(UUID id, Kit kit) {
        layoutsCfg.set(id + "." + kit.id, null);
        saveLayoutsFile();
    }

    private void clearLayoutsOf(String kitId) {
        for (String uuid : layoutsCfg.getKeys(false)) layoutsCfg.set(uuid + "." + kitId, null);
        saveLayoutsFile();
    }

    // ---------------- Administración desde el juego ----------------

    public boolean isEditing(UUID id) {
        return editing.containsKey(id);
    }

    /** Guarda tu inventario real y empieza la sesión. Si el kit existe, se carga en tu inventario. */
    public void startEdit(Player p, String id, Kit existing) {
        plugin.getStore().save(p);
        if (existing != null) applyTo(p, existing.items);
        editing.put(p.getUniqueId(), id);
    }

    /** Termina la sesión: si save=true escribe el inventario actual en kits.yml. Siempre restaura tu inventario real. */
    public boolean finishEdit(Player p, boolean save) {
        String id = editing.remove(p.getUniqueId());
        if (id == null) return false;
        if (save) {
            Kit kit = get(id);
            if (kit == null) {
                kit = new Kit(id);
                kits.put(id.toLowerCase(), kit);
            }
            readInventory(p, kit.items);
            save(kit);
            clearLayoutsOf(kit.id);
        }
        plugin.getStore().restore(p);
        return true;
    }

    public String editingKit(UUID id) {
        return editing.get(id);
    }

    private void readInventory(Player p, ItemStack[] out) {
        Arrays.fill(out, null);
        PlayerInventory inv = p.getInventory();
        ItemStack[] st = inv.getStorageContents();
        for (int i = 0; i < 36 && i < st.length; i++) out[i] = cl(st[i]);
        ItemStack[] ar = inv.getArmorContents();
        out[BOOTS] = cl(ar[0]);
        out[LEGGINGS] = cl(ar[1]);
        out[CHESTPLATE] = cl(ar[2]);
        out[HELMET] = cl(ar[3]);
        out[OFFHAND] = cl(inv.getItemInOffHand());
    }

    public boolean deleteKit(String id) {
        Kit kit = kits.remove(id.toLowerCase());
        if (kit == null) return false;
        kitsCfg.set("kits." + kit.id, null);
        saveKitsFile();
        clearLayoutsOf(kit.id);
        return true;
    }

    /** Al apagar el servidor, devuelve el inventario a los admins que estaban editando. */
    public void shutdown() {
        for (UUID id : new ArrayList<>(editing.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) finishEdit(p, false);
        }
    }
                                                                                 }
