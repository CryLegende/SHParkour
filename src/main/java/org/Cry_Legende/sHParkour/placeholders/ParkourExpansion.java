package org.Cry_Legende.sHParkour.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.managers.PlayerDataManager;
import org.Cry_Legende.sHParkour.utils.TimeUtil;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholders (le nom du parcours peut contenir des "_") :
 * <ul>
 *     <li>%shparkour_&lt;parcours&gt;_time_formatted% : meilleur temps du joueur (secondes, 3 décimales)</li>
 *     <li>%shparkour_&lt;parcours&gt;_time_ms% : meilleur temps du joueur en millisecondes</li>
 *     <li>%shparkour_&lt;parcours&gt;_rank% : position du joueur au classement</li>
 *     <li>%shparkour_&lt;parcours&gt;_top_&lt;n&gt;_name|time|ms% : n-ième du classement</li>
 * </ul>
 * Les classements viennent du cache de PlayerDataManager : un temps retiré par /parkour resettime
 * disparaît au rafraîchissement suivant de l'hologramme ou du menu qui affiche le placeholder.
 */
public class ParkourExpansion extends PlaceholderExpansion {

    private static final String NONE = "N/A";
    private static final Pattern TOP = Pattern.compile("^(.+)_top_(\\d+)_(name|time|ms)$");
    private static final Pattern PERSONAL = Pattern.compile("^(.+)_(time_formatted|time_ms|rank)$");

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
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        String request = params.toLowerCase(Locale.ROOT);
        PlayerDataManager data = plugin.getPlayerDataManager();

        Matcher top = TOP.matcher(request);
        if (top.matches()) {
            return topValue(data, top.group(1), top.group(2), top.group(3));
        }

        Matcher personal = PERSONAL.matcher(request);
        if (personal.matches()) {
            if (player == null) {
                return NONE;
            }
            String parkour = personal.group(1);
            long time = data.getBestTime(player.getUniqueId(), parkour);
            return switch (personal.group(2)) {
                case "time_formatted" -> TimeUtil.formatTime(time);
                case "time_ms" -> time != -1 ? String.valueOf(time) : NONE;
                default -> {
                    Integer rank = data.getLeaderboard(parkour).ranks().get(player.getUniqueId());
                    yield rank != null ? String.valueOf(rank) : NONE;
                }
            };
        }

        return null; // Placeholder inconnu : PlaceholderAPI le laisse tel quel, l'erreur se voit.
    }

    private String topValue(PlayerDataManager data, String parkour, String rankText, String type) {
        int rank;
        try {
            rank = Integer.parseInt(rankText);
        } catch (NumberFormatException e) {
            return NONE;
        }
        List<PlayerDataManager.Entry> entries = data.getLeaderboard(parkour).entries();
        if (rank < 1 || rank > entries.size()) {
            return NONE;
        }
        PlayerDataManager.Entry entry = entries.get(rank - 1);
        return switch (type) {
            case "name" -> {
                String name = data.getName(entry.uuid());
                yield name != null ? name : "Inconnu";
            }
            case "time" -> TimeUtil.formatTime(entry.time());
            default -> String.valueOf(entry.time());
        };
    }
}
