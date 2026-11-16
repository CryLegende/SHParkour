package org.Cry_Legende.sHParkour.utils;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Objets du parcours (marqués) et objets reçus pendant un parcours.
 * <p>
 * Tout objet non marqué qui arrive dans l'inventaire de parcours (clé de vote, lot d'un autre plugin, objet ramassé)
 * est retiré dès qu'il est vu, écrit dans le fichier d'état du joueur et rendu à la fin du parcours, après son
 * inventaire. Les objets d'interface d'autres plugins (config.yml objets-recus.ignorer) restent en place.
 * <p>
 * La marque de session posée sur le joueur quand son inventaire est vidé dit si l'inventaire actuel est bien
 * l'inventaire de parcours : après un arrêt brutal, le fichier du joueur peut dater d'avant le parcours et contenir
 * ses propres objets, qu'il ne faut pas compter une deuxième fois.
 */
public final class ParkourItems {

    private static NamespacedKey itemKey;
    private static NamespacedKey sessionKey;
    private static volatile Set<NamespacedKey> ignored = Set.of();

    private ParkourItems() {
    }

    public static void init(Plugin plugin) {
        itemKey = new NamespacedKey(plugin, "item");
        sessionKey = new NamespacedKey(plugin, "session");
    }

    /** Clés (namespace:clé) des objets d'interface d'autres plugins, à laisser en place. */
    public static void ignore(Collection<String> keys, Logger logger) {
        Set<NamespacedKey> set = new HashSet<>();
        for (String key : keys) {
            NamespacedKey parsed = key == null ? null : NamespacedKey.fromString(key.trim().toLowerCase(Locale.ROOT));
            if (parsed == null) {
                logger.warning("objets-recus.ignorer : clé invalide ignorée : " + key);
            } else {
                set.add(parsed);
            }
        }
        ignored = Set.copyOf(set);
    }

    public static void mark(ItemMeta meta) {
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isMarked(ItemStack item) {
        return item != null && !item.isEmpty() && item.getPersistentDataContainer().has(itemKey);
    }

    /** Objet reçu d'ailleurs : ni objet du parcours, ni objet d'interface d'un autre plugin. */
    public static boolean isForeign(ItemStack item) {
        if (item == null || item.isEmpty() || isMarked(item)) return false;
        for (NamespacedKey key : ignored) {
            if (item.getPersistentDataContainer().has(key)) return false;
        }
        return true;
    }

    /** Objets reçus d'ailleurs encore dans l'inventaire (armure et seconde main comprises). */
    public static List<ItemStack> foreign(Player player) {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (isForeign(item)) list.add(item.clone());
        }
        return list;
    }

    /** Retire de l'inventaire (et du curseur) les objets reçus d'ailleurs et les retourne. */
    public static List<ItemStack> takeForeign(Player player) {
        List<ItemStack> taken = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isForeign(contents[i])) {
                taken.add(contents[i].clone());
                inventory.setItem(i, null);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (isForeign(cursor)) {
            taken.add(cursor.clone());
            player.setItemOnCursor(null);
        }
        return taken;
    }

    /** Ajoute les objets à l'inventaire et retourne ce qui ne rentre pas. */
    public static List<ItemStack> give(Player player, Collection<ItemStack> items) {
        if (items.isEmpty()) return List.of();
        return new ArrayList<>(player.getInventory().addItem(items.toArray(ItemStack[]::new)).values());
    }

    /** Jette au sol, aux pieds du joueur. */
    public static void drop(Player player, Collection<ItemStack> items) {
        for (ItemStack item : items) {
            player.getWorld().dropItem(player.getLocation(), item);
        }
    }

    public static void setSession(Player player, String token) {
        player.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, token);
    }

    public static String session(Player player) {
        return player.getPersistentDataContainer().get(sessionKey, PersistentDataType.STRING);
    }

    public static void clearSession(Player player) {
        player.getPersistentDataContainer().remove(sessionKey);
    }
}
