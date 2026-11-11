package org.Cry_Legende.sHParkour.managers;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.network.NetworkParkour;
import org.Cry_Legende.sHParkour.network.ParkourDatabase;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
public class PlayerDataManager implements NetworkParkour.Sink {

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
    // Non nul seulement avec SHNetwork : temps et récompenses sont dans la base partagée, pas dans playerdata.yml
    private NetworkParkour network;
    // Mode réseau : joueurs de ce serveur dont les données sont lues en base
    private final Set<UUID> ready = ConcurrentHashMap.newKeySet();

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

    /** Passe en mode réseau (avant loadPlayerData) : playerdata.yml n'est alors ni lu ni écrit. */
    public void setNetwork(NetworkParkour network) {
        this.network = network;
    }

    public void loadPlayerData() {
        if (network != null) {
            plugin.getLogger().info("Mode réseau : temps et récompenses sont lus en base à la connexion de chaque joueur.");
            return;
        }
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
        if (network != null) {
            return; // mode réseau : chaque changement est écrit en base tout de suite
        }
        if (writeFile(snapshot(), ++saveSequence)) {
            plugin.getLogger().info("Toutes les donnees des joueurs ont ete sauvegardees.");
        }
    }

    /**
     * Sauvegarde si des données ont changé (déconnexion, sauvegarde périodique) :
     * l'écriture du fichier se fait hors du thread principal.
     */
    public void savePlayerDataAsync() {
        if (network != null || !dirty) {
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
            if (network != null) {
                network.writeName(uuid, name);
            }
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
        long now = System.currentTimeMillis();
        if (newBest) {
            bestTimes.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()).put(parkour, time);
            recordDates.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()).put(parkour, now);
            if (network == null) {
                leaderboards.remove(parkour);
            }
        }
        boolean first = completed.computeIfAbsent(uuid, ignored -> ConcurrentHashMap.newKeySet()).add(parkour);
        dirty = true;
        if (network != null) {
            // Écrit en base (le plus petit temps l'emporte, atomiquement) puis relit le classement ici et chez les autres
            network.recordFinish(uuid, name, parkour, time, now);
        }
        return new FinishResult(previous, newBest, first);
    }

    /** Classement trié du parcours (mis en cache jusqu'au prochain changement de temps). */
    public Leaderboard getLeaderboard(String parkourName) {
        String parkour = parkourName.toLowerCase(Locale.ROOT);
        if (network != null) {
            // Mode réseau : classement lu en base et gardé en mémoire (jamais de requête ici : les placeholders
            // l'appellent de n'importe quel fil). Pas encore lu : vide, et sa lecture est demandée.
            Leaderboard cached = leaderboards.get(parkour);
            if (cached != null) {
                return cached;
            }
            if (network.isKnown(parkour)) {
                network.requestRefresh(parkour);
            }
            return Leaderboard.EMPTY;
        }
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
        return rank(entries);
    }

    /** Trie les entrées (temps, puis date du record, puis UUID) et calcule la position de chaque joueur. */
    private static Leaderboard rank(List<Entry> entries) {
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
        if (network == null) {
            leaderboards.remove(parkour);
        }
        dirty = true;
        return true;
    }

    /**
     * Retire le meilleur temps d'un joueur, connecté ici ou non (commande resettime). {@code done} reçoit l'ancien temps en
     * millisecondes, -1 s'il n'y en avait pas, ou null si la base du réseau n'a pas répondu (mode réseau seulement).
     * Sans SHNetwork, tout se passe tout de suite, sur place.
     */
    public void resetPlayerTime(UUID uuid, String parkourName, Consumer<Long> done) {
        if (network == null) {
            long removed = getBestTime(uuid, parkourName);
            done.accept(resetPlayerTime(uuid, parkourName) ? removed : -1L);
            return;
        }
        network.resetTime(uuid, parkourName.toLowerCase(Locale.ROOT), removed -> {
            if (removed != null && removed >= 0) {
                resetPlayerTime(uuid, parkourName); // mémoire de ce serveur, si le joueur y est
            }
            done.accept(removed);
        });
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
        if (network != null) {
            network.claimCheckpoint(uuid, parkourName.toLowerCase(Locale.ROOT), checkpointIndex);
        }
    }

    // ------------------------------------------------------------------ mode réseau

    /**
     * Mode réseau : vrai quand les données du joueur sont lues en base. Tant qu'elles ne le sont pas, il ne peut pas
     * démarrer de parcours : sans elles, une arrivée passerait pour sa première et redonnerait la récompense.
     */
    public boolean isReady(UUID uuid) {
        return network == null || ready.contains(uuid);
    }

    /**
     * Mode réseau : charge les données du joueur qui arrive. Elles sont lues en base une fois que l'autre serveur a fini de
     * le sauvegarder (lecture lancée à la pré-connexion, sans la bloquer). Elles remplacent toujours celles d'une session
     * précédente.
     */
    public void beginNetworkJoin(Player player) {
        UUID uuid = player.getUniqueId();
        forget(uuid);
        Optional<ParkourDatabase.PlayerRows> loaded = network.takePreloaded(uuid);
        if (loaded.isPresent()) {
            apply(uuid, player.getName(), loaded.get());
            return;
        }
        network.loadLater(uuid, rows -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) { // parti entre-temps : ses données ne reviennent pas
                apply(uuid, online.getName(), rows);
            }
        });
    }

    private void apply(UUID uuid, String currentName, ParkourDatabase.PlayerRows rows) {
        if (!rows.times().isEmpty()) {
            bestTimes.put(uuid, new ConcurrentHashMap<>(rows.times()));
        }
        if (!rows.dates().isEmpty()) {
            recordDates.put(uuid, new ConcurrentHashMap<>(rows.dates()));
        }
        if (!rows.completed().isEmpty()) {
            Set<String> done = ConcurrentHashMap.newKeySet();
            done.addAll(rows.completed());
            completed.put(uuid, done);
        }
        if (!rows.claims().isEmpty()) {
            Map<String, Set<Integer>> claims = new ConcurrentHashMap<>();
            rows.claims().forEach((parkour, indexes) -> {
                Set<Integer> set = ConcurrentHashMap.newKeySet();
                set.addAll(indexes);
                claims.put(parkour, set);
            });
            claimedCheckpointRewards.put(uuid, claims);
        }
        if (rows.name() != null && !rows.name().isBlank()) {
            names.put(uuid, rows.name());
        }
        ready.add(uuid);
        rememberName(uuid, currentName); // pseudo changé depuis la dernière fois : réécrit en base
    }

    private void forget(UUID uuid) {
        ready.remove(uuid);
        bestTimes.remove(uuid);
        recordDates.remove(uuid);
        completed.remove(uuid);
        claimedCheckpointRewards.remove(uuid);
    }

    /**
     * Mode réseau, au départ du joueur (fil principal) : oublie ses données ; le futur se termine quand les écritures déjà
     * demandées pour lui sont faites, avant que l'autre serveur ne les lise.
     */
    public CompletableFuture<Void> flushPlayer(Player player) {
        forget(player.getUniqueId());
        return network.flush();
    }

    /** Nouveau classement lu en base (fil de la base). */
    @Override
    public void leaderboard(String parkour, List<ParkourDatabase.Row> rows) {
        List<Entry> entries = new ArrayList<>(rows.size());
        for (ParkourDatabase.Row row : rows) {
            entries.add(new Entry(row.uuid(), row.time(), row.date()));
            if (row.name() != null && !row.name().isBlank()) {
                names.put(row.uuid(), row.name());
            }
        }
        leaderboards.put(parkour, rank(entries));
    }

    /** Le temps d'un joueur a été retiré depuis un autre serveur : s'il est ici, il l'oublie aussi. */
    @Override
    public void remoteReset(UUID uuid, String parkour) {
        resetPlayerTime(uuid, parkour);
    }
}
