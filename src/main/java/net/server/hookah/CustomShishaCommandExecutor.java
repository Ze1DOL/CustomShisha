package net.server.hookah;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class CustomShishaCommandExecutor implements CommandExecutor, TabCompleter {

    // Хранилище для возврата назад: UUID администратора -> его точка перед телепортацией
    private static final HashMap<UUID, Location> lastAdminLocations = new HashMap<>();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {

        if (!sender.hasPermission("customshisha.admin")) {
            sender.sendMessage("§cУ вас нет прав на выполнение этой команды.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelpMenu(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "give": {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /shisha give <тип/предмет> [вариант] [игрок]");
                    return true;
                }

                String firstArg = args[1].toLowerCase();
                String recipeKey;
                Player target = (sender instanceof Player p) ? p : null;

                switch (firstArg) {
                    case "hookah":
                        recipeKey = "hookah";
                        if (args.length > 2) target = Bukkit.getPlayer(args[2]);
                        break;
                    case "mouthpiece":
                        if (args.length < 3) {
                            sender.sendMessage("§cУкажите вариант мундштука! Пример: /shisha give mouthpiece wooden_mouthpiece");
                            return true;
                        }
                        recipeKey = args[2].toLowerCase();
                        if (args.length > 3) target = Bukkit.getPlayer(args[3]);
                        break;
                    case "tobacco":
                        if (args.length < 3) {
                            sender.sendMessage("§cУкажите вариант табака! Пример: /shisha give tobacco berry_tobacco");
                            return true;
                        }
                        recipeKey = args[2].toLowerCase();
                        if (args.length > 3) target = Bukkit.getPlayer(args[3]);
                        break;
                    case "tablet", "potion", "item":
                        if (args.length < 3) {
                            sender.sendMessage("§cУкажите предмет! Пример: /shisha give tablet prismarine_tablet");
                            return true;
                        }
                        recipeKey = args[2].toLowerCase();
                        if (args.length > 3) target = Bukkit.getPlayer(args[3]);
                        break;
                    default:
                        recipeKey = firstArg;
                        if (args.length > 2) target = Bukkit.getPlayer(args[2]);
                        break;
                }

                if (target == null) {
                    sender.sendMessage("§cИгрок не найден или вы не в игре!");
                    return true;
                }

                if (recipeKey.equals("hookah")) {
                    recipeKey = "standard_hookah";
                }

                ItemStack item = CustomShishaPlugin.getInstance().getItemFromRecipe(recipeKey);
                if (item == null) {
                    sender.sendMessage("§cПредмет/рецепт с ID '" + recipeKey + "' не найден!");
                    return true;
                }

                giveItemToPlayer(target, item);
                sender.sendMessage("§6[CustomShisha] §aПредмет " + recipeKey + " выдан игроку " + target.getName());
                return true;
            }

            case "faint", "effect": {
                Player target = getTargetPlayer(sender, args);
                if (target != null) {
                    CustomShishaListener.forceFaintPlayer(target);
                    sender.sendMessage("§6[CustomShisha] §aИгрок " + target.getName() + " отправлен в обморок.");
                }
                return true;
            }

            case "clear", "wakeup": {
                Player target = getTargetPlayer(sender, args);
                if (target != null) {
                    CustomShishaListener.forceWakeUpPlayer(target, "§aАдминистратор привел вас в чувство!");
                    sender.sendMessage("§6[CustomShisha] §aИгрок " + target.getName() + " успешно приведен в чувство.");
                }
                return true;
            }

            case "reload": {
                CustomShishaPlugin.getInstance().reloadCustomShisha();
                sender.sendMessage("§6[CustomShisha] §aКонфигурация и файлы рецептов успешно перезагружены!");
                return true;
            }

            case "logs": {
                sendLogsToSender(sender, args);
                return true;
            }

            case "back": {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cЭту команду могут использовать только игроки!");
                    return true;
                }
                if (!lastAdminLocations.containsKey(player.getUniqueId())) {
                    player.sendMessage("§6[CustomShisha] §cУ вас нет сохраненной точки для возврата!");
                    return true;
                }

                Location prevLoc = lastAdminLocations.remove(player.getUniqueId());
                player.teleport(prevLoc);
                player.sendMessage("§6[CustomShisha] §aВы успешно вернулись на предыдущую точку!");
                return true;
            }

            default:
                sender.sendMessage("§cНеизвестная подкоманда! Используйте §f/shisha help");
                return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("give", "faint", "clear", "reload", "logs", "back", "help");
            StringUtil.copyPartialMatches(args[0], subCommands, completions);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> giveCategories = Arrays.asList("hookah", "mouthpiece", "tobacco", "tablet");
            StringUtil.copyPartialMatches(args[1], giveCategories, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String type = args[1].toLowerCase();
            if (type.isBlank()) {
                StringUtil.copyPartialMatches(args[2], Arrays.asList("hookah", "mouthpiece", "tobacco", "tablet"), completions);
                return completions;
            }
            if (type.equals("hookah")) {
                StringUtil.copyPartialMatches(args[2], onlinePlayerNames(), completions);
                return completions;
            }
            List<String> variants = new ArrayList<>();

            Iterator<Recipe> iterator = Bukkit.recipeIterator();
            String pluginNamespace = CustomShishaPlugin.getInstance().getName().toLowerCase();

            while (iterator.hasNext()) {
                Recipe recipe = iterator.next();
                if (recipe instanceof Keyed keyed) {
                    NamespacedKey nsKey = keyed.getKey();
                    if (nsKey.getNamespace().equals(pluginNamespace)) {
                        String key = nsKey.getKey();

                        if (type.equals("mouthpiece")) {
                            if (key.contains("mouthpiece")) variants.add(key);
                        } else if (type.equals("tobacco")) {
                            if (key.contains("tobacco")) variants.add(key);
                        } else if (type.equals("tablet") || type.equals("potion")) {
                            if (key.contains("tablet") || key.contains("potion") || key.contains("antidote")) variants.add(key);
                        }
                    }
                }
            }

            StringUtil.copyPartialMatches(args[2], variants, completions);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            List<String> players = new ArrayList<>();
            players.addAll(onlinePlayerNames());
            StringUtil.copyPartialMatches(args[3], players, completions);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("logs")) {
            List<String> logFilters = Arrays.asList("place", "break", "tobacco", "faint");
            List<String> suggestions = new ArrayList<>(logFilters);
            suggestions.addAll(onlinePlayerNames());
            StringUtil.copyPartialMatches(args[1], suggestions, completions);
        } else if (args.length >= 2 && !args[0].equalsIgnoreCase("give") && !args[0].equalsIgnoreCase("logs")) {
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                players.add(p.getName());
            }
            StringUtil.copyPartialMatches(args[1], players, completions);
        }

        Collections.sort(completions);
        return completions;
    }

    private void sendLogsToSender(CommandSender sender, String[] args) {
        java.io.File pluginFolder = CustomShishaPlugin.getInstance().getDataFolder();
        java.io.File logFile = new java.io.File(pluginFolder, "logs.txt");

        if (!logFile.exists()) {
            sender.sendMessage("§6[CustomShisha] §cФайл логов (logs.txt) пуст или еще не создан.");
            return;
        }

        int radius = -1;
        String filterQuery = null;
        int limit = 15;

        if (args.length > 1) {
            String arg1 = args[1];
            try {
                radius = Integer.parseInt(arg1);
            } catch (NumberFormatException e) {
                filterQuery = arg1.toLowerCase();
            }
        }
        if (args.length > 2) {
            String arg2 = args[2];
            try {
                limit = Integer.parseInt(arg2);
            } catch (NumberFormatException e) {
                if (filterQuery == null) {
                    filterQuery = arg2.toLowerCase();
                }
            }
        }
        if (args.length > 3) {
            try {
                limit = Integer.parseInt(args[3]);
            } catch (NumberFormatException ignored) {}
        }

        try {
            List<String> allLines = java.nio.file.Files.readAllLines(logFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            List<String> filteredLines = new ArrayList<>();
            Location senderLoc = (sender instanceof Player p) ? p.getLocation() : null;

            java.util.regex.Pattern coordPattern = java.util.regex.Pattern.compile("X:\\s*(-?\\d+),\\s*Y:\\s*(-?\\d+),\\s*Z:\\s*(-?\\d+)");

            for (String line : allLines) {
                if (filterQuery != null && !line.toLowerCase().contains(filterQuery)) {
                    continue;
                }

                if (radius > 0 && senderLoc != null && line.contains("X:")) {
                    java.util.regex.Matcher matcher = coordPattern.matcher(line);
                    if (matcher.find()) {
                        try {
                            double logX = Double.parseDouble(matcher.group(1));
                            double logY = Double.parseDouble(matcher.group(2));
                            double logZ = Double.parseDouble(matcher.group(3));

                            Location logLoc = new Location(senderLoc.getWorld(), logX, logY, logZ);
                            if (senderLoc.getWorld().equals(logLoc.getWorld()) && senderLoc.distance(logLoc) <= radius) {
                                filteredLines.add(line);
                            }
                        } catch (Exception ignored) {}
                    }
                } else {
                    filteredLines.add(line);
                }
            }

            // ГРУППИРОВКА ПОХОЖИХ ПОСЛЕДОВАТЕЛЬНЫХ ДЕЙСТВИЙ
            List<GroupedLog> groupedLogs = new ArrayList<>();
            for (String line : filteredLines) {
                String actionType = extractActionType(line);
                String coords = extractCoords(line, coordPattern);
                String playerName = extractPlayerName(line);

                if (actionType != null && coords != null && !groupedLogs.isEmpty()) {
                    GroupedLog lastGroup = groupedLogs.get(groupedLogs.size() - 1);
                    if (lastGroup.actionType.equals(actionType) &&
                            lastGroup.coords.equals(coords) &&
                            lastGroup.playerName.equals(playerName)) {
                        lastGroup.count++;
                        lastGroup.lastLine = line; // Обновляем на самую свежую дату/время
                        continue;
                    }
                }

                groupedLogs.add(new GroupedLog(line, actionType, coords, playerName));
            }

            sender.sendMessage("§6=== Результаты поиска логов (Сгруппировано: " + groupedLogs.size() + ") ===");

            int start = Math.max(0, groupedLogs.size() - limit);
            for (int i = start; i < groupedLogs.size(); i++) {
                GroupedLog group = groupedLogs.get(i);
                String displayLine = group.lastLine;

                // Если было несколько повторений подряд на одних координатах
                if (group.count > 1) {
                    displayLine = displayLine.replaceFirst("(\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\] \\[[A-Z]+\\])", "$1 §d(x" + group.count + " раз)§7");
                }

                if (sender instanceof Player p && displayLine.contains("X:")) {
                    sendClickableLogLine(p, displayLine);
                } else {
                    if (displayLine.contains("[PLACE]")) displayLine = "§a" + displayLine;
                    else if (displayLine.contains("[BREAK]")) displayLine = "§c" + displayLine;
                    else if (displayLine.contains("[TOBACCO]")) displayLine = "§e" + displayLine;
                    else if (displayLine.contains("[FAINT]")) displayLine = "§4" + displayLine;
                    sender.sendMessage(displayLine);
                }
            }

        } catch (Exception e) {
            sender.sendMessage("§6[CustomShisha] §cОшибка при чтении логов: " + e.getMessage());
        }
    }

    // Вспомогательный класс для группировки
    private static class GroupedLog {
        String lastLine;
        String actionType;
        String coords;
        String playerName;
        int count = 1;

        public GroupedLog(String line, String actionType, String coords, String playerName) {
            this.lastLine = line;
            this.actionType = actionType;
            this.coords = coords;
            this.playerName = playerName;
        }
    }

    private String extractActionType(String line) {
        if (line.contains("[PLACE]")) return "PLACE";
        if (line.contains("[BREAK]")) return "BREAK";
        if (line.contains("[TOBACCO]")) return "TOBACCO";
        if (line.contains("[FAINT]")) return "FAINT";
        return null;
    }

    private String extractCoords(String line, java.util.regex.Pattern pattern) {
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1) + "," + matcher.group(2) + "," + matcher.group(3);
        }
        return null;
    }

    private String extractPlayerName(String line) {
        try {
            int startIdx = line.indexOf("] ") + 2;
            startIdx = line.indexOf("] ", startIdx) + 2;
            int endIdx = line.indexOf(":", startIdx);
            if (startIdx != -1 && endIdx != -1) {
                return line.substring(startIdx, endIdx).trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void sendClickableLogLine(Player player, String line) {
        String colorPrefix = "§7";
        if (line.contains("[PLACE]")) colorPrefix = "§a";
        else if (line.contains("[BREAK]")) colorPrefix = "§c";
        else if (line.contains("[TOBACCO]")) colorPrefix = "§e";
        else if (line.contains("[FAINT]")) colorPrefix = "§4";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("X:\\s*(-?\\d+),\\s*Y:\\s*(-?\\d+),\\s*Z:\\s*(-?\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(line);

        if (matcher.find()) {
            try {
                int x = Integer.parseInt(matcher.group(1));
                int y = Integer.parseInt(matcher.group(2));
                int z = Integer.parseInt(matcher.group(3));

                Component clickableMessage = LegacyComponentSerializer.legacySection()
                        .deserialize(colorPrefix + line)
                        .clickEvent(ClickEvent.runCommand("/shishatp " + x + " " + y + " " + z))
                        .hoverEvent(HoverEvent.showText(LegacyComponentSerializer.legacySection()
                                .deserialize("§e▶ Нажмите в любое место строки, чтобы телепортироваться!\n§7(Координаты: X: " + x + " Y: " + y + " Z: " + z + ")")));

                player.sendMessage(clickableMessage);
                return;
            } catch (Exception ignored) {}
        }

        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(colorPrefix + line));
    }

    public static void executeTeleport(Player player, double x, double y, double z) {
        lastAdminLocations.put(player.getUniqueId(), player.getLocation().clone());
        Location targetLoc = new Location(player.getWorld(), x, y + 1.0, z);
        player.teleport(targetLoc);
        player.sendMessage("§6[CustomShisha] §aВы телепортированы к точке лога! Введите §e/shisha back§a, чтобы вернуться назад.");
    }

    private void sendHelpMenu(CommandSender sender) {
        sender.sendMessage("§6§l=== CustomShisha — Помощь по командам ===");
        sender.sendMessage("§e/shisha give hookah [игрок] §7— Выдать кальян");
        sender.sendMessage("§e/shisha give mouthpiece <вариант> [игрок] §7— Выдать мундштук");
        sender.sendMessage("§e/shisha give tobacco <вариант> [игрок] §7— Выдать табак");
        sender.sendMessage("§e/shisha give tablet <вариант> [игрок] §7— Выдать таблетку/зелье");
        sender.sendMessage("§e/shisha faint [игрок] §7— Отправить в обморок");
        sender.sendMessage("§e/shisha clear [игрок] §7— Вывести из обморока");
        sender.sendMessage("§e/shisha logs [действие/ник/радиус] [лимит] §7— Поиск по логам");
        sender.sendMessage("§e/shisha back §7— Вернуться на точку до телепортации");
        sender.sendMessage("§e/shisha reload §7— Перезагрузить конфиги и рецепты плагина");
    }

    private Player getTargetPlayer(CommandSender sender, String[] args) {
        if (args.length > 1) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§cИгрок '" + args[1] + "' не найден!");
            }
            return target;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("§cВы должны указать ник игрока!");
        return null;
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
        return names;
    }

    private void giveItemToPlayer(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.sendMessage("§6[CustomShisha] §aВы получили кастомный предмет!");
    }
}