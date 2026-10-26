package org.Cry_Legende.sHParkour.listeners;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.managers.ParkourManager;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

public class ProtectionListener implements Listener {

    private final SHParkour plugin;
    private final ParkourManager parkourManager;
    private final List<String> whitelistedCommands;

    public ProtectionListener(SHParkour plugin) {
        this.plugin = plugin;
        this.parkourManager = plugin.getParkourManager();
        this.whitelistedCommands = plugin.getConfigManager().getWhitelistedCommands();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!parkourManager.isInParkour(player)) {
            return;
        }

        // <<<< CORRECTION: Logique de téléportation avec marqueur >>>>
        if (!parkourManager.isPluginTeleport(player)) {
            // C'est une téléportation externe, on annule le parcours
            // (les téléportations du plugin passent par ParkourManager.safeTeleport)
            player.sendMessage(plugin.getConfigManager().getMessage("protection.teleport-denied"));
            parkourManager.endParkour(player, false, false);
            // On n'annule PAS l'événement pour que la téléportation ait bien lieu
        }
    }

    // Le reste de la classe est fourni pour la complétude.

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (parkourManager.isInParkour(player) && !player.hasPermission("shparkour.admin.bypass.commands")) {
            String command = event.getMessage().substring(1).split(" ")[0].toLowerCase();
            if (!whitelistedCommands.contains(command)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getConfigManager().getMessage("protection.command-denied"));
            }
        }
    }

    // ignoreCancelled : si un autre plugin (double saut, fly) a déjà refusé le décollage,
    // le joueur n'a pas triché et ne doit pas être expulsé de son parcours.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (event.isFlying() && parkourManager.isInParkour(player)) {
            player.sendMessage(plugin.getConfigManager().getMessage("protection.fly-denied"));
            parkourManager.endParkour(player, false, false);
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl && event.getEntity().getShooter() instanceof Player player) {
            if (parkourManager.isInParkour(player)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getConfigManager().getMessage("protection.item-use-denied"));
            }
        }
    }

    // Mort pendant un parcours : l'inventaire porté est celui du parcours (le vrai est sauvegardé à part
    // et restauré à la sortie). Rien ne doit tomber : ni les objets du parcours, ni des orbes d'expérience
    // que la restauration du niveau rendrait une seconde fois.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!parkourManager.isInParkour(event.getPlayer())) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onMoveCheckEffects(org.bukkit.event.player.PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (parkourManager.isInParkour(player)) {
            if (player.hasPotionEffect(PotionEffectType.SPEED) ||
                player.hasPotionEffect(PotionEffectType.JUMP_BOOST) ||
                player.hasPotionEffect(PotionEffectType.LEVITATION) ||
                player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
                player.removePotionEffect(PotionEffectType.SPEED);
                player.removePotionEffect(PotionEffectType.JUMP_BOOST);
                player.removePotionEffect(PotionEffectType.LEVITATION);
                player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                player.sendMessage(plugin.getConfigManager().getMessage("protection.effects-denied"));
            }
        }
    }
}
