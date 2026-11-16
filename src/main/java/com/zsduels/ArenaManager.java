package com.zsduels;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class ArenaManager {

    public static class Arena {
        public final String name;
        public Location spawn1, spawn2, pos1, pos2;
        public boolean inUse;

        public Arena(String name) {
            this.name = name;
        }

        public boolean isReady() {
            return spawn1 != null && spawn2 != null && spawn1.getWorld() != null && spawn2.getWorld() != null;
        }

        public boolean hasRegion() {
            return pos1 != null && pos2 != null && pos1.getWorld() != null && pos1.getWorld().equals(pos2.getWorld());
        }

        /** Sin región definida, no se limita la salida. */
        public boolean contains(Location l) {
            if (!hasRegion()) return true;
            if (l.getWorld() == null || !l.getWorld().equals(pos1.getWorld())) return false;
            double minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
            double maxX = Math.max(pos1.getBlockX(), pos2.getBlockX()) + 1;
            double minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
            double maxY = Math.max(pos1.getBlockY(), pos2.getBlockY()) + 1;
            double minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
            double maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ()) + 1;
            return l.getX() >= minX && l.getX() <= maxX
                    && l.getY() >= minY && l.getY() <= maxY
                    && l.getZ() >= minZ && l.getZ() <= maxZ;
        }
    }

    private final ZSDuels plugin;
    private final File file;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(ZSDuels plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
        load();
    }

    public void load() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("arenas");
        Map<String, Arena> old = new LinkedHashMap<>(arenas);
        arenas.clear();
        if (sec == null) return;
        for (String n : sec.getKeys(false)) {
            Arena a = old.containsKey(n) ? old.get(n) : new Arena(n);
            a.spawn1 = sec.getLocation(n + ".spawn1");
            a.spawn2 = sec.getLocation(n + ".spawn2");
            a.pos1 = sec.getLocation(n + ".pos1");
            a.pos2 = sec.getLocation(n + ".pos2");
            arenas.put(n, a);
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            String b = "arenas." + a.name;
            if (a.spawn1 != null) cfg.set(b + ".spawn1", a.spawn1);
            if (a.spawn2 != null) cfg.set(b + ".spawn2", a.spawn2);
            if (a.pos1 != null) cfg.set(b + ".pos1", a.pos1);
            if (a.pos2 != null) cfg.set(b + ".pos2", a.pos2);
            if (a.spawn1 == null && a.spawn2 == null && a.pos1 == null && a.pos2 == null) cfg.createSection(b);
        }
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar arenas.yml: " + e.getMessage());
        }
    }

    public Arena get(String name) {
        return name == null ? null : arenas.get(name.toLowerCase());
    }

    public Arena create(String name) {
        String n = name.toLowerCase();
        if (arenas.containsKey(n)) return null;
        Arena a = new Arena(n);
        arenas.put(n, a);
        save();
        return a;
    }

    public boolean delete(String name) {
        Arena a = arenas.remove(name.toLowerCase());
        if (a == null) return false;
        save();
        return true;
    }

    public Collection<Arena> all() {
        return arenas.values();
    }

    public Arena findFree() {
        for (Arena a : arenas.values()) {
            if (a.isReady() && !a.inUse) return a;
        }
        return null;
    }

    public boolean hasReadyArena() {
        for (Arena a : arenas.values()) {
            if (a.isReady()) return true;
        }
        return false;
    }

    public boolean contains(Arena a, Location l) {
        return a == null || a.contains(l);
    }
}
