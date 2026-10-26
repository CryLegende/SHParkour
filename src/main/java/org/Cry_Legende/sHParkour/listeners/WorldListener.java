package org.Cry_Legende.sHParkour.listeners;

import org.Cry_Legende.sHParkour.SHParkour;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * Les mondes de PhantomWorlds (Christmas, Easter...) sont chargés après onEnable :
 * les parcours qui s'y trouvent sont mis en place à leur chargement.
 */
public class WorldListener implements Listener {

    private final SHParkour plugin;

    public WorldListener(SHParkour plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        String worldName = event.getWorld().getName();
        // Tick suivant : le monde finit de s'initialiser avant que l'on pose des blocs.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (Bukkit.getWorld(worldName) != null) {
                plugin.getParkourManager().handleWorldLoad(worldName);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        plugin.getParkourManager().handleWorldUnload(event.getWorld());
    }
}
