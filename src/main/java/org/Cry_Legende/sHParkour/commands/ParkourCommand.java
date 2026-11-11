package org.Cry_Legende.sHParkour.commands;

import org.Cry_Legende.sHParkour.SHParkour;
import org.Cry_Legende.sHParkour.managers.ParkourManager;
import org.Cry_Legende.sHParkour.managers.PlayerDataManager;
import org.Cry_Legende.sHParkour.network.NetworkParkour;
import org.Cry_Legende.sHParkour.objects.Parkour;
import org.Cry_Legende.sHParkour.utils.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
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
            sender.sendMessage(usage(sender));
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
            case "top":
                handleTop(sender, args);
                break;
            case "import":
                if (sender.hasPermission("shparkour.admin.import")) {
                    handleImport(sender);
                } else {
                    sender.sendMessage(usage(sender)); // sans la permission : comme toute sous-commande inconnue
                }
                break;
            default:
                sender.sendMessage(usage(sender));
                break;
        }
        return true;
    }

    /** Aide de la commande ; « import » n'y figure qu'en mode réseau, pour qui peut s'en servir. */
    private String usage(CommandSender sender) {
        boolean withImport = plugin.getNetwork() != null && sender.hasPermission("shparkour.admin.import");
        return withImport ? "§b/parkour <create|delete|setup|reload|resettime|top|import>"
            : "§b/parkour <create|delete|setup|reload|resettime|top>";
    }

    /** /parkour import : copie playerdata.yml dans la base du réseau, sans rien écraser. */
    private void handleImport(CommandSender sender) {
        NetworkParkour network = plugin.getNetwork();
        if (network == null) {
            sender.sendMessage(plugin.getConfigManager().getMessage("network.import-no-network"));
            return;
        }
        network.importFile(new File(plugin.getDataFolder(), "playerdata.yml"), result -> sender.sendMessage(result == null
            ? plugin.getConfigManager().getMessage("network.import-failed")
            : plugin.getConfigManager().getMessage("network.import-done")
                .replace("{players}", String.valueOf(result[0]))
                .replace("{parkours}", String.valueOf(result[1]))
                .replace("{checkpoints}", String.valueOf(result[2]))
                .replace("{present}", String.valueOf(result[3]))
                .replace("{read}", String.valueOf(result[4]))));
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
        // config.yml (messages, objets, commandes autorisées) puis les parcours.
        plugin.getConfigManager().reloadConfigs();
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

        // Sans SHNetwork la suite s'exécute tout de suite ; en mode réseau, la recherche du pseudo et le retrait passent par la base
        resolvePlayer(playerName, internalName, target -> {
            if (target == null) {
                sender.sendMessage(plugin.getConfigManager().getMessage("player-not-found-for-reset").replace("{player}", playerName));
                return;
            }
            String targetName = Objects.requireNonNullElse(playerDataManager.getName(target), playerName);

            playerDataManager.resetPlayerTime(target, internalName, removedTime -> {
                if (removedTime == null) {
                    sender.sendMessage(plugin.getConfigManager().getMessage("network.database-error"));
                } else if (removedTime >= 0) {
                    sender.sendMessage(plugin.getConfigManager().getMessage("reset-time-success")
                        .replace("{player}", targetName)
                        .replace("{parkour}", displayName));
                    plugin.getLogger().info(sender.getName() + " a retire le temps de " + targetName + " (" + target + ") sur '"
                        + internalName + "' : " + TimeUtil.formatTime(removedTime) + "s");
                    playerDataManager.savePlayerDataAsync();
                } else {
                    sender.sendMessage(plugin.getConfigManager().getMessage("reset-time-no-time")
                        .replace("{player}", targetName)
                        .replace("{parkour}", displayName));
                }
            });
        });
    }

    /**
     * Joueur visé par resettime : UUID, joueur connecté, ou pseudo connu de nos données.
     * Jamais Bukkit.getOfflinePlayer(nom) : avec le proxy en online-mode il interroge Mojang (thread principal
     * bloqué) et rend l'UUID premium, alors que le réseau range les joueurs sous leur UUID offline.
     */
    private void resolvePlayer(String input, String internalName, Consumer<UUID> done) {
        UUID literal = null;
        try {
            literal = UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            // Ce n'est pas un UUID : on cherche un pseudo.
        }
        if (literal != null) {
            done.accept(literal);
            return;
        }
        UUID known = playerDataManager.findPlayer(input, internalName);
        if (known != null) {
            done.accept(known);
            return;
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            done.accept(online.getUniqueId());
            return;
        }
        NetworkParkour network = plugin.getNetwork();
        if (network != null) {
            // Mode réseau : le joueur n'a pas de données ici, il est peut-être connu de la base partagée
            network.findByName(input, internalName, done);
            return;
        }
        done.accept(null);
    }

    private static final int TOP_PAGE_SIZE = 10;
    private static final DateTimeFormatter TOP_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    /** /parkour top <parcours> [page] : classement complet, pour repérer les temps impossibles. */
    private void handleTop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shparkour.admin.top")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /parkour top <nom_parcours> [page]");
            return;
        }
        Parkour parkour = parkourManager.getParkour(args[1]);
        String internalName = parkour != null ? parkour.getInternalName() : args[1].toLowerCase();
        String displayName = parkour != null ? parkour.getDisplayName() : args[1];
        List<PlayerDataManager.Entry> entries = playerDataManager.getLeaderboard(internalName).entries();
        if (entries.isEmpty()) {
            sender.sendMessage("§7Aucun temps enregistré sur le parcours §e" + displayName + "§7.");
            return;
        }
        int pages = (entries.size() + TOP_PAGE_SIZE - 1) / TOP_PAGE_SIZE;
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Math.max(1, Math.min(pages, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                sender.sendMessage("§cLa page doit être un nombre entier.");
                return;
            }
        }
        sender.sendMessage("§6§lClassement " + displayName + " §7(page " + page + "/" + pages + ", " + entries.size() + " joueurs)");
        int from = (page - 1) * TOP_PAGE_SIZE;
        for (int i = from; i < Math.min(entries.size(), from + TOP_PAGE_SIZE); i++) {
            PlayerDataManager.Entry entry = entries.get(i);
            String name = Objects.requireNonNullElse(playerDataManager.getName(entry.uuid()), entry.uuid().toString());
            String date = entry.date() > 0 ? " §8(" + TOP_DATE.format(Instant.ofEpochMilli(entry.date())) + ")" : "";
            sender.sendMessage("§7#" + (i + 1) + " §f" + TimeUtil.formatTime(entry.time()) + "s §7- §e" + name + date);
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
            if (sender.hasPermission("shparkour.admin.top")) completions.add("top");
            if (plugin.getNetwork() != null && sender.hasPermission("shparkour.admin.import")) completions.add("import");
            return completions.stream().filter(s -> s.startsWith(currentArg)).collect(Collectors.toList());
        }

        String subCommand = args[0].toLowerCase();
        if (args.length == 2) {
            if (subCommand.equals("delete") || subCommand.equals("setup") || subCommand.equals("resettime") || subCommand.equals("rt") || subCommand.equals("top")) {
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
                // Joueurs qui ont un temps sur ce parcours, du plus rapide au plus lent (connectés ou non).
                return playerDataManager.getLeaderboard(args[1]).entries().stream()
                    .map(entry -> playerDataManager.getName(entry.uuid()))
                    .filter(name -> name != null && name.toLowerCase().startsWith(currentArg))
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
