package org.Cry_Legende.sHParkour.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protège les règles de ParkourDatabase sur une vraie MariaDB : le plus petit temps l'emporte même avec des écritures
 * simultanées, « terminé » survit à resettime, une récompense de checkpoint ne se donne qu'une fois, l'importation ne
 * touche jamais une ligne déjà là. Ne s'exécute que si SH_TEST_DB_URL, SH_TEST_DB_USER et SH_TEST_DB_PASSWORD sont
 * définis ; la base visée doit être une base de brouillon vide (jamais bo_net ni une base de production : les tables y
 * sont créées et l'importation est comparée à des nombres exacts).
 */
class ParkourDatabaseTest {

    private ParkourDatabase db;

    @BeforeEach
    void open() throws Exception {
        String url = System.getenv("SH_TEST_DB_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "SH_TEST_DB_URL non défini : test de base ignoré");
        db = new ParkourDatabase(url, System.getenv("SH_TEST_DB_USER"), System.getenv("SH_TEST_DB_PASSWORD"), 3000);
        db.createTables();
    }

    @AfterEach
    void close() {
        if (db != null) db.close();
    }

    @Test
    void leMeilleurTempsNeSEcritQuePlusPetit() throws Exception {
        UUID a = UUID.randomUUID();
        db.recordFinish(a, "Alpha", "usine", 20000L, 1111L);
        db.recordFinish(a, "Alpha", "usine", 25000L, 2222L);
        db.recordFinish(a, "Alpha", "usine", 20000L, 3333L);
        ParkourDatabase.PlayerRows rows = db.load(a);
        assertEquals(20000L, rows.times().get("usine"));
        assertEquals(1111L, rows.dates().get("usine"), "temps plus lent ou égal : la date ne bouge pas");
        db.recordFinish(a, "Alpha", "usine", 18000L, 4444L);
        rows = db.load(a);
        assertEquals(18000L, rows.times().get("usine"));
        assertEquals(4444L, rows.dates().get("usine"), "la date suit le temps");
    }

    @Test
    void ecrituresSimultaneesLePlusPetitGagne() throws Exception {
        UUID s = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        Random random = new Random(42);
        long min = Long.MAX_VALUE;
        for (int i = 0; i < 64; i++) {
            long t = 10000 + random.nextInt(50000);
            min = Math.min(min, t);
            futures.add(pool.submit(() -> {
                db.recordFinish(s, "Sprint", "jump", t, 10_000 + t);
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        ParkourDatabase.PlayerRows rows = db.load(s);
        assertEquals(min, rows.times().get("jump"));
        assertEquals(10_000 + min, rows.dates().get("jump"));
    }

    @Test
    void resettimeGardeLeParcoursTermine() throws Exception {
        UUID a = UUID.randomUUID();
        db.recordFinish(a, "Alpha", "usine", 18000L, 4444L);
        assertEquals(18000L, db.resetBest(a, "usine"));
        assertEquals(-1L, db.resetBest(a, "usine"));
        ParkourDatabase.PlayerRows rows = db.load(a);
        assertFalse(rows.times().containsKey("usine"));
        assertTrue(rows.completed().contains("usine"), "pas de seconde récompense de première arrivée");
        assertTrue(db.leaderboard("usine").stream().noneMatch(r -> r.uuid().equals(a)));
        db.recordFinish(a, "Alpha", "usine", 30000L, 6000L);
        assertEquals(30000L, db.load(a).times().get("usine"), "un temps plus lent est accepté après resettime");
    }

    @Test
    void recompenseDeCheckpointUneSeuleFois() throws Exception {
        UUID a = UUID.randomUUID();
        assertTrue(db.claimCheckpoint(a, "easter", 0));
        assertFalse(db.claimCheckpoint(a, "easter", 0));
        assertTrue(db.claimCheckpoint(a, "easter", 1));
        assertEquals(Set.of(0, 1), db.load(a).claims().get("easter"));
    }

    @Test
    void importNeToucheJamaisUneLigneExistante() throws Exception {
        UUID i1 = UUID.randomUUID();
        UUID i2 = UUID.randomUUID();
        Map<UUID, ParkourDatabase.PlayerRows> data = new HashMap<>();
        data.put(i1, new ParkourDatabase.PlayerRows("Imp1", Map.of("usine", 21000L), Map.of("usine", 7000L),
            Set.of("usine", "jump"), Map.of("easter", Set.of(0, 2))));
        data.put(i2, new ParkourDatabase.PlayerRows(null, Map.of(), Map.of(), Set.of("christmas"), Map.of()));
        assertArrayEquals(new int[]{1, 3, 2, 0}, db.importAll(data));
        assertArrayEquals(new int[]{0, 0, 0, 6}, db.importAll(data));
        ParkourDatabase.PlayerRows rows = db.load(i1);
        assertEquals(21000L, rows.times().get("usine"));
        assertEquals(Set.of("usine", "jump"), rows.completed());
        assertFalse(rows.times().containsKey("jump"), "parcours terminé sans temps");
        db.resetBest(i1, "usine");
        db.importAll(data);
        assertNull(db.load(i1).times().get("usine"), "un temps retiré ne revient pas à la réimportation");
    }

    @Test
    void pseudoRetrouveSansCasseEtPrefereLeCompteAvecTemps() throws Exception {
        UUID a = UUID.randomUUID();
        String name = "Zz" + (a.getMostSignificantBits() & 0xffffff);
        db.recordFinish(a, name, "usine", 20000L, 1L);
        UUID twin = UUID.randomUUID();
        db.writeName(twin, name);
        assertEquals(a, db.findByName(name.toLowerCase(), "usine"));
        assertNull(db.findByName("Inconnu" + a, "usine"));
    }
}
