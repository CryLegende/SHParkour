package org.Cry_Legende.sHParkour.utils;

public class TimeUtil {

    /**
     * Formate les millisecondes en un total de secondes avec 3 décimales (ex: 123.456).
     *
     * @param millis Le temps en millisecondes.
     * @return Le temps formaté en String, ou "N/A" si invalide.
     */
    public static String formatTime(long millis) {
        if (millis < 0) return "N/A";

        // On convertit les millisecondes en secondes (double pour garder la précision)
        double seconds = millis / 1000.0;

        // On formate le double pour avoir exactement 3 décimales
        return String.format("%.3f", seconds);
    }
}
