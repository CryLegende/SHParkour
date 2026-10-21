package org.Cry_Legende.sHParkour.objects;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute; // IMPORT AJOUTÉ
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;
import java.util.List;

public class PlayerState {

    private final ItemStack[] inventoryContents;
    private final ItemStack[] armorContents;
    private final GameMode gameMode;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final float exhaustion;
    private final int level;
    private final float exp;
    private final Collection<PotionEffect> potionEffects;
    private final boolean allowFlight;

    /**
     * Crée une nouvelle instance de PlayerState en capturant l'état actuel du joueur.
     *
     * @param player Le joueur dont l'état doit être sauvegardé.
     */
    public PlayerState(Player player) {
        this.inventoryContents = player.getInventory().getContents();
        this.armorContents = player.getInventory().getArmorContents();
        this.gameMode = player.getGameMode();
        this.health = player.getHealth();
        this.foodLevel = player.getFoodLevel();
        this.saturation = player.getSaturation();
        this.exhaustion = player.getExhaustion();
        this.level = player.getLevel();
        this.exp = player.getExp();
        this.potionEffects = player.getActivePotionEffects();
        this.allowFlight = player.getAllowFlight();
    }

    /**
     * Crée une nouvelle instance de PlayerState en chargeant les données depuis un fichier de configuration.
     *
     * @param config La configuration Yaml contenant l'état du joueur.
     */
    @SuppressWarnings("unchecked")
    public PlayerState(FileConfiguration config) {
        this.inventoryContents = ((List<ItemStack>) config.get("inventory.contents")).toArray(new ItemStack[0]);
        this.armorContents = ((List<ItemStack>) config.get("inventory.armor")).toArray(new ItemStack[0]);
        this.gameMode = GameMode.valueOf(config.getString("gamemode", "SURVIVAL"));
        this.health = config.getDouble("stats.health", 20.0);
        this.foodLevel = config.getInt("stats.food", 20);
        this.saturation = (float) config.getDouble("stats.saturation", 20.0);
        this.exhaustion = (float) config.getDouble("stats.exhaustion", 0.0);
        this.level = config.getInt("experience.level", 0);
        this.exp = (float) config.getDouble("experience.exp", 0.0);
        this.potionEffects = (Collection<PotionEffect>) config.get("potion-effects", List.of());
        this.allowFlight = config.getBoolean("allow-flight", false);
    }

    /**
     * Sauvegarde l'état du joueur dans un objet FileConfiguration.
     *
     * @param config La configuration où sauvegarder les données.
     */
    public void saveToConfig(FileConfiguration config) {
        config.set("inventory.contents", inventoryContents);
        config.set("inventory.armor", armorContents);
        config.set("gamemode", gameMode.name());
        config.set("stats.health", health);
        config.set("stats.food", foodLevel);
        config.set("stats.saturation", saturation);
        config.set("stats.exhaustion", exhaustion);
        config.set("experience.level", level);
        config.set("experience.exp", exp);
        config.set("potion-effects", potionEffects);
        config.set("allow-flight", allowFlight);
    }

    /**
     * Restaure l'état sauvegardé sur le joueur spécifié.
     *
     * @param player Le joueur à qui restaurer l'état.
     */
    public void restore(Player player) {
        // Vider l'état actuel avant de restaurer
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));

        // Restaurer l'inventaire et l'armure
        player.getInventory().setContents(inventoryContents);
        player.getInventory().setArmorContents(armorContents);

        // Restaurer le mode de jeu
        player.setGameMode(gameMode);

        // --- CORRECTION APPLIQUÉE ICI ---
        // On récupère la vie maximale autorisée pour le joueur au moment de la restauration
        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        // On s'assure que la vie à restaurer ne dépasse pas ce maximum
        double healthToRestore = Math.min(this.health, maxHealth);
        player.setHealth(healthToRestore);
        // --- FIN DE LA CORRECTION ---

        // Restaurer les autres statistiques
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        player.setLevel(level);
        player.setExp(exp);

        // Restaurer les effets de potion
        if (potionEffects != null) {
            potionEffects.forEach(player::addPotionEffect);
        }

        // Restaurer la capacité de voler
        player.setAllowFlight(allowFlight);
    }
}
