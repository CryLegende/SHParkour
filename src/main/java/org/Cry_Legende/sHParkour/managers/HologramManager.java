package org.Cry_Legende.sHParkour.managers;

import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color; // <<<< NOUVEL IMPORT
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

public class HologramManager {

    private final SHParkour plugin;
    private final de.oliver.fancyholograms.api.HologramManager hologramManagerAPI;

    public HologramManager(SHParkour plugin) {
        this.plugin = plugin;
        this.hologramManagerAPI = de.oliver.fancyholograms.api.FancyHologramsPlugin.get().getHologramManager();
    }

    public void deleteHolograms(Parkour parkour) {
        String prefix = "shparkour_" + parkour.getInternalName();
        deleteHologramByName(prefix + "_start");
        deleteHologramByName(prefix + "_end");
        for (int i = 0; i < parkour.getCheckpointCount(); i++) {
            deleteHologramByName(prefix + "_checkpoint_" + i);
        }
    }

    public void deleteCheckpointHologram(Parkour parkour, int checkpointIndex) {
        String holoName = "shparkour_" + parkour.getInternalName() + "_checkpoint_" + checkpointIndex;
        deleteHologramByName(holoName);
    }

    private void deleteHologramByName(String name) {
        hologramManagerAPI.getHologram(name).ifPresent(hologramManagerAPI::removeHologram);
    }

    public void createSpecificHologram(Parkour parkour, String type) {
        ConfigurationSection holoConfig = parkour.getConfig().getConfigurationSection("holograms");
        if (holoConfig == null) return;

        String holoName;
        Location baseLocation = null;
        double offsetY = 1.5;
        List<String> lines = null;
        int[] args = new int[0];

        if (type.equals("start")) {
            if (!holoConfig.getBoolean("start.enabled")) return;
            baseLocation = parkour.getStart();
            offsetY = holoConfig.getDouble("start.offset-y", 1.5);
            lines = holoConfig.getStringList("start.lines");
        } else if (type.equals("end")) {
            if (!holoConfig.getBoolean("end.enabled")) return;
            baseLocation = parkour.getEnd();
            offsetY = holoConfig.getDouble("end.offset-y", 1.5);
            lines = holoConfig.getStringList("end.lines");
        } else if (type.startsWith("checkpoint_")) {
            if (!holoConfig.getBoolean("checkpoint.enabled")) return;
            try {
                int index = Integer.parseInt(type.split("_")[1]);
                baseLocation = parkour.getCheckpoint(index);
                offsetY = holoConfig.getDouble("checkpoint.offset-y", 1.5);
                lines = holoConfig.getStringList("checkpoint.lines");
                args = new int[]{index + 1};
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                return;
            }
        }

        if (baseLocation == null || lines == null || lines.isEmpty()) {
            return;
        }

        holoName = "shparkour_" + parkour.getInternalName() + "_" + type;

        final Location finalBaseLocation = baseLocation;
        final double finalOffsetY = offsetY;
        final List<String> finalList = lines;
        final String finalHoloName = holoName;
        final int[] finalArgs = args;

        Bukkit.getScheduler().runTask(plugin, () -> createHologram(finalHoloName, finalBaseLocation, finalOffsetY, finalList, parkour, finalArgs));
    }

    private void createHologram(String name, Location baseLocation, double offsetY, List<String> lines, Parkour parkour, int... args) {
        if (hologramManagerAPI.getHologram(name).isPresent()) return;

        Location holoLocation = baseLocation.getBlock().getLocation().clone().add(0.5, offsetY, 0.5);
        TextHologramData data = new TextHologramData(name, holoLocation);

        data.setTextShadow(true);
        data.setBillboard(Display.Billboard.VERTICAL);
        // <<<< CORRECTION: On définit une couleur explicitement transparente >>>>
        data.setBackground(Color.fromARGB(0, 0, 0, 0));

        List<String> processedLines = lines.stream()
            .map(line -> ChatColor.translateAlternateColorCodes('&', line))
            .map(line -> line.replace("{parkour_display_name}", parkour.getDisplayName()).replace("{parkour_name}", parkour.getInternalName()))
            .map(line -> (args.length > 0) ? line.replace("{checkpoint_index}", String.valueOf(args[0])) : line)
            .toList();

        data.setText(processedLines);
        data.setPersistent(true);

        Hologram hologramObject;
        try {
            hologramObject = hologramManagerAPI.create(data);

            if (hologramObject != null) {
                hologramManagerAPI.addHologram(hologramObject);
                plugin.getLogger().info(ChatColor.GREEN + "Hologramme '" + name + "' créé et ajouté au manager avec succès.");
            } else {
                plugin.getLogger().log(Level.SEVERE, "L'API FancyHolograms a retourné 'null' lors de la création de l'hologramme '" + name + "'.");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Une exception est survenue lors de la création de l'hologramme '" + name + "': " + e.getMessage());
            e.printStackTrace();
        }
    }
}
