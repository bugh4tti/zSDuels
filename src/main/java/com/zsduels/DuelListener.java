package com.zsduels;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DuelListener implements Listener {

    private final ZSDuels plugin;
    private final Map<UUID, Long> leaveWarn = new HashMap<>();

    public DuelListener(ZSDuels plugin) {
        this.plugin = plugin;
    }

    private DuelManager duels() {
        return plugin.getDuels();
    }

    private Player attacker(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player sp) return sp;
        return d instanceof Player pl ? pl : null;
    }

    // ---------------- Entrada / salida ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.getStats().get(p);
        UUID id = p.getUniqueId();
        if (plugin.getStore().has(id) && !duels().inDuel(id) && !plugin.getKits().isEditing(id)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || duels().inDuel(id)) return;
                plugin.getStore().restore(p);
                plugin.runCommand(p, plugin.getConfig().getString("settings.spawn-command", "spawn"));
            }, 5L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        duels().handleQuit(e.getPlayer());
    }

    // ---------------- Daño: los duelos no matan ----------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Duel d = duels().getDuel(victim.getUniqueId());
        if (d == null) return;
        if (d.state != Duel.State.FIGHTING) {
            e.setCancelled(true);
            return;
        }
        UUID opp = d.other(victim.getUniqueId());
        Player att = (e instanceof EntityDamageByEntityEvent ev) ? attacker(ev) : null;
        if (att != null && !att.getUniqueId().equals(opp)) {
            e.setCancelled(true);
            return;
        }
        // Con tótem en mano, que actúe el tótem
        if (victim.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || victim.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;
        if (e.getFinalDamage() >= victim.getHealth()) {
            boolean kill = att != null && att.getUniqueId().equals(opp);
            e.setCancelled(true);
            duels().endDuel(d, opp, kill, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        Player att = attacker(e);
        if (att == null || !(e.getEntity() instanceof Player v)) return;
        Duel da = duels().getDuel(att.getUniqueId());
        if (da != null && !da.has(v.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent e) {
        Player att = attacker(e);
        if (att == null || !(e.getEntity() instanceof Player v) || att.equals(v)) return;
        if (duels().inDuel(att.getUniqueId()) || duels().inDuel(v.getUniqueId())) return;
        duels().markCombat(att.getUniqueId(), v.getUniqueId());
    }

    /** Respaldo: si por algún motivo el jugador muere dentro del duelo, no cuenta ni dropea nada. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Duel d = duels().getDuel(p.getUniqueId());
        if (d == null) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.setDeathMessage(null);
        if (d.state != Duel.State.ENDED) duels().endDuel(d, d.other(p.getUniqueId()), false, false);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.isDead()) p.spigot().respawn();
        });
    }

    // ---------------- Movimiento ----------------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Duel d = duels().getDuel(p.getUniqueId());
        if (d == null) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (d.state == Duel.State.COUNTDOWN) {
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                Location l = from.clone();
                l.setYaw(to.getYaw());
                l.setPitch(to.getPitch());
                e.setTo(l);
            }
            return;
        }
        if (d.state == Duel.State.FIGHTING && !plugin.getArenas().contains(d.arena, to)) {
            e.setCancelled(true);
            long now = System.currentTimeMillis();
            Long last = leaveWarn.get(p.getUniqueId());
            if (last == null || now - last > 1500L) {
                leaveWarn.put(p.getUniqueId(), now);
                plugin.send(p, "leave-arena");
            }
        }
    }

    // ---------------- Reglas del duelo ----------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (duels().getDuel(p.getUniqueId()) == null) return;
        if (p.hasPermission("zsduels.admin")) return;
        String msg = e.getMessage();
        if (msg.length() < 2) return;
        String cmd = msg.substring(1).split(" ")[0].toLowerCase();
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        List<String> allowed = plugin.getConfig().getStringList("settings.allowed-commands");
        for (String a : allowed) {
            if (a.equalsIgnoreCase(cmd)) return;
        }
        e.setCancelled(true);
        plugin.send(p, "command-blocked");
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl)) return;
        if (!plugin.getConfig().getBoolean("settings.disable-ender-pearls", true)) return;
        if (e.getEntity().getShooter() instanceof Player p && duels().inDuel(p.getUniqueId())) {
            e.setCancelled(true);
            plugin.send(p, "pearl-blocked");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!duels().inDuel(e.getPlayer().getUniqueId())) return;
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || c == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!plugin.getConfig().getBoolean("settings.block-break-place", false)
                && duels().inDuel(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!plugin.getConfig().getBoolean("settings.block-break-place", false)
                && duels().inDuel(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (plugin.getConfig().getBoolean("settings.disable-item-drop", true)
                && duels().inDuel(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p
                && plugin.getConfig().getBoolean("settings.disable-item-pickup", true)
                && duels().inDuel(p.getUniqueId())) e.setCancelled(true);
    }
          }
