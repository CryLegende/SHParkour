package org.Cry_Legende.sHParkour.network;

import com.google.gson.JsonObject;
import org.Cry_Legende.sHParkour.SHParkour;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.File;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Mode réseau : les temps, parcours terminés et récompenses de checkpoint de chaque joueur sont dans la base partagée au
 * lieu de {@code playerdata.yml}, et les classements viennent de cette base.
 *
 * <ul>
 *   <li>à la connexion : une fois le départ de l'autre serveur confirmé, les données du joueur sont lues en base (en
 *       attendant, il ne peut pas démarrer de parcours : sans ses données, une arrivée passerait pour une première) ;</li>
 *   <li>tant que le joueur est sur un serveur, la mémoire de ce serveur fait foi pour lui ; chaque arrivée, chaque
 *       récompense de checkpoint est écrite en base aussitôt, par des instructions atomiques (le meilleur temps ne s'écrit
 *       que s'il est plus petit) ;</li>
 *   <li>au départ : la barrière de SHNetwork attend que ces écritures soient finies avant que l'autre serveur ne lise ;</li>
 *   <li>les classements sont gardés en mémoire (les placeholders ne touchent jamais la base) : relus après chaque
 *       changement, sur message du bus des autres serveurs, et à intervalle régulier ;</li>
 *   <li>un temps retiré par {@code /parkour resettime} l'est en base et chez le joueur s'il est sur un autre serveur.</li>
 * </ul>
 */
public final class NetworkParkour implements Listener {

    /** Ce que le mode réseau fait remonter au gestionnaire de données. */
    public interface Sink {

        /** Nouveau classement d'un parcours, non trié (fil de la base). */
        void leaderboard(String parkour, List<ParkourDatabase.Row> rows);

        /** Le temps d'un joueur a été retiré depuis un autre serveur (fil principal). */
        void remoteReset(UUID uuid, String parkour);
    }

    private static final int WRITE_ATTEMPTS = 3;
    private static final int LOAD_RETRY_SECONDS = 5;
    private static final int LOAD_RETRY_MAX = 60;
    private static final long PRELOAD_KEEP_MS = 120_000L;

    private record Preloaded(CompletableFuture<ParkourDatabase.PlayerRows> rows, long at) {
    }

    /** Écriture en base qui rend un résultat. */
    private interface SqlAction<T> {
        T run() throws SQLException;
    }

    private final SHParkour plugin;
    private final ParkourDatabase db;
    private final NetworkLink link;
    private final Duration waitMax;
    private final String channel;
    private final int refreshSeconds;
    private final ScheduledThreadPoolExecutor io = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "SHParkour-reseau");
        thread.setDaemon(true);
        return thread;
    });
    /** Données lues pendant la pré-connexion, reprises à l'arrivée du joueur. */
    private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();
    private final Set<String> refreshQueued = ConcurrentHashMap.newKeySet();
    /** Parcours de ce serveur : seuls leurs classements et ceux des parcours de la base sont lus. Remplacé au fil principal. */
    private volatile Set<String> localParkours = Set.of();
    /** Parcours vus en base ou annoncés par un autre serveur, absents de ce serveur : leur classement se lit aussi. */
    private final Set<String> remoteParkours = ConcurrentHashMap.newKeySet();
    private volatile Sink sink;

    public NetworkParkour(SHParkour plugin, ParkourDatabase db, NetworkLink link, Duration waitMax, String channel,
                          int refreshSeconds) {
        this.plugin = plugin;
        this.db = db;
        this.link = link;
        this.waitMax = waitMax;
        this.channel = channel;
        this.refreshSeconds = Math.max(5, refreshSeconds);
        io.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        io.setRemoveOnCancelPolicy(true);
    }

    public NetworkLink link() {
        return link;
    }

    /**
     * Branche le mode réseau : lecture à la connexion, sauvegarde au départ de chaque joueur, classements partagés.
     *
     * @param saveTask      exécutée au départ de chaque joueur (fil principal)
     * @param sink          reçoit les classements relus et les temps retirés ailleurs
     * @param parkourNames  noms internes (en minuscules) des parcours de ce serveur, y compris ceux en attente de leur monde ;
     *                      appelée sur le fil principal
     */
    public void start(Function<Player, CompletableFuture<Void>> saveTask, Sink sink, Supplier<Collection<String>> parkourNames) {
        this.sink = sink;
        this.localParkours = Set.copyOf(parkourNames.get());
        link.registerSaveTask(saveTask);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        link.listen(channel, this::onBus);
        // Relecture de tous les classements à intervalle régulier (fil principal : liste des parcours ; lecture : fil de la base)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Set<String> names = Set.copyOf(parkourNames.get());
            localParkours = names;
            submit(() -> {
                try {
                    remoteParkours.addAll(db.parkourNames());
                } catch (SQLException e) {
                    plugin.getLogger().warning("Parcours du réseau non lus en base : " + e.getMessage());
                }
                Set<String> all = new HashSet<>(names);
                all.addAll(remoteParkours);
                for (String parkour : all) {
                    refreshLeaderboard(parkour);
                }
            });
        }, 1L, refreshSeconds * 20L);
    }

    /** Termine les écritures en cours puis ferme la base. */
    public void stop() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Des écritures de parcours n'ont pas pu être terminées à l'arrêt.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    // ------------------------------------------------------------------ lecture

    /**
     * Lance la lecture des données dès la pré-connexion, sans l'attendre : le serveur quitté ne sauvegarde le joueur qu'une
     * fois cette connexion terminée (le proxy ferme l'ancienne connexion après), attendre ici bloquerait la connexion
     * jusqu'au délai maximal. La lecture se fait sur le fil de la base une fois le départ confirmé.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        long now = System.currentTimeMillis();
        preloaded.values().removeIf(p -> now - p.at() > PRELOAD_KEEP_MS);
        CompletableFuture<ParkourDatabase.PlayerRows> rows = new CompletableFuture<>();
        preloaded.put(uuid, new Preloaded(rows, now));
        link.awaitSaved(uuid, waitMax).whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                plugin.getLogger().warning("Départ de " + name + " non confirmé par l'autre serveur"
                    + (error != null ? " (" + error + ")" : "") + " : données de parcours relues quand même.");
            }
            try {
                io.execute(() -> {
                    try {
                        rows.complete(db.load(uuid));
                    } catch (SQLException e) {
                        // le joueur entre quand même : ses données sont relues dès que la base répond (voir loadLater)
                        plugin.getLogger().warning("Données de parcours de " + name + " non lues en base : " + e.getMessage());
                        rows.completeExceptionally(e);
                    }
                });
            } catch (RejectedExecutionException stopped) {
                rows.completeExceptionally(stopped);
            }
        });
    }

    /** Données lues à la pré-connexion du joueur qui entre, si c'est déjà fait ; sinon vide (voir loadLater). */
    public Optional<ParkourDatabase.PlayerRows> takePreloaded(UUID uuid) {
        Preloaded loaded = preloaded.get(uuid);
        if (loaded == null || !loaded.rows().isDone() || loaded.rows().isCompletedExceptionally()) {
            return Optional.empty();
        }
        preloaded.remove(uuid, loaded);
        return Optional.of(loaded.rows().join());
    }

    /**
     * Lit les données d'un joueur qui entre : reprend la lecture lancée à sa pré-connexion, sinon (rechargement à chaud,
     * base absente à sa connexion) lit en base. Réessaie tant que la base ne répond pas ; {@code onMain} reçoit les
     * données sur le fil principal.
     */
    public void loadLater(UUID uuid, Consumer<ParkourDatabase.PlayerRows> onMain) {
        Preloaded started = preloaded.remove(uuid);
        if (started != null) {
            // lecture lancée à la pré-connexion, pas encore finie : reprise telle quelle, relancée si elle a échoué
            started.rows().whenComplete((rows, error) -> {
                if (error == null) {
                    toMain(() -> onMain.accept(rows));
                } else {
                    submit(() -> loadOnce(uuid, onMain, 0));
                }
            });
            return;
        }
        submit(() -> loadOnce(uuid, onMain, 0));
    }

    private void loadOnce(UUID uuid, Consumer<ParkourDatabase.PlayerRows> onMain, int attempt) {
        try {
            ParkourDatabase.PlayerRows rows = db.load(uuid);
            toMain(() -> onMain.accept(rows));
        } catch (SQLException e) {
            plugin.getLogger().warning("Données de parcours de " + uuid + " non lues en base : " + e.getMessage());
            if (attempt + 1 >= LOAD_RETRY_MAX) {
                plugin.getLogger().severe("Données de parcours de " + uuid + " jamais lues en base, abandon.");
                return;
            }
            try {
                io.schedule(() -> loadOnce(uuid, onMain, attempt + 1), LOAD_RETRY_SECONDS, TimeUnit.SECONDS);
            } catch (RejectedExecutionException stopped) {
                // plugin arrêté
            }
        }
    }

    // ------------------------------------------------------------------ classements

    /** Relit le classement d'un parcours dès que possible (plusieurs demandes rapprochées n'en font qu'une). */
    public void requestRefresh(String parkour) {
        if (refreshQueued.add(parkour)) {
            submit(() -> {
                refreshQueued.remove(parkour);
                refreshLeaderboard(parkour);
            });
        }
    }

    /**
     * Vrai pour un parcours de ce serveur ou du réseau (déjà présent en base, ou annoncé par un autre serveur) : les autres
     * noms (placeholder inventé) ne déclenchent aucune lecture.
     */
    public boolean isKnown(String parkour) {
        return localParkours.contains(parkour) || remoteParkours.contains(parkour);
    }

    private void refreshLeaderboard(String parkour) {
        try {
            sink.leaderboard(parkour, db.leaderboard(parkour));
        } catch (SQLException e) {
            plugin.getLogger().warning("Classement de " + parkour + " non lu en base : " + e.getMessage());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Classement de " + parkour + " non appliqué", e);
        }
    }

    // ------------------------------------------------------------------ écriture

    /** Futur terminé quand toutes les écritures déjà demandées sont faites (elles passent toutes par le même fil, dans l'ordre). */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            io.execute(() -> done.complete(null));
        } catch (RejectedExecutionException stopped) {
            done.complete(null);
        }
        return done;
    }

    /** Écrit une arrivée, relit le classement et prévient les autres serveurs. */
    public void recordFinish(UUID uuid, String name, String parkour, long timeMs, long dateMs) {
        submit(() -> {
            write("arrivée de " + name + " (" + uuid + ") sur " + parkour + " en " + timeMs + " ms",
                () -> {
                    db.recordFinish(uuid, name, parkour, timeMs, dateMs);
                    return true;
                });
            refreshLeaderboard(parkour);
            JsonObject message = new JsonObject();
            message.addProperty("type", "top");
            message.addProperty("parkour", parkour);
            link.publish(channel, message);
        });
    }

    /** Écrit une récompense de checkpoint donnée. */
    public void claimCheckpoint(UUID uuid, String parkour, int checkpoint) {
        submit(() -> write("récompense du checkpoint " + checkpoint + " de " + parkour + " pour " + uuid,
            () -> db.claimCheckpoint(uuid, parkour, checkpoint)));
    }

    /** Écrit le pseudo actuel d'un joueur qui a des données. */
    public void writeName(UUID uuid, String name) {
        submit(() -> write("pseudo de " + uuid, () -> {
            db.writeName(uuid, name);
            return true;
        }));
    }

    /**
     * Retire le meilleur temps d'un joueur (commande resettime), relit le classement et prévient les autres serveurs :
     * celui où se trouve le joueur oublie aussi son temps en mémoire. {@code done} reçoit, sur le fil principal, le temps
     * retiré en millisecondes, -1 s'il n'y en avait pas, ou null si la base a refusé.
     */
    public void resetTime(UUID uuid, String parkour, Consumer<Long> done) {
        submit(() -> {
            Long removed = write("retrait du temps de " + uuid + " sur " + parkour, () -> db.resetBest(uuid, parkour));
            if (removed != null && removed >= 0) {
                refreshLeaderboard(parkour);
                JsonObject message = new JsonObject();
                message.addProperty("type", "reset");
                message.addProperty("parkour", parkour);
                message.addProperty("uuid", uuid.toString());
                link.publish(channel, message);
            }
            toMain(() -> done.accept(removed));
        });
    }

    /** Retrouve un joueur par son pseudo parmi ceux qui ont des données ; {@code done} reçoit son UUID ou null (fil principal). */
    public void findByName(String name, String parkour, Consumer<UUID> done) {
        submit(() -> {
            UUID found = null;
            try {
                found = db.findByName(name, parkour);
            } catch (SQLException e) {
                plugin.getLogger().warning("Joueur " + name + " non cherché en base : " + e.getMessage());
            }
            UUID result = found;
            toMain(() -> done.accept(result));
        });
    }

    /** Message d'un autre serveur (fil principal) : une demande de relecture, ou un temps retiré. */
    private void onBus(JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        if (!message.has("parkour")) {
            return;
        }
        String parkour = message.get("parkour").getAsString().toLowerCase(Locale.ROOT);
        if (type.equals("reset") && message.has("uuid")) {
            try {
                sink.remoteReset(UUID.fromString(message.get("uuid").getAsString()), parkour);
            } catch (IllegalArgumentException invalid) {
                return;
            }
        } else if (!type.equals("top")) {
            return;
        }
        if (!localParkours.contains(parkour)) {
            remoteParkours.add(parkour); // parcours joué sur un autre serveur : son classement se lit ici aussi
        }
        requestRefresh(parkour);
    }

    /** Exécute une écriture avec quelques essais ; null si la base l'a refusée. */
    private <T> T write(String what, SqlAction<T> action) {
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                return action.run();
            } catch (SQLException e) {
                if (attempt == WRITE_ATTEMPTS) {
                    plugin.getLogger().log(Level.SEVERE, "Parcours non écrit en base (" + what + ") : " + e.getMessage());
                    return null;
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ importation

    /**
     * Copie l'ancien playerdata.yml dans la base, sans toucher aux lignes déjà en base. {@code done} reçoit
     * {joueurs ajoutés, parcours ajoutés, récompenses de checkpoint ajoutées, déjà présents, joueurs lus} sur le fil
     * principal, ou null si la base a refusé ou si le fichier manque.
     */
    public void importFile(File file, Consumer<int[]> done) {
        submit(() -> {
            int[] result = null;
            try {
                if (file.isFile()) {
                    Map<UUID, ParkourDatabase.PlayerRows> byPlayer = readLegacy(file);
                    int[] counts = db.importAll(byPlayer);
                    result = new int[]{counts[0], counts[1], counts[2], counts[3], byPlayer.size()};
                    for (String parkour : byParkour(byPlayer)) {
                        remoteParkours.add(parkour);
                        refreshLeaderboard(parkour);
                        JsonObject message = new JsonObject();
                        message.addProperty("type", "top");
                        message.addProperty("parkour", parkour);
                        link.publish(channel, message);
                    }
                } else {
                    plugin.getLogger().warning("Importation : " + file.getName() + " introuvable.");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Importation des parcours impossible : " + e.getMessage());
            }
            int[] finalResult = result;
            toMain(() -> done.accept(finalResult));
        });
    }

    private static Set<String> byParkour(Map<UUID, ParkourDatabase.PlayerRows> byPlayer) {
        Set<String> parkours = new HashSet<>();
        for (ParkourDatabase.PlayerRows rows : byPlayer.values()) {
            parkours.addAll(rows.completed());
            parkours.addAll(rows.times().keySet());
        }
        return parkours;
    }

    /** Lit playerdata.yml comme le fait le mode local (parcours en minuscules, « terminé » = temps ou liste completed). */
    private Map<UUID, ParkourDatabase.PlayerRows> readLegacy(File file) {
        Map<UUID, ParkourDatabase.PlayerRows> byPlayer = new HashMap<>();
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) {
            return byPlayer;
        }
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException invalid) {
                plugin.getLogger().warning("UUID invalide trouvé dans playerdata.yml : " + key);
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Map<String, Long> times = readLongs(section.getConfigurationSection("best-times"));
            Map<String, Long> dates = readLongs(section.getConfigurationSection("record-dates"));
            dates.keySet().retainAll(times.keySet());
            Set<String> completed = new HashSet<>();
            section.getStringList("completed").forEach(parkour -> completed.add(parkour.toLowerCase(Locale.ROOT)));
            completed.addAll(times.keySet());
            Map<String, Set<Integer>> claims = new HashMap<>();
            ConfigurationSection claimSection = section.getConfigurationSection("checkpoint-rewards");
            if (claimSection != null) {
                for (String parkour : claimSection.getKeys(false)) {
                    List<Integer> indexes = claimSection.getIntegerList(parkour);
                    if (!indexes.isEmpty()) {
                        claims.put(parkour.toLowerCase(Locale.ROOT), new HashSet<>(indexes));
                    }
                }
            }
            if (times.isEmpty() && completed.isEmpty() && claims.isEmpty()) {
                continue;
            }
            byPlayer.put(uuid, new ParkourDatabase.PlayerRows(section.getString("name"), times, dates, completed, claims));
        }
        return byPlayer;
    }

    private static Map<String, Long> readLongs(ConfigurationSection section) {
        Map<String, Long> values = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                values.put(key.toLowerCase(Locale.ROOT), section.getLong(key));
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ fils

    private void toMain(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException stopped) {
            // plugin arrêté : plus personne à prévenir
        }
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException stopped) {
            // plugin arrêté : plus rien à faire
        }
    }
}
