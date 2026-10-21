package org.Cry_Legende.sHParkour.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.Cry_Legende.sHInventoryPages.api.ISHInventoryPagesAPI;
import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.Cry_Legende.sHParkour.objects.ParkourSession;
import org.Cry_Legende.sHParkour.utils.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ParkourManager {

    private final SHParkour plugin;
    private final Map<String, Parkour> parkours = new HashMap<>();
    private final Map<UUID, ParkourSession> playerSessions = new HashMap<>();
    private BukkitTask actionbarTask;
    public static final String TELEPORT_METADATA_TAG = "shparkour.teleport";
    /**
     * Métadonnée posée sur le joueur pendant toute la durée d'un parcours.
     * Les autres plugins maison (SHTempFly, SHDoubleJump) la lisent pour se mettre
     * en retrait : aucune dépendance de compilation n'est nécessaire de leur côté.
     */
    public static final String ACTIVE_METADATA_TAG = "shparkour.active";

    private final ISHInventoryPagesAPI shInventoryPagesAPI;
    private final String INVENTORY_BLOCK_ID = "shparkour_in_parkour";

    public ParkourManager(SHParkour plugin, @Nullable ISHInventoryPagesAPI shInventoryPagesAPI) {
        this.plugin = plugin;
        this.shInventoryPagesAPI = shInventoryPagesAPI;
        startActionbarTask();
    }

    public void loadParkours() {
        parkours.clear();
        File parkoursFolder = plugin.getConfigManager().getParkoursFolder();
        File[] files = parkoursFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            String parkourName = file.getName().replace(".yml", "");
            Parkour parkour = new Parkour(plugin, parkourName);
            parkours.put(parkourName.toLowerCase(), parkour);
            plugin.getBlockManager().placeParkourBlocks(parkour);
        }
        plugin.getLogger().info(parkours.size() + " parcours ont été chargés.");
    }

    public void reloadParkours() {
        new ArrayList<>(playerSessions.values()).forEach(session -> {
            Player p = Bukkit.getPlayer(session.getPlayerUUID());
            if (p != null) endParkour(p, false);
        });
        playerSessions.clear();
        for (Parkour parkour : parkours.values()) {
            plugin.getHologramManager().deleteHolograms(parkour);
            plugin.getBlockManager().removeParkourBlocks(parkour);
        }
        loadParkours();
        plugin.getLogger().info("Tous les parcours ont été rechargés.");
    }

    public void startParkour(Player player, Parkour parkour) {
        ParkourSession existingSession = getSession(player);
        if (existingSession != null && existingSession.getParkour().equals(parkour)) {
            existingSession.resetTime();
            existingSession.setLastSafeLocation(player.getLocation());
            player.sendMessage(plugin.getConfigManager().getMessage("parkour-join").replace("{parkour}", parkour.getDisplayName()));
            return;
        }
        if (isInParkour(player)) {
            endParkour(player, false);
        }
        if (parkour.getStart() == null) {
            player.sendMessage("§cCe parkour n'est pas encore configuré (pas de point de départ).");
            return;
        }
        plugin.getPlayerStateManager().saveState(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setAllowFlight(false);
        player.setFlying(false);
        giveParkourItems(player);
        playerSessions.put(player.getUniqueId(), new ParkourSession(player.getUniqueId(), parkour, player.getLocation()));
        player.setMetadata(ACTIVE_METADATA_TAG, new FixedMetadataValue(plugin, true));
        player.sendMessage(plugin.getConfigManager().getMessage("parkour-join").replace("{parkour}", parkour.getDisplayName()));
        if (shInventoryPagesAPI != null) {
            shInventoryPagesAPI.blockPageSwitching(player, INVENTORY_BLOCK_ID);
        }
    }

    /**
     * Méthode principale pour terminer un parcours.
     *
     * @param player             Le joueur qui termine.
     * @param finished           Si le parcours a été terminé avec succès.
     * @param teleportToEndSpawn Si le joueur doit être téléporté au point de spawn de fin.
     */
    public void endParkour(Player player, boolean finished, boolean teleportToEndSpawn) {
        if (!isInParkour(player)) return;

        ParkourSession session = getSession(player);
        Parkour parkour = session.getParkour();

        if (shInventoryPagesAPI != null) {
            shInventoryPagesAPI.unblockPageSwitching(player, INVENTORY_BLOCK_ID);
        }
        if (!session.arePlayersVisible()) {
            restorePlayerVisibility(player);
        }

        playerSessions.remove(player.getUniqueId());
        player.removeMetadata(ACTIVE_METADATA_TAG, plugin);
        plugin.getPlayerStateManager().restoreState(player);

        if (finished) {
            long time = session.getElapsedTime();
            String formattedTime = TimeUtil.formatTime(time);
            player.sendMessage(plugin.getConfigManager().getMessage("parkour-finish").replace("{parkour}", parkour.getDisplayName()).replace("{time}", formattedTime));
            long bestTime = plugin.getPlayerDataManager().getBestTime(player.getUniqueId(), parkour.getInternalName());
            boolean isFirstCompletion = (bestTime == -1);
            if (isFirstCompletion || time < bestTime) {
                plugin.getPlayerDataManager().setBestTime(player.getUniqueId(), parkour.getInternalName(), time);
                player.sendMessage(plugin.getConfigManager().getMessage("new-best-time").replace("{time}", formattedTime));
            }
            handleRewards(player, parkour, isFirstCompletion);
        } else {
            player.sendMessage(plugin.getConfigManager().getMessage("parkour-leave").replace("{parkour}", parkour.getDisplayName()));
        }

        if (teleportToEndSpawn && parkour.getEndSpawn() != null) {
            safeTeleport(player, parkour.getEndSpawn());
        }
    }

    /**
     * Surcharge de endParkour pour les cas normaux où la téléportation est souhaitée.
     */
    public void endParkour(Player player, boolean finished) {
        endParkour(player, finished, true);
    }

    public void restartFromItem(Player player) {
        if (!isInParkour(player)) return;
        ParkourSession session = getSession(player);
        Parkour parkour = session.getParkour();
        Location respawnPoint = parkour.getRespawn();
        if (respawnPoint != null) {
            safeTeleport(player, respawnPoint);
            session.resetTime();
            session.setLastSafeLocation(respawnPoint);
        } else {
            Location startLoc = parkour.getStart().clone().add(0.5, 1.0, 0.5);
            safeTeleport(player, startLoc);
            session.resetTime();
            session.setLastSafeLocation(startLoc);
        }
    }

    private void safeTeleport(Player player, Location location) {
        player.setMetadata(TELEPORT_METADATA_TAG, new FixedMetadataValue(plugin, true));
        player.teleport(location);
    }

    private void handleRewards(Player player, Parkour parkour, boolean isFirstCompletion) {
        ConfigurationSection rewardsConfig = parkour.getConfig().getConfigurationSection("rewards");
        if (rewardsConfig == null || !rewardsConfig.getBoolean("enabled", false)) return;
        boolean firstTimeOnly = rewardsConfig.getBoolean("first-time-only", true);
        if (firstTimeOnly && !isFirstCompletion) return;
        List<String> commands = rewardsConfig.getStringList("commands");
        for (String cmd : commands) {
            String processedCmd = cmd.replace("{player}", player.getName()).replace("{parkour_display_name}", parkour.getDisplayName());
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), processedCmd);
        }
    }

    private void handleCheckpointRewards(Player player, Parkour parkour, int checkpointIndex) {
        ConfigurationSection rewardsConfig = parkour.getConfig().getConfigurationSection("checkpoint-rewards");
        if (rewardsConfig == null || !rewardsConfig.getBoolean("enabled", false)) return;
        boolean firstTimeOnly = rewardsConfig.getBoolean("first-time-only", true);
        if (firstTimeOnly && plugin.getPlayerDataManager().hasClaimedCheckpointReward(player.getUniqueId(), parkour.getInternalName(), checkpointIndex)) {
            return;
        }

        List<String> commands = rewardsConfig.getStringList("commands");
        for (String cmd : commands) {
            String processedCmd = cmd
                .replace("{player}", player.getName())
                .replace("{parkour_display_name}", parkour.getDisplayName())
                .replace("{checkpoint_index}", String.valueOf(checkpointIndex + 1));
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), processedCmd);
        }

        if (firstTimeOnly) {
            plugin.getPlayerDataManager().setCheckpointRewardClaimed(player.getUniqueId(), parkour.getInternalName(), checkpointIndex);
        }
    }

    public void fall(Player player) {
        if (!isInParkour(player)) return;
        ParkourSession session = getSession(player);
        Location teleportLocation;
        int lastCheckpointIndex = session.getLastCheckpoint();
        if (lastCheckpointIndex != -1) {
            teleportLocation = session.getParkour().getCheckpoint(lastCheckpointIndex);
        } else {
            teleportLocation = session.getParkour().getRespawn();
        }
        if (teleportLocation == null && session.getParkour().getStart() != null) {
            teleportLocation = session.getParkour().getStart().clone().add(0.5, 1.0, 0.5);
        }
        if (teleportLocation != null) {
            safeTeleport(player, teleportLocation);
        }
    }

    public void reachCheckpoint(Player player, int checkpointIndex) {
        if (!isInParkour(player)) return;
        ParkourSession session = getSession(player);
        if (checkpointIndex == session.getLastCheckpoint() + 1) {
            session.setLastCheckpoint(checkpointIndex);
            player.sendMessage(plugin.getConfigManager().getMessage("checkpoint-reached").replace("{index}", String.valueOf(checkpointIndex + 1)));
            handleCheckpointRewards(player, session.getParkour(), checkpointIndex);
        }
    }

    public Parkour getParkour(String name) {
        return parkours.get(name.toLowerCase());
    }

    public Collection<Parkour> getAllParkours() {
        return parkours.values();
    }

    public ParkourSession getSession(Player player) {
        return playerSessions.get(player.getUniqueId());
    }

    public boolean isInParkour(Player player) {
        return playerSessions.containsKey(player.getUniqueId());
    }

    public void createParkour(String name) {
        if (plugin.getConfigManager().createParkourFile(name)) {
            Parkour parkour = new Parkour(plugin, name);
            parkours.put(name.toLowerCase(), parkour);
            plugin.getBlockManager().placeParkourBlocks(parkour);
        }
    }

    public void deleteParkour(String name) {
        Parkour parkour = getParkour(name);
        if (parkour != null) {
            new ArrayList<>(playerSessions.keySet()).forEach(uuid -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && getSession(p).getParkour().equals(parkour)) {
                    endParkour(p, false);
                }
            });
            plugin.getHologramManager().deleteHolograms(parkour);
            plugin.getBlockManager().removeParkourBlocks(parkour);
            parkours.remove(name.toLowerCase());
            plugin.getConfigManager().deleteParkourFile(name);
        }
    }

    public void shutdown() {
        new ArrayList<>(playerSessions.keySet()).forEach(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) endParkour(player, false);
        });
        if (actionbarTask != null) actionbarTask.cancel();
    }

    private void startActionbarTask() {
        actionbarTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (playerSessions.isEmpty()) return;
                new ArrayList<>(playerSessions.values()).forEach(session -> {
                    Player player = Bukkit.getPlayer(session.getPlayerUUID());
                    if (player == null || !player.isOnline()) {
                        playerSessions.remove(session.getPlayerUUID());
                        return;
                    }

                    // Vérification du changement de monde
                    if (!player.getWorld().getName().equals(session.getWorldName())) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage(plugin.getConfigManager().getMessage("protection.teleport-denied"));
                            endParkour(player, false, false); // Ne pas téléporter
                        });
                        return;
                    }

                    Parkour parkour = session.getParkour();
                    if (parkour.getMaxTime() > 0 && session.getElapsedTime() > parkour.getMaxTime()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§cTemps écoulé ! Vous avez été retiré du parcours.");
                            endParkour(player, false, true); // Téléporter au spawn de fin
                        });
                        return;
                    }
                    String format = plugin.getConfigManager().getActionbarFormat();
                    String time = TimeUtil.formatTime(session.getElapsedTime());
                    String message = format.replace("{parkour_display_name}", parkour.getDisplayName()).replace("{time}", time);
                    player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
                });
            }
        }.runTaskTimerAsynchronously(plugin, 0L, 20L);
    }

    private void giveParkourItems(Player player) {
        player.getInventory().clear();
        if (plugin.getConfigManager().getMainConfig().getBoolean("parkour-items.checkpoint.enabled"))
            createParkourItem(player, "checkpoint");
        if (plugin.getConfigManager().getMainConfig().getBoolean("parkour-items.restart.enabled"))
            createParkourItem(player, "restart");
        if (plugin.getConfigManager().getMainConfig().getBoolean("parkour-items.leave.enabled"))
            createParkourItem(player, "leave");
        if (plugin.getConfigManager().getMainConfig().getBoolean("parkour-items.toggle-players.enabled"))
            createParkourItem(player, "toggle-players");
    }

    private void createParkourItem(Player player, String itemName) {
        String basePath = "parkour-items." + itemName;
        FileConfiguration config = plugin.getConfigManager().getMainConfig();
        int slot = config.getInt(basePath + ".slot");
        Material material = Material.getMaterial(config.getString(basePath + ".material", "STONE"));
        String name = config.getString(basePath + ".name", "&cNom d'item non trouvé");
        List<String> lore = config.getStringList(basePath + ".lore");
        ItemStack item = new ItemStack(material != null ? material : Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
            List<Component> loreComponents = new ArrayList<>();
            for (String line : lore) {
                loreComponents.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line).colorIfAbsent(NamedTextColor.GRAY));
            }
            meta.lore(loreComponents);
            item.setItemMeta(meta);
        }
        player.getInventory().setItem(slot, item);
    }

    public void restorePlayerVisibility(Player player) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            player.showPlayer(plugin, onlinePlayer);
        }
    }
}
