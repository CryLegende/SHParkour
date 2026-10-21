package org.Cry_Legende.sHParkour.managers;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.scheduler.BukkitRunnable;

public class BlockManager {

    private final SHParkour plugin;

    public BlockManager(SHParkour plugin) {
        this.plugin = plugin;
    }

    public void placeParkourBlocks(Parkour parkour) {
        if (parkour.getStart() != null && parkour.getStartPlate() != null) {
            parkour.getStart().getBlock().setType(parkour.getStartPlate());
        }
        if (parkour.getEnd() != null && parkour.getEndPlate() != null) {
            parkour.getEnd().getBlock().setType(parkour.getEndPlate());
        }
        for (Location checkpointLoc : parkour.getCheckpoints()) {
            if (checkpointLoc != null && parkour.getCheckpointPlate() != null) {
                checkpointLoc.getBlock().setType(parkour.getCheckpointPlate());
            }
        }
    }

    public void removeParkourBlocks(Parkour parkour) {
        if (parkour.getStart() != null && parkour.getStart().getBlock().getType() == parkour.getStartPlate()) {
            parkour.getStart().getBlock().setType(Material.AIR);
        }
        if (parkour.getEnd() != null && parkour.getEnd().getBlock().getType() == parkour.getEndPlate()) {
            parkour.getEnd().getBlock().setType(Material.AIR);
        }
        for (Location checkpointLoc : parkour.getCheckpoints()) {
            if (checkpointLoc != null && checkpointLoc.getBlock().getType() == parkour.getCheckpointPlate()) {
                checkpointLoc.getBlock().setType(Material.AIR);
            }
        }
    }

    public void removeCheckpointPlate(Parkour parkour, Location checkpointLocation) {
        if (checkpointLocation == null || parkour.getCheckpointPlate() == null) {
            return;
        }
        if (checkpointLocation.getBlock().getType() == parkour.getCheckpointPlate()) {
            checkpointLocation.getBlock().setType(Material.AIR);
        }
    }

    public void startIntegrityCheck() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Parkour parkour : plugin.getParkourManager().getAllParkours()) {
                    placeParkourBlocks(parkour);
                }
            }
        }.runTaskTimer(plugin, 100L, 200L);
    }
}
