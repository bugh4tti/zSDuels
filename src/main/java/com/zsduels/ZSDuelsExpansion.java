package com.zsduels;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;

public class ZSDuelsExpansion extends PlaceholderExpansion {

    private final ZSDuels plugin;

    public ZSDuelsExpansion(ZSDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "zsduels";
    }

    @Override
    public String getAuthor() {
        return "Bughatti";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase();

        // Tops: top_wins_<n>_name | top_wins_<n>_value | top_kills_<n>_name | top_kills_<n>_value
        if (p.startsWith("top_wins_") || p.startsWith("top_kills_")) {
            String[] parts = p.split("_");
            if (parts.length != 4) return "";
            boolean wins = parts[1].equals("wins");
            int n;
            try {
                n = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return "";
            }
            List<StatsManager.PlayerData> list = wins ? plugin.getStats().getTopWins() : plugin.getStats().getTopKills();
            boolean name = parts[3].equals("name");
            if (n < 1 || n > list.size()) {
                return name ? plugin.getConfig().getString("top.empty-name", "---")
                        : plugin.getConfig().getString("top.empty-value", "0");
            }
            StatsManager.PlayerData d = list.get(n - 1);
            return name ? d.name : String.valueOf(wins ? d.wins : d.kills);
        }

        switch (p) {
            case "queue_size":
                return String.valueOf(plugin.getDuels().queueSize());
            case "live_duels":
                return String.valueOf(plugin.getDuels().liveCount());
            default:
                break;
        }

        if (player == null) return "";
        StatsManager.PlayerData d = plugin.getStats().get(player.getUniqueId(), player.getName());
        switch (p) {
            case "wins": return String.valueOf(d.wins);
            case "losses": return String.valueOf(d.losses);
            case "kills": return String.valueOf(d.kills);
            case "winrate": return String.valueOf(d.winrate());
            case "streak": return String.valueOf(d.streak);
            case "best_streak": return String.valueOf(d.bestStreak);
            case "gems": return String.valueOf(d.gems);
            case "in_duel": return String.valueOf(plugin.getDuels().inDuel(player.getUniqueId()));
            default: return null;
        }
    }
}
