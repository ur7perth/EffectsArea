package com.effectsarea;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.command.TabCompleter;

import java.util.*;
import java.util.stream.Collectors;

public class EffectsArea extends JavaPlugin implements Listener, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[EffectsArea] " + ChatColor.RESET;

    // Infinite effect marker duration. Effects given by this plugin use durations
    // between 10 and 15 seconds (200-300 ticks) and are refreshed constantly, so they never run out.
    private static final int MIN_TICKS = 10 * 20;  // 10 seconds
    private static final int MAX_TICKS = 15 * 20;  // 15 seconds

    private AreaManager areaManager;
    private NamespacedKey wandKey;

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    // name chosen by /effectarea waiting for /addeffect
    private final Map<UUID, String> lastAreaName = new HashMap<>();
    // effects currently applied by this plugin per player: player -> effect types
    private final Map<UUID, Set<PotionEffectType>> applied = new HashMap<>();

    @Override
    public void onEnable() {
        wandKey = new NamespacedKey(this, "effect_wand");
        areaManager = new AreaManager(this);
        areaManager.load();

        Bukkit.getPluginManager().registerEvents(this, this);

        for (String cmd : new String[]{"effectwand", "effectarea", "addeffect", "removeeffect"}) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
                getCommand(cmd).setTabCompleter(this);
            }
        }

        // Check players every 5 ticks. Entering gives the effect, leaving removes it instantly.
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(this, 5L, 5L);

        getLogger().info("EffectsArea enabled.");
    }

    @Override
    public void onDisable() {
        // Remove all effects applied by this plugin
        for (Player p : Bukkit.getOnlinePlayers()) {
            clearApplied(p);
        }
        areaManager.save();
    }

    // ------------------------------------------------------------------
    // Effect logic
    // ------------------------------------------------------------------

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<PotionEffectType, Integer> wanted = new HashMap<>();

            for (Area area : areaManager.getAreas()) {
                if (area.getEffects().isEmpty()) continue;
                if (!area.contains(player.getLocation())) continue;

                for (Map.Entry<String, Integer> e : area.getEffects().entrySet()) {
                    PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(e.getKey()));
                    if (type == null) continue;
                    int amp = Math.max(0, e.getValue() - 1);
                    wanted.merge(type, amp, Math::max);
                }
            }

            Set<PotionEffectType> current = applied.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());

            // Remove effects the player no longer qualifies for (left the area)
            Iterator<PotionEffectType> it = current.iterator();
            while (it.hasNext()) {
                PotionEffectType type = it.next();
                if (!wanted.containsKey(type)) {
                    player.removePotionEffect(type);
                    it.remove();
                }
            }

            // Apply / refresh wanted effects
            for (Map.Entry<PotionEffectType, Integer> e : wanted.entrySet()) {
                PotionEffect existing = player.getPotionEffect(e.getKey());
                boolean needsRefresh = existing == null
                        || existing.getAmplifier() != e.getValue()
                        || existing.getDuration() <= MIN_TICKS;
                if (needsRefresh) {
                    // Duration is set to 15s; it is topped up again when it drops to 10s,
                    // so it never expires while the player stays inside.
                    player.addPotionEffect(new PotionEffect(
                            e.getKey(), MAX_TICKS, e.getValue(), false, false, true));
                }
                current.add(e.getKey());
            }
        }
    }

    private void clearApplied(Player player) {
        Set<PotionEffectType> current = applied.remove(player.getUniqueId());
        if (current != null) {
            for (PotionEffectType type : current) {
                player.removePotionEffect(type);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearApplied(event.getPlayer());
        pos1.remove(event.getPlayer().getUniqueId());
        pos2.remove(event.getPlayer().getUniqueId());
        lastAreaName.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Wand
    // ------------------------------------------------------------------

    private ItemStack createWand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Effect Wand");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Left click a block: Position 1",
                ChatColor.GRAY + "Right click a block: Position 2"
        ));
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getClickedBlock() == null) return;
        if (!isWand(event.getItem())) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("effectsarea.admin")) return;

        Location loc = event.getClickedBlock().getLocation();

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            pos1.put(player.getUniqueId(), loc);
            player.sendMessage(PREFIX + ChatColor.GREEN + "Position 1 set to "
                    + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            pos2.put(player.getUniqueId(), loc);
            player.sendMessage(PREFIX + ChatColor.GREEN + "Position 2 set to "
                    + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        // Prevent breaking blocks with the wand (creative mode)
        if (isWand(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (!sender.hasPermission("effectsarea.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission.");
            return true;
        }

        switch (cmd) {
            case "effectwand":
                return cmdWand(sender);
            case "effectarea":
                return cmdArea(sender, args);
            case "addeffect":
                return cmdAddEffect(sender, args);
            case "removeeffect":
                return cmdRemoveEffect(sender, args);
            default:
                return false;
        }
    }

    private boolean cmdWand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;
        player.getInventory().addItem(createWand());
        player.sendMessage(PREFIX + ChatColor.GREEN + "You received the Effect Wand.");
        player.sendMessage(ChatColor.GRAY + "Left click = Position 1, Right click = Position 2.");
        return true;
    }

    private boolean cmdArea(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;

        if (args.length != 1) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /effectarea <name>");
            return true;
        }

        Location a = pos1.get(player.getUniqueId());
        Location b = pos2.get(player.getUniqueId());
        if (a == null || b == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "You must set Position 1 and Position 2 with the wand first.");
            return true;
        }
        if (a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            player.sendMessage(PREFIX + ChatColor.RED + "Both positions must be in the same world.");
            return true;
        }

        String name = args[0];
        if (areaManager.getArea(name) != null) {
            player.sendMessage(PREFIX + ChatColor.RED + "An area with this name already exists.");
            return true;
        }

        Area area = new Area(name, a.getWorld().getName(), a, b);
        areaManager.addArea(area);
        lastAreaName.put(player.getUniqueId(), name);

        player.sendMessage(PREFIX + ChatColor.GREEN + "Area '" + name + "' created.");
        player.sendMessage(ChatColor.GRAY + "Now use /addeffect to see your areas and add effects.");
        return true;
    }

    private boolean cmdAddEffect(CommandSender sender, String[] args) {
        // /addeffect -> list areas
        if (args.length == 0) {
            if (areaManager.getAreas().isEmpty()) {
                sender.sendMessage(PREFIX + ChatColor.RED + "No areas created yet. Use /effectarea <name>.");
                return true;
            }
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Your areas:");
            for (Area area : areaManager.getAreas()) {
                sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + area.getName()
                        + ChatColor.DARK_GRAY + " (" + area.getEffects().size() + " effects)");
            }
            sender.sendMessage(ChatColor.GRAY + "Use: /addeffect <area> to see effects, or /addeffect <area> <effect> <level>");
            return true;
        }

        Area area = areaManager.getArea(args[0]);
        if (area == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Area '" + args[0] + "' does not exist.");
            return true;
        }

        // /addeffect <area> -> list all effects as commands
        if (args.length == 1) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Available effects for area '" + area.getName() + "':");
            List<String> names = getEffectNames();
            sender.sendMessage(ChatColor.WHITE + String.join(ChatColor.GRAY + ", " + ChatColor.WHITE, names));
            sender.sendMessage(ChatColor.GRAY + "Usage: /addeffect " + area.getName() + " <effect> <level>");
            sender.sendMessage(ChatColor.GRAY + "Example: /addeffect " + area.getName() + " strength 1");
            return true;
        }

        // /addeffect <area> <effect> <level>
        String effectName = args[1].toLowerCase();
        PotionEffectType type = resolveEffect(effectName);
        if (type == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Unknown effect '" + args[1] + "'.");
            sender.sendMessage(ChatColor.GRAY + "Use /addeffect " + area.getName() + " to see all effects.");
            return true;
        }

        int level = 1;
        if (args.length >= 3) {
            try {
                level = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Level must be a number.");
                return true;
            }
        }
        if (level < 1 || level > 255) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Level must be between 1 and 255.");
            return true;
        }

        area.getEffects().put(type.getKey().getKey(), level);
        areaManager.save();
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Added effect " + type.getKey().getKey()
                + " " + level + " to area '" + area.getName() + "'.");
        return true;
    }

    private boolean cmdRemoveEffect(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /removeeffect <name>");
            return true;
        }
        Area area = areaManager.getArea(args[0]);
        if (area == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Area '" + args[0] + "' does not exist.");
            return true;
        }
        areaManager.removeArea(area.getName());
        // effects are cleaned up automatically on the next tick
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Area '" + area.getName() + "' removed.");
        return true;
    }

    // ------------------------------------------------------------------
    // Effect name helpers (friendly names -> vanilla effect types)
    // ------------------------------------------------------------------

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("speed", "speed");
        ALIASES.put("slowness", "slowness");
        ALIASES.put("haste", "haste");
        ALIASES.put("mining_fatigue", "mining_fatigue");
        ALIASES.put("strength", "strength");
        ALIASES.put("instant_health", "instant_health");
        ALIASES.put("instant_damage", "instant_damage");
        ALIASES.put("jump_boost", "jump_boost");
        ALIASES.put("nausea", "nausea");
        ALIASES.put("regeneration", "regeneration");
        ALIASES.put("resistance", "resistance");
        ALIASES.put("fire_resistance", "fire_resistance");
        ALIASES.put("water_breathing", "water_breathing");
        ALIASES.put("invisibility", "invisibility");
        ALIASES.put("blindness", "blindness");
        ALIASES.put("night_vision", "night_vision");
        ALIASES.put("hunger", "hunger");
        ALIASES.put("weakness", "weakness");
        ALIASES.put("poison", "poison");
        ALIASES.put("wither", "wither");
        ALIASES.put("health_boost", "health_boost");
        ALIASES.put("absorption", "absorption");
        ALIASES.put("saturation", "saturation");
        ALIASES.put("glowing", "glowing");
        ALIASES.put("levitation", "levitation");
        ALIASES.put("luck", "luck");
        ALIASES.put("unluck", "unluck");
        ALIASES.put("slow_falling", "slow_falling");
        ALIASES.put("conduit_power", "conduit_power");
        ALIASES.put("dolphins_grace", "dolphins_grace");
        ALIASES.put("bad_omen", "bad_omen");
        ALIASES.put("hero_of_the_village", "hero_of_the_village");
        ALIASES.put("darkness", "darkness");
    }

    private List<String> getEffectNames() {
        return new ArrayList<>(ALIASES.keySet());
    }

    private PotionEffectType resolveEffect(String input) {
        String key = ALIASES.get(input);
        if (key == null) {
            // fallback to the raw vanilla key
            key = input;
        }
        return PotionEffectType.getByKey(NamespacedKey.minecraft(key));
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase();
        List<String> result = new ArrayList<>();

        if (cmd.equals("addeffect")) {
            if (args.length == 1) {
                for (Area a : areaManager.getAreas()) result.add(a.getName());
            } else if (args.length == 2) {
                result.addAll(getEffectNames());
            } else if (args.length == 3) {
                result.addAll(Arrays.asList("1", "2", "3", "4", "5"));
            }
        } else if (cmd.equals("removeeffect")) {
            if (args.length == 1) {
                for (Area a : areaManager.getAreas()) result.add(a.getName());
            }
        }

        String last = args.length > 0 ? args[args.length - 1].toLowerCase() : "";
        return result.stream()
                .filter(s -> s.toLowerCase().startsWith(last))
                .collect(Collectors.toList());
    }
}
