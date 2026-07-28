package io.github.danimbrogno.lessonportal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.Component;

public final class PortalListener implements Listener {

    private static final long DENY_MESSAGE_COOLDOWN_MILLIS = 2000L;
    private static final String TELEPORT_FAILED_MESSAGE = "Teleport failed, please try again.";

    private final LessonPortalPlugin plugin;
    private final Map<UUID, Long> lastDenyMessageAt = new ConcurrentHashMap<>();

    public PortalListener(LessonPortalPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || !changedBlockPosition(from, to)) {
            return;
        }

        Optional<BoundBox> portal = plugin.layout().portal();
        if (portal.isEmpty()
                || !portal.get().contains(to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
            return;
        }

        Player player = event.getPlayer();
        TeleportDecision decision = TeleportGate.decide(
                plugin.store().current(),
                plugin.catalog(),
                worldName -> Bukkit.getWorld(worldName) != null);

        switch (decision) {
            case TeleportDecision.Allow allow -> teleport(player, allow.lesson());
            case TeleportDecision.Deny deny -> denyWithCooldown(player, deny.message());
        }
    }

    private void teleport(Player player, LessonDefinition lesson) {
        Destination destination = lesson.destination();
        World world = Bukkit.getWorld(destination.world());
        if (world == null) {
            denyWithCooldown(
                    player, TeleportGate.apply(plugin.catalog().message("world-missing"), "world", destination.world()));
            return;
        }

        Location location = new Location(
                world, destination.x(), destination.y(), destination.z(), destination.yaw(), destination.pitch());
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        if (!player.teleport(location)) {
            denyWithCooldown(player, TELEPORT_FAILED_MESSAGE);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastDenyMessageAt.remove(event.getPlayer().getUniqueId());
    }

    private void denyWithCooldown(Player player, String message) {
        long now = System.currentTimeMillis();
        Long lastSentAt = lastDenyMessageAt.get(player.getUniqueId());
        if (lastSentAt != null && now - lastSentAt < DENY_MESSAGE_COOLDOWN_MILLIS) {
            return;
        }
        lastDenyMessageAt.put(player.getUniqueId(), now);
        player.sendMessage(Component.text(message));
    }

    private static boolean changedBlockPosition(Location from, Location to) {
        return from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()
                || !worldsEqual(from.getWorld(), to.getWorld());
    }

    private static boolean worldsEqual(World a, World b) {
        return a != null && a.equals(b);
    }
}
