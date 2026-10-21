package org.Cry_Legende.sHParkour.managers;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.objects.PlayerState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public class PlayerStateManager {

    private final SHParkour plugin;
    private final File statesFolder;

    public PlayerStateManager(SHParkour plugin) {
        this.plugin = plugin;
        this.statesFolder = new File(plugin.getDataFolder(), "playerstates");
        if (!statesFolder.exists()) {
            statesFolder.mkdirs();
        }
    }

    public void saveState(Player player) {
        File stateFile = new File(statesFolder, player.getUniqueId() + ".yml");
        if (stateFile.exists()) {
            plugin.getLogger().warning("Un fichier d'état existait déjà pour " + player.getName() + ". Il va être écrasé.");
        }

        PlayerState state = new PlayerState(player);
        YamlConfiguration config = new YamlConfiguration();
        state.saveToConfig(config);

        try {
            config.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de sauvegarder l'état du joueur " + player.getName(), e);
        }
    }

    public void restoreState(Player player) {
        File stateFile = new File(statesFolder, player.getUniqueId() + ".yml");
        if (!stateFile.exists()) {
            // Pas d'état à restaurer, situation normale.
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(stateFile);
        PlayerState state = new PlayerState(config); // On utilise le constructeur qui charge depuis la config
        state.restore(player);

        // Très important : supprimer le fichier après restauration pour éviter les doubles restaurations.
        if (!stateFile.delete()) {
            plugin.getLogger().warning("Impossible de supprimer le fichier d'état pour " + player.getName());
        }
    }

    public boolean hasSavedState(Player player) {
        return new File(statesFolder, player.getUniqueId() + ".yml").exists();
    }

    // Méthode de sécurité à appeler au onJoin
    public void restoreStateIfAvailable(Player player) {
        if (hasSavedState(player)) {
            plugin.getLogger().info("Fichier d'état trouvé pour " + player.getName() + " (probable crash/déco). Restauration...");
            restoreState(player);
        }
    }
}
