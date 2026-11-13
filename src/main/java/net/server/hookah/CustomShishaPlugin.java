package net.server.hookah;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class CustomShishaPlugin extends JavaPlugin {
    private static CustomShishaPlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        File modelsFolder = new File(getDataFolder(), "models/hookahs");
        if (!modelsFolder.exists()) {
            if (modelsFolder.mkdirs()) {
                getLogger().info("[BeehiveHookah] Папка для моделей успешно создана.");
            }
        }

        saveDefaultRecipes();

        getServer().getPluginManager().registerEvents(new CustomShishaListener(), this);

        loadRecipesFromConfigs();

        CustomShishaCommandExecutor executor = new CustomShishaCommandExecutor();
        registerCommand("shisha", executor);

        // Регистрация скрытой команды телепортации по клику в чате
        PluginCommand tpCommand = getCommand("shishatp");
        if (tpCommand != null) {
            tpCommand.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) return true;
                if (!player.hasPermission("customshisha.admin")) return true;
                if (args.length >= 3) {
                    try {
                        double x = Double.parseDouble(args[0]);
                        double y = Double.parseDouble(args[1]);
                        double z = Double.parseDouble(args[2]);
                        CustomShishaCommandExecutor.executeTeleport(player, x, y, z);
                    } catch (NumberFormatException ignored) {}
                }
                return true;
            });
        }

        // Вызов метода, который выведет ваш рисунок в консоль:
        printAsciiArt();

        getLogger().info("Плагин CustomShisha успешно запущен! Автор: Ze1DOL");
    }

    @Override
    public void onDisable() {
        getLogger().info("Плагин CustomShisha выключен.");
        instance = null;
    }

    public static CustomShishaPlugin getInstance() {
        return instance;
    }

    public void reloadCustomShisha() {
        reloadConfig();

        Iterator<org.bukkit.inventory.Recipe> iterator = Bukkit.recipeIterator();
        String pluginNamespace = getName().toLowerCase();
        List<NamespacedKey> keysToRemove = new ArrayList<>();
        while (iterator.hasNext()) {
            org.bukkit.inventory.Recipe recipe = iterator.next();
            if (recipe instanceof org.bukkit.Keyed keyed && keyed.getKey().getNamespace().equals(pluginNamespace)) {
                keysToRemove.add(keyed.getKey());
            }
        }
        for (NamespacedKey key : keysToRemove) {
            Bukkit.removeRecipe(key);
        }

        loadRecipesFromConfigs();
        getLogger().info("[CustomShisha] Конфигурация и рецепты успешно перезагружены!");
    }

    public ItemStack getItemFromRecipe(String recipeKey) {
        NamespacedKey key = new NamespacedKey(this, recipeKey);
        org.bukkit.inventory.Recipe recipe = Bukkit.getRecipe(key);
        if (recipe != null) {
            return recipe.getResult().clone();
        }
        return null;
    }

    private void registerCommand(String name, CustomShishaCommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    private void saveDefaultRecipes() {
        String[] recipeFiles = {"mouthpieces.yml", "tobaccos.yml", "potions_and_tablets.yml", "hookahs.yml"};
        File recipesDir = new File(getDataFolder(), "recipes");
        if (!recipesDir.exists()) {
            if (!recipesDir.mkdirs()) {
                getLogger().warning("Не удалось создать директорию recipes!");
            }
        }
        for (String fileName : recipeFiles) {
            File file = new File(recipesDir, fileName);
            if (!file.exists()) {
                try {
                    saveResource("recipes/" + fileName, false);
                } catch (IllegalArgumentException e) {
                    try {
                        if (!file.createNewFile()) {
                            getLogger().warning("Не удалось создать файл: " + fileName);
                        }
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    private void loadRecipesFromConfigs() {
        File recipesDir = new File(getDataFolder(), "recipes");
        if (!recipesDir.exists()) return;

        File[] files = recipesDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!file.getName().endsWith(".yml")) continue;
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);

            ConfigurationSection shapedSec = config.getConfigurationSection("recipes");
            if (shapedSec != null) {
                for (String key : shapedSec.getKeys(false)) {
                    ConfigurationSection recipeSec = shapedSec.getConfigurationSection(key);
                    if (recipeSec == null) continue;
                    try {
                        registerShapedRecipe(key, recipeSec);
                    } catch (Exception e) {
                        getLogger().warning("Не удалось загрузить shaped-рецепт '" + key + "' из файла " + file.getName() + ": " + e.getMessage());
                    }
                }
            }

            ConfigurationSection shapelessSec = config.getConfigurationSection("shapeless_recipes");
            if (shapelessSec != null) {
                for (String key : shapelessSec.getKeys(false)) {
                    ConfigurationSection recipeSec = shapelessSec.getConfigurationSection(key);
                    if (recipeSec == null) continue;
                    try {
                        registerShapelessRecipe(key, recipeSec);
                    } catch (Exception e) {
                        getLogger().warning("Не удалось загрузить shapeless-рецепт '" + key + "' из файла " + file.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private void applyCustomTag(ItemStack item, String tagValue) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            NamespacedKey key = new NamespacedKey(this, "custom_item");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, tagValue);
            item.setItemMeta(meta);
        }
    }

    private void applyTagBasedOnKey(ItemStack item, String key) {
        String lowerKey = key.toLowerCase();
        if (lowerKey.contains("mouthpiece")) {
            applyCustomTag(item, "mouthpiece");
        } else if (lowerKey.contains("tobacco")) {
            applyCustomTag(item, "tobacco");
        } else if (lowerKey.contains("tablet")) {
            applyCustomTag(item, "tablet");
        } else if (lowerKey.contains("potion") || lowerKey.contains("antidote")) {
            applyCustomTag(item, "potion");
        } else if (lowerKey.contains("hookah")) {
            applyCustomTag(item, "hookah");
        }
    }

    private void applyItemEffects(ItemStack item, ConfigurationSection sec) {
        if (item == null || !item.hasItemMeta()) return;
        List<Map<?, ?>> effectsList = sec.getMapList("effects");
        if (effectsList.isEmpty()) return;

        StringBuilder effectsString = new StringBuilder();
        for (Map<?, ?> effMap : effectsList) {
            Object typeObj = effMap.get("type");
            Object durationObj = effMap.get("duration");
            Object amplifierObj = effMap.get("amplifier");

            if (typeObj != null && durationObj != null && amplifierObj != null) {
                if (!effectsString.isEmpty()) effectsString.append(";");
                effectsString.append(typeObj)
                        .append(",")
                        .append(durationObj)
                        .append(",")
                        .append(amplifierObj);
            }
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            NamespacedKey effKey = new NamespacedKey(this, "item_effects");
            meta.getPersistentDataContainer().set(effKey, PersistentDataType.STRING, effectsString.toString());
            item.setItemMeta(meta);
        }
    }

    private void registerShapedRecipe(String key, ConfigurationSection sec) {
        ItemStack resultItem = parseItemStack(sec.getConfigurationSection("result"));
        if (resultItem == null) return;

        applyTagBasedOnKey(resultItem, key);
        applyItemEffects(resultItem, sec);

        NamespacedKey nsKey = new NamespacedKey(this, key);
        ShapedRecipe recipe = new ShapedRecipe(nsKey, resultItem);

        List<String> shapeList = sec.getStringList("shape");
        if (shapeList.isEmpty()) return;
        recipe.shape(shapeList.toArray(new String[0]));

        ConfigurationSection ingSec = sec.getConfigurationSection("ingredients");
        if (ingSec != null) {
            for (String charKey : ingSec.getKeys(false)) {
                String matName = ingSec.getString(charKey);
                if (matName != null) {
                    Material mat = Material.matchMaterial(matName);
                    if (mat != null) {
                        recipe.setIngredient(charKey.charAt(0), mat);
                    }
                }
            }
        }
        Bukkit.addRecipe(recipe);
    }

    private void registerShapelessRecipe(String key, ConfigurationSection sec) {
        ItemStack resultItem = parseItemStack(sec.getConfigurationSection("result"));
        if (resultItem == null) return;

        applyTagBasedOnKey(resultItem, key);
        applyItemEffects(resultItem, sec);

        NamespacedKey nsKey = new NamespacedKey(this, key);
        ShapelessRecipe recipe = new ShapelessRecipe(nsKey, resultItem);
        List<String> ingredients = sec.getStringList("ingredients");
        for (String matName : ingredients) {
            Material mat = Material.matchMaterial(matName);
            if (mat != null) {
                recipe.addIngredient(mat);
            }
        }
        Bukkit.addRecipe(recipe);
    }

    private ItemStack parseItemStack(ConfigurationSection sec) {
        if (sec == null) return null;
        String matName = sec.getString("material", "STONE");
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = sec.getString("name");
            if (name != null) {
                meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(name));
            }
            List<String> lore = sec.getStringList("lore");
            if (!lore.isEmpty()) {
                List<net.kyori.adventure.text.Component> componentLore = new java.util.ArrayList<>();
                for (String line : lore) {
                    componentLore.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(line));
                }
                meta.lore(componentLore);
            }
            if (meta instanceof PotionMeta potionMeta) {
                String pType = sec.getString("potion_type");
                if (pType != null) {
                    try {
                        potionMeta.setBasePotionType(PotionType.valueOf(pType.toUpperCase()));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void printAsciiArt() {
        String[] art = {
"⠐⣿⣿⡿⡛⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣀⣀⣀⣀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⢘⣿⣿⣟⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⡀⠀⠀⠀⠒⢿⣿⣷⣶⡶⠒⠖⠀⠈⣿⣿⣿⣿⣿⣿⣿⢿⣶⢶⣶⣶⣤⣤⣤⣄⣀⣀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⠀⠉⠉⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⠶⢾⣤⣦⢤⠀⠀⠀⠈⠹⣿⣿⡅⠀⠀⠀⢺⠙⠉⠉⠙⠛⠉⠀⠀⠁⣸⡯⠹⠿⢿⣿⢿⣿⣿⣿⣷⣶⣦⣄⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣤⣴⣾⣿⣿⣿⣿⣿⣷⣾⣎⠇⠉⠉⠀⠀⡀⠀⠐⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠋⠀⠀⠀⠀⠀⠀⠀⢀⣿⣿⣿⣿⣿⣿⣿⣶⣄⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠘⠛⠛⠛⠿⢿⣿⣿⣿⣿⣿⣿⣿⣷⣀⠀⠄⠀⠀⣾⠐⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣀⣴⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣷⡄⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠀⠀⠈⠀⠈⠙⠻⠟⠧⡆⠀⠀⠀⣹⣷⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣀⣠⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣆⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠄⢱⡀⠀⢰⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣠⣶⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡟⠻⠿⢿⣿⣿⣿⣿⢀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⠀⠀⠀⠀⠀⠀⣠⣶⣿⣟⠻⠯⢶⣷⣶⣤⣦⣤⣄⣀⠀⠀⠀⠀⡄⢄⣦⣾⣧⡀⠘⣿⣿⣿⡟⠀⠀⠀⠀⠀⠀⠀⠀⠀⣀⣴⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡿⠟⠻⠻⢿⣦⠀⠀⠈⠻⣿⣿⣿⣦⠀⠀⠀⠀⠀",
"⠀⠀⠀⠀⢀⣾⣿⠟⠁⠀⠀⢀⠞⠉⠀⠈⠙⠛⣿⣿⣿⣷⣷⣿⣿⣿⣿⣿⣿⣿⣾⣿⣿⣿⠃⠀⠀⠀⢀⣴⣶⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡿⣿⣿⣿⣿⣷⣦⡀⠀⠐⣿⣧⠀⠀⠀⠘⣯⠉⢻⣧⠀⠀",
"⡀⠀⠀⠀⢸⡿⠁⠀⠀⠀⠠⠀⠀⠀⠀⣤⣶⣿⣿⣿⣏⣹⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠟⠉⠀⠀⠀⠀⢴⡝⢿⣿⣿⣿⣿⣿⣿⣿⠿⠛⠉⠀⢸⣿⣿⣿⣿⣿⣿⡇⠀⠈⢿⠟⠀⠀⠀⠀⠸⡄⠀⢿⡀⠀⠀⠀",
"⣷⣤⠀⠀⠸⠁⠀⠀⠀⠀⠀⠀⠀⠀⢼⣿⣿⣿⣿⣿⣿⡇⠉⠛⢿⣿⠉⠉⠿⠟⠁⠀⠀⠀⠀⠀⠀⠀⠈⣿⣆⠹⣿⣿⣏⠁⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣇⠀⠀⢸⣶⡇⠀⠀⠀⠀⡏⠀⠀⢃⠀⠀⠀⠀⠀⠀",
"⣿⡿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢹⣿⣿⣿⣿⣿⣿⠃⠀⢀⣽⣿⠃⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢹⣿⣆⠈⢿⣿⣆⠀⠀⣦⠀⠀⠀⠙⣿⣿⣿⣿⣿⡿⠁⠀⠀⣼⣿⠁⠀⠀⠀⢠⣿⡆⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⣿⣧⠀⠀⠀⠀⠀⠀⠀⠀⢦⠀⠀⠀⠙⢿⣿⣿⣿⣿⠿⠀⠀⣠⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⢻⣿⣆⠈⢿⣿⣧⡀⠸⣶⡄⠀⠠⠀⠙⠋⠋⠻⠀⠀⠀⣸⣿⠏⠀⠀⠀⠠⠋⣿⡇⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠘⢆⡀⠀⢀⠀⠋⠉⠛⢃⣠⣰⣾⣿⣿⠃⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣿⣿⣧⡘⣿⣿⣿⣆⡙⢿⣷⣇⣀⣄⡀⣠⣠⣴⣾⣿⡿⠃⠀⠀⠀⠀⢠⣾⡏⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠻⣷⣧⣠⣤⣤⣦⣼⣿⡿⣟⣡⣰⠆⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠁⢹⣿⣿⣿⣿⣷⣬⣻⣾⣿⣿⣿⣿⣿⣿⡿⠟⠋⠁⠀⠀⠀⢀⠀⣴⣿⣿⠁⠀⢀⠄⠀⠀⠀⠀⠀⠀",
"⣿⣿⡆⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠉⠛⠛⠿⠟⠋⠁⠾⠋⠙⡠⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠘⣿⣿⡿⣿⣿⣿⢿⣿⣿⣿⣿⣷⣶⣄⡄⠀⠀⢀⣠⡀⣰⣿⣾⣿⡏⠀⠀⠠⠋⠀⠀⠀⠀⠀⠀⠀",
"⣿⣿⣿⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⡤⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠛⢽⣿⠁⠉⠀⠀⠻⣿⣿⣿⣿⣿⣿⠇⠀⣠⡏⠘⢿⣿⣿⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⣿⣿⣿⣧⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠉⢻⣿⣿⣧⣿⠀⢠⡿⠁⢠⣾⣿⣿⡟⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⣿⣿⣿⣿⣧⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠫⠙⠸⢧⣾⣿⣍⡡⣿⣿⣿⠏⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⣿⣿⣿⣿⣿⡀⠀▀▀▀▀█ █▀▀ ▒▄█░ █▀▄ ▄▀▄ █░░ ⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠘⣿⣿⣿⣿⠏⠁⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⣿⣿⣿⣿⣿⣷⠀░▄▀▀░ █▀▀ ░▒█░ █░█ █░█ █░▄ ⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀  ⢿⣿⣿⡿⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
"⣿⣿⣿⣿⣿⣿⡄▀▀▀▀▀ ▀▀▀ ▒▄█▄ ▀▀░ ░▀░ ▀▀▀ ⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢹⣿⠃⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀",
"⣿⣿⣿⣿⣿⣿⣧⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠟⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⣿",
"⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠢⢰⡀⠀⠀⠀⠀⠀⠀⢀⢀⣠⣤⣤⣶⣶⣶⣶⣶⣾⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⡆⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⡀⠀⣾⡇⠀⠀⠀⠀⠀⢠⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣷⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⡀⠀⠀⡀⢀⣶⣾⣿⣿⣿⣧⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡧⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠇⠀⠀⠀⠀⠀⠘⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡟⠀⠀⠀⠀⠀⠀⠀⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣀⣤⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡇⣰⠆⠀⠀⠀⠀⢀⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣿⣷⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⠀⠀⢀⣀⣀⣀⣀⣀⣤⣤⣤⣤⣤⣴⣶⣶⣿⣿⣿⣿⡏⠻⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡏⢳⠟⠀⠀⠀⠀⠀⣸⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠉⠉⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠉⠉⠉⢿⣿⣦⣈⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⢰⣤⠀⠀⠀⠀⢀⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣀⣀⣤⣄⣄⣀⣀⢀⣀⣐⣶⣿⣿⣿⣿⣧⡀⣹⣿⣿⣿⣿⣿⣿⠏⣼⣿⠀⠀⠀⠀⣼⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠉⠉⠉⠉⠉⠛⠛⠻⣿⣿⣿⣿⣿⣿⣿⣿⣿⠋⢙⡿⠟⣛⠛⣸⠟⠁⠀⠀⢀⣼⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⢻⣿⣿⣿⣿⣿⣿⣿⣷⢊⠔⠁⠙⢷⣌⣀⡠⠂⣠⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠄⣀⣀⣀⢰⣿⣿⣿⣿⣿⣿⣿⣿⣧⡖⠀⠀⠀⠀⠀⣀⣤⠀⢙⠛⣻⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⣿⣿⠟⠋⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⣼⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣦⡀⠀⠀⢨⡾⠉⢷⣼⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⣿⣿⠟⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣽⣿⣿⣿⣿⣿⣿⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡅⢠⢿⡏⠀⠀⠀⢁⣴⣿⣿⣷⡽⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣿⣿⣿⠟⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠙⣿⣿⣿⡿⠿⠋⠈⢻⣿⣿⣿⣿⣿⣿⣿⣿⣷⣼⣦⣧⠀⢀⡜⠉⠙⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⡿⠟⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠩⠧⠉⠀⠀⠀⠀⢈⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡏⢀⣰⣿⣿⣦⡀⠘⣿⣿⣿⣿⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⣿⣿⣴⣾⣿⣶⣤⡄⠀⡀⠀⢀⣄⠀⣤⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣼⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣷⣦⡈⠋⢹⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⠻⣿⣿⣿⣿⣿⣿⣷⣾⣿⣿⣿⣿⣿⣿⣇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣯⣿⣿⣿⣿⣷⣦⣼⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⠀⠹⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣦⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣴⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡄⠁⠈⠻⣿⣿⣿⣿⣿⣿⣿⣿",
"⠀⠀⠀⠀⠈⠁⠁⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣧⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠨⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣦⣀⠐⠋⣠⣌⣡⣤⣾⣿⣿",
"⠀⠀⠀⠀⠀⠀⢀⣻⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⡠⣼⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠏⣠⣾⠟⠋⠙⢿⡟⢿⠿⢿",
"⠀⠀⠀⠀⠀⠀⠀⠹⣿⣿⣿⣿⣿⣿⣿⣿⣿⠏⠛⠃⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣴⣷⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡏⢠⣿⡋⠀⠀⠀⠀⠀⠀⠟⢒",
"⠀⠀⠀⠀⠀⠀⠀⠘⠻⣿⣿⣿⣿⣿⣿⣿⠏⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠶⠞⠾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣷⠀⠀⠀⢠⣶⠖⠒⣦",
"⠀⠀⠀⠀⠀⠀⠀⠀⠀⣻⣿⣿⣿⣿⣿⠏⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠁⠉⠩⣴⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠛⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⢿⠛⠿⣿⣿⠀⠀⣴⡟⠁⠀⠀⠁",
"⠀⠀⠀⠀⠀⠀⠀⠀⠀⢘⣿⣿⣿⠟⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠉⠙⢿⣿⣿⣿⣿⣿⣿⣿⣿⠇⠀⠀⠻⣿⠟⢹⣿⣿⣿⣿⠀⠀⠀⢠⣿⣿⣷⣤⣿⣄⠀⠀⠀⠀",
"⠀⠀⠀⠀⠀⠀⠀⠀⢠⣴⣶⡦⠋⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⣿⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀⠉⠻⣿⣗⠀⠀⠀⠙⢻⣿⣿⣿⣿⣿⡆⠀⠀⠀",
"⠀⠀⠀⠀⠀⠀⠀⠀⠀⣿⠟⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠸⣿⣿⣿⣿⣿⣿⣿⣷⣆⠀⠀⠀⠀⠀⠀⠀⠀⠈⠙⣀⡀⠀⠠⣽⣿⣿⣿⣿⣿⣿⣿⢤⢠",
"⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢸⣿⣿⣿⣿⣿⣿⣿⣿⣿⡇⢀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢿⣦⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣾",
"⠀⠀⠀⠀⠀⠀⣠⣴⣄⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣤⡀⠀⠀⠀⠀⠀⠀⠀⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⠀⠀⠀⠀⠀⠠⣿⣷⣿⣿⣦⡄⠀⠀⡀⠀⣀⣴⣶⣦⣄⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢠⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣦⣤⣄⣀⡀⠀⣶⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⠀⠀⠀⠀⠀⠀⠻⣿⣿⣿⣿⣿⠶⢿⣿⣿⠿⣿⣿⣿⣿⡆⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠐⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡏⠉⠉⠉⠉⠉⠛⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⠀⠀⠀⠀⠀⠀⠀⠀⠉⠁⠀⠀⠀⠀⠀⠀⠀⠀⠉⠉⠛⣿⣤⣄⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣀⣴⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀⠀⠠⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",
"⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣸⡿⠿⠻⠀⠀⠀⠀⠀⠀⠀⠀⠀⠠⠶⠶⠒⠶⠾⠿⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣧⠀⠀⠀⠀⠀⢰⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿",


        };
        for (String line : art) {
            Bukkit.getConsoleSender().sendMessage(line);
        }
    }
}