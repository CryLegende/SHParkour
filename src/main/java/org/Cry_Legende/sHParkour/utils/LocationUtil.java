package org.Cry_Legende.sHParkour.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;

public class LocationUtil {

    public static String serialize(Location loc) {
        if (loc == null) {
            return null;
        }
        return loc.getWorld().getName() + ";" +
            loc.getX() + ";" +
            loc.getY() + ";" +
            loc.getZ() + ";" +
            loc.getYaw() + ";" +
            loc.getPitch();
    }

    public static Location deserialize(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String[] parts = s.split(";");
        if (parts.length != 6) {
            return null;
        }
        try {
            return new Location(
                Bukkit.getWorld(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
                Float.parseFloat(parts[4]),
                Float.parseFloat(parts[5])
            );
        } catch (Exception e) {
            // Gérer le cas où le monde n'existe pas ou le format est incorrect
            return null;
        }
    }

    /**
     * Nom du monde d'une position sérialisée, sans exiger que ce monde soit chargé.
     *
     * @return le nom du monde, ou null si la chaîne est vide ou mal formée.
     */
    public static String worldName(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String[] parts = s.split(";");
        if (parts.length != 6 || parts[0].isEmpty()) {
            return null;
        }
        return parts[0];
    }
}
