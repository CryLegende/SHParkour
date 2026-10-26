package org.Cry_Legende.sHParkour.objects;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.utils.LocationUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Parkour {

    private final SHParkour plugin;
    private final String internalName;
    private String displayName;
    private Location start;
    private Location end;
    private Location respawn;
    private Location endSpawn;
    private List<Location> checkpoints;
    private Material startPlate;
    private Material checkpointPlate;
    private Material endPlate;
    private long maxTime; // en millisecondes

    public Parkour(SHParkour plugin, String internalName) {
        this.plugin = plugin;
        this.internalName = internalName;
        this.checkpoints = new ArrayList<>();
        load();
    }

    public void load() {
        FileConfiguration config = plugin.getConfigManager().getParkourConfig(internalName);
        if (config == null) return;

        this.displayName = config.getString("display-name", internalName);
        this.start = LocationUtil.deserialize(config.getString("start"));
        this.end = LocationUtil.deserialize(config.getString("end"));
        this.respawn = LocationUtil.deserialize(config.getString("respawn"));
        this.endSpawn = LocationUtil.deserialize(config.getString("end-spawn"));

        this.startPlate = getMaterialFromString(config.getString("plates.start", "HEAVY_WEIGHTED_PRESSURE_PLATE"), "HEAVY_WEIGHTED_PRESSURE_PLATE");
        this.checkpointPlate = getMaterialFromString(config.getString("plates.checkpoint", "STONE_PRESSURE_PLATE"), "STONE_PRESSURE_PLATE");
        this.endPlate = getMaterialFromString(config.getString("plates.end", "LIGHT_WEIGHTED_PRESSURE_PLATE"), "LIGHT_WEIGHTED_PRESSURE_PLATE");

        int maxTimeSeconds = config.getInt("max-time", 0);
        this.maxTime = (maxTimeSeconds > 0) ? maxTimeSeconds * 1000L : 0;

        this.checkpoints.clear();
        List<String> checkpointStrings = config.getStringList("checkpoints");
        for (String s : checkpointStrings) {
            Location loc = LocationUtil.deserialize(s);
            if (loc != null) {
                this.checkpoints.add(loc);
            }
        }
    }

    public void save() {
        File parkourFile = plugin.getConfigManager().getParkourFile(internalName);
        FileConfiguration config = YamlConfiguration.loadConfiguration(parkourFile);

        config.set("display-name", displayName);
        config.set("start", LocationUtil.serialize(start));
        config.set("end", LocationUtil.serialize(end));
        config.set("respawn", LocationUtil.serialize(respawn));
        config.set("end-spawn", LocationUtil.serialize(endSpawn));
        config.set("checkpoints", checkpoints.stream().map(LocationUtil::serialize).collect(Collectors.toList()));

        try {
            config.save(parkourFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder le parcours " + internalName + ": " + e.getMessage());
        }
    }

    private Material getMaterialFromString(String materialName, String defaultMaterial) {
        Material mat = Material.getMaterial(materialName.toUpperCase());
        if (mat == null || !mat.name().endsWith("_PRESSURE_PLATE")) {
            plugin.getLogger().warning("Matériau invalide ou n'est pas une plaque de pression '" + materialName + "' pour le parcours " + internalName + ". Utilisation du défaut.");
            return Material.getMaterial(defaultMaterial);
        }
        return mat;
    }

    // Getters
    public FileConfiguration getConfig() {
        return plugin.getConfigManager().getParkourConfig(internalName);
    }

    public String getInternalName() {
        return internalName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Location getStart() {
        return start;
    }

    public Location getEnd() {
        return end;
    }

    public Location getRespawn() {
        return respawn;
    }

    public Location getEndSpawn() {
        return endSpawn;
    }

    public List<Location> getCheckpoints() {
        return checkpoints;
    }

    public int getCheckpointCount() {
        return checkpoints.size();
    }

    public Location getCheckpoint(int index) {
        if (index >= 0 && index < checkpoints.size()) {
            return checkpoints.get(index);
        }
        return null;
    }

    public Material getStartPlate() {
        return startPlate;
    }

    public Material getCheckpointPlate() {
        return checkpointPlate;
    }

    public Material getEndPlate() {
        return endPlate;
    }

    public long getMaxTime() {
        return maxTime;
    }

    /** Vrai si une des positions du parcours se trouve dans ce monde. */
    public boolean usesWorld(World world) {
        List<Location> locations = new ArrayList<>(checkpoints);
        locations.add(start);
        locations.add(end);
        locations.add(respawn);
        locations.add(endSpawn);
        for (Location location : locations) {
            if (location != null && world.equals(location.getWorld())) {
                return true;
            }
        }
        return false;
    }

    // Setters
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        save();
    }

    public void setStart(Location start) {
        this.start = start;
        save();
    }

    public void setEnd(Location end) {
        this.end = end;
        save();
    }

    public void setRespawn(Location respawn) {
        this.respawn = respawn;
        save();
    }

    public void setEndSpawn(Location endSpawn) {
        this.endSpawn = endSpawn;
        save();
    }

    public int addCheckpoint(Location checkpoint) {
        this.checkpoints.add(checkpoint);
        save();
        return this.checkpoints.size();
    }

    public boolean editCheckpoint(int index, Location checkpoint) {
        if (index < 0 || index >= this.checkpoints.size()) {
            return false;
        }
        this.checkpoints.set(index, checkpoint);
        save();
        return true;
    }

    public boolean removeCheckpoint(int index) {
        if (index < 0 || index >= this.checkpoints.size()) {
            return false;
        }
        this.checkpoints.remove(index);
        save();
        return true;
    }
}
