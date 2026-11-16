package com.zsduels;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Duel {

    public enum State { COUNTDOWN, FIGHTING, ENDED }

    public final UUID p1, p2;
    public final String n1, n2;
    public final ArenaManager.Arena arena;
    public final KitManager.Kit kit;
    public final Set<UUID> spectators = new HashSet<>();
    public State state = State.COUNTDOWN;
    public long startMillis;
    public BukkitTask task;

    public Duel(Player a, Player b, ArenaManager.Arena arena, KitManager.Kit kit) {
        this.p1 = a.getUniqueId();
        this.p2 = b.getUniqueId();
        this.n1 = a.getName();
        this.n2 = b.getName();
        this.arena = arena;
        this.kit = kit;
    }

    public boolean has(UUID id) {
        return p1.equals(id) || p2.equals(id);
    }

    public UUID other(UUID id) {
        return p1.equals(id) ? p2 : p1;
    }

    public String name(UUID id) {
        return p1.equals(id) ? n1 : n2;
    }

    public long elapsedSeconds() {
        return startMillis == 0 ? 0 : (System.currentTimeMillis() - startMillis) / 1000;
    }
}
