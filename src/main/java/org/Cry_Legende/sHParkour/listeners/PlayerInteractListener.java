package org.Cry_Legende.sHParkour.listeners;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.managers.ParkourManager;
import org.Cry_Legende.sHParkour.objects.ParkourSession;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class PlayerInteractListener implements Listener {

    private final SHParkour plugin;
    private final ParkourManager parkourManager;

    public PlayerInteractListener(SHParkour plugin) {
        this.plugin = plugin;
        this.parkourManager = plugin.getParkourManager();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!parkourManager.isInParkour(player)) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }

        event.setCancelled(true);

        ParkourSession session = parkourManager.getSession(player);
        FileConfiguration config = plugin.getConfigManager().getMainConfig();
        int slot = player.getInventory().getHeldItemSlot();

        if (slot == config.getInt("parkour-items.checkpoint.slot")) {
            parkourManager.fall(player);
        } else if (slot == config.getInt("parkour-items.restart.slot")) {
            parkourManager.restartFromItem(player);
        } else if (slot == config.getInt("parkour-items.leave.slot")) {
            parkourManager.endParkour(player, false);
        } else if (slot == config.getInt("parkour-items.toggle-players.slot")) {
            togglePlayerVisibility(player, session);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (parkourManager.isInParkour(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (parkourManager.isInParkour(player)) {
                event.setCancelled(true);
            }
        }
    }

    private void togglePlayerVisibility(Player player, ParkourSession session) {
        boolean newVisibility = !session.arePlayersVisible();
        session.setPlayersVisible(newVisibility);

        ItemStack visibilityItem = player.getInventory().getItem(plugin.getConfigManager().getMainConfig().getInt("parkour-items.toggle-players.slot"));
        if (visibilityItem == null) return;

        if (newVisibility) {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                player.showPlayer(plugin, onlinePlayer);
            }
            visibilityItem.setType(Material.LIME_DYE);
        } else {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (!onlinePlayer.equals(player)) {
                    player.hidePlayer(plugin, onlinePlayer);
                }
            }
            visibilityItem.setType(Material.GRAY_DYE);
        }
    }
}
