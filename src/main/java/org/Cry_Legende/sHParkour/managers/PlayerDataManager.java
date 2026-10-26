package org.Cry_Legende.sHParkour.managers;

import org.Cry_Legende.sHParkour.SHParkour;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class PlayerDataManager {

    private final SHParkour plugin;
    private final File playerDataFile;
    private FileConfiguration playerDataConfig;

    private final Map<UUID, Map<String, Long>> bestTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Set<Integer>>> claimedCheckpointRewards = new ConcurrentHashMap<>();

    // Sauvegardes : dirty et saveSequence sur le thread principal, writtenSequence sous writeLock.
    private final Object writeLock = new Object();
    private boolean dirty;
    private long saveSequence;
    private long writtenSequence;

    public PlayerDataManager(SHParkour plugin) {
        this.plugin = plugin;
        this.playerDataFile = new File(plugin.getDataFolder(), "playerdata.yml");
    }

    public void loadPlayerData() {
        if (!playerDataFile.exists()) {
            try {
                if (playerDataFile.createNewFile()) {
                    plugin.getLogger().info("playerdata.yml cree.");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible de creer playerdata.yml", e);
            }
        }
        playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);

        ConfigurationSection playersSection = playerDataConfig.getConfigurationSection("players");
        if (playersSection == null) {
            return;
        }

        for (String uuidString : playersSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);

                ConfigurationSection bestTimesSection = playersSection.getConfigurationSection(uuidString + ".best-times");
                Map<String, Long> playerTimes = new ConcurrentHashMap<>();
                if (bestTimesSection != null) {
                    for (String parkourName : bestTimesSection.getKeys(false)) {
                        playerTimes.put(parkourName.toLowerCase(), bestTimesSection.getLong(parkourName));
                    }
                }
                if (!playerTimes.isEmpty()) {
                    bestTimes.put(uuid, playerTimes);
                }

                ConfigurationSection checkpointRewardsSection = playersSection.getConfigurationSection(uuidString + ".checkpoint-rewards");
                Map<String, Set<Integer>> playerClaims = new ConcurrentHashMap<>();
                if (checkpointRewardsSection != null) {
                    for (String parkourName : checkpointRewardsSection.getKeys(false)) {
                        List<Integer> checkpointIndices = checkpointRewardsSection.getIntegerList(parkourName);
                        if (!checkpointIndices.isEmpty()) {
                            playerClaims.put(parkourName.toLowerCase(), new HashSet<>(checkpointIndices));
                        }
                    }
                }
                if (!playerClaims.isEmpty()) {
                    claimedCheckpointRewards.put(uuid, playerClaims);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("UUID invalide trouve dans playerdata.yml: " + uuidString);
            }
        }

        plugin.getLogger().info("Donnees de " + bestTimes.size() + " joueurs chargees.");
    }

    public void loadPlayerData(UUID uuid) {
        if (bestTimes.containsKey(uuid) || claimedCheckpointRewards.containsKey(uuid)) {
            return;
        }

        ConfigurationSection bestTimesSection = playerDataConfig.getConfigurationSection("players." + uuid + ".best-times");
        Map<String, Long> playerTimes = new ConcurrentHashMap<>();
        if (bestTimesSection != null) {
            for (String parkourName : bestTimesSection.getKeys(false)) {
                playerTimes.put(parkourName.toLowerCase(), bestTimesSection.getLong(parkourName));
            }
        }
        if (!playerTimes.isEmpty()) {
            bestTimes.put(uuid, playerTimes);
        }

        ConfigurationSection checkpointRewardsSection = playerDataConfig.getConfigurationSection("players." + uuid + ".checkpoint-rewards");
        Map<String, Set<Integer>> claims = new ConcurrentHashMap<>();
        if (checkpointRewardsSection != null) {
            for (String parkourName : checkpointRewardsSection.getKeys(false)) {
                List<Integer> checkpointIndices = checkpointRewardsSection.getIntegerList(parkourName);
                if (!checkpointIndices.isEmpty()) {
                    claims.put(parkourName.toLowerCase(), new HashSet<>(checkpointIndices));
                }
            }
        }
        if (!claims.isEmpty()) {
            claimedCheckpointRewards.put(uuid, claims);
        }
    }

    /** Sauvegarde synchrone (arrêt du plugin, commande admin). */
    public void savePlayerData() {
        if (writeFile(snapshot(), ++saveSequence)) {
            plugin.getLogger().info("Toutes les donnees des joueurs ont ete sauvegardees.");
        }
    }

    /**
     * Sauvegarde à la déconnexion : seulement si des données ont changé, et l'écriture du fichier
     * se fait hors du thread principal (il était réécrit en entier à chaque déconnexion).
     */
    public void savePlayerDataAsync() {
        if (!dirty) {
            return;
        }
        String data = snapshot();
        long sequence = ++saveSequence;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> writeFile(data, sequence));
    }

    private boolean writeFile(String data, long sequence) {
        synchronized (writeLock) {
            // Une sauvegarde plus récente a déjà été écrite : ne pas la remplacer par une ancienne.
            if (sequence < writtenSequence) {
                return false;
            }
            try {
                Files.writeString(playerDataFile.toPath(), data, StandardCharsets.UTF_8);
                writtenSequence = sequence;
                return true;
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible de sauvegarder playerdata.yml", e);
                return false;
            }
        }
    }

    /** Recopie les données en mémoire dans playerDataConfig (thread principal) et renvoie le YAML à écrire. */
    private String snapshot() {
        if (playerDataConfig == null) {
            playerDataConfig = new YamlConfiguration();
        }
        dirty = false;

        playerDataConfig.set("players", null);

        Set<UUID> allPlayers = new HashSet<>();
        allPlayers.addAll(bestTimes.keySet());
        allPlayers.addAll(claimedCheckpointRewards.keySet());

        for (UUID uuid : allPlayers) {
            Map<String, Long> playerTimes = bestTimes.get(uuid);
            Map<String, Set<Integer>> playerClaims = claimedCheckpointRewards.get(uuid);

            if (playerTimes != null && !playerTimes.isEmpty()) {
                for (Map.Entry<String, Long> entry : playerTimes.entrySet()) {
                    playerDataConfig.set("players." + uuid + ".best-times." + entry.getKey().toLowerCase(), entry.getValue());
                }
            }

            if (playerClaims != null && !playerClaims.isEmpty()) {
                for (Map.Entry<String, Set<Integer>> entry : playerClaims.entrySet()) {
                    playerDataConfig.set("players." + uuid + ".checkpoint-rewards." + entry.getKey().toLowerCase(), new ArrayList<>(entry.getValue()));
                }
            }

            if ((playerTimes == null || playerTimes.isEmpty()) && (playerClaims == null || playerClaims.isEmpty())) {
                playerDataConfig.set("players." + uuid, null);
            }
        }

        return playerDataConfig.saveToString();
    }

    public long getBestTime(UUID uuid, String parkourName) {
        Map<String, Long> playerTimes = bestTimes.get(uuid);
        return playerTimes == null ? -1L : playerTimes.getOrDefault(parkourName.toLowerCase(), -1L);
    }

    public void setBestTime(UUID uuid, String parkourName, long time) {
        bestTimes.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()).put(parkourName.toLowerCase(), time);
        dirty = true;
    }

    public Map<UUID, Long> getLeaderboard(String parkourName) {
        Map<UUID, Long> leaderboard = new ConcurrentHashMap<>();
        String parkourInternalName = parkourName.toLowerCase();

        bestTimes.forEach((uuid, times) -> {
            if (times.containsKey(parkourInternalName)) {
                leaderboard.put(uuid, times.get(parkourInternalName));
            }
        });

        return leaderboard;
    }

    public boolean resetPlayerTime(UUID uuid, String parkourName) {
        String parkourInternalName = parkourName.toLowerCase();

        boolean removedBestTime = false;
        Map<String, Long> playerTimes = bestTimes.get(uuid);
        if (playerTimes != null && playerTimes.containsKey(parkourInternalName)) {
            playerTimes.remove(parkourInternalName);
            removedBestTime = true;
            if (playerTimes.isEmpty()) {
                bestTimes.remove(uuid);
            }
        }

        boolean removedClaims = false;
        Map<String, Set<Integer>> playerClaims = claimedCheckpointRewards.get(uuid);
        if (playerClaims != null && playerClaims.containsKey(parkourInternalName)) {
            playerClaims.remove(parkourInternalName);
            removedClaims = true;
            if (playerClaims.isEmpty()) {
                claimedCheckpointRewards.remove(uuid);
            }
        }

        if (removedBestTime || removedClaims) {
            dirty = true;
        }
        return removedBestTime || removedClaims;
    }

    public boolean hasClaimedCheckpointReward(UUID uuid, String parkourName, int checkpointIndex) {
        Map<String, Set<Integer>> playerClaims = claimedCheckpointRewards.get(uuid);
        if (playerClaims == null) {
            return false;
        }

        Set<Integer> claimedIndexes = playerClaims.get(parkourName.toLowerCase());
        return claimedIndexes != null && claimedIndexes.contains(checkpointIndex);
    }

    public void setCheckpointRewardClaimed(UUID uuid, String parkourName, int checkpointIndex) {
        claimedCheckpointRewards
            .computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(parkourName.toLowerCase(), ignored -> ConcurrentHashMap.newKeySet())
            .add(checkpointIndex);
        dirty = true;
    }
}
