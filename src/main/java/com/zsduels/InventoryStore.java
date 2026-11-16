package com.zsduels;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Guarda en disco el inventario real del jugador mientras está en un duelo o editando un kit. */
public class InventoryStore {

    private final ZSDuels plugin;
    private final File dir;

    public InventoryStore(ZSDuels plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "saved");
        if (!dir.exists()) dir.mkdirs();
    }

    private File file(UUID id) {
        return new File(dir, id + ".yml");
    }

    public boolean has(UUID id) {
        return file(id).exists();
    }

    /** No sobrescribe un guardado pendiente (por ejemplo, de un crash). */
    public void save(Player p) {
        if (has(p.getUniqueId())) return;
        PlayerInventory inv = p.getInventory();
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("storage", new ArrayList<>(Arrays.asList(inv.getStorageContents())));
        cfg.set("armor", new ArrayList<>(Arrays.asList(inv.getArmorContents())));
        cfg.set("offhand", inv.getItemInOffHand());
        cfg.set("gamemode", p.getGameMode().name());
        cfg.set("allow-flight", p.getAllowFlight());
        cfg.set("flying", p.isFlying());
        try {
            cfg.save(file(p.getUniqueId()));
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar el inventario de " + p.getName() + ": " + e.getMessage());
        }
    }

    /** Limpia el inventario actual y devuelve el guardado (vacío si no había nada guardado). */
    public boolean restore(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(new ItemStack(Material.AIR));
        File f = file(p.getUniqueId());
        if (!f.exists()) {
            p.updateInventory();
            return false;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        inv.setStorageContents(toArray(cfg.getList("storage"), 36));
        inv.setArmorContents(toArray(cfg.getList("armor"), 4));
        ItemStack off = cfg.getItemStack("offhand");
        inv.setItemInOffHand(off == null ? new ItemStack(Material.AIR) : off);
        try {
            p.setGameMode(GameMode.valueOf(cfg.getString("gamemode", "SURVIVAL")));
        } catch (IllegalArgumentException ignored) {
        }
        p.setAllowFlight(cfg.getBoolean("allow-flight", false));
        if (p.getAllowFlight() && cfg.getBoolean("flying", false)) p.setFlying(true);
        f.delete();
        p.updateInventory();
        return true;
    }

    private ItemStack[] toArray(List<?> list, int size) {
        ItemStack[] arr = new ItemStack[size];
        if (list != null) {
            for (int i = 0; i < size && i < list.size(); i++) {
                if (list.get(i) instanceof ItemStack s) arr[i] = s;
            }
        }
        return arr;
    }
}
