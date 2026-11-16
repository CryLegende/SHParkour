package org.Cry_Legende.sHParkour.objects;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Règles des médailles de temps : lecture de la liste, médailles atteintes, cumul, une fois par médaille. */
class MedalEvaluatorTest {

    private static Map<String, Object> entry(String name, Object seconds, Object commands) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (name != null) map.put("nom", name);
        if (seconds != null) map.put("temps-max", seconds);
        if (commands != null) map.put("commandes", commands);
        return map;
    }

    private static List<Medal> christmas() {
        List<Map<?, ?>> raw = new ArrayList<>();
        raw.add(entry("&6Bronze", 360, List.of("a {player}")));
        raw.add(entry("&fArgent", 180, List.of("b {player}")));
        raw.add(entry("&eOr", 100, List.of("c {player}", "d {player}")));
        return MedalEvaluator.parse(raw, warning -> {
            throw new AssertionError("avertissement inattendu : " + warning);
        });
    }

    @Test
    void parseReadsNameTimeAndCommands() {
        List<Medal> medals = christmas();
        assertEquals(3, medals.size());
        assertEquals("&6Bronze", medals.get(0).name());
        assertEquals(360_000L, medals.get(0).maxTimeMs());
        assertEquals(List.of("c {player}", "d {player}"), medals.get(2).commands());
    }

    @Test
    void parseAcceptsDecimalsAndTextNumbers() {
        List<Map<?, ?>> raw = new ArrayList<>();
        raw.add(entry("x", 99.5, null));
        raw.add(entry("y", "42,25", "say bonjour"));
        List<Medal> medals = MedalEvaluator.parse(raw, warning -> {
            throw new AssertionError(warning);
        });
        assertEquals(99_500L, medals.get(0).maxTimeMs());
        assertEquals(42_250L, medals.get(1).maxTimeMs());
        assertEquals(List.of("say bonjour"), medals.get(1).commands());
        assertTrue(medals.get(0).commands().isEmpty());
    }

    @Test
    void invalidEntryKeepsItsPositionAndWarns() {
        List<Map<?, ?>> raw = new ArrayList<>();
        raw.add(entry("ok", 100, null));
        raw.add(entry("sans temps", null, null));
        raw.add(entry("negatif", -5, null));
        raw.add(entry(null, "abc", null));
        raw.add(entry("dernier", 50, null));
        List<String> warnings = new ArrayList<>();
        List<Medal> medals = MedalEvaluator.parse(raw, warnings::add);
        assertEquals(5, medals.size());
        assertEquals(3, warnings.size());
        assertFalse(medals.get(1).valid());
        assertFalse(medals.get(2).valid());
        assertFalse(medals.get(3).valid());
        assertEquals("Médaille 4", medals.get(3).name());
        assertTrue(medals.get(4).valid());
        // une médaille invalide n'est jamais atteinte, même avec un temps nul
        assertEquals(List.of(0, 4), MedalEvaluator.reached(medals, 1, p -> false));
    }

    @Test
    void nullListGivesNoMedal() {
        assertTrue(MedalEvaluator.parse(null, w -> {
        }).isEmpty());
    }

    @Test
    void fastRunReachesEveryMedalAtOnce() {
        assertEquals(List.of(0, 1, 2), MedalEvaluator.reached(christmas(), 95_000, p -> false));
    }

    @Test
    void slowRunReachesOnlyTheEasyOnes() {
        List<Medal> medals = christmas();
        assertEquals(List.of(0), MedalEvaluator.reached(medals, 359_999, p -> false));
        assertEquals(List.of(0, 1), MedalEvaluator.reached(medals, 150_000, p -> false));
        assertEquals(List.of(), MedalEvaluator.reached(medals, 360_001, p -> false));
    }

    @Test
    void timeEqualToTheMaximumCounts() {
        List<Medal> medals = christmas();
        assertEquals(List.of(0, 1, 2), MedalEvaluator.reached(medals, 100_000, p -> false));
        assertEquals(List.of(0, 1), MedalEvaluator.reached(medals, 100_001, p -> false));
    }

    @Test
    void claimedMedalsAreNeverGivenTwice() {
        List<Medal> medals = christmas();
        Set<Integer> claimed = new HashSet<>();
        for (int position : MedalEvaluator.reached(medals, 95_000, claimed::contains)) claimed.add(position);
        assertEquals(Set.of(0, 1, 2), claimed);
        // même temps ou meilleur : plus rien
        assertEquals(List.of(), MedalEvaluator.reached(medals, 95_000, claimed::contains));
        assertEquals(List.of(), MedalEvaluator.reached(medals, 60_000, claimed::contains));
    }

    @Test
    void laterFasterRunPaysOnlyTheMissingMedal() {
        List<Medal> medals = christmas();
        Set<Integer> claimed = new HashSet<>(List.of(0));
        assertEquals(List.of(1), MedalEvaluator.reached(medals, 170_000, claimed::contains));
        claimed.add(1);
        assertEquals(List.of(2), MedalEvaluator.reached(medals, 90_000, claimed::contains));
    }

    @Test
    void appendedMedalIsGivenToPlayersWhoHadTheOthers() {
        List<Map<?, ?>> raw = new ArrayList<>();
        raw.add(entry("Bronze", 360, null));
        raw.add(entry("Argent", 180, null));
        raw.add(entry("Or", 100, null));
        raw.add(entry("Platine", 75, null));
        List<Medal> medals = MedalEvaluator.parse(raw, w -> {
        });
        Set<Integer> claimed = new HashSet<>(List.of(0, 1, 2));
        assertEquals(List.of(3), MedalEvaluator.reached(medals, 70_000, claimed::contains));
    }

    @Test
    void countAndBest() {
        List<Medal> medals = christmas();
        assertEquals(0, MedalEvaluator.count(medals, p -> false));
        assertNull(MedalEvaluator.best(medals, p -> false));
        Set<Integer> claimed = Set.of(0, 1);
        assertEquals(2, MedalEvaluator.count(medals, claimed::contains));
        assertEquals("&fArgent", MedalEvaluator.best(medals, claimed::contains).name());
        // la plus difficile se juge au temps maximal, pas à la position
        Set<Integer> onlyGold = Set.of(2);
        assertEquals("&eOr", MedalEvaluator.best(medals, onlyGold::contains).name());
    }

    @Test
    void claimIndexLeavesCheckpointIndexesAlone() {
        assertEquals(1000, MedalEvaluator.claimIndex(0));
        assertEquals(1002, MedalEvaluator.claimIndex(2));
        assertTrue(MedalEvaluator.CLAIM_BASE > 999);
    }
}
