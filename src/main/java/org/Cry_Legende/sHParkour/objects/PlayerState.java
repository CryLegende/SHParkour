package org.Cry_Legende.sHParkour.objects;

import org.Cry_Legende.sHParkour.utils.ParkourItems;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Ce que le joueur avait en entrant dans un parcours, plus les objets reçus pendant le parcours (mis de côté,
 * rendus après l'inventaire).
 */
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
    /** Marque posée sur le joueur quand son inventaire est vidé (null : fichier d'une version sans marque). */
    private final String token;
    private final List<ItemStack> heldItems;

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
        this.token = UUID.randomUUID().toString();
        this.heldItems = new ArrayList<>();
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
        this.token = config.getString("token");
        this.heldItems = new ArrayList<>();
        for (Object value : config.getList("held-items", List.of())) {
            if (value instanceof ItemStack item && !item.isEmpty()) heldItems.add(item);
        }
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
        config.set("token", token);
        config.set("held-items", heldItems.isEmpty() ? null : heldItems);
    }

    public String getToken() {
        return token;
    }

    /** Vrai si l'inventaire actuel du joueur est l'inventaire de parcours de cet état. */
    public boolean ownsParkourInventory(Player player) {
        return token != null && token.equals(ParkourItems.session(player));
    }

    /** Met de côté des objets reçus pendant le parcours. */
    public void hold(List<ItemStack> items) {
        heldItems.addAll(items);
    }

    /**
     * Restaure l'état sauvegardé sur le joueur spécifié.
     *
     * @param player Le joueur à qui restaurer l'état.
     * @return les objets reçus pendant le parcours (mis de côté, puis ceux encore dans l'inventaire de parcours), à
     * ajouter à l'inventaire rendu
     */
    public List<ItemStack> restore(Player player) {
        List<ItemStack> received = new ArrayList<>(heldItems);
        if (ownsParkourInventory(player)) {
            received.addAll(ParkourItems.foreign(player));
        }

        // Vider l'état actuel avant de restaurer
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));

        // Restaurer l'inventaire et l'armure
        player.getInventory().setContents(inventoryContents);
        player.getInventory().setArmorContents(armorContents);
        ParkourItems.clearSession(player);

        // Restaurer le mode de jeu
        player.setGameMode(gameMode);

        // La vie restaurée ne dépasse pas la vie maximale du joueur au moment du retour
        double maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        player.setHealth(Math.min(this.health, maxHealth));

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
        return received;
    }
}
