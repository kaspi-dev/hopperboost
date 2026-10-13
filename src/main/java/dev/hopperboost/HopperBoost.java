package dev.hopperboost;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HopperBoost extends JavaPlugin implements Listener, TabExecutor {

    /** Vanilla hopper: 1 item every 8 ticks. */
    private static final int COOLDOWN_TICKS = 8;
    private static final double VANILLA_RATE = 1.0 / COOLDOWN_TICKS;
    /** Max items per tick you can set (8/tick = 64 items per hopper cycle). */
    private static final double MAX_RATE = 8.0;

    private static final Set<InventoryType> SAFE_TYPES = EnumSet.of(
            InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX,
            InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER);

    /** chunk key -> items per tick */
    private final Map<String, Double> rates = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String line : getConfig().getStringList("boosted")) {
            String[] p = line.split(";");
            if (p.length != 4) continue;
            try {
                rates.put(p[0] + ";" + p[1] + ";" + p[2], Double.parseDouble(p[3]));
            } catch (NumberFormatException ignored) {
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("hopperspeed");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    private void save() {
        List<String> out = new ArrayList<>();
        rates.forEach((k, v) -> out.add(k + ";" + v));
        getConfig().set("boosted", out);
        saveConfig();
    }

    private static String key(String world, int cx, int cz) {
        return world + ";" + cx + ";" + cz;
    }

    private static int itemsPerCycle(double rate) {
        return Math.max(1, (int) Math.round(rate * COOLDOWN_TICKS));
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (rates.isEmpty()) return;
        Location loc = e.getInitiator().getLocation();
        if (loc == null || loc.getWorld() == null) return;

        Double rate = rates.get(key(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
        if (rate == null) return;

        int extra = itemsPerCycle(rate) - 1;
        if (extra <= 0) return;

        Inventory src = e.getSource();
        Inventory dst = e.getDestination();
        if (!SAFE_TYPES.contains(src.getType()) || !SAFE_TYPES.contains(dst.getType())) return;

        // Do the bonus transfers next tick so we never edit inventories mid-event.
        getServer().getScheduler().runTask(this, () -> {
            for (int i = 0; i < extra; i++) {
                if (!moveOne(src, dst)) break;
            }
        });
    }

    private boolean moveOne(Inventory src, Inventory dst) {
        for (int slot = 0; slot < src.getSize(); slot++) {
            ItemStack stack = src.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;

            ItemStack one = stack.clone();
            one.setAmount(1);
            if (!dst.addItem(one).isEmpty()) continue; // doesn't fit, try next slot

            int left = stack.getAmount() - 1;
            if (left > 0) {
                ItemStack rest = stack.clone();
                rest.setAmount(left);
                src.setItem(slot, rest);
            } else {
                src.setItem(slot, null);
            }
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this in-game, it uses the chunk you're standing in.");
            return true;
        }
        Chunk chunk = player.getLocation().getChunk();
        String k = key(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());

        if (args.length == 0) {
            player.sendMessage("Usage: /hopperspeed <set <itemsPerTick>|get|reset|list>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set" -> {
                if (args.length < 2) {
                    player.sendMessage("Usage: /hopperspeed set <itemsPerTick>  (vanilla = 0.125, max " + MAX_RATE + ")");
                    return true;
                }
                double rate;
                try {
                    rate = Double.parseDouble(args[1]);
                } catch (NumberFormatException ex) {
                    player.sendMessage("That's not a number.");
                    return true;
                }
                if (rate <= VANILLA_RATE) {
                    rates.remove(k);
                    save();
                    player.sendMessage("Rate is at or below vanilla (0.125/tick), so this chunk is back to normal.");
                    return true;
                }
                rate = Math.min(rate, MAX_RATE);
                rates.put(k, rate);
                save();
                player.sendMessage("Hoppers in chunk " + chunk.getX() + ", " + chunk.getZ() + " now move "
                        + rate + " items/tick (" + itemsPerCycle(rate) + " items every " + COOLDOWN_TICKS + " ticks).");
            }
            case "get" -> {
                Double rate = rates.get(k);
                if (rate == null) {
                    player.sendMessage("This chunk is vanilla: 0.125 items/tick (1 item every 8 ticks).");
                } else {
                    player.sendMessage("This chunk: " + rate + " items/tick (" + itemsPerCycle(rate)
                            + " items every " + COOLDOWN_TICKS + " ticks).");
                }
            }
            case "reset" -> {
                rates.remove(k);
                save();
                player.sendMessage("This chunk is back to vanilla hopper speed.");
            }
            case "list" -> {
                if (rates.isEmpty()) {
                    player.sendMessage("No boosted chunks.");
                } else {
                    rates.forEach((key, v) -> player.sendMessage(key.replace(';', ' ') + " -> " + v + "/tick"));
                }
            }
            default -> player.sendMessage("Usage: /hopperspeed <set <itemsPerTick>|get|reset|list>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("set", "get", "reset", "list");
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) return List.of("0.5", "1", "2", "4", "8");
        return List.of();
    }
}
