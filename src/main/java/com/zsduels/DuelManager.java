package com.zsduels;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class DuelManager {

    private static class Request {
        UUID from, to;
        String fromName, toName;
        KitManager.Kit kit;
        long expires;
    }

    private static class Rematch {
        UUID opponent;
        String name;
        String kitId;
        long expires;
    }

    private static class SpecState {
        Location loc;
        GameMode mode;
        boolean allowFlight;
    }

    private final ZSDuels plugin;
    private final Map<UUID, Duel> byPlayer = new HashMap<>();
    private final List<Duel> duels = new ArrayList<>();
    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private final List<Request> requests = new ArrayList<>();
    private final Map<UUID, Rematch> rematches = new HashMap<>();
    private final Map<UUID, Long> combat = new HashMap<>();
    private final Map<UUID, Duel> spectating = new HashMap<>();
    private final Map<UUID, SpecState> specStates = new HashMap<>();
    private int tickCounter;

    public DuelManager(ZSDuels plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    static double maxHealth(Player p) {
        Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        AttributeInstance ai = attr == null ? null : p.getAttribute(attr);
        return ai == null ? 20.0 : ai.getValue();
    }

    // ---------------- Consultas ----------------

    public boolean inDuel(UUID id) { return byPlayer.containsKey(id); }
    public Duel getDuel(UUID id) { return byPlayer.get(id); }
    public boolean inQueue(UUID id) { return queue.contains(id); }
    public boolean isSpectating(UUID id) { return spectating.containsKey(id); }
    public int queueSize() { return queue.size(); }

    public List<Duel> liveDuels() {
        List<Duel> out = new ArrayList<>();
        for (Duel d : duels) {
            if (d.state != Duel.State.ENDED) out.add(d);
        }
        return out;
    }

    public int liveCount() { return liveDuels().size(); }

    private String busyKey(Player p) {
        UUID id = p.getUniqueId();
        if (byPlayer.containsKey(id)) return "already-in-duel";
        if (queue.contains(id)) return "already-in-queue";
        if (spectating.containsKey(id)) return "spectate-busy";
        if (plugin.getKits().isEditing(id)) return "kitadmin-busy";
        return null;
    }

    public boolean isBusy(Player p) {
        return busyKey(p) != null;
    }

    // ---------------- Combate ----------------

    public void markCombat(UUID a, UUID b) {
        long now = System.currentTimeMillis();
        combat.put(a, now);
        combat.put(b, now);
    }

    public boolean inCombat(UUID id) {
        int s = plugin.getConfig().getInt("settings.combat-block-seconds", 15);
        if (s <= 0) return false;
        Long t = combat.get(id);
        return t != null && System.currentTimeMillis() - t < s * 1000L;
    }

    // ---------------- Tick (1 vez por segundo) ----------------

    private void tick() {
        tickCounter++;
        long now = System.currentTimeMillis();

        Iterator<Request> it = requests.iterator();
        while (it.hasNext()) {
            Request r = it.next();
            if (r.expires <= now) {
                it.remove();
                Player from = Bukkit.getPlayer(r.from);
                Player to = Bukkit.getPlayer(r.to);
                if (from != null) plugin.send(from, "challenge-expired", "%player%", r.toName);
                if (to != null) plugin.send(to, "challenge-expired", "%player%", r.fromName);
            }
        }
        rematches.values().removeIf(r -> r.expires <= now);
        combat.values().removeIf(t -> now - t > 120000L);

        queue.removeIf(id -> Bukkit.getPlayer(id) == null);
        matchQueue();

        int interval = Math.max(1, plugin.getConfig().getInt("settings.queue-message-interval", 5));
        if (!queue.isEmpty() && tickCounter % interval == 0) {
            int total = queue.size();
            int per = plugin.getConfig().getInt("settings.queue-seconds-per-position", 3);
            int pos = 0;
            for (UUID id : queue) {
                pos++;
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                int time = (int) Math.ceil(pos / 2.0) * per;
                plugin.send(p, "queue-position", "%position%", String.valueOf(pos),
                        "%total%", String.valueOf(total), "%time%", String.valueOf(time));
            }
        }
    }

    private void matchQueue() {
        while (queue.size() >= 2) {
            Iterator<UUID> qi = queue.iterator();
            Player a = Bukkit.getPlayer(qi.next());
            Player b = Bukkit.getPlayer(qi.next());
            if (a == null || b == null) return;
            ArenaManager.Arena arena = plugin.getArenas().findFree();
            if (arena == null) return;
            KitManager.Kit kit = plugin.getKits().randomKit(a, b);
            if (kit == null) return;
            queue.remove(a.getUniqueId());
            queue.remove(b.getUniqueId());
            plugin.send(a, "match-found", "%opponent%", b.getName(), "%kit%", kit.displayName);
            plugin.send(b, "match-found", "%opponent%", a.getName(), "%kit%", kit.displayName);
            startDuel(a, b, kit, arena);
        }
    }

    // ---------------- Cola ----------------

    public void joinQueue(Player p) {
        String busy = busyKey(p);
        if (busy != null) {
            plugin.send(p, busy);
            return;
        }
        if (!plugin.getKits().hasRandomKits()) {
            plugin.send(p, "no-kits");
            return;
        }
        if (!plugin.getArenas().hasReadyArena()) {
            plugin.send(p, "no-arenas");
            return;
        }
        queue.add(p.getUniqueId());
        plugin.send(p, "queue-joined");
    }

    public boolean leaveQueue(Player p) {
        boolean removed = queue.remove(p.getUniqueId());
        plugin.send(p, removed ? "queue-left" : "not-in-queue");
        return removed;
    }

    // ---------------- Retos ----------------

    public void challenge(Player from, Player to, KitManager.Kit kit) {
        if (from.getUniqueId().equals(to.getUniqueId())) {
            plugin.send(from, "self-challenge");
            return;
        }
        String busy = busyKey(from);
        if (busy != null) {
            plugin.send(from, busy);
            return;
        }
        if (busyKey(to) != null) {
            plugin.send(from, "target-in-duel", "%target%", to.getName());
            return;
        }
        if (!plugin.getStats().get(from).enabled) {
            plugin.send(from, "duels-disabled");
            return;
        }
        if (!plugin.getStats().get(to).enabled) {
            plugin.send(from, "target-duels-disabled", "%target%", to.getName());
            return;
        }
        if (inCombat(from.getUniqueId())) {
            plugin.send(from, "in-combat");
            return;
        }
        if (inCombat(to.getUniqueId())) {
            plugin.send(from, "target-in-combat", "%target%", to.getName());
            return;
        }
        if (!plugin.getArenas().hasReadyArena()) {
            plugin.send(from, "no-arenas");
            return;
        }
        KitManager kits = plugin.getKits();
        if (kit == null) {
            kit = kits.randomKit(from, to);
            if (kit == null) {
                plugin.send(from, "no-kits");
                return;
            }
        } else if (!kits.hasPermission(from, kit) || !kits.hasPermission(to, kit)) {
            plugin.send(from, "no-permission");
            return;
        }

        // Si el otro jugador ya te había retado, se acepta automáticamente (sirve para la revancha)
        Request cross = findRequest(from.getUniqueId(), to.getName());
        if (cross != null) {
            requests.remove(cross);
            tryStart(to, from, cross.kit);
            return;
        }
        for (Request r : requests) {
            if (r.from.equals(from.getUniqueId()) && r.to.equals(to.getUniqueId())) {
                plugin.send(from, "challenge-already-pending");
                return;
            }
        }

        Request r = new Request();
        r.from = from.getUniqueId();
        r.to = to.getUniqueId();
        r.fromName = from.getName();
        r.toName = to.getName();
        r.kit = kit;
        r.expires = System.currentTimeMillis() + plugin.getConfig().getInt("settings.request-expire-seconds", 30) * 1000L;
        requests.add(r);

        plugin.send(from, "challenge-sent", "%target%", to.getName(), "%kit%", kit.displayName);
        sendChallenge(from, to, kit);
        plugin.sound(to, "request");
    }

    private void sendChallenge(Player from, Player to, KitManager.Kit kit) {
        String text = plugin.msg("challenge-received", "%player%", from.getName(), "%kit%", kit.displayName);
        TextComponent root = new TextComponent(TextComponent.fromLegacyText(text));
        root.addExtra(new TextComponent(" "));
        root.addExtra(button("challenge-accept-button", "challenge-accept-hover", "/duel accept " + from.getName()));
        root.addExtra(new TextComponent(" "));
        root.addExtra(button("challenge-deny-button", "challenge-deny-hover", "/duel deny " + from.getName()));
        to.spigot().sendMessage(root);
    }

    private TextComponent button(String textKey, String hoverKey, String command) {
        String t = plugin.format(plugin.getConfig().getString("messages." + textKey, textKey));
        String h = plugin.format(plugin.getConfig().getString("messages." + hoverKey, ""));
        TextComponent c = new TextComponent(TextComponent.fromLegacyText(t));
        c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(h))));
        return c;
    }

    private Request findRequest(UUID to, String fromName) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            Request r = requests.get(i);
            if (r.to.equals(to) && (fromName == null || r.fromName.equalsIgnoreCase(fromName))) return r;
        }
        return null;
    }

    public void accept(Player to, String fromName) {
        Request r = findRequest(to.getUniqueId(), fromName);
        if (r == null) {
            plugin.send(to, "challenge-no-pending");
            return;
        }
        requests.remove(r);
        Player from = Bukkit.getPlayer(r.from);
        if (from == null) {
            plugin.send(to, "player-not-found");
            return;
        }
        String busy = busyKey(to);
        if (busy != null) {
            plugin.send(to, busy);
            return;
        }
        if (busyKey(from) != null) {
            plugin.send(to, "target-in-duel", "%target%", from.getName());
            return;
        }
        tryStart(from, to, r.kit);
    }

    public void deny(Player to, String fromName) {
        Request r = findRequest(to.getUniqueId(), fromName);
        if (r == null) {
            plugin.send(to, "challenge-no-pending");
            return;
        }
        requests.remove(r);
        Player from = Bukkit.getPlayer(r.from);
        if (from != null) plugin.send(from, "challenge-denied", "%player%", to.getName());
        plugin.send(to, "challenge-denied-self", "%player%", r.fromName);
    }

    private boolean tryStart(Player a, Player b, KitManager.Kit kit) {
        ArenaManager.Arena arena = plugin.getArenas().findFree();
        if (arena == null) {
            plugin.send(a, "no-arenas");
            plugin.send(b, "no-arenas");
            return false;
        }
        startDuel(a, b, kit, arena);
        return true;
    }

    private void removeRequestsOf(UUID a, UUID b) {
        requests.removeIf(r -> r.from.equals(a) || r.to.equals(a) || r.from.equals(b) || r.to.equals(b));
    }

    // ---------------- Inicio del duelo ----------------

    private void forPlayers(Duel d, Consumer<Player> action) {
        Player a = Bukkit.getPlayer(d.p1);
        Player b = Bukkit.getPlayer(d.p2);
        if (a != null) action.accept(a);
        if (b != null) action.accept(b);
    }

    public void startDuel(Player a, Player b, KitManager.Kit kit, ArenaManager.Arena arena) {
        arena.inUse = true;
        Duel d = new Duel(a, b, arena, kit);
        duels.add(d);
        byPlayer.put(d.p1, d);
        byPlayer.put(d.p2, d);
        queue.remove(d.p1);
        queue.remove(d.p2);
        removeRequestsOf(d.p1, d.p2);

        prepare(a, kit, arena.spawn1);
        prepare(b, kit, arena.spawn2);

        final int[] remaining = {Math.max(0, plugin.getConfig().getInt("settings.countdown-seconds", 5))};
        d.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (d.state == Duel.State.COUNTDOWN) {
                if (remaining[0] > 0) {
                    String sec = String.valueOf(remaining[0]);
                    forPlayers(d, p -> {
                        plugin.title(p, "countdown", "%seconds%", sec);
                        plugin.sound(p, "countdown");
                    });
                    remaining[0]--;
                } else {
                    d.state = Duel.State.FIGHTING;
                    d.startMillis = System.currentTimeMillis();
                    forPlayers(d, p -> {
                        plugin.title(p, "start", "%kit%", d.kit.displayName);
                        plugin.send(p, "duel-start", "%kit%", d.kit.displayName);
                        plugin.sound(p, "start");
                    });
                }
            } else if (d.state == Duel.State.FIGHTING) {
                int max = plugin.getConfig().getInt("settings.max-duel-seconds", 0);
                if (max > 0 && d.elapsedSeconds() >= max) endDuel(d, null, false, false);
            }
        }, 0L, 20L);
    }

    private void prepare(Player p, KitManager.Kit kit, Location spawn) {
        p.closeInventory();
        p.leaveVehicle();
        plugin.getStore().save(p);
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(new ItemStack(Material.AIR));
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setGameMode(GameMode.SURVIVAL);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setHealth(maxHealth(p));
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        plugin.getKits().giveKit(p, kit);
        p.teleport(spawn);
    }

    // ---------------- Fin del duelo ----------------

    /** winner = null significa empate. kill = el ganador mató al rival (no por desconexión ni entorno). */
    public void endDuel(Duel d, UUID winner, boolean kill, boolean disconnect) {
        if (d.state == Duel.State.ENDED) return;
        d.state = Duel.State.ENDED;
        if (d.task != null) d.task.cancel();

        if (winner != null) {
            UUID loser = d.other(winner);
            Player pw = Bukkit.getPlayer(winner);
            Player pl = Bukkit.getPlayer(loser);
            StatsManager.PlayerData wd = plugin.getStats().recordWin(winner, d.name(winner), loser, d.name(loser), kill);
            int reward = Math.max(0, plugin.getConfig().getInt("settings.gem-reward", 1));
            if (pw != null) {
                plugin.send(pw, disconnect ? "disconnect-win" : "win-message", "%opponent%", d.name(loser));
                plugin.title(pw, "win", "%opponent%", d.name(loser));
                plugin.sound(pw, "win");
                if (reward > 0) {
                    plugin.send(pw, "gem-reward", "%amount%", String.valueOf(reward), "%gems%", String.valueOf(wd.gems));
                }
            }
            if (pl != null) {
                plugin.send(pl, "lose-message", "%opponent%", d.name(winner));
                plugin.title(pl, "lose", "%opponent%", d.name(winner));
                plugin.sound(pl, "lose");
            }
            if (plugin.getConfig().getBoolean("settings.broadcast-win", true)) {
                Bukkit.broadcastMessage(plugin.msg("win-broadcast", "%winner%", d.name(winner), "%loser%", d.name(loser)));
            }
        } else {
            forPlayers(d, p -> {
                plugin.send(p, "draw-message");
                plugin.title(p, "draw");
            });
        }

        long exp = System.currentTimeMillis() + plugin.getConfig().getInt("settings.rematch-expire-seconds", 30) * 1000L;
        rematches.put(d.p1, newRematch(d.p2, d.n2, d.kit.id, exp));
        rematches.put(d.p2, newRematch(d.p1, d.n1, d.kit.id, exp));

        Runnable cleanup = () -> {
            for (UUID id : new UUID[]{d.p1, d.p2}) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) finishPlayer(p, d, !disconnect);
            }
            releaseSpectators(d);
            d.arena.inUse = false;
            duels.remove(d);
            byPlayer.remove(d.p1, d);
            byPlayer.remove(d.p2, d);
        };
        long delay = Math.max(0L, plugin.getConfig().getLong("settings.end-delay-ticks", 40L));
        if (plugin.isEnabled() && delay > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, cleanup, delay);
        } else {
            cleanup.run();
        }
    }

    private Rematch newRematch(UUID opp, String name, String kitId, long exp) {
        Rematch r = new Rematch();
        r.opponent = opp;
        r.name = name;
        r.kitId = kitId;
        r.expires = exp;
        return r;
    }

    /** Devuelve las cosas reales, borra las del duelo y ejecuta /spawn. */
    private void finishPlayer(Player p, Duel d, boolean prompt) {
        p.closeInventory();
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setFireTicks(0);
        if (!p.isDead()) p.setHealth(maxHealth(p));
        p.setFoodLevel(20);
        p.setFallDistance(0f);
        plugin.getStore().restore(p);
        plugin.runCommand(p, plugin.getConfig().getString("settings.spawn-command", "spawn"));
        if (prompt) {
            Player op = Bukkit.getPlayer(d.other(p.getUniqueId()));
            if (op != null) sendRematchPrompt(p, op.getName());
        }
    }

    private void sendRematchPrompt(Player p, String opponentName) {
        TextComponent root = new TextComponent(TextComponent.fromLegacyText(plugin.msg("rematch-prompt", "%opponent%", opponentName)));
        root.addExtra(new TextComponent(" "));
        root.addExtra(button("rematch-button", "rematch-hover", "/duel rematch"));
        p.spigot().sendMessage(root);
    }

    // ---------------- Revancha ----------------

    public void rematch(Player p) {
        Rematch r = rematches.get(p.getUniqueId());
        if (r == null || r.expires <= System.currentTimeMillis()) {
            rematches.remove(p.getUniqueId());
            plugin.send(p, "rematch-none");
            return;
        }
        Player opp = Bukkit.getPlayer(r.opponent);
        KitManager.Kit kit = plugin.getKits().get(r.kitId);
        if (opp == null || kit == null) {
            plugin.send(p, "rematch-unavailable");
            return;
        }
        challenge(p, opp, kit);
    }

    // ---------------- Espectadores ----------------

    public void spectate(Player p, String targetName) {
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null) {
            plugin.send(p, "player-not-found");
            return;
        }
        Duel d = byPlayer.get(t.getUniqueId());
        if (d == null || d.state == Duel.State.ENDED) {
            plugin.send(p, "spectate-no-duel", "%target%", t.getName());
            return;
        }
        spectate(p, d);
    }

    public void spectate(Player p, Duel d) {
        if (d == null || d.state == Duel.State.ENDED) {
            plugin.send(p, "spectate-none");
            return;
        }
        UUID id = p.getUniqueId();
        if (byPlayer.containsKey(id) || queue.contains(id) || plugin.getKits().isEditing(id)) {
            plugin.send(p, "spectate-busy");
            return;
        }
        Duel prev = spectating.get(id);
        if (prev != null) {
            prev.spectators.remove(id);
        } else {
            SpecState s = new SpecState();
            s.loc = p.getLocation();
            s.mode = p.getGameMode();
            s.allowFlight = p.getAllowFlight();
            specStates.put(id, s);
        }
        d.spectators.add(id);
        spectating.put(id, d);
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(d.arena.spawn1);
        plugin.send(p, "spectate-start", "%player1%", d.n1, "%player2%", d.n2);
    }

    public boolean stopSpectating(Player p, boolean message) {
        UUID id = p.getUniqueId();
        Duel d = spectating.remove(id);
        if (d == null) return false;
        d.spectators.remove(id);
        SpecState s = specStates.remove(id);
        if (s != null) {
            p.setGameMode(s.mode);
            p.setAllowFlight(s.allowFlight);
            if (s.loc != null && s.loc.getWorld() != null) p.teleport(s.loc);
        }
        if (message) plugin.send(p, "spectate-stop");
        return true;
    }

    private void releaseSpectators(Duel d) {
        for (UUID id : new ArrayList<>(d.spectators)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                stopSpectating(p, false);
                plugin.send(p, "spectate-ended");
            } else {
                spectating.remove(id);
                specStates.remove(id);
            }
        }
        d.spectators.clear();
    }

    // ---------------- Eventos externos ----------------

    public void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        queue.remove(id);
        requests.removeIf(r -> r.from.equals(id) || r.to.equals(id));
        rematches.remove(id);
        Duel d = byPlayer.get(id);
        if (d != null && d.state != Duel.State.ENDED) endDuel(d, d.other(id), false, true);
        stopSpectating(p, false);
        plugin.getKits().finishEdit(p, false);
    }

    public boolean forceEnd(Player target) {
        Duel d = byPlayer.get(target.getUniqueId());
        if (d == null || d.state == Duel.State.ENDED) return false;
        endDuel(d, null, false, false);
        return true;
    }

    public void shutdown() {
        for (Duel d : new ArrayList<>(duels)) endDuel(d, null, false, false);
    }
}
