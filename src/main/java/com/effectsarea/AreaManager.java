package com.effectsarea;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class AreaManager {

    private final EffectsArea plugin;
    private final File file;
    private final Map<String, Area> areas = new LinkedHashMap<>();

    public AreaManager(EffectsArea plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "areas.yml");
    }

    public Collection<Area> getAreas() {
        return areas.values();
    }

    public Area getArea(String name) {
        return areas.get(name.toLowerCase());
    }

    public void addArea(Area area) {
        areas.put(area.getName().toLowerCase(), area);
        save();
    }

    public boolean removeArea(String name) {
        Area removed = areas.remove(name.toLowerCase());
        if (removed != null) {
            save();
            return true;
        }
        return false;
    }

    public void load() {
        areas.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("areas");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            Area area = new Area(
                    s.getString("name", key),
                    s.getString("world", "world"),
                    s.getInt("minX"), s.getInt("minY"), s.getInt("minZ"),
                    s.getInt("maxX"), s.getInt("maxY"), s.getInt("maxZ")
            );
            ConfigurationSection eff = s.getConfigurationSection("effects");
            if (eff != null) {
                for (String e : eff.getKeys(false)) {
                    area.getEffects().put(e, eff.getInt(e));
                }
            }
            areas.put(key.toLowerCase(), area);
        }
    }

    public void save() {
        FileConfiguration cfg = new YamlConfiguration();
        for (Area a : areas.values()) {
            String p = "areas." + a.getName().toLowerCase();
            cfg.set(p + ".name", a.getName());
            cfg.set(p + ".world", a.getWorld());
            cfg.set(p + ".minX", a.getMinX());
            cfg.set(p + ".minY", a.getMinY());
            cfg.set(p + ".minZ", a.getMinZ());
            cfg.set(p + ".maxX", a.getMaxX());
            cfg.set(p + ".maxY", a.getMaxY());
            cfg.set(p + ".maxZ", a.getMaxZ());
            for (Map.Entry<String, Integer> e : a.getEffects().entrySet()) {
                cfg.set(p + ".effects." + e.getKey(), e.getValue());
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save areas.yml: " + ex.getMessage());
        }
    }
}
