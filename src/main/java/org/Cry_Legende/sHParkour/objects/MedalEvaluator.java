package org.Cry_Legende.sHParkour.objects;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Règles des médailles de temps, sans rien de Bukkit : lecture de la liste {@code medailles}, médailles atteintes par un
 * temps, médaille la plus difficile déjà obtenue.
 * <p>
 * Une médaille est identifiée par sa position dans la liste (0 pour la première). Les médailles déjà données sont
 * mémorisées avec les récompenses de checkpoint, sous l'index {@link #CLAIM_BASE} + position : pas de nouvelle table
 * en mode réseau. L'ordre de la liste ne doit donc jamais changer, une nouvelle médaille s'ajoute à la fin.
 */
public final class MedalEvaluator {

    /** Premier index de médaille dans la mémoire des récompenses de checkpoint (un parcours n'a jamais 1000 checkpoints). */
    public static final int CLAIM_BASE = 1000;

    private MedalEvaluator() {
    }

    /** Index de la médaille à la position donnée dans la mémoire des récompenses. */
    public static int claimIndex(int position) {
        return CLAIM_BASE + position;
    }

    /**
     * Lit la liste {@code medailles} d'un parcours. Une entrée invalide garde sa place (les positions ne bougent pas) mais
     * ne sera jamais obtenue, et {@code warn} reçoit la raison.
     */
    public static List<Medal> parse(@Nullable List<Map<?, ?>> raw, Consumer<String> warn) {
        List<Medal> medals = new ArrayList<>();
        if (raw == null) {
            return medals;
        }
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            String label = "médaille " + (i + 1);
            Object nameValue = entry.get("nom");
            String name = nameValue == null ? "Médaille " + (i + 1) : String.valueOf(nameValue);

            long maxTimeMs = 0;
            Object timeValue = entry.get("temps-max");
            Double seconds = toSeconds(timeValue);
            if (seconds == null || seconds <= 0) {
                warn.accept(label + " (" + name + ") : 'temps-max' doit être un nombre de secondes supérieur à 0, médaille ignorée.");
            } else {
                maxTimeMs = Math.round(seconds * 1000.0);
            }

            List<String> commands = new ArrayList<>();
            Object commandValue = entry.get("commandes");
            if (commandValue instanceof List<?> list) {
                for (Object command : list) {
                    if (command != null && !String.valueOf(command).isBlank()) {
                        commands.add(String.valueOf(command));
                    }
                }
            } else if (commandValue != null && !String.valueOf(commandValue).isBlank()) {
                commands.add(String.valueOf(commandValue));
            }
            medals.add(new Medal(name, maxTimeMs, List.copyOf(commands)));
        }
        return medals;
    }

    @Nullable
    private static Double toSeconds(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Positions des médailles que ce temps atteint et que le joueur n'a pas encore, dans l'ordre de la liste. Un temps
     * rapide les atteint toutes d'un coup (médailles cumulatives). Le temps égal au maximum compte.
     */
    public static List<Integer> reached(List<Medal> medals, long timeMs, IntPredicate alreadyClaimed) {
        List<Integer> positions = new ArrayList<>();
        for (int position = 0; position < medals.size(); position++) {
            Medal medal = medals.get(position);
            if (medal.valid() && timeMs <= medal.maxTimeMs() && !alreadyClaimed.test(position)) {
                positions.add(position);
            }
        }
        return positions;
    }

    /** Nombre de médailles du parcours déjà obtenues. */
    public static int count(List<Medal> medals, IntPredicate claimed) {
        int total = 0;
        for (int position = 0; position < medals.size(); position++) {
            if (medals.get(position).valid() && claimed.test(position)) {
                total++;
            }
        }
        return total;
    }

    /** Médaille obtenue au temps maximal le plus court (la plus difficile), ou null s'il n'y en a aucune. */
    @Nullable
    public static Medal best(List<Medal> medals, IntPredicate claimed) {
        Medal best = null;
        for (int position = 0; position < medals.size(); position++) {
            Medal medal = medals.get(position);
            if (medal.valid() && claimed.test(position) && (best == null || medal.maxTimeMs() < best.maxTimeMs())) {
                best = medal;
            }
        }
        return best;
    }
}
