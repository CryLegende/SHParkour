package org.Cry_Legende.sHParkour.managers;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;

public class BlockManager {

    private final SHParkour plugin;

    public BlockManager(SHParkour plugin) {
        this.plugin = plugin;
    }

    public void placeParkourBlocks(Parkour parkour) {
        placeParkourBlocks(parkour, true);
    }

    /**
     * @param loadChunks false pour la vérification périodique : les chunks non chargés sont ignorés
     *                   au lieu d'être chargés de force toutes les 10 secondes.
     */
    private void placeParkourBlocks(Parkour parkour, boolean loadChunks) {
        placePlate(parkour.getStart(), parkour.getStartPlate(), loadChunks);
        placePlate(parkour.getEnd(), parkour.getEndPlate(), loadChunks);
        for (Location checkpointLoc : parkour.getCheckpoints()) {
            placePlate(checkpointLoc, parkour.getCheckpointPlate(), loadChunks);
        }
    }

    private void placePlate(Location location, Material plate, boolean loadChunks) {
        if (location == null || plate == null) {
            return;
        }
        // Monde absent : le parcours est remis en place au chargement du monde (ParkourManager).
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        if (!loadChunks && !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return;
        }
        Block block = location.getBlock();
        // Pas de setType si la plaque est déjà là : il remettrait à zéro une plaque enfoncée.
        if (block.getType() != plate) {
            block.setType(plate);
        }
    }

    public void removeParkourBlocks(Parkour parkour) {
        removePlate(parkour.getStart(), parkour.getStartPlate());
        removePlate(parkour.getEnd(), parkour.getEndPlate());
        for (Location checkpointLoc : parkour.getCheckpoints()) {
            removePlate(checkpointLoc, parkour.getCheckpointPlate());
        }
    }

    public void removeCheckpointPlate(Parkour parkour, Location checkpointLocation) {
        removePlate(checkpointLocation, parkour.getCheckpointPlate());
    }

    private void removePlate(Location location, Material plate) {
        if (location == null || plate == null || location.getWorld() == null) {
            return;
        }
        if (location.getBlock().getType() == plate) {
            location.getBlock().setType(Material.AIR);
        }
    }

    public void startIntegrityCheck() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Parkour parkour : plugin.getParkourManager().getAllParkours()) {
                    placeParkourBlocks(parkour, false);
                }
            }
        }.runTaskTimer(plugin, 100L, 200L);
    }
}
