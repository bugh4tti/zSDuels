package com.zsduels;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;

public class ZSDuels extends JavaPlugin {

    private YamlConfiguration menuConfig;
    private InventoryStore store;
    private StatsManager stats;
    private ArenaManager arenas;
    private KitManager kits;
    private DuelManager duels;
    private MenuManager menus;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        applyDefaults();
        if (!new File(getDataFolder(), "menu.yml").exists()) saveResource("menu.yml", false);
        if (!new File(getDataFolder(), "kits.yml").exists()) saveResource("kits.yml", false);
        loadMenuConfig();

        store = new InventoryStore(this);
        stats = new StatsManager(this);
        arenas = new ArenaManager(this);
        kits = new KitManager(this);
        duels = new DuelManager(this);
        menus = new MenuManager(this);

        DuelCommand command = new DuelCommand(this);
        PluginCommand pc = getCommand("zsduels");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        Bukkit.getPluginManager().registerEvents(new DuelListener(this), this);
        Bukkit.getPluginManager().registerEvents(menus, this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ZSDuelsExpansion(this).register();
            getLogger().info("PlaceholderAPI detectado: placeholders %zsduels_*% registrados.");
        }
        stats.startTasks();
        getLogger().info("zSDuels activado.");
    }

    @Override
    public void onDisable() {
        if (duels != null) duels.shutdown();
        if (kits != null) kits.shutdown();
        if (stats != null) stats.save();
    }

    /** Agrega al config.yml los mensajes nuevos si faltan. */
    private void applyDefaults() {
        FileConfiguration c = getConfig();
        c.addDefault("messages.kitadmin-usage", Arrays.asList(
                "%prefix%&eUso de kits (admin):",
                "&b/duel kitadmin create <id> &7- crea un kit con tu inventario actual",
                "&b/duel kitadmin edit <id> &7- carga el kit en tu inventario",
                "&b/duel kitadmin save &7| &b/duel kitadmin cancel",
                "&b/duel kitadmin delete <id>",
                "&b/duel kitadmin list",
                "&b/duel kitadmin setname <id> <nombre>",
                "&b/duel kitadmin seticon <id> &7- usa el ítem de tu mano",
                "&b/duel kitadmin setrandom <id> <true|false>",
                "&b/duel kitadmin setperm <id> <permiso|none>"));
        c.addDefault("messages.kitadmin-exists", "%prefix%&cEse kit ya existe.");
        c.addDefault("messages.kitadmin-not-found", "%prefix%&cEse kit no existe.");
        c.addDefault("messages.kitadmin-bad-id", "%prefix%&cNombre inválido. Usa solo a-z, 0-9, _ y -");
        c.addDefault("messages.kitadmin-busy", "%prefix%&cNo puedes editar kits estando en duelo, cola o espectando.");
        c.addDefault("messages.kitadmin-already", "%prefix%&cYa estás editando un kit. Usa &f/duel kitadmin save &co &f/duel kitadmin cancel&c.");
        c.addDefault("messages.kitadmin-started", "%prefix%&aEditando el kit &f%kit%&a. Tu inventario fue guardado. Pon los ítems en tu inventario (armadura y mano secundaria incluidas) y usa &f/duel kitadmin save &ao &f/duel kitadmin cancel&a.");
        c.addDefault("messages.kitadmin-saved", "%prefix%&aKit &f%kit% &aguardado en kits.yml. Tu inventario fue restaurado.");
        c.addDefault("messages.kitadmin-cancelled", "%prefix%&7Edición cancelada. Tu inventario fue restaurado.");
        c.addDefault("messages.kitadmin-not-editing", "%prefix%&cNo estás editando ningún kit.");
        c.addDefault("messages.kitadmin-deleted", "%prefix%&7Kit &f%kit% &7eliminado.");
        c.addDefault("messages.kitadmin-updated", "%prefix%&aKit &f%kit% &aactualizado (&f%what%&a).");
        c.addDefault("messages.kitadmin-icon-air", "%prefix%&cTen un ítem en la mano.");
        c.addDefault("messages.kitadmin-list-header", "%prefix%&eKits:");
        c.addDefault("messages.kitadmin-list-line", "&8▪ &f%kit% &8- %name% &8- &7random: &f%random% &8- &7permiso: &f%permission%");
        c.addDefault("messages.kit-editor-cursor", "%prefix%&cSuelta el ítem en un slot antes de guardar.");
        c.addDefault("messages.kit-editor-invalid", "%prefix%&cEl orden no es válido (faltan o sobran ítems). Intenta de nuevo.");
        c.options().copyDefaults(true);
        saveConfig();
    }

    private void loadMenuConfig() {
        menuConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "menu.yml"));
    }

    public void reloadAll() {
        reloadConfig();
        applyDefaults();
        loadMenuConfig();
        kits.load();
        arenas.load();
        stats.rebuildTop();
    }

    // ---------------- Mensajes ----------------

    public String format(String raw, String... pairs) {
        String r = raw.replace("%prefix%", getConfig().getString("messages.prefix", ""));
        for (int i = 0; i + 1 < pairs.length; i += 2) r = r.replace(pairs[i], pairs[i + 1]);
        return Colors.color(r);
    }

    public String msg(String key, String... pairs) {
        String raw = getConfig().getString("messages." + key);
        return format(raw == null ? "&cMensaje faltante: " + key : raw, pairs);
    }

    public void send(CommandSender to, String key, String... pairs) {
        to.sendMessage(msg(key, pairs));
    }

    public void sendList(CommandSender to, String key, String... pairs) {
        for (String line : getConfig().getStringList("messages." + key)) to.sendMessage(format(line, pairs));
    }

    public void sound(Player p, String key) {
        String s = getConfig().getString("sounds." + key);
        if (s != null && !s.isEmpty()) p.playSound(p.getLocation(), s, 1f, 1f);
    }

    public void title(Player p, String key, String... pairs) {
        ConfigurationSection s = getConfig().getConfigurationSection("titles." + key);
        if (s == null) return;
        p.sendTitle(format(s.getString("title", ""), pairs), format(s.getString("subtitle", ""), pairs),
                s.getInt("fade-in", 5), s.getInt("stay", 40), s.getInt("fade-out", 10));
    }

    /** Ejecuta un comando. Acepta prefijos [console] y [player]. */
    public void runCommand(Player p, String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return;
        String c = cmd.replace("%player%", p.getName()).trim();
        String low = c.toLowerCase();
        if (low.startsWith("[console]")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.substring(9).trim());
        } else if (low.startsWith("[player]")) {
            Bukkit.dispatchCommand(p, c.substring(8).trim());
        } else {
            Bukkit.dispatchCommand(p, c.startsWith("/") ? c.substring(1) : c);
        }
    }

    // ---------------- Getters ----------------

    public YamlConfiguration getMenuConfig() { return menuConfig; }
    public InventoryStore getStore() { return store; }
    public StatsManager getStats() { return stats; }
    public ArenaManager getArenas() { return arenas; }
    public KitManager getKits() { return kits; }
    public DuelManager getDuels() { return duels; }
    public MenuManager getMenus() { return menus; }
          }
