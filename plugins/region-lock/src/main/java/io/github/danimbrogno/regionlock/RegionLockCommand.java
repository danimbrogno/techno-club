package io.github.danimbrogno.regionlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class RegionLockCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "regionlock.toggle";

    private final RegionLockPlugin plugin;

    public RegionLockCommand(RegionLockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("You do not have permission to use RegionLock commands.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "on" -> handleOn(sender, args);
            case "off" -> handleOff(sender, args);
            case "toggle" -> handleToggle(sender, args);
            case "status" -> {
                sendStatus(sender);
                yield true;
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("RegionLock config reloaded. Global: "
                        + (plugin.isProtectionActive() ? "ON" : "OFF")
                        + ", zones: " + plugin.zones().zones().size());
                yield true;
            }
            default -> {
                sendUsage(sender, label);
                yield true;
            }
        };
    }

    private boolean handleOn(CommandSender sender, String[] args) {
        if (args.length == 1) {
            plugin.setProtectionActive(true);
            sender.sendMessage("RegionLock global protection is now ON.");
            return true;
        }
        return setZoneEnabled(sender, args[1], true);
    }

    private boolean handleOff(CommandSender sender, String[] args) {
        if (args.length == 1) {
            plugin.setProtectionActive(false);
            sender.sendMessage("RegionLock global protection is now OFF.");
            return true;
        }
        return setZoneEnabled(sender, args[1], false);
    }

    private boolean handleToggle(CommandSender sender, String[] args) {
        if (args.length == 1) {
            boolean next = !plugin.isProtectionActive();
            plugin.setProtectionActive(next);
            sender.sendMessage("RegionLock global protection is now " + (next ? "ON" : "OFF") + ".");
            return true;
        }
        Optional<LockedZone> zone = plugin.zones().findByName(args[1]);
        if (zone.isEmpty()) {
            sender.sendMessage("Unknown zone: " + args[1]);
            return true;
        }
        boolean next = !zone.get().enabled();
        return setZoneEnabled(sender, args[1], next);
    }

    private boolean setZoneEnabled(CommandSender sender, String zoneName, boolean enabled) {
        Optional<LockedZone> zone = plugin.zones().findByName(zoneName);
        if (zone.isEmpty()) {
            sender.sendMessage("Unknown zone: " + zoneName);
            return true;
        }
        plugin.setZoneEnabled(zone.get().name(), enabled);
        sender.sendMessage("Zone '" + zone.get().name() + "' is now " + (enabled ? "ON" : "OFF") + ".");
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("RegionLock global: " + (plugin.isProtectionActive() ? "ON" : "OFF"));
        List<LockedZone> zones = plugin.zones().zones();
        if (zones.isEmpty()) {
            sender.sendMessage("No zones loaded.");
            return;
        }
        for (LockedZone zone : zones) {
            sender.sendMessage("- " + zone.name() + ": " + (zone.enabled() ? "ON" : "OFF"));
        }
    }

    private static void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("Usage:");
        sender.sendMessage("/" + label + " on|off|toggle [zone]");
        sender.sendMessage("/" + label + " status");
        sender.sendMessage("/" + label + " reload");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("on", "off", "toggle", "status", "reload"), args[0]);
        }
        if (args.length == 2 && List.of("on", "off", "toggle").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            for (LockedZone zone : plugin.zones().zones()) {
                names.add(zone.name());
            }
            return filter(names, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}
