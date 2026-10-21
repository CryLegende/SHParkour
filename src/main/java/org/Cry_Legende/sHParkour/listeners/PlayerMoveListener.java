package org.Cry_Legende.sHParkour.listeners;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.managers.ParkourManager;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.Cry_Legende.sHParkour.objects.ParkourSession;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class PlayerMoveListener implements Listener {

    private final SHParkour plugin;
    private final ParkourManager parkourManager;

    public PlayerMoveListener(SHParkour plugin) {
        this.plugin = plugin;
        this.parkourManager = plugin.getParkourManager();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();

        if (event.getFrom().getBlockX() == to.getBlockX() &&
            event.getFrom().getBlockY() == to.getBlockY() &&
            event.getFrom().getBlockZ() == to.getBlockZ()) {
            return;
        }

        if (parkourManager.isInParkour(player)) {
            ParkourSession session = parkourManager.getSession(player);
            if (player.isOnGround()) {
                session.setLastSafeLocation(player.getLocation());
            } else {
                double maxFallDistance = plugin.getConfigManager().getMainConfig().getDouble("fall-protection.max-fall-distance", 8.0);
                if (maxFallDistance > 0 && session.getLastSafeLocation() != null) {
                    if (session.getLastSafeLocation().getY() - to.getY() > maxFallDistance) {
                        parkourManager.fall(player);
                        session.setLastSafeLocation(player.getLocation());
                        return;
                    }
                }
            }
        }

        Block blockAtFeet = to.getBlock();
        if (!isPressurePlate(blockAtFeet.getType())) {
            return;
        }

        Location plateLocation = blockAtFeet.getLocation();
        for (Parkour parkour : parkourManager.getAllParkours()) {
            if (areLocationsEqual(parkour.getStart(), plateLocation)) {
                parkourManager.startParkour(player, parkour);
                return;
            }
            if (areLocationsEqual(parkour.getEnd(), plateLocation)) {
                if (parkourManager.isInParkour(player) && parkourManager.getSession(player).getParkour().equals(parkour)) {
                    // <<<< CORRECTION: Vérification des checkpoints avant de finir >>>>
                    ParkourSession session = parkourManager.getSession(player);
                    if (session.getLastCheckpoint() == parkour.getCheckpointCount() - 1) {
                        parkourManager.endParkour(player, true);
                    } else {
                        player.sendMessage("§cVous devez passer tous les checkpoints avant de finir !");
                    }
                }
                return;
            }
            for (int i = 0; i < parkour.getCheckpointCount(); i++) {
                if (areLocationsEqual(parkour.getCheckpoint(i), plateLocation)) {
                    if (parkourManager.isInParkour(player) && parkourManager.getSession(player).getParkour().equals(parkour)) {
                        parkourManager.reachCheckpoint(player, i);
                    }
                    return;
                }
            }
        }
    }

    private boolean isPressurePlate(Material material) {
        return material.name().endsWith("_PRESSURE_PLATE");
    }

    private boolean areLocationsEqual(Location loc1, Location loc2) {
        if (loc1 == null || loc2 == null) return false;
        return loc1.getWorld().equals(loc2.getWorld()) &&
            loc1.getBlockX() == loc2.getBlockX() &&
            loc1.getBlockY() == loc2.getBlockY() &&
            loc1.getBlockZ() == loc2.getBlockZ();
    }
}
