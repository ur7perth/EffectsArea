package com.effectsarea;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;

public class Area {

    private final String name;
    private final String world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    // effect key (e.g. "increase_damage") -> level (amplifier + 1)
    private final Map<String, Integer> effects = new LinkedHashMap<>();

    public Area(String name, String world, Location a, Location b) {
        this.name = name;
        this.world = world;
        this.minX = Math.min(a.getBlockX(), b.getBlockX());
        this.minY = Math.min(a.getBlockY(), b.getBlockY());
        this.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        this.maxX = Math.max(a.getBlockX(), b.getBlockX());
        this.maxY = Math.max(a.getBlockY(), b.getBlockY());
        this.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
    }

    public Area(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.name = name;
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public String getName() {
        return name;
    }

    public String getWorld() {
        return world;
    }

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    public Map<String, Integer> getEffects() {
        return effects;
    }

    /**
     * Checks whether a location is inside this area.
     * The player's body box is considered, so touching the edge counts.
     */
    public boolean contains(Location loc) {
        World w = loc.getWorld();
        if (w == null || !w.getName().equals(world)) {
            return false;
        }
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        // Region covers full blocks: [min, max + 1)
        return x >= minX && x < maxX + 1
                && y >= minY && y < maxY + 1
                && z >= minZ && z < maxZ + 1;
    }
}
