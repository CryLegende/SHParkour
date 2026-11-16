package org.Cry_Legende.sHParkour.managers;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.objects.PlayerState;
import org.Cry_Legende.sHParkour.utils.ParkourItems;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;

/**
 * playerstates/&lt;uuid&gt;.yml : ce que le joueur avait en entrant dans son parcours, et les objets reçus depuis.
 * Écrit en entier (fichier temporaire puis remplacement) avant de toucher à l'inventaire, effacé une fois rendu.
 */
public class PlayerStateManager {

    private final SHParkour plugin;
    private final File statesFolder;

    /**
     * Retour d'un état.
     *
     * @param received  nombre d'objets reçus pendant le parcours, rendus avec l'inventaire
     * @param leftovers objets reçus qui ne rentraient plus dans l'inventaire (à jeter aux pieds du joueur)
     */
    public record Restored(int received, List<ItemStack> leftovers) {
        static final Restored NONE = new Restored(0, List.of());
    }

    public PlayerStateManager(SHParkour plugin) {
        this.plugin = plugin;
        this.statesFolder = new File(plugin.getDataFolder(), "playerstates");
        if (!statesFolder.exists()) {
            statesFolder.mkdirs();
        }
    }

    /**
     * @return l'état écrit, ou null s'il n'a pas pu l'être : le parcours ne doit alors pas démarrer.
     */
    public PlayerState saveState(Player player) {
        if (stateFile(player).exists()) {
            plugin.getLogger().warning("Un fichier d'état existait déjà pour " + player.getName() + ". Il va être écrasé.");
        }
        PlayerState state = new PlayerState(player);
        return write(player, state) ? state : null;
    }

    /**
     * Ajoute des objets reçus pendant le parcours au fichier d'état.
     *
     * @return false si le fichier n'existe pas ou n'a pas pu être écrit (les objets doivent alors être rendus)
     */
    public boolean hold(Player player, List<ItemStack> items) {
        PlayerState state = load(player);
        if (state == null || !state.ownsParkourInventory(player)) return false;
        state.hold(items);
        return write(player, state);
    }

    /**
     * Rend l'état sauvegardé, ajoute à l'inventaire les objets reçus pendant le parcours, écrit le fichier du joueur
     * puis efface l'état (un arrêt brutal juste après ne fait rien perdre).
     */
    public Restored restoreState(Player player) {
        File stateFile = stateFile(player);
        PlayerState state = load(player);
        if (state == null) {
            // Pas d'état à restaurer, situation normale.
            return Restored.NONE;
        }
        List<ItemStack> received = state.restore(player);
        List<ItemStack> leftovers = ParkourItems.give(player, received);
        try {
            player.saveData();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Sauvegarde du fichier de " + player.getName() + " impossible", e);
        }
        // Très important : supprimer le fichier après restauration pour éviter les doubles restaurations.
        if (!stateFile.delete()) {
            plugin.getLogger().warning("Impossible de supprimer le fichier d'état pour " + player.getName());
        }
        if (!received.isEmpty()) {
            plugin.getLogger().info(player.getName() + " : " + received.size() + " objet(s) reçu(s) pendant le parcours rendu(s)"
                + (leftovers.isEmpty() ? "." : ", " + leftovers.size() + " au sol (inventaire plein)."));
        }
        return new Restored(received.size(), leftovers);
    }

    public boolean hasSavedState(Player player) {
        return stateFile(player).exists();
    }

    // Méthode de sécurité à appeler au onJoin
    public void restoreStateIfAvailable(Player player) {
        if (hasSavedState(player)) {
            plugin.getLogger().info("Fichier d'état trouvé pour " + player.getName() + " (probable crash/déco). Restauration...");
            Restored restored = restoreState(player);
            ParkourItems.drop(player, restored.leftovers());
            if (restored.received() > 0) {
                // À la connexion, le message part avant que le client affiche le chat : envoyé 2 s plus tard.
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) player.sendMessage(plugin.getConfigManager().getMessage("objets-recus.rendus"));
                }, 40L);
            }
        }
    }

    private PlayerState load(Player player) {
        File stateFile = stateFile(player);
        if (!stateFile.exists()) return null;
        try {
            return new PlayerState(YamlConfiguration.loadConfiguration(stateFile));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Fichier d'état de " + player.getName() + " illisible, gardé tel quel", e);
            return null;
        }
    }

    private boolean write(Player player, PlayerState state) {
        File stateFile = stateFile(player);
        File temp = new File(statesFolder, player.getUniqueId() + ".yml.tmp");
        YamlConfiguration config = new YamlConfiguration();
        state.saveToConfig(config);
        try {
            config.save(temp);
            Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de sauvegarder l'état du joueur " + player.getName(), e);
            return false;
        }
    }

    private File stateFile(Player player) {
        return new File(statesFolder, player.getUniqueId() + ".yml");
    }
}
