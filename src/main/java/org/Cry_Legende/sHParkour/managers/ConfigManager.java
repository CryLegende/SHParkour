package org.Cry_Legende.sHParkour.managers;

import org.Cry_Legende.sHParkour.SHParkour;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;

public class ConfigManager {

    private final SHParkour plugin;
    private FileConfiguration mainConfig;
    private File parkoursFolder;

    public ConfigManager(SHParkour plugin) {
        this.plugin = plugin;
    }

    public void loadConfigs() {
        plugin.saveDefaultConfig();
        mainConfig = plugin.getConfig();

        parkoursFolder = new File(plugin.getDataFolder(), "parkours");
        if (!parkoursFolder.exists()) {
            parkoursFolder.mkdirs();
        }
    }

    public void reloadConfigs() {
        plugin.reloadConfig();
        mainConfig = plugin.getConfig();
        plugin.getParkourManager().reloadParkours();
    }

    public String getMessage(String path) {
        String message = mainConfig.getString("messages." + path, "&cMessage non trouve: " + path);
        String prefix = mainConfig.getString("prefix", "&8[&bSHParkour&8] &r");
        return ChatColor.translateAlternateColorCodes('&', prefix + message);
    }

    public String getRawMessage(String path) {
        String message = mainConfig.getString("messages." + path, "&cMessage non trouve: " + path);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public List<String> getWhitelistedCommands() {
        List<String> commands = mainConfig.getStringList("allowed-commands");
        commands.add("parkour");
        commands.add("pk");
        return commands;
    }

    public String getActionbarFormat() {
        return mainConfig.getString("actionbar-format", "&b{parkour_display_name} &7| Temps: &f{time}");
    }

    public FileConfiguration getMainConfig() {
        return mainConfig;
    }

    public File getParkoursFolder() {
        return parkoursFolder;
    }

    public File getParkourFile(String parkourName) {
        return new File(parkoursFolder, parkourName.toLowerCase() + ".yml");
    }

    public FileConfiguration getParkourConfig(String parkourName) {
        File parkourFile = getParkourFile(parkourName);
        if (!parkourFile.exists()) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(parkourFile);
    }

    public boolean createParkourFile(String parkourName) {
        File parkourFile = getParkourFile(parkourName);
        if (parkourFile.exists()) {
            return false;
        }

        try {
            InputStream templateStream = plugin.getResource("parkour_template.yml");
            if (templateStream == null) {
                plugin.getLogger().severe("Impossible de trouver parkour_template.yml dans les ressources du jar !");
                return false;
            }

            String templateContent;
            try (templateStream) {
                templateContent = new String(templateStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            String placeholdersComment = buildPlaceholdersComment(parkourName.toLowerCase());
            String fileContent = templateContent + System.lineSeparator() + placeholdersComment;
            Files.writeString(parkourFile.toPath(), fileContent, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de creer le fichier de parcours pour " + parkourName, e);
            return false;
        }
    }

    private String buildPlaceholdersComment(String parkourName) {
        String lineSeparator = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("# --- Placeholders utiles ---").append(lineSeparator);
        builder.append("# Variables internes SHParkour:").append(lineSeparator);
        builder.append("# - {player}").append(lineSeparator);
        builder.append("# - {parkour_display_name}").append(lineSeparator);
        builder.append("# - {checkpoint_index} (uniquement dans checkpoint-rewards)").append(lineSeparator);
        builder.append("#").append(lineSeparator);
        builder.append("# Placeholders PlaceholderAPI pour ce parcours:").append(lineSeparator);
        builder.append("# - %shparkour_").append(parkourName).append("_time_formatted%").append(lineSeparator);
        builder.append("# - %shparkour_").append(parkourName).append("_time_ms%").append(lineSeparator);
        builder.append("# - %shparkour_").append(parkourName).append("_top_1_name%").append(lineSeparator);
        builder.append("# - %shparkour_").append(parkourName).append("_top_1_time%").append(lineSeparator);
        builder.append("# - %shparkour_").append(parkourName).append("_top_2_name%").append(lineSeparator);
        builder.append("# - %shparkour_").append(parkourName).append("_top_2_time%").append(lineSeparator);
        builder.append("# - %shparkour_").append(parkourName).append("_top_3_name%").append(lineSeparator);
        builder.append("# - %shparkour_").append(parkourName).append("_top_3_time%");
        return builder.toString();
    }

    public boolean deleteParkourFile(String parkourName) {
        File parkourFile = getParkourFile(parkourName);
        if (!parkourFile.exists()) {
            return false;
        }
        return parkourFile.delete();
    }
}
