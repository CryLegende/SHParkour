package org.Cry_Legende.sHParkour.listeners;

import de.oliver.fancyholograms.api.HologramManager;
import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Collection;

public class HologramVisibilityListener implements Listener {

    private final SHParkour plugin;
    private final HologramManager hologramManagerAPI;

    public HologramVisibilityListener(SHParkour plugin) {
        this.plugin = plugin;
        this.hologramManagerAPI = de.oliver.fancyholograms.api.FancyHologramsPlugin.get().getHologramManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Optimisation: ne s'exécute que si le joueur change de bloc.
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockY() == event.getTo().getBlockY() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        Location playerLoc = player.getLocation();
        Collection<Parkour> parkours = plugin.getParkourManager().getAllParkours();

        for (Parkour parkour : parkours) {
            // Vérifier et créer l'hologramme de départ si nécessaire
            checkAndCreateHolo(parkour.getStart(), "start", playerLoc, parkour);

            // Vérifier et créer l'hologramme de fin si nécessaire
            checkAndCreateHolo(parkour.getEnd(), "end", playerLoc, parkour);

            // Vérifier et créer les hologrammes de checkpoints
            for (int i = 0; i < parkour.getCheckpoints().size(); i++) {
                checkAndCreateHolo(parkour.getCheckpoint(i), "checkpoint_" + i, playerLoc, parkour);
            }
        }
    }

    private void checkAndCreateHolo(Location holoPoint, String type, Location playerLoc, Parkour parkour) {
        if (holoPoint == null) return;

        // On ne vérifie que pour les hologrammes dans le même monde que le joueur.
        if (!holoPoint.getWorld().equals(playerLoc.getWorld())) return;

        // Le joueur doit être à moins de 50 blocs pour déclencher la création.
        if (holoPoint.distanceSquared(playerLoc) > 50 * 50) return;

        String holoName = "shparkour_" + parkour.getInternalName() + "_" + type;

        // Si l'hologramme n'existe PAS, on le crée.
        if (hologramManagerAPI.getHologram(holoName).isEmpty()) {
            // On appelle la méthode de création de notre HologramManager
            // Il est important que cette méthode soit maintenant synchrone
            plugin.getHologramManager().createSpecificHologram(parkour, type);
        }
    }
}
