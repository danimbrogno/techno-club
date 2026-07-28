package io.github.danimbrogno.lessonportal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Per-player scratch pad for the two corner positions used by {@code /lessonportal sethub}
 * and {@code /lessonportal setportal}. Purely in-memory; never persisted.
 */
public final class SelectionSession implements Listener {

    private final Map<UUID, BlockPos> pos1 = new ConcurrentHashMap<>();
    private final Map<UUID, BlockPos> pos2 = new ConcurrentHashMap<>();

    public void setPos1(UUID player, BlockPos pos) {
        pos1.put(player, pos);
    }

    public void setPos2(UUID player, BlockPos pos) {
        pos2.put(player, pos);
    }

    public Optional<BlockPos> pos1(UUID player) {
        return Optional.ofNullable(pos1.get(player));
    }

    public Optional<BlockPos> pos2(UUID player) {
        return Optional.ofNullable(pos2.get(player));
    }

    public void clear(UUID player) {
        pos1.remove(player);
        pos2.remove(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }
}
