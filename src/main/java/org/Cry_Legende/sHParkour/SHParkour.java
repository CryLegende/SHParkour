package org.Cry_Legende.sHParkour;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import org.Cry_Legende.sHParkour.commands.ParkourCommand;
import org.Cry_Legende.sHParkour.listeners.*;
import org.Cry_Legende.sHParkour.managers.*;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.Cry_Legende.sHParkour.placeholders.ParkourExpansion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.api.ISHInventoryPagesAPI;

import java.util.Objects;

public final class SHParkour extends JavaPlugin {

    private static SHParkour instance;
    private ConfigManager configManager;
    private ParkourManager parkourManager;
    private HologramManager hologramManager;
    private PlayerDataManager playerDataManager;
    private PlayerStateManager playerStateManager;
    private BlockManager blockManager;

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
        playerDataManager.loadPlayerData();
        parkourManager.loadParkours();

        // Enregistrement des commandes et listeners
        Objects.requireNonNull(getCommand("parkour")).setExecutor(new ParkourCommand(this));
        Objects.requireNonNull(getCommand("parkour")).setTabCompleter(new ParkourCommand(this));
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new HologramVisibilityListener(this), this);

        new ParkourExpansion(this).register();
        blockManager.startIntegrityCheck();

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
        getLogger().info("SHParkour a été désactivé.");
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
