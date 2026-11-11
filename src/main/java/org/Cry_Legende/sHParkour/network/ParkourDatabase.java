package org.Cry_Legende.sHParkour.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Temps, parcours terminés et récompenses de checkpoint en MariaDB, partagés par tous les serveurs. Chargée seulement
 * avec SHNetwork (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal.</p>
 */
public final class ParkourDatabase {

    private static final String PLAYERS = "shparkour_players";
    private static final String TIMES = "shparkour_times";
    private static final String CHECKPOINTS = "shparkour_checkpoints";

    /**
     * Un meilleur temps ne s'écrit que s'il est plus petit que celui de la base (ou si la ligne n'a pas de temps : première
     * arrivée, ou temps retiré par resettime). La date suit le temps. Dans un {@code ON DUPLICATE KEY UPDATE}, les colonnes
     * sont changées de gauche à droite : la date est donc décidée avant que best_ms ne change. Une seule instruction,
     * atomique : deux serveurs qui écrivent en même temps gardent le plus petit.
     */
    private static final String UPSERT_TIME = "INSERT INTO " + TIMES + " (uuid, parkour, best_ms, record_date) VALUES (?, ?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE "
        + "record_date = IF(best_ms IS NULL OR VALUES(best_ms) < best_ms, VALUES(record_date), record_date), "
        + "best_ms = IF(best_ms IS NULL OR VALUES(best_ms) < best_ms, VALUES(best_ms), best_ms)";

    private static final String UPSERT_NAME = "INSERT INTO " + PLAYERS + " (uuid, name) VALUES (?, ?) "
        + "ON DUPLICATE KEY UPDATE name = VALUES(name)";

    /**
     * Données d'un joueur. {@code times} et {@code dates} (epoch ms, absente si inconnue) ne contiennent que les parcours
     * qui ont un temps ; {@code completed} contient tous les parcours terminés, temps retiré ou non.
     */
    public record PlayerRows(String name, Map<String, Long> times, Map<String, Long> dates, Set<String> completed,
                             Map<String, Set<Integer>> claims) {
    }

    /** Une ligne de classement. */
    public record Row(UUID uuid, String name, long time, long date) {
    }

    private final HikariDataSource pool;

    public ParkourDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHParkour");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : le plugin reste alors en mode local
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Crée les tables si elles manquent. Des tables déjà là ne demandent aucun droit CREATE à l'utilisateur. */
    public void createTables() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + PLAYERS + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL DEFAULT '', "
                + "PRIMARY KEY (uuid), "
                + "KEY idx_name (name)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TIMES + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "parkour VARCHAR(100) NOT NULL, "
                + "best_ms BIGINT NULL, "
                + "record_date BIGINT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid, parkour), "
                + "KEY idx_rank (parkour, best_ms, record_date)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + CHECKPOINTS + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "parkour VARCHAR(100) NOT NULL, "
                + "checkpoint INT NOT NULL, "
                + "PRIMARY KEY (uuid, parkour, checkpoint)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    // ------------------------------------------------------------------ lecture

    /** Données du joueur (pseudo null, aucun temps, aucun parcours terminé sans ligne). */
    public PlayerRows load(UUID uuid) throws SQLException {
        String name = null;
        Map<String, Long> times = new HashMap<>();
        Map<String, Long> dates = new HashMap<>();
        Set<String> completed = new HashSet<>();
        Map<String, Set<Integer>> claims = new HashMap<>();
        try (Connection c = pool.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM " + PLAYERS + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        name = rs.getString(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT parkour, best_ms, record_date FROM " + TIMES + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String parkour = rs.getString(1);
                        completed.add(parkour);
                        long best = rs.getLong(2);
                        if (!rs.wasNull()) {
                            times.put(parkour, best);
                            long date = rs.getLong(3);
                            if (date > 0) {
                                dates.put(parkour, date);
                            }
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT parkour, checkpoint FROM " + CHECKPOINTS + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        claims.computeIfAbsent(rs.getString(1), ignored -> new HashSet<>()).add(rs.getInt(2));
                    }
                }
            }
        }
        return new PlayerRows(name, times, dates, completed, claims);
    }

    /** Classement non trié d'un parcours : les joueurs qui ont un temps, avec leur pseudo (peut être null). */
    public List<Row> leaderboard(String parkour) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT t.uuid, p.name, t.best_ms, t.record_date FROM " + TIMES + " t "
                 + "LEFT JOIN " + PLAYERS + " p ON p.uuid = t.uuid WHERE t.parkour = ? AND t.best_ms IS NOT NULL")) {
            ps.setString(1, parkour);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException invalid) {
                        continue;
                    }
                    rows.add(new Row(uuid, rs.getString(2), rs.getLong(3), rs.getLong(4)));
                }
            }
        }
        return rows;
    }

    /** Noms des parcours qui ont au moins une ligne (parcours joués sur un autre serveur, inconnus de celui-ci). */
    public Set<String> parkourNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection c = pool.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT parkour FROM " + TIMES)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /**
     * Joueur de ce pseudo (sans tenir compte de la casse) parmi ceux qui ont des données. À pseudo égal, préfère le compte
     * qui a un temps sur ce parcours. Null si personne.
     */
    public UUID findByName(String name, String parkour) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT p.uuid FROM " + PLAYERS + " p WHERE p.name = ? "
                 + "ORDER BY EXISTS(SELECT 1 FROM " + TIMES + " t WHERE t.uuid = p.uuid AND t.parkour = ? AND t.best_ms IS NOT NULL) DESC, p.uuid LIMIT 1")) {
            ps.setString(1, name);
            ps.setString(2, parkour);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        return UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException invalid) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ écriture

    /**
     * Enregistre une arrivée : pseudo, ligne « parcours terminé » et meilleur temps (le plus petit des deux, voir
     * {@link #UPSERT_TIME}). Rejouée, elle ne change rien de plus.
     */
    public void recordFinish(UUID uuid, String name, String parkour, long timeMs, long dateMs) throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                upsertName(c, uuid, name);
                try (PreparedStatement ps = c.prepareStatement(UPSERT_TIME)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, parkour);
                    ps.setLong(3, timeMs);
                    ps.setLong(4, dateMs);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Pseudo actuel d'un joueur qui a des données. */
    public void writeName(UUID uuid, String name) throws SQLException {
        try (Connection c = pool.getConnection()) {
            upsertName(c, uuid, name);
        }
    }

    private static void upsertName(Connection c, UUID uuid, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPSERT_NAME)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    /**
     * Note une récompense de checkpoint comme donnée. L'instruction ne touche jamais une ligne déjà là.
     *
     * @return vrai si elle ne l'était pas encore
     */
    public boolean claimCheckpoint(UUID uuid, String parkour, int checkpoint) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + CHECKPOINTS + " (uuid, parkour, checkpoint) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, parkour);
            ps.setInt(3, checkpoint);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Retire le meilleur temps d'un joueur (commande resettime). La ligne reste, sans temps : le parcours est toujours
     * « terminé » et la récompense de première arrivée n'est pas redonnée.
     *
     * @return le temps retiré en millisecondes, ou -1 s'il n'y en avait pas
     */
    public long resetBest(UUID uuid, String parkour) throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                long removed = -1L;
                try (PreparedStatement ps = c.prepareStatement("SELECT best_ms FROM " + TIMES + " WHERE uuid = ? AND parkour = ? FOR UPDATE")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, parkour);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long best = rs.getLong(1);
                            if (!rs.wasNull()) {
                                removed = best;
                            }
                        }
                    }
                }
                if (removed >= 0) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE " + TIMES + " SET best_ms = NULL, record_date = 0 WHERE uuid = ? AND parkour = ?")) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, parkour);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return removed;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // ------------------------------------------------------------------ importation

    /**
     * Reprend l'ancien playerdata.yml. Une ligne déjà en base n'est pas touchée (un temps retiré depuis par resettime ne
     * revient donc pas), l'importation peut être relancée sans rien écraser.
     *
     * @return {joueurs ajoutés, parcours ajoutés, récompenses de checkpoint ajoutées, lignes déjà présentes}
     */
    public int[] importAll(Map<UUID, PlayerRows> byPlayer) throws SQLException {
        int players = 0;
        int parkours = 0;
        int claims = 0;
        int present = 0;
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement insertName = c.prepareStatement("INSERT IGNORE INTO " + PLAYERS + " (uuid, name) VALUES (?, ?)");
                 PreparedStatement insertTime = c.prepareStatement("INSERT IGNORE INTO " + TIMES + " (uuid, parkour, best_ms, record_date) VALUES (?, ?, ?, ?)");
                 PreparedStatement insertClaim = c.prepareStatement("INSERT IGNORE INTO " + CHECKPOINTS + " (uuid, parkour, checkpoint) VALUES (?, ?, ?)")) {
                int pending = 0;
                for (Map.Entry<UUID, PlayerRows> entry : byPlayer.entrySet()) {
                    String uuid = entry.getKey().toString();
                    PlayerRows data = entry.getValue();
                    if (data.name() != null && !data.name().isBlank()) {
                        insertName.setString(1, uuid);
                        insertName.setString(2, data.name());
                        if (insertName.executeUpdate() > 0) {
                            players++;
                        } else {
                            present++;
                        }
                    }
                    Set<String> all = new HashSet<>(data.completed());
                    all.addAll(data.times().keySet());
                    for (String parkour : all) {
                        Long time = data.times().get(parkour);
                        insertTime.setString(1, uuid);
                        insertTime.setString(2, parkour);
                        if (time == null) {
                            insertTime.setNull(3, java.sql.Types.BIGINT);
                        } else {
                            insertTime.setLong(3, time);
                        }
                        insertTime.setLong(4, time == null ? 0L : data.dates().getOrDefault(parkour, 0L));
                        if (insertTime.executeUpdate() > 0) {
                            parkours++;
                        } else {
                            present++;
                        }
                    }
                    for (Map.Entry<String, Set<Integer>> claim : data.claims().entrySet()) {
                        for (int checkpoint : claim.getValue()) {
                            insertClaim.setString(1, uuid);
                            insertClaim.setString(2, claim.getKey());
                            insertClaim.setInt(3, checkpoint);
                            if (insertClaim.executeUpdate() > 0) {
                                claims++;
                            } else {
                                present++;
                            }
                        }
                    }
                    if (++pending % 200 == 0) {
                        c.commit();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
        return new int[]{players, parkours, claims, present};
    }

    public void close() {
        pool.close();
    }
}
