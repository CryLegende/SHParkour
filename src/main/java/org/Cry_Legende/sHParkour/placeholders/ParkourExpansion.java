package org.Cry_Legende.sHParkour.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.utils.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ParkourExpansion extends PlaceholderExpansion {

    private final SHParkour plugin;

    public ParkourExpansion(SHParkour plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "shparkour";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Cry_Legende";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        String[] parts = params.split("_");
        if (parts.length < 2) {
            return null; // Format de placeholder invalide
        }

        String parkourName = parts[0];
        String requestType = parts[parts.length - 1]; // "formatted", "ms", ou "name"/"time" pour le top

        // --- Placeholders de temps personnel ---
        // %shparkour_<parkour_name>_time_formatted%
        if (requestType.equals("formatted") && parts[parts.length - 2].equals("time")) {
            long time = plugin.getPlayerDataManager().getBestTime(player.getUniqueId(), parkourName);
            // <<<< CORRECTION: Utilise le nouveau TimeUtil >>>>
            return TimeUtil.formatTime(time);
        }

        // <<<< NOUVEAU: %shparkour_<parkour_name>_time_ms% >>>>
        if (requestType.equals("ms") && parts[parts.length - 2].equals("time")) {
            long time = plugin.getPlayerDataManager().getBestTime(player.getUniqueId(), parkourName);
            return time != -1 ? String.valueOf(time) : "N/A";
        }

        // --- Placeholders du leaderboard ---
        // %shparkour_<parkour_name>_top_<rank>_name%
        // %shparkour_<parkour_name>_top_<rank>_time%
        if (params.contains("_top_")) {
            try {
                int rank = Integer.parseInt(parts[parts.length - 2]); // L'avant-dernier est le rang
                String type = parts[parts.length - 1]; // Le dernier est 'name' ou 'time'
                String actualParkourName = params.substring(0, params.indexOf("_top_"));

                Map<UUID, Long> leaderboard = plugin.getPlayerDataManager().getLeaderboard(actualParkourName);
                List<Map.Entry<UUID, Long>> sortedList = leaderboard.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .collect(Collectors.toList());

                if (rank > 0 && rank <= sortedList.size()) {
                    Map.Entry<UUID, Long> entry = sortedList.get(rank - 1);
                    if ("name".equalsIgnoreCase(type)) {
                        OfflinePlayer topPlayer = Bukkit.getOfflinePlayer(entry.getKey());
                        return topPlayer.getName() != null ? topPlayer.getName() : "Inconnu";
                    } else if ("time".equalsIgnoreCase(type)) {
                        // <<<< CORRECTION: Utilise le nouveau TimeUtil >>>>
                        return TimeUtil.formatTime(entry.getValue());
                    }
                } else {
                    return "N/A";
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                // Le format est incorrect, on ne fait rien.
            }
        }

        return null; // Le placeholder n'est pas reconnu
    }
}
