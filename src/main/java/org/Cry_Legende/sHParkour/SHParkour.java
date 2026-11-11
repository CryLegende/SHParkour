package org.Cry_Legende.sHParkour;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import org.Cry_Legende.sHParkour.commands.ParkourCommand;
import org.Cry_Legende.sHParkour.listeners.*;
import org.Cry_Legende.sHParkour.managers.*;
import org.Cry_Legende.sHParkour.network.NetworkLink;
import org.Cry_Legende.sHParkour.network.NetworkParkour;
import org.Cry_Legende.sHParkour.network.ParkourDatabase;
import org.Cry_Legende.sHParkour.network.ShNetworkLink;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.Cry_Legende.sHParkour.placeholders.ParkourExpansion;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.api.ISHInventoryPagesAPI;

import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;

public final class SHParkour extends JavaPlugin {

    private static SHParkour instance;
    private ConfigManager configManager;
    private ParkourManager parkourManager;
    private HologramManager hologramManager;
    private PlayerDataManager playerDataManager;
    private PlayerStateManager playerStateManager;
    private BlockManager blockManager;
    // Non nul seulement avec SHNetwork : temps et récompenses sont dans la base partagée
    private NetworkParkour network;

    private ISHInventoryPagesAPI shInventoryPagesAPI = null;

    @Override
    public void onEnable() {
        instance = this;

        // Vérification des dépendances
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null || !FancyHologramsPlugin.isEnabled()) {
            getLogger().severe("Une dépendance requise (PlaceholderAPI ou FancyHolograms) est manquante. Désactivation...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // AJOUT: Hook avec SHInventoryPages
        Plugin shInventoryPlugin = Bukkit.getPluginManager().getPlugin("SHInventoryPages");
        if (shInventoryPlugin instanceof SHInventoryPages && shInventoryPlugin.isEnabled()) {
            this.shInventoryPagesAPI = ((SHInventoryPages) shInventoryPlugin).getApi();
            if (this.shInventoryPagesAPI != null) {
                getLogger().info("Successfully hooked into SHInventoryPages API.");
            } else {
                getLogger().warning("Could not retrieve SHInventoryPages API, page switch blocking will not work.");
            }
        } else {
            getLogger().warning("SHInventoryPages plugin not found or not enabled. Page switch blocking will not work.");
            // Tu peux décider si c'est une dépendance dure et désactiver ton plugin ici si nécessaire.
            // Pour l'instant, on le laisse comme une dépendance optionnelle.
        }
        // FIN AJOUT

        // Initialisation des managers
        this.configManager = new ConfigManager(this);
        this.blockManager = new BlockManager(this);
        this.playerStateManager = new PlayerStateManager(this);
        this.playerDataManager = new PlayerDataManager(this);
        this.hologramManager = new HologramManager(this);
        // MODIFICATION: Passer l'API au ParkourManager
        this.parkourManager = new ParkourManager(this, shInventoryPagesAPI); // Passage de l'API

        // Chargement des données
        configManager.loadConfigs();
        enableNetwork();
        playerDataManager.loadPlayerData();
        parkourManager.loadParkours();
        if (network != null) {
            network.start(playerDataManager::flushPlayer, playerDataManager, parkourManager::knownParkourNames);
            // joueurs déjà là (rechargement à chaud) : lus comme à une arrivée
            for (Player online : Bukkit.getOnlinePlayers()) {
                playerDataManager.beginNetworkJoin(online);
            }
        }

        // Enregistrement des commandes et listeners
        Objects.requireNonNull(getCommand("parkour")).setExecutor(new ParkourCommand(this));
        Objects.requireNonNull(getCommand("parkour")).setTabCompleter(new ParkourCommand(this));
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new HologramVisibilityListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldListener(this), this);

        new ParkourExpansion(this).register();
        blockManager.startIntegrityCheck();
        // Sauvegarde périodique : sans elle, un arrêt brutal perdait les records faits depuis la dernière déconnexion.
        getServer().getScheduler().runTaskTimer(this, playerDataManager::savePlayerDataAsync, 6000L, 6000L);

        getLogger().info("SHParkour a été activé avec succès !");
    }

    @Override
    public void onDisable() {
        if (parkourManager != null) {
            for (Parkour parkour : parkourManager.getAllParkours()) {
                hologramManager.deleteHolograms(parkour);
                blockManager.removeParkourBlocks(parkour);
            }
            parkourManager.shutdown(); // parkourManager s'occupera de débloquer les joueurs
        }
        if (playerDataManager != null) {
            playerDataManager.savePlayerData();
        }
        if (network != null) {
            network.stop(); // termine les écritures en cours
        }
        getLogger().info("SHParkour a été désactivé.");
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont chargées qu'ici : sans lui, le plugin se comporte exactement comme avant (playerdata.yml).
     */
    private void enableNetwork() {
        FileConfiguration config = configManager.getMainConfig();
        if (!config.getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        ParkourDatabase db = null;
        try {
            NetworkLink link = ShNetworkLink.open();
            db = new ParkourDatabase(
                config.getString("reseau.base-de-donnees.url", ""),
                config.getString("reseau.base-de-donnees.utilisateur", ""),
                config.getString("reseau.base-de-donnees.mot-de-passe", ""),
                Math.max(250, config.getInt("reseau.base-de-donnees.delai-ms", 3000)));
            db.createTables();
            Duration wait = Duration.ofSeconds(Math.max(1, config.getInt("reseau.attente-max-secondes", 10)));
            String channel = config.getString("reseau.canal", "shparkour").toLowerCase();
            network = new NetworkParkour(this, db, link, wait, channel, config.getInt("reseau.cache-classements-secondes", 15));
            playerDataManager.setNetwork(network);
            getLogger().info("SHNetwork détecté : temps, parcours terminés et classements sont dans la base partagée.");
        } catch (Exception | LinkageError e) {
            network = null;
            if (db != null) db.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, parcours locaux à ce serveur : " + e);
        }
    }

    /** Le mode réseau, ou null sans SHNetwork. */
    public NetworkParkour getNetwork() {
        return network;
    }

    public static SHParkour getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ParkourManager getParkourManager() {
        return parkourManager;
    }

    public HologramManager getHologramManager() {
        return hologramManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public PlayerStateManager getPlayerStateManager() {
        return playerStateManager;
    }

    public BlockManager getBlockManager() {
        return blockManager;
    }

    // AJOUT: Getter pour l'API (si d'autres classes en ont besoin, bien que ParkourManager soit le point central ici)
    public ISHInventoryPagesAPI getShInventoryPagesAPI() {
        return shInventoryPagesAPI;
    }
    // FIN AJOUT
}
