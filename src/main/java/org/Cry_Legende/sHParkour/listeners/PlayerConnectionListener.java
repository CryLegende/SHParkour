package org.Cry_Legende.sHParkour.listeners;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.managers.ParkourManager;
import org.Cry_Legende.sHParkour.managers.PlayerDataManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerConnectionListener implements Listener {

    private final SHParkour plugin;
    private final ParkourManager parkourManager;
    private final PlayerDataManager playerDataManager;

    public PlayerConnectionListener(SHParkour plugin) {
        this.plugin = plugin;
        this.parkourManager = plugin.getParkourManager();
        this.playerDataManager = plugin.getPlayerDataManager();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        plugin.getPlayerStateManager().restoreStateIfAvailable(player);
        playerDataManager.loadPlayerData(player.getUniqueId());

        if (parkourManager.isInParkour(player)) {
            parkourManager.endParkour(player, false);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (parkourManager.isInParkour(player)) {
            parkourManager.endParkour(player, false);
        }

        playerDataManager.savePlayerData();
    }
}