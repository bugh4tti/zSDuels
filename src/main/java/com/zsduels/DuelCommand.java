package com.zsduels;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class DuelCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("help", "menu", "accept", "deny", "random", "leave",
            "toggle", "spectate", "stats", "top", "kit", "rematch", "shop", "rules", "guide");
    private static final List<String> ADMIN_SUBS = Arrays.asList("reload", "arena", "forceend", "kitadmin");
    private static final List<String> ARENA_SUBS = Arrays.asList("create", "delete", "setspawn1", "setspawn2",
            "setpos1", "setpos2", "list");
    private static final List<String> KITADMIN_SUBS = Arrays.asList("create", "edit", "save", "cancel", "delete",
            "list", "setname", "seticon", "setrandom", "setperm");

    private final ZSDuels plugin;

    public DuelCommand(ZSDuels plugin) {
        this.plugin = plugin;
    }

    private String kitNames() {
        List<String> names = new ArrayList<>();
        for (KitManager.Kit k : plugin.getKits().all()) names.add(k.id);
        return String.join(", ", names);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player p = sender instanceof Player pl ? pl : null;
        boolean admin = sender.hasPermission("zsduels.admin");

        if (args.length == 0) {
            if (p != null) plugin.getMenus().openMain(p);
            else plugin.sendList(sender, "help");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        // ----- Comandos que también puede usar la consola -----
        switch (sub) {
            case "help" -> {
                plugin.sendList(sender, "help");
                if (admin) plugin.sendList(sender, "help-admin");
                return true;
            }
            case "reload" -> {
                if (!admin) {
                    plugin.send(sender, "no-permission");
                    return true;
                }
                plugin.reloadAll();
                plugin.send(sender, "reloaded");
                return true;
            }
            case "forceend" -> {
                if (!admin) {
                    plugin.send(sender, "no-permission");
                    return true;
                }
                if (args.length < 2) {
                    plugin.sendList(sender, "help-admin");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    plugin.send(sender, "player-not-found");
                    return true;
                }
                if (plugin.getDuels().forceEnd(t)) plugin.send(sender, "forceend-done", "%target%", t.getName());
                else plugin.send(sender, "forceend-not-in-duel", "%target%", t.getName());
                return true;
            }
            case "rules" -> {
                plugin.sendList(sender, "rules");
                return true;
            }
            case "guide" -> {
                plugin.sendList(sender, "guide");
                return true;
            }
            case "top" -> {
                List<StatsManager.PlayerData> top = plugin.getStats().getTopWins();
                if (top.isEmpty()) {
                    plugin.send(sender, "top-empty");
                    return true;
                }
                plugin.send(sender, "top-header");
                for (int i = 0; i < top.size(); i++) {
                    StatsManager.PlayerData d = top.get(i);
                    plugin.send(sender, "top-line", "%pos%", String.valueOf(i + 1), "%name%", d.name,
                            "%value%", String.valueOf(d.wins));
                }
                return true;
            }
            case "stats" -> {
                String name = args.length > 1 ? args[1] : (p != null ? p.getName() : null);
                if (name == null) {
                    plugin.send(sender, "only-players");
                    return true;
                }
                StatsManager.PlayerData d = (p != null && name.equalsIgnoreCase(p.getName()))
                        ? plugin.getStats().get(p) : plugin.getStats().findByName(name);
                if (d == null) {
                    plugin.send(sender, "player-not-found");
                    return true;
                }
                plugin.sendList(sender, "stats", "%name%", d.name, "%wins%", String.valueOf(d.wins),
                        "%losses%", String.valueOf(d.losses), "%kills%", String.valueOf(d.kills),
                        "%winrate%", String.valueOf(d.winrate()), "%streak%", String.valueOf(d.streak),
                        "%best_streak%", String.valueOf(d.bestStreak), "%gems%", String.valueOf(d.gems));
                return true;
            }
            default -> {
            }
        }

        // ----- Solo jugadores -----
        if (p == null) {
            plugin.send(sender, "only-players");
            return true;
        }
        if (!p.hasPermission("zsduels.use")) {
            plugin.send(p, "no-permission");
            return true;
        }
        DuelManager duels = plugin.getDuels();

        switch (sub) {
            case "menu" -> plugin.getMenus().openMain(p);
            case "accept" -> duels.accept(p, args.length > 1 ? args[1] : null);
            case "deny" -> duels.deny(p, args.length > 1 ? args[1] : null);
            case "random" -> duels.joinQueue(p);
            case "leave" -> {
                if (!duels.stopSpectating(p, true)) duels.leaveQueue(p);
            }
            case "toggle" -> {
                boolean on = plugin.getStats().toggle(p);
                plugin.send(p, on ? "duels-enabled" : "duels-disabled");
            }
            case "spectate", "spec" -> {
                if (args.length > 1) duels.spectate(p, args[1]);
                else plugin.getMenus().openSpectate(p);
            }
            case "kit" -> plugin.getMenus().openKitSelect(p);
            case "rematch" -> duels.rematch(p);
            case "shop" -> plugin.getMenus().openShop(p);
            case "arena" -> {
                if (!admin) plugin.send(p, "no-permission");
                else handleArena(p, args);
            }
            case "kitadmin" -> {
                if (!admin) plugin.send(p, "no-permission");
                else handleKitAdmin(p, args);
            }
            default -> {
                // /duel <jugador> [kit]
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    plugin.send(p, "player-not-found");
                    return true;
                }
                KitManager.Kit kit = null;
                if (args.length > 1) {
                    kit = plugin.getKits().get(args[1]);
                    if (kit == null) {
                        plugin.send(p, "kit-not-found", "%kits%", kitNames());
                        return true;
                    }
                }
                duels.challenge(p, target, kit);
            }
        }
        return true;
    }

    // ---------------- /duel arena ----------------

    private void handleArena(Player p, String[] a) {
        ArenaManager arenas = plugin.getArenas();
        if (a.length < 2) {
            plugin.sendList(p, "arena-usage");
            return;
        }
        String sub = a[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            if (arenas.all().isEmpty()) {
                plugin.send(p, "arena-list-empty");
                return;
            }
            plugin.send(p, "arena-list-header");
            for (ArenaManager.Arena ar : arenas.all()) {
                plugin.send(p, "arena-list-line", "%arena%", ar.name,
                        "%status%", ar.isReady() ? "&aLista" : "&cIncompleta",
                        "%state%", ar.inUse ? "En uso" : "Libre");
            }
            return;
        }
        if (a.length < 3) {
            plugin.sendList(p, "arena-usage");
            return;
        }
        String name = a[2].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (arenas.create(name) == null) plugin.send(p, "arena-exists");
                else plugin.send(p, "arena-created", "%arena%", name);
            }
            case "delete" -> {
                if (arenas.delete(name)) plugin.send(p, "arena-deleted", "%arena%", name);
                else plugin.send(p, "arena-not-found");
            }
            case "setspawn1", "setspawn2", "setpos1", "setpos2" -> {
                ArenaManager.Arena ar = arenas.get(name);
                if (ar == null) {
                    plugin.send(p, "arena-not-found");
                    return;
                }
                Location loc = p.getLocation();
                switch (sub) {
                    case "setspawn1" -> ar.spawn1 = loc;
                    case "setspawn2" -> ar.spawn2 = loc;
                    case "setpos1" -> ar.pos1 = loc;
                    default -> ar.pos2 = loc;
                }
                arenas.save();
                plugin.send(p, "arena-updated", "%arena%", ar.name, "%what%", sub.substring(3));
            }
            default -> plugin.sendList(p, "arena-usage");
        }
    }

    // ---------------- /duel kitadmin ----------------

    private void handleKitAdmin(Player p, String[] a) {
        KitManager kits = plugin.getKits();
        if (a.length < 2) {
            plugin.sendList(p, "kitadmin-usage");
            return;
        }
        String sub = a[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                plugin.send(p, "kitadmin-list-header");
                for (KitManager.Kit k : kits.all()) {
                    plugin.send(p, "kitadmin-list-line", "%kit%", k.id, "%name%", k.displayName,
                            "%random%", String.valueOf(k.random),
                            "%permission%", k.permission == null || k.permission.isEmpty() ? "-" : k.permission);
                }
            }
            case "save", "cancel" -> {
                String id = kits.editingKit(p.getUniqueId());
                if (id == null) {
                    plugin.send(p, "kitadmin-not-editing");
                    return;
                }
                kits.finishEdit(p, sub.equals("save"));
                plugin.send(p, sub.equals("save") ? "kitadmin-saved" : "kitadmin-cancelled", "%kit%", id);
            }
            case "create", "edit" -> {
                if (a.length < 3) {
                    plugin.sendList(p, "kitadmin-usage");
                    return;
                }
                String id = a[2].toLowerCase(Locale.ROOT);
                if (kits.isEditing(p.getUniqueId())) {
                    plugin.send(p, "kitadmin-already");
                    return;
                }
                if (plugin.getDuels().isBusy(p)) {
                    plugin.send(p, "kitadmin-busy");
                    return;
                }
                KitManager.Kit existing = kits.get(id);
                if (sub.equals("create")) {
                    if (!KitManager.isValidId(id)) {
                        plugin.send(p, "kitadmin-bad-id");
                        return;
                    }
                    if (existing != null) {
                        plugin.send(p, "kitadmin-exists");
                        return;
                    }
                    kits.startEdit(p, id, null);
                } else {
                    if (existing == null) {
                        plugin.send(p, "kitadmin-not-found");
                        return;
                    }
                    kits.startEdit(p, existing.id, existing);
                }
                plugin.send(p, "kitadmin-started", "%kit%", id);
            }
            case "delete", "setname", "seticon", "setrandom", "setperm" -> {
                if (a.length < 3) {
                    plugin.sendList(p, "kitadmin-usage");
                    return;
                }
                KitManager.Kit kit = kits.get(a[2]);
                if (kit == null) {
                    plugin.send(p, "kitadmin-not-found");
                    return;
                }
                switch (sub) {
                    case "delete" -> {
                        kits.deleteKit(kit.id);
                        plugin.send(p, "kitadmin-deleted", "%kit%", kit.id);
                        return;
                    }
                    case "setname" -> {
                        if (a.length < 4) {
                            plugin.sendList(p, "kitadmin-usage");
                            return;
                        }
                        kit.displayName = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
                    }
                    case "seticon" -> {
                        Material m = p.getInventory().getItemInMainHand().getType();
                        if (m.isAir()) {
                            plugin.send(p, "kitadmin-icon-air");
                            return;
                        }
                        kit.icon = m;
                    }
                    case "setrandom" -> {
                        if (a.length < 4) {
                            plugin.sendList(p, "kitadmin-usage");
                            return;
                        }
                        kit.random = Boolean.parseBoolean(a[3]);
                    }
                    default -> {
                        if (a.length < 4) {
                            plugin.sendList(p, "kitadmin-usage");
                            return;
                        }
                        kit.permission = a[3].equalsIgnoreCase("none") ? "" : a[3];
                    }
                }
                kits.save(kit);
                plugin.send(p, "kitadmin-updated", "%kit%", kit.id, "%what%", sub.substring(3));
            }
            default -> plugin.sendList(p, "kitadmin-usage");
        }
    }

    // ---------------- Tab complete ----------------

    private List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(o);
        }
        return out;
    }

    private List<String> players() {
        List<String> names = new ArrayList<>();
        for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
        return names;
    }

    private List<String> kitIds() {
        List<String> ids = new ArrayList<>();
        for (KitManager.Kit k : plugin.getKits().all()) ids.add(k.id);
        return ids;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] a) {
        boolean admin = sender.hasPermission("zsduels.admin");
        if (a.length == 1) {
            List<String> all = new ArrayList<>(SUBS);
            if (admin) all.addAll(ADMIN_SUBS);
            all.addAll(players());
            return filter(all, a[0]);
        }
        String first = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            switch (first) {
                case "arena":
                    return admin ? filter(ARENA_SUBS, a[1]) : new ArrayList<>();
                case "kitadmin":
                    return admin ? filter(KITADMIN_SUBS, a[1]) : new ArrayList<>();
                case "spectate", "spec", "stats", "accept", "deny", "forceend":
                    return filter(players(), a[1]);
                default:
                    return filter(kitIds(), a[1]);
            }
        }
        if (a.length == 3 && admin) {
            if (first.equals("arena") && !a[1].equalsIgnoreCase("create") && !a[1].equalsIgnoreCase("list")) {
                List<String> names = new ArrayList<>();
                for (ArenaManager.Arena ar : plugin.getArenas().all()) names.add(ar.name);
                return filter(names, a[2]);
            }
            if (first.equals("kitadmin") && !a[1].equalsIgnoreCase("create") && !a[1].equalsIgnoreCase("list")
                    && !a[1].equalsIgnoreCase("save") && !a[1].equalsIgnoreCase("cancel")) {
                return filter(kitIds(), a[2]);
            }
        }
        if (a.length == 4 && admin && first.equals("kitadmin") && a[1].equalsIgnoreCase("setrandom")) {
            return filter(Arrays.asList("true", "false"), a[3]);
        }
        return new ArrayList<>();
    }
                            }
