package com.zsduels;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StatsManager {

    public static class PlayerData {
        public final UUID id;
        public String name = "?";
        public int wins, losses, kills, streak, bestStreak, gems;
        public boolean enabled;

        public PlayerData(UUID id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }

        public int winrate() {
            int total = wins + losses;
            return total == 0 ? 0 : Math.round(wins * 100f / total);
        }
    }

    private final ZSDuels plugin;
    private final File file;
    // ConcurrentHashMap: los plugins de hologramas pueden pedir placeholders en otro hilo
    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private volatile List<PlayerData> topWins = List.of();
    private volatile List<PlayerData> topKills = List.of();
    private volatile boolean dirty;

    public StatsManager(ZSDuels plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        load();
        rebuildTop();
    }

    private void load() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("players");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                PlayerData d = new PlayerData(id, sec.getBoolean(k + ".enabled", true));
                d.name = sec.getString(k + ".name", "?");
                d.wins = sec.getInt(k + ".wins");
                d.losses = sec.getInt(k + ".losses");
                d.kills = sec.getInt(k + ".kills");
                d.streak = sec.getInt(k + ".streak");
                d.bestStreak = sec.getInt(k + ".best-streak");
                d.gems = sec.getInt(k + ".gems");
                data.put(id, d);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (PlayerData d : data.values()) {
            String b = "players." + d.id;
            cfg.set(b + ".name", d.name);
            cfg.set(b + ".enabled", d.enabled);
            cfg.set(b + ".wins", d.wins);
            cfg.set(b + ".losses", d.losses);
            cfg.set(b + ".kills", d.kills);
            cfg.set(b + ".streak", d.streak);
            cfg.set(b + ".best-streak", d.bestStreak);
            cfg.set(b + ".gems", d.gems);
        }
        try {
            cfg.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar data.yml: " + e.getMessage());
        }
    }

    public void startTasks() {
        int refresh = Math.max(5, plugin.getConfig().getInt("top.refresh-seconds", 30));
        Bukkit.getScheduler().runTaskTimer(plugin, this::rebuildTop, 20L, refresh * 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) save();
        }, 1200L, 1200L);
    }

    public void markDirty() {
        dirty = true;
    }

    public PlayerData get(UUID id, String name) {
        PlayerData d = data.computeIfAbsent(id,
                k -> new PlayerData(k, plugin.getConfig().getBoolean("settings.enabled-by-default", true)));
        if (name != null && !name.isEmpty() && !name.equals(d.name)) {
            d.name = name;
            dirty = true;
        }
        return d;
    }

    public PlayerData get(Player p) {
        return get(p.getUniqueId(), p.getName());
    }

    public PlayerData findByName(String name) {
        for (PlayerData d : data.values()) {
            if (d.name.equalsIgnoreCase(name)) return d;
        }
        return null;
    }

    public boolean toggle(Player p) {
        PlayerData d = get(p);
        d.enabled = !d.enabled;
        dirty = true;
        return d.enabled;
    }

    /** Suma victoria, kill (si corresponde), racha y gemas al ganador; derrota al perdedor. */
    public PlayerData recordWin(UUID winner, String winnerName, UUID loser, String loserName, boolean kill) {
        PlayerData w = get(winner, winnerName);
        PlayerData l = get(loser, loserName);
        w.wins++;
        if (kill) w.kills++;
        w.streak++;
        if (w.streak > w.bestStreak) w.bestStreak = w.streak;
        w.gems += Math.max(0, plugin.getConfig().getInt("settings.gem-reward", 1));
        l.losses++;
        l.streak = 0;
        dirty = true;
        save();
        return w;
    }

    public void rebuildTop() {
        int size = Math.max(1, plugin.getConfig().getInt("top.size", 10));
        List<PlayerData> all = new ArrayList<>(data.values());
        topWins = all.stream()
                .filter(d -> d.wins > 0)
                .sorted(Comparator.comparingInt((PlayerData d) -> d.wins).reversed().thenComparing(d -> d.name))
                .limit(size)
                .toList();
        topKills = all.stream()
                .filter(d -> d.kills > 0)
                .sorted(Comparator.comparingInt((PlayerData d) -> d.kills).reversed().thenComparing(d -> d.name))
                .limit(size)
                .toList();
    }

    public List<PlayerData> getTopWins() {
        return topWins;
    }

    public List<PlayerData> getTopKills() {
        return topKills;
    }
}
