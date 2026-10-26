package org.Cry_Legende.sHParkour.commands;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.managers.ParkourManager;
import org.Cry_Legende.sHParkour.managers.PlayerDataManager;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ParkourCommand implements CommandExecutor, TabCompleter {

    private final SHParkour plugin;
    private final ParkourManager parkourManager;
    private final PlayerDataManager playerDataManager;

    public ParkourCommand(SHParkour plugin) {
        this.plugin = plugin;
        this.parkourManager = plugin.getParkourManager();
        this.playerDataManager = plugin.getPlayerDataManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§b/parkour <create|delete|setup|reload|resettime>");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                handleCreate(sender, args);
                break;
            case "delete":
                handleDelete(sender, args);
                break;
            case "setup":
                handleSetup(sender, args);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "resettime":
            case "rt":
                handleResetTime(sender, args);
                break;
            default:
                sender.sendMessage("§b/parkour <create|delete|setup|reload|resettime>");
                break;
        }
        return true;
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shparkour.admin.create")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /parkour create <nom>");
            return;
        }
        String parkourName = args[1];
        if (parkourManager.getParkour(parkourName) != null || parkourManager.isPending(parkourName)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("parkour-already-exists").replace("{parkour}", parkourName));
            return;
        }

        parkourManager.createParkour(parkourName);
        sender.sendMessage(plugin.getConfigManager().getMessage("parkour-created").replace("{parkour}", parkourName));
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shparkour.admin.delete")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /parkour delete <nom>");
            return;
        }
        String parkourName = args[1];
        if (parkourManager.getParkour(parkourName) == null && !parkourManager.isPending(parkourName)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("parkour-not-found").replace("{parkour}", parkourName));
            return;
        }

        parkourManager.deleteParkour(parkourName);
        sender.sendMessage(plugin.getConfigManager().getMessage("parkour-deleted").replace("{parkour}", parkourName));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("shparkour.admin.reload")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        plugin.getParkourManager().reloadParkours();
        sender.sendMessage(plugin.getConfigManager().getMessage("parkour-reloaded"));
    }

    private void handleSetup(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only"));
            return;
        }
        if (!player.hasPermission("shparkour.admin.setup")) {
            player.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        if (args.length < 3) {
            player.sendMessage("§cUsage: /parkour setup <nom> <option> [valeur]");
            return;
        }

        String parkourName = args[1];
        Parkour parkour = parkourManager.getParkour(parkourName);
        if (parkour == null && parkourManager.isPending(parkourName)) {
            // Parcours en attente de son monde (voir ParkourManager) : rien à configurer avant son chargement.
            player.sendMessage("§cLe parcours '" + parkourName + "' attend le chargement du monde "
                + String.join(", ", parkourManager.getPendingWorlds(parkourName)) + ".");
            return;
        }
        if (parkour == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("parkour-not-found").replace("{parkour}", parkourName));
            return;
        }

        Location playerBlockLocation = player.getLocation().getBlock().getLocation();
        String option = args[2].toLowerCase();
        boolean needsVisualUpdate = false;

        switch (option) {
            case "setstart":
                parkour.setStart(playerBlockLocation);
                player.sendMessage(plugin.getConfigManager().getMessage("setup.set-start").replace("{parkour}", parkour.getDisplayName()));
                needsVisualUpdate = true;
                break;
            case "setend":
                parkour.setEnd(playerBlockLocation);
                player.sendMessage(plugin.getConfigManager().getMessage("setup.set-end").replace("{parkour}", parkour.getDisplayName()));
                needsVisualUpdate = true;
                break;
            case "addcheckpoint":
                int index = parkour.addCheckpoint(player.getLocation());
                player.sendMessage(plugin.getConfigManager().getMessage("setup.add-checkpoint")
                    .replace("{parkour}", parkour.getDisplayName())
                    .replace("{index}", String.valueOf(index)));
                needsVisualUpdate = true;
                break;
            case "editcheckpoint":
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /parkour setup " + parkourName + " editcheckpoint <index>");
                    return;
                }
                Integer editIndex = parseCheckpointIndex(args[3], player);
                if (editIndex == null) {
                    return;
                }
                Location oldCheckpointLocation = parkour.getCheckpoint(editIndex);
                if (!parkour.editCheckpoint(editIndex, player.getLocation())) {
                    player.sendMessage("§cCheckpoint introuvable. Index valide: 1 à " + parkour.getCheckpointCount() + ".");
                    return;
                }
                plugin.getBlockManager().removeCheckpointPlate(parkour, oldCheckpointLocation);
                plugin.getHologramManager().deleteCheckpointHologram(parkour, editIndex);
                player.sendMessage("§aCheckpoint #" + (editIndex + 1) + " mis à jour pour le parcours '" + parkour.getDisplayName() + "'.");
                needsVisualUpdate = true;
                break;
            case "deletecheckpoint":
            case "removecheckpoint":
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /parkour setup " + parkourName + " deletecheckpoint <index>");
                    return;
                }
                Integer deleteIndex = parseCheckpointIndex(args[3], player);
                if (deleteIndex == null) {
                    return;
                }
                int checkpointCountBeforeDelete = parkour.getCheckpointCount();
                Location removedCheckpointLocation = parkour.getCheckpoint(deleteIndex);
                if (!parkour.removeCheckpoint(deleteIndex)) {
                    player.sendMessage("§cCheckpoint introuvable. Index valide: 1 à " + parkour.getCheckpointCount() + ".");
                    return;
                }
                plugin.getBlockManager().removeCheckpointPlate(parkour, removedCheckpointLocation);
                plugin.getHologramManager().deleteCheckpointHologram(parkour, deleteIndex);
                if (checkpointCountBeforeDelete > 0) {
                    plugin.getHologramManager().deleteCheckpointHologram(parkour, checkpointCountBeforeDelete - 1);
                }
                player.sendMessage("§aCheckpoint #" + (deleteIndex + 1) + " supprimé du parcours '" + parkour.getDisplayName() + "'.");
                needsVisualUpdate = true;
                break;
            case "setrespawn":
                parkour.setRespawn(player.getLocation());
                player.sendMessage(plugin.getConfigManager().getMessage("setup.set-respawn").replace("{parkour}", parkour.getDisplayName()));
                parkour.save();
                break;
            case "setendspawn":
                parkour.setEndSpawn(player.getLocation());
                player.sendMessage(plugin.getConfigManager().getMessage("setup.set-endspawn").replace("{parkour}", parkour.getDisplayName()));
                parkour.save();
                break;
            case "setname":
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /parkour setup " + parkourName + " setname <nouveau nom>");
                    return;
                }
                String newName = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                parkour.setDisplayName(newName);
                player.sendMessage(plugin.getConfigManager().getMessage("setup.set-displayname")
                    .replace("{parkour}", parkourName)
                    .replace("{name}", newName));
                parkour.save();
                break;
            default:
                player.sendMessage("§cOption inconnue. Options: setstart, setend, addcheckpoint, editcheckpoint, deletecheckpoint, setrespawn, setendspawn, setname.");
                break;
        }

        if (needsVisualUpdate) {
            parkour.save();
            plugin.getHologramManager().deleteHolograms(parkour);
            plugin.getBlockManager().placeParkourBlocks(parkour);
        }
    }

    private void handleResetTime(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shparkour.admin.resettime")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /parkour resettime <nom_parcours> <nom_joueur>");
            return;
        }

        String parkourName = args[1];
        String playerName = args[2];

        Parkour parkour = parkourManager.getParkour(parkourName);
        if (parkour == null && !parkourManager.isPending(parkourName)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("parkour-not-found").replace("{parkour}", parkourName));
            return;
        }
        // Les temps sont rangés par nom : un parcours en attente de son monde se réinitialise aussi.
        String internalName = parkour != null ? parkour.getInternalName() : parkourName.toLowerCase();
        String displayName = parkour != null ? parkour.getDisplayName() : parkourName;

        @SuppressWarnings("deprecation")
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(playerName);

        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-not-found-for-reset").replace("{player}", playerName));
            return;
        }

        if (playerDataManager.resetPlayerTime(targetPlayer.getUniqueId(), internalName)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("reset-time-success")
                .replace("{player}", targetPlayer.getName() != null ? targetPlayer.getName() : playerName)
                .replace("{parkour}", displayName));
            playerDataManager.savePlayerData();
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessage("reset-time-no-time")
                .replace("{player}", targetPlayer.getName() != null ? targetPlayer.getName() : playerName)
                .replace("{parkour}", displayName));
        }
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        String currentArg = args[args.length - 1].toLowerCase();

        if (args.length == 1) {
            if (sender.hasPermission("shparkour.admin.create")) completions.add("create");
            if (sender.hasPermission("shparkour.admin.delete")) completions.add("delete");
            if (sender.hasPermission("shparkour.admin.setup")) completions.add("setup");
            if (sender.hasPermission("shparkour.admin.reload")) completions.add("reload");
            if (sender.hasPermission("shparkour.admin.resettime")) {
                completions.add("resettime");
                completions.add("rt");
            }
            return completions.stream().filter(s -> s.startsWith(currentArg)).collect(Collectors.toList());
        }

        String subCommand = args[0].toLowerCase();
        if (args.length == 2) {
            if (subCommand.equals("delete") || subCommand.equals("setup") || subCommand.equals("resettime") || subCommand.equals("rt")) {
                if (sender.hasPermission("shparkour.admin." + (subCommand.equals("rt") ? "resettime" : subCommand))) {
                    return parkourManager.getAllParkours().stream()
                        .map(Parkour::getInternalName)
                        .filter(name -> name.toLowerCase().startsWith(currentArg))
                        .collect(Collectors.toList());
                }
            }
        }

        if (args.length == 3) {
            if (subCommand.equals("setup") && sender.hasPermission("shparkour.admin.setup")) {
                List<String> setupOptions = Arrays.asList(
                    "setstart",
                    "setend",
                    "addcheckpoint",
                    "editcheckpoint",
                    "deletecheckpoint",
                    "removecheckpoint",
                    "setrespawn",
                    "setendspawn",
                    "setname"
                );
                return setupOptions.stream()
                    .filter(s -> s.startsWith(currentArg))
                    .collect(Collectors.toList());
            } else if ((subCommand.equals("resettime") || subCommand.equals("rt")) && sender.hasPermission("shparkour.admin.resettime")) {
                return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                    .collect(Collectors.toList());
            }
        }

        if (args.length == 4 && subCommand.equals("setup") && sender.hasPermission("shparkour.admin.setup")) {
            String setupOption = args[2].toLowerCase();
            if (setupOption.equals("editcheckpoint") || setupOption.equals("deletecheckpoint") || setupOption.equals("removecheckpoint")) {
                Parkour parkour = parkourManager.getParkour(args[1]);
                if (parkour == null || parkour.getCheckpointCount() == 0) {
                    return completions;
                }
                for (int i = 1; i <= parkour.getCheckpointCount(); i++) {
                    completions.add(String.valueOf(i));
                }
                return completions.stream()
                    .filter(s -> s.startsWith(currentArg))
                    .collect(Collectors.toList());
            }
        }

        return completions;
    }

    private Integer parseCheckpointIndex(String value, Player player) {
        int index;
        try {
            index = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            player.sendMessage("§cL'index du checkpoint doit être un nombre entier.");
            return null;
        }

        if (index <= 0) {
            player.sendMessage("§cL'index du checkpoint doit commencer à 1.");
            return null;
        }

        return index - 1;
    }
}
