package org.Cry_Legende.sHParkour.managers;

import org.Cry_Legende.sHParkour.SHParkour;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Données persistantes des joueurs (playerdata.yml) :
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     name: Pseudo                 # dernier pseudo connu (classements, /parkour resettime hors ligne)
 *     best-times: {usine: 19850}   # meilleur temps en ms
 *     record-dates: {usine: ...}   # date (epoch ms) du meilleur temps
 *     completed: [usine]           # parcours déjà terminés : la récompense de première fois est acquise
 *     checkpoint-rewards: {...}    # récompenses de checkpoint déjà données
 * </pre>
 * Les écritures se font sur le thread principal ; les placeholders peuvent lire depuis un autre thread.
 */
public class PlayerDataManager {

    /** Une ligne de classement. date vaut 0 pour les temps enregistrés avant que la date soit conservée. */
    public record Entry(UUID uuid, long time, long date) {
    }

    /** Classement trié d'un parcours, avec la position de chaque joueur (1 = premier). */
    public record Leaderboard(List<Entry> entries, Map<UUID, Integer> ranks) {
        static final Leaderboard EMPTY = new Leaderboard(List.of(), Map.of());
    }

    /** Résultat d'une arrivée : ancien record (-1 s'il n'y en avait pas) et première arrivée ou non. */
    public record FinishResult(long previousBest, boolean newBest, boolean firstCompletion) {
    }

    private static final Comparator<Entry> RANKING = Comparator
        .comparingLong(Entry::time)
        .thenComparingLong(Entry::date)
        .thenComparing(entry -> entry.uuid().toString());

    private final SHParkour plugin;
    private final File playerDataFile;

    private final Map<UUID, Map<String, Long>> bestTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> recordDates = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> completed = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Set<Integer>>> claimedCheckpointRewards = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** Classements triés, invalidés à chaque changement de temps sur le parcours. */
    private final Map<String, Leaderboard> leaderboards = new ConcurrentHashMap<>();

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
        FileConfiguration config = YamlConfiguration.loadConfiguration(playerDataFile);

        ConfigurationSection playersSection = config.getConfigurationSection("players");
        if (playersSection == null) {
            return;
        }

        for (String uuidString : playersSection.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("UUID invalide trouve dans playerdata.yml: " + uuidString);
                continue;
            }
            ConfigurationSection section = playersSection.getConfigurationSection(uuidString);
            if (section == null) {
                continue;
            }

            String name = section.getString("name");
            if (name != null && !name.isBlank()) {
                names.put(uuid, name);
            }

            Map<String, Long> times = readLongs(section.getConfigurationSection("best-times"));
            if (!times.isEmpty()) {
                bestTimes.put(uuid, times);
            }
            Map<String, Long> dates = readLongs(section.getConfigurationSection("record-dates"));
            dates.keySet().retainAll(times.keySet());
            if (!dates.isEmpty()) {
                recordDates.put(uuid, dates);
            }

            // Fichiers d'avant "completed" : un joueur qui a un temps a déjà touché la récompense de première fois.
            Set<String> done = ConcurrentHashMap.newKeySet();
            section.getStringList("completed").forEach(parkour -> done.add(parkour.toLowerCase(Locale.ROOT)));
            done.addAll(times.keySet());
            if (!done.isEmpty()) {
                completed.put(uuid, done);
            }

            ConfigurationSection checkpointRewardsSection = section.getConfigurationSection("checkpoint-rewards");
            Map<String, Set<Integer>> playerClaims = new ConcurrentHashMap<>();
            if (checkpointRewardsSection != null) {
                for (String parkourName : checkpointRewardsSection.getKeys(false)) {
                    List<Integer> checkpointIndices = checkpointRewardsSection.getIntegerList(parkourName);
                    if (!checkpointIndices.isEmpty()) {
                        Set<Integer> claims = ConcurrentHashMap.newKeySet();
                        claims.addAll(checkpointIndices);
                        playerClaims.put(parkourName.toLowerCase(Locale.ROOT), claims);
                    }
                }
            }
            if (!playerClaims.isEmpty()) {
                claimedCheckpointRewards.put(uuid, playerClaims);
            }
        }

        plugin.getLogger().info("Donnees de " + bestTimes.size() + " joueurs chargees.");
        resolveMissingNames();
    }

    private static Map<String, Long> readLongs(@Nullable ConfigurationSection section) {
        Map<String, Long> values = new ConcurrentHashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                values.put(key.toLowerCase(Locale.ROOT), section.getLong(key));
            }
        }
        return values;
    }

    /**
     * Les fichiers d'avant le champ "name" n'ont que des UUID : le pseudo est relu une fois depuis les
     * données du serveur, puis conservé. Évite qu'un placeholder de classement lise le disque à chaque appel.
     */
    private void resolveMissingNames() {
        Set<UUID> known = new HashSet<>(bestTimes.keySet());
        known.addAll(completed.keySet());
        known.addAll(claimedCheckpointRewards.keySet());
        int resolved = 0;
        for (UUID uuid : known) {
            if (names.containsKey(uuid)) {
                continue;
            }
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name != null && !name.isBlank()) {
                names.put(uuid, name);
                resolved++;
            }
        }
        if (resolved > 0) {
            dirty = true;
            plugin.getLogger().info(resolved + " pseudos de joueurs retrouves pour les classements.");
        }
    }

    /** Sauvegarde synchrone (arrêt du plugin, commande admin). */
    public void savePlayerData() {
        if (writeFile(snapshot(), ++saveSequence)) {
            plugin.getLogger().info("Toutes les donnees des joueurs ont ete sauvegardees.");
        }
    }

    /**
     * Sauvegarde si des données ont changé (déconnexion, sauvegarde périodique) :
     * l'écriture du fichier se fait hors du thread principal.
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
                // Fichier temporaire puis renommage : un arrêt brutal pendant l'écriture ne laisse pas un fichier tronqué.
                File tmp = new File(playerDataFile.getParentFile(), playerDataFile.getName() + ".tmp");
                Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), playerDataFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                writtenSequence = sequence;
                return true;
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible de sauvegarder playerdata.yml", e);
                return false;
            }
        }
    }

    /** Recopie les données en mémoire dans un YAML (thread principal) et renvoie le texte à écrire. */
    private String snapshot() {
        dirty = false;
        YamlConfiguration config = new YamlConfiguration();

        Set<UUID> allPlayers = new HashSet<>();
        allPlayers.addAll(bestTimes.keySet());
        allPlayers.addAll(completed.keySet());
        allPlayers.addAll(claimedCheckpointRewards.keySet());

        for (UUID uuid : allPlayers) {
            String base = "players." + uuid;
            String name = names.get(uuid);
            if (name != null) {
                config.set(base + ".name", name);
            }
            Map<String, Long> playerTimes = bestTimes.get(uuid);
            if (playerTimes != null) {
                playerTimes.forEach((parkour, time) -> config.set(base + ".best-times." + parkour, time));
            }
            Map<String, Long> dates = recordDates.get(uuid);
            if (dates != null) {
                dates.forEach((parkour, date) -> config.set(base + ".record-dates." + parkour, date));
            }
            Set<String> done = completed.get(uuid);
            if (done != null && !done.isEmpty()) {
                config.set(base + ".completed", done.stream().sorted().toList());
            }
            Map<String, Set<Integer>> playerClaims = claimedCheckpointRewards.get(uuid);
            if (playerClaims != null) {
                playerClaims.forEach((parkour, claims) ->
                    config.set(base + ".checkpoint-rewards." + parkour, claims.stream().sorted().toList()));
            }
        }

        return config.saveToString();
    }

    // --- Pseudos ---------------------------------------------------------------------------------

    /** Mémorise le pseudo actuel du joueur (connexion, arrivée). */
    public void rememberName(UUID uuid, String name) {
        if (!name.equals(names.put(uuid, name)) && hasData(uuid)) {
            dirty = true;
        }
    }

    private boolean hasData(UUID uuid) {
        return bestTimes.containsKey(uuid) || completed.containsKey(uuid) || claimedCheckpointRewards.containsKey(uuid);
    }

    /** Dernier pseudo connu, ou null. */
    @Nullable
    public String getName(UUID uuid) {
        return names.get(uuid);
    }

    /**
     * Retrouve un joueur par son pseudo parmi ceux qui ont des données, sans requête Mojang
     * (le proxy est en online-mode : Bukkit.getOfflinePlayer(nom) rendrait l'UUID premium, pas celui du réseau).
     * À pseudo égal, préfère le compte qui a un temps sur ce parcours.
     */
    @Nullable
    public UUID findPlayer(String name, String parkourName) {
        String parkour = parkourName.toLowerCase(Locale.ROOT);
        UUID match = null;
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (!entry.getValue().equalsIgnoreCase(name)) {
                continue;
            }
            if (getBestTime(entry.getKey(), parkour) != -1) {
                return entry.getKey();
            }
            if (match == null) {
                match = entry.getKey();
            }
        }
        return match;
    }

    // --- Temps -----------------------------------------------------------------------------------

    public long getBestTime(UUID uuid, String parkourName) {
        Map<String, Long> playerTimes = bestTimes.get(uuid);
        return playerTimes == null ? -1L : playerTimes.getOrDefault(parkourName.toLowerCase(Locale.ROOT), -1L);
    }

    /** Date (epoch ms) du meilleur temps, 0 si inconnue. */
    public long getRecordDate(UUID uuid, String parkourName) {
        Map<String, Long> dates = recordDates.get(uuid);
        return dates == null ? 0L : dates.getOrDefault(parkourName.toLowerCase(Locale.ROOT), 0L);
    }

    /** Enregistre une arrivée : met à jour le record si le temps est meilleur et marque le parcours comme terminé. */
    public FinishResult recordFinish(UUID uuid, String name, String parkourName, long time) {
        String parkour = parkourName.toLowerCase(Locale.ROOT);
        rememberName(uuid, name);
        long previous = getBestTime(uuid, parkour);
        boolean newBest = previous == -1 || time < previous;
        if (newBest) {
            bestTimes.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()).put(parkour, time);
            recordDates.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()).put(parkour, System.currentTimeMillis());
            leaderboards.remove(parkour);
        }
        boolean first = completed.computeIfAbsent(uuid, ignored -> ConcurrentHashMap.newKeySet()).add(parkour);
        dirty = true;
        return new FinishResult(previous, newBest, first);
    }

    /** Classement trié du parcours (mis en cache jusqu'au prochain changement de temps). */
    public Leaderboard getLeaderboard(String parkourName) {
        String parkour = parkourName.toLowerCase(Locale.ROOT);
        return leaderboards.computeIfAbsent(parkour, this::buildLeaderboard);
    }

    private Leaderboard buildLeaderboard(String parkour) {
        List<Entry> entries = new ArrayList<>();
        bestTimes.forEach((uuid, times) -> {
            Long time = times.get(parkour);
            if (time != null) {
                entries.add(new Entry(uuid, time, getRecordDate(uuid, parkour)));
            }
        });
        if (entries.isEmpty()) {
            return Leaderboard.EMPTY;
        }
        entries.sort(RANKING);
        Map<UUID, Integer> ranks = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            ranks.put(entries.get(i).uuid(), i + 1);
        }
        return new Leaderboard(List.copyOf(entries), Map.copyOf(ranks));
    }

    /**
     * Retire le meilleur temps d'un joueur sur un parcours (classements compris).
     * Le parcours reste marqué comme terminé et les récompenses déjà données restent acquises :
     * un tricheur remis à zéro ne retouche pas la récompense de première fois en refaisant le parcours.
     */
    public boolean resetPlayerTime(UUID uuid, String parkourName) {
        String parkour = parkourName.toLowerCase(Locale.ROOT);
        Map<String, Long> playerTimes = bestTimes.get(uuid);
        if (playerTimes == null || playerTimes.remove(parkour) == null) {
            return false;
        }
        if (playerTimes.isEmpty()) {
            bestTimes.remove(uuid);
        }
        Map<String, Long> dates = recordDates.get(uuid);
        if (dates != null) {
            dates.remove(parkour);
            if (dates.isEmpty()) {
                recordDates.remove(uuid);
            }
        }
        leaderboards.remove(parkour);
        dirty = true;
        return true;
    }

    // --- Récompenses de checkpoint ---------------------------------------------------------------

    public boolean hasClaimedCheckpointReward(UUID uuid, String parkourName, int checkpointIndex) {
        Map<String, Set<Integer>> playerClaims = claimedCheckpointRewards.get(uuid);
        if (playerClaims == null) {
            return false;
        }
        Set<Integer> claimedIndexes = playerClaims.get(parkourName.toLowerCase(Locale.ROOT));
        return claimedIndexes != null && claimedIndexes.contains(checkpointIndex);
    }

    public void setCheckpointRewardClaimed(UUID uuid, String parkourName, int checkpointIndex) {
        claimedCheckpointRewards
            .computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(parkourName.toLowerCase(Locale.ROOT), ignored -> ConcurrentHashMap.newKeySet())
            .add(checkpointIndex);
        dirty = true;
    }
}
