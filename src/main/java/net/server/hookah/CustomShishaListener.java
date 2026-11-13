package net.server.hookah;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class CustomShishaListener implements Listener {

    private final HashMap<UUID, Long> cooldowns = new HashMap<>();
    private static final HashMap<UUID, Entity> activeTubes = new HashMap<>();
    private final HashMap<UUID, Location> hookahLocations = new HashMap<>();
    private final HashMap<UUID, BlockDisplay> connectedHookahs = new HashMap<>();
    private final HashMap<UUID, Long> inhaleStartTimes = new HashMap<>();
    private final HashMap<UUID, Long> lastClickTimes = new HashMap<>();
    private final HashMap<UUID, org.bukkit.scheduler.BukkitTask> inhaleTasks = new HashMap<>();
    private final HashMap<UUID, Integer> disengagedCounters = new HashMap<>();
    private final HashMap<UUID, Long> tobaccoExtractClicks = new HashMap<>();
    private final HashMap<UUID, Long> lastReviveHitTimes = new HashMap<>();

    private static final HashMap<UUID, Integer> faintedPlayers = new HashMap<>();
    private static final HashMap<UUID, org.bukkit.scheduler.BukkitTask> faintedTasks = new HashMap<>();
    private static final HashMap<UUID, Location> faintedSeatViews = new HashMap<>();

    private final HashMap<UUID, Long> sneakMessageCooldowns = new HashMap<>();

    private final NamespacedKey hookahKey;
    private final NamespacedKey flavorKey;
    private final NamespacedKey tobaccoKey;
    private final NamespacedKey tobaccoEffectsKey;
    private final NamespacedKey tobaccoCountKey;
    private final NamespacedKey tobaccoPuffsKey;

    public CustomShishaListener() {
        this.hookahKey = new NamespacedKey(CustomShishaPlugin.getInstance(), "beehive_hookah");
        this.flavorKey = new NamespacedKey(CustomShishaPlugin.getInstance(), "hookah_flavor");
        this.tobaccoKey = new NamespacedKey(CustomShishaPlugin.getInstance(), "hookah_tobacco_type");
        this.tobaccoEffectsKey = new NamespacedKey(CustomShishaPlugin.getInstance(), "tobacco_effects");
        this.tobaccoCountKey = new NamespacedKey(CustomShishaPlugin.getInstance(), "hookah_tobacco_count");
        this.tobaccoPuffsKey = new NamespacedKey(CustomShishaPlugin.getInstance(), "hookah_tobacco_puffs");

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                for (UUID uuid : new ArrayList<>(faintedSeatViews.keySet())) {
                    Player p = org.bukkit.Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        if (!p.isInsideVehicle()) {
                            faintedSeatViews.remove(uuid);
                            forceLayDownFainted(p);
                            continue;
                        }
                        Location locked = faintedSeatViews.get(uuid);
                        p.setRotation(locked.getYaw(), locked.getPitch());
                    } else {
                        faintedSeatViews.remove(uuid);
                    }
                }

                for (UUID uuid : new ArrayList<>(faintedPlayers.keySet())) {
                    if (faintedSeatViews.containsKey(uuid)) continue;
                    Player p = org.bukkit.Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        Block blockBelow = p.getLocation().getBlock().getRelative(0, -1, 0);
                        if (!blockBelow.getType().isAir() && blockBelow.getType().isSolid() &&
                                p.getPose() != org.bukkit.entity.Pose.SLEEPING && !p.isInsideVehicle()) {
                            org.bukkit.Bukkit.dispatchCommand(p, "lay");
                            org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "gsit lay " + p.getName());
                        }
                    }
                }

                if (!activeTubes.isEmpty()) {
                    List<UUID> playersToCheck = new ArrayList<>(activeTubes.keySet());
                    for (UUID uuid : playersToCheck) {
                        Player player = org.bukkit.Bukkit.getPlayer(uuid);
                        if (player == null || !player.isOnline()) {
                            cleanPlayerState(uuid);
                            continue;
                        }

                        Location hookahLoc = hookahLocations.get(uuid);
                        if (hookahLoc != null && player.getLocation().distanceSquared(hookahLoc) > 16.0) {
                            removePlayerTube(player);
                            sendMessage(player, "§6[CustomShisha] §7Трубка порвалась, так как вы отошли слишком далеко (более 4 блоков).");
                            player.getWorld().playSound(player.getLocation(), "entity.leash_knot.break", 1.0f, 1.2f);
                        }
                    }
                }

                if (!inhaleStartTimes.isEmpty()) {
                    List<UUID> smokingPlayers = new ArrayList<>(inhaleStartTimes.keySet());
                    for (UUID uuid : smokingPlayers) {
                        Player player = org.bukkit.Bukkit.getPlayer(uuid);
                        if (player == null) {
                            cleanPlayerState(uuid);
                            continue;
                        }

                        long lastClick = lastClickTimes.getOrDefault(uuid, 0L);
                        long start = inhaleStartTimes.get(uuid);
                        long duration = now - start;

                        if ((now - lastClick > 350) || duration >= 4000) {
                            handleReleaseInhale(player, duration);
                        }
                    }
                }
            }
        }.runTaskTimer(CustomShishaPlugin.getInstance(), 0L, 2L);
    }

    private void sendMessage(Player player, String message) {
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    private void sendActionBar(Player player, String message) {
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    private boolean isCustomMouthpiece(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        NamespacedKey key = new NamespacedKey(CustomShishaPlugin.getInstance(), "custom_item");
        String tagValue = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return "mouthpiece".equals(tagValue);
    }

    private boolean isCustomTobacco(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        NamespacedKey key = new NamespacedKey(CustomShishaPlugin.getInstance(), "custom_item");
        String tagValue = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return "tobacco".equals(tagValue);
    }

    private Object getCoreProtectAPI() {
        try {
            org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getServer().getPluginManager().getPlugin("CoreProtect");
            if (plugin == null) return null;
            java.lang.reflect.Method getApiMethod = plugin.getClass().getMethod("getAPI");
            Object api = getApiMethod.invoke(plugin);
            if (api == null) return null;

            java.lang.reflect.Method isEnabledMethod = api.getClass().getMethod("isEnabled");
            if (!(Boolean) isEnabledMethod.invoke(api)) return null;

            java.lang.reflect.Method apiVersionMethod = api.getClass().getMethod("APIVersion");
            if ((Integer) apiVersionMethod.invoke(api) < 13) return null;

            return api;
        } catch (Exception e) {
            return null;
        }
    }

    private void logAction(Player player, String actionType, String details) {
        try {
            File pluginFolder = CustomShishaPlugin.getInstance().getDataFolder();
            if (!pluginFolder.exists()) {
                pluginFolder.mkdirs();
            }
            File logFile = new File(pluginFolder, "logs.txt");

            String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String logEntry = String.format("[%s] [%s] %s: %s%n",
                    timeStamp,
                    actionType,
                    player.getName(),
                    details);

            Files.write(
                    logFile.toPath(),
                    logEntry.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onFaintedPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (faintedPlayers.containsKey(uuid)) {
            if (faintedSeatViews.containsKey(uuid)) return;

            Block blockBelow = player.getLocation().getBlock().getRelative(0, -1, 0);
            if (blockBelow.getType().isAir() || player.isSwimming() || player.getPose() == org.bukkit.entity.Pose.FALL_FLYING) {
                return;
            }

            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                Location back = from.clone();
                back.setX(from.getX());
                back.setZ(from.getZ());
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                event.setTo(back);
            }
        }
    }

    @EventHandler
    public void onBlockBreakForLay(BlockBreakEvent event) {
        Block brokenBlock = event.getBlock();
        Location blockLoc = brokenBlock.getLocation();

        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            Location pLoc = player.getLocation();
            boolean isUnder = pLoc.getWorld().equals(blockLoc.getWorld()) &&
                    Math.abs(pLoc.getBlockX() - blockLoc.getBlockX()) <= 1 &&
                    (pLoc.getBlockY() == blockLoc.getBlockY() || pLoc.getBlockY() == blockLoc.getBlockY() - 1) &&
                    Math.abs(pLoc.getBlockZ() - blockLoc.getBlockZ()) <= 1;

            if (isUnder) {
                UUID targetUuid = player.getUniqueId();
                if (faintedPlayers.containsKey(targetUuid)) {
                    faintedSeatViews.remove(targetUuid);
                    forceLayDownFainted(player);
                } else if (player.getPose() == org.bukkit.entity.Pose.SLEEPING) {
                    forceFaintPlayer(player);
                    sendMessage(player, "§6[CustomShisha] §cВашу опору сломали! Вы потеряли сознание от падения.");
                }
            }
        }
    }

    @EventHandler
    public void onFaintedPlayerSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (faintedPlayers.containsKey(uuid)) {
            if (event.isSneaking()) {
                event.setCancelled(true);
            }
            long now = System.currentTimeMillis();
            long lastMessageTime = sneakMessageCooldowns.getOrDefault(uuid, 0L);
            if (now - lastMessageTime > 60000L) {
                sneakMessageCooldowns.put(uuid, now);
                sendMessage(player, "§6[CustomShisha] §cВы слишком слабы от угарного газа и не можете встать самостоятельно! Дождитесь помощи друга.");
            }
        }
    }

    @EventHandler
    public void onPrismarineTabletConsume(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item.getType() == Material.PRISMARINE_CRYSTALS && item.hasItemMeta()) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                UUID uuid = player.getUniqueId();
                if (faintedPlayers.containsKey(uuid) || player.hasPotionEffect(PotionEffectType.NAUSEA)) {
                    if (player.getGameMode() != GameMode.CREATIVE) {
                        item.setAmount(item.getAmount() - 1);
                    }
                    if (faintedPlayers.containsKey(uuid)) {
                        forceWakeUpPlayer(player, "§aВы приняли таблетку и мгновенно пришли в себя!");
                    } else {
                        player.removePotionEffect(PotionEffectType.NAUSEA);
                        player.removePotionEffect(PotionEffectType.BLINDNESS);
                        sendMessage(player, "§6[CustomShisha] §aТаблетка сняла симптомы отравления угарным газом.");
                    }
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1.0f, 1.2f);
                }
            }
        }
    }

    @EventHandler
    public void onMilkConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.MILK_BUCKET) {
            Player player = event.getPlayer();
            final PotionEffect nausea = player.getPotionEffect(PotionEffectType.NAUSEA);
            final PotionEffect blindness = player.getPotionEffect(PotionEffectType.BLINDNESS);

            boolean holdingHookahEffects = faintedPlayers.containsKey(player.getUniqueId()) ||
                    disengagedCounters.getOrDefault(player.getUniqueId(), 0) > 0;

            if (holdingHookahEffects) {
                org.bukkit.Bukkit.getScheduler().runTask(CustomShishaPlugin.getInstance(), () -> {
                    if (!player.isOnline()) return;
                    if (nausea != null) player.addPotionEffect(nausea);
                    if (blindness != null) player.addPotionEffect(blindness);
                    sendMessage(player, "§6[CustomShisha] §cМолоко очистило желудок, но угарный газ кальяна из лёгких так просто не вывести!");
                });
            }
        }
    }

    @EventHandler
    public void onFaintedPlayerHit(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player target && event.getDamager() instanceof Player damager) {
            UUID targetUUID = target.getUniqueId();
            if (faintedPlayers.containsKey(targetUUID)) {
                event.setCancelled(true);
                processPlayerReviveHit(damager, target, targetUUID);
            }
        }
    }

    @EventHandler
    public void onFaintedPlayerLeftClick(PlayerAnimationEvent event) {
        Player damager = event.getPlayer();
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        for (Entity entity : damager.getNearbyEntities(3.0, 3.0, 3.0)) {
            if (entity instanceof Player target) {
                UUID targetUUID = target.getUniqueId();
                if (faintedPlayers.containsKey(targetUUID)) {
                    Location eye = damager.getEyeLocation();
                    Location targetLoc = target.getLocation().add(0, 0.5, 0);
                    if (eye.getWorld().equals(targetLoc.getWorld()) && eye.distanceSquared(targetLoc) <= 9.0) {
                        processPlayerReviveHit(damager, target, targetUUID);
                        break;
                    }
                }
            }
        }
    }

    private void processPlayerReviveHit(Player damager, Player target, UUID targetUUID) {
        // Both EntityDamageByEntityEvent and PlayerAnimationEvent can fire for one swing.
        // Share one cooldown between handlers so a single swing counts only once.
        UUID damagerId = damager.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastHit = lastReviveHitTimes.get(damagerId);
        if (lastHit != null && now - lastHit < 250L) {
            return;
        }
        lastReviveHitTimes.put(damagerId, now);

        Integer previousHits = faintedPlayers.get(targetUUID);
        if (previousHits == null) {
            return;
        }
        int hits = previousHits + 1;
        faintedPlayers.put(targetUUID, hits);

        sendMessage(damager, "§6[CustomShisha] §eВы приводите в чувство " + target.getName() + " (" + hits + "/3 ударов)");
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.8f, 1.0f);

        if (hits >= 3) {
            forceWakeUpPlayer(target, "§aИгрок " + damager.getName() + " привёл вас в чувство пощёчинами!");
            sendMessage(damager, "§6[CustomShisha] §aВы успешно подняли игрока на ноги!");
        }
    }

    @EventHandler
    public void onFaintedPlayerInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Player target) {
            Player player = event.getPlayer();
            UUID targetUUID = target.getUniqueId();

            if (faintedPlayers.containsKey(targetUUID)) {
                ItemStack item = player.getInventory().getItem(event.getHand());
                if (item.getType() == Material.POTION) {
                    if (item.getItemMeta() instanceof PotionMeta potionMeta) {
                        if (potionMeta.hasCustomEffect(PotionEffectType.REGENERATION) ||
                                (potionMeta.getBasePotionType() != null && potionMeta.getBasePotionType().getPotionEffects().stream().anyMatch(e -> e.getType() == PotionEffectType.REGENERATION))) {

                            event.setCancelled(true);
                            if (player.getGameMode() != GameMode.CREATIVE) {
                                item.setAmount(item.getAmount() - 1);
                            }
                            forceWakeUpPlayer(target, "§a" + player.getName() + " отпоил вас зельем регенерации, вы пришли в себя!");
                            sendMessage(player, "§6[CustomShisha] §aВы использовали целебное зелье и подняли друга на ноги!");
                            target.getWorld().playSound(target.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1.0f, 1.0f);
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onLeashDrop(EntityUnleashEvent event) {
        if (event.getEntity() instanceof Slime slime && slime.getScoreboardTags().contains("hookah_tube")) {
            event.setDropLeash(false);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        cleanPlayerState(player.getUniqueId());
        for (Entity entity : player.getNearbyEntities(10, 10, 10)) {
            if (entity instanceof Slime slime && slime.getScoreboardTags().contains("hookah_tube")) {
                if (!slime.isLeashed() || slime.getLeashHolder() == player) {
                    entity.remove();
                }
            }
        }
    }

    @EventHandler
    public void onHookahPlace(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (item == null || item.getType() != Material.CHISELED_COPPER) return;
        if (!item.hasItemMeta() || event.getClickedBlock() == null || !event.getClickedBlock().getType().isSolid()) return;

        event.setCancelled(true);
        Block targetBlock = event.getClickedBlock();
        Block hookahBlock = targetBlock.getRelative(event.getBlockFace());
        if (!hookahBlock.getType().isAir() && hookahBlock.getType() != Material.WATER) return;

        hookahBlock.setType(Material.BARRIER);

        Object cpApi = getCoreProtectAPI();
        if (cpApi != null) {
            try {
                java.lang.reflect.Method logMethod = cpApi.getClass().getMethod("logPlacement", String.class, Location.class, Material.class, org.bukkit.block.data.BlockData.class);
                logMethod.invoke(cpApi, player.getName(), hookahBlock.getLocation(), Material.CHISELED_COPPER, org.bukkit.Bukkit.createBlockData(Material.CHISELED_COPPER));
            } catch (Exception ignored) {}
        }

        Location spawnLoc = hookahBlock.getLocation().add(0.5, 0.0, 0.5);
        BlockDisplay hookahDisplay = (BlockDisplay) spawnLoc.getWorld().spawnEntity(spawnLoc, EntityType.BLOCK_DISPLAY);
        hookahDisplay.setBlock(org.bukkit.Bukkit.createBlockData(Material.CHISELED_COPPER));
        hookahDisplay.getPersistentDataContainer().set(hookahKey, PersistentDataType.BYTE, (byte) 1);

        Transformation transformation = hookahDisplay.getTransformation();
        transformation.getScale().set(0.25f, 0.65f, 0.25f);
        transformation.getTranslation().set(-0.125f, 0.0f, -0.125f);
        hookahDisplay.setTransformation(transformation);

        item.setAmount(item.getAmount() - 1);
        sendMessage(player, "§6[CustomShisha] §aКальян успешно установлен!");

        logAction(player, "PLACE", "Установил кальян на координаты X: " + hookahBlock.getX() + ", Y: " + hookahBlock.getY() + ", Z: " + hookahBlock.getZ());
    }

    @EventHandler
    public void onHookahInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Action action = event.getAction();
        UUID uuid = player.getUniqueId();

        if (faintedPlayers.containsKey(uuid)) {
            event.setCancelled(true);
            return;
        }

        ItemStack handItem = player.getInventory().getItemInMainHand();

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            Material handMat = handItem.getType();
            if (handMat.isEdible() || handMat == Material.POTION || handMat == Material.MILK_BUCKET || handMat == Material.HONEY_BOTTLE) {
                return;
            }
        }

        boolean isMouthpiece = isCustomMouthpiece(handItem);

        if (activeTubes.containsKey(uuid) && isMouthpiece && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);

            BlockDisplay display = connectedHookahs.get(uuid);
            if (display == null || !display.isValid() || !display.getPersistentDataContainer().has(flavorKey, PersistentDataType.STRING)) {
                removePlayerTube(player);
                sendMessage(player, "§6[CustomShisha] §cТабак в кальяне закончился! Трубка отсоединилась.");
                player.getWorld().playSound(player.getLocation(), "entity.leash_knot.break", 1.0f, 1.2f);
                return;
            }
            int puffsLeft = display.getPersistentDataContainer().getOrDefault(tobaccoPuffsKey, PersistentDataType.INTEGER, 0);
            if (puffsLeft <= 0) {
                removePlayerTube(player);
                sendMessage(player, "§6[CustomShisha] §cТабак в кальяне полностью прогорел! Трубка отсоединилась.");
                player.getWorld().playSound(player.getLocation(), "entity.leash_knot.break", 1.0f, 1.2f);
                return;
            }

            lastClickTimes.put(uuid, System.currentTimeMillis());
            if (!inhaleStartTimes.containsKey(uuid)) {
                handleStartInhale(player);
            }
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.BARRIER) return;

        Location blockCenter = clickedBlock.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : clickedBlock.getWorld().getNearbyEntities(blockCenter, 0.6, 0.6, 0.6)) {
            if (entity instanceof BlockDisplay display && display.getPersistentDataContainer().has(hookahKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);

                if (action == Action.LEFT_CLICK_BLOCK) {
                    if (player.isSneaking()) {
                        List<UUID> uuids = new ArrayList<>(activeTubes.keySet());
                        for (UUID targetId : uuids) {
                            Player target = org.bukkit.Bukkit.getPlayer(targetId);
                            if (target != null && target.isOnline()) {
                                removePlayerTube(target);
                            } else {
                                cleanPlayerState(targetId);
                            }
                        }
                        for (Entity nearby : display.getNearbyEntities(0.6, 0.6, 0.6)) {
                            if (nearby instanceof Slime slime && slime.getScoreboardTags().contains("hookah_tube")) {
                                slime.setLeashHolder(null);
                                nearby.remove();
                            }
                        }

                        if (display.getPersistentDataContainer().has(flavorKey, PersistentDataType.STRING)) {
                            String tobaccoRecipeKey = display.getPersistentDataContainer().get(tobaccoKey, PersistentDataType.STRING);
                            int puffsLeft = display.getPersistentDataContainer().getOrDefault(tobaccoPuffsKey, PersistentDataType.INTEGER, 0);
                            int count = puffsLeft / 2;

                            if (tobaccoRecipeKey != null && count > 0) {
                                ItemStack tobaccoItem = CustomShishaPlugin.getInstance().getItemFromRecipe(tobaccoRecipeKey);
                                if (tobaccoItem != null) {
                                    tobaccoItem.setAmount(count);
                                    java.util.Map<Integer, ItemStack> leftovers = player.getInventory().addItem(tobaccoItem);
                                    for (ItemStack leftover : leftovers.values()) {
                                        player.getWorld().dropItemNaturally(display.getLocation().add(0.5, 0.5, 0.5), leftover);
                                    }
                                }
                                sendMessage(player, "§6[CustomShisha] §aВы демонтировали кальян и забрали уцелевший табак (" + count + " шт.).");
                            } else {
                                sendMessage(player, "§6[CustomShisha] §eКальян демонтирован. Остаток табака прогорел и был выброшен.");
                            }
                        } else {
                            sendMessage(player, "§6[CustomShisha] §eКальян успешно демонтирован.");
                        }

                        logAction(player, "BREAK", "Демонтировал кальян на координатах X: " + clickedBlock.getX() + ", Y: " + clickedBlock.getY() + ", Z: " + clickedBlock.getZ());

                        Object cpApi = getCoreProtectAPI();
                        if (cpApi != null) {
                            try {
                                java.lang.reflect.Method logMethod = cpApi.getClass().getMethod("logRemoval", String.class, Location.class, Material.class, org.bukkit.block.data.BlockData.class);
                                logMethod.invoke(cpApi, player.getName(), clickedBlock.getLocation(), Material.CHISELED_COPPER, org.bukkit.Bukkit.createBlockData(Material.CHISELED_COPPER));
                            } catch (Exception ignored) {}
                        }

                        display.remove();
                        clickedBlock.setType(Material.AIR);

                        ItemStack hookahItem = CustomShishaPlugin.getInstance().getItemFromRecipe("standard_hookah");
                        if (hookahItem == null) {
                            hookahItem = new ItemStack(Material.CHISELED_COPPER);
                            ItemMeta meta = hookahItem.getItemMeta();
                            if (meta != null) {
                                meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§6§lКальян"));
                                meta.lore(java.util.Arrays.asList(LegacyComponentSerializer.legacySection().deserialize("§7Поставьте на блок, чтобы установить"), LegacyComponentSerializer.legacySection().deserialize("§7Поставьте на блок, чтобы установить")));
                                hookahItem.setItemMeta(meta);
                            }
                        }
                        java.util.Map<Integer, ItemStack> leftovers = player.getInventory().addItem(hookahItem);
                        for (ItemStack leftover : leftovers.values()) {
                            player.getWorld().dropItemNaturally(display.getLocation(), leftover);
                        }
                        player.getWorld().playSound(display.getLocation(), Sound.BLOCK_COPPER_BREAK, 1.0f, 1.0f);
                        return;
                    } else {
                        if (activeTubes.containsKey(uuid)) {
                            removePlayerTube(player);
                            sendMessage(player, "§6[CustomShisha] §7Вы отсоединили свой мундштук от кальяна.");
                            player.getWorld().playSound(player.getLocation(), "entity.leash_knot.break", 1.0f, 1.2f);
                        }
                        return;
                    }
                }

                if (action != Action.RIGHT_CLICK_BLOCK) return;

                if (player.isSneaking() && handItem.getType().isAir()) {
                    if (!display.getPersistentDataContainer().has(flavorKey, PersistentDataType.STRING)) {
                        sendMessage(player, "§6[CustomShisha] §cЭтот кальян уже пуст!");
                        return;
                    }

                    long now = System.currentTimeMillis();
                    long lastExtractClick = tobaccoExtractClicks.getOrDefault(uuid, 0L);

                    if (now - lastExtractClick < 500) {
                        tobaccoExtractClicks.remove(uuid);

                        String tobaccoRecipeKey = display.getPersistentDataContainer().get(tobaccoKey, PersistentDataType.STRING);
                        int puffsLeft = display.getPersistentDataContainer().getOrDefault(tobaccoPuffsKey, PersistentDataType.INTEGER, 0);
                        int count = puffsLeft / 2;

                        if (tobaccoRecipeKey != null && count > 0) {
                            ItemStack tobaccoItem = CustomShishaPlugin.getInstance().getItemFromRecipe(tobaccoRecipeKey);
                            if (tobaccoItem != null) {
                                tobaccoItem.setAmount(count);
                                if (!player.getInventory().addItem(tobaccoItem).isEmpty()) {
                                    player.getWorld().dropItemNaturally(display.getLocation().add(0.5, 0.5, 0.5), tobaccoItem);
                                }
                            }
                            sendMessage(player, "§6[CustomShisha] §aВы достали уцелевшие пачки табака (" + count + " шт.).");
                        } else {
                            sendMessage(player, "§6[CustomShisha] §cОстаток табака прогорел (осталось меньше 1 полной пачки) и был выброшен!");
                        }

                        display.getPersistentDataContainer().remove(flavorKey);
                        display.getPersistentDataContainer().remove(tobaccoKey);
                        display.getPersistentDataContainer().remove(tobaccoEffectsKey);
                        display.getPersistentDataContainer().remove(tobaccoCountKey);
                        display.getPersistentDataContainer().remove(tobaccoPuffsKey);

                        player.getWorld().playSound(display.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                    } else {
                        tobaccoExtractClicks.put(uuid, now);
                        int puffsLeft = display.getPersistentDataContainer().getOrDefault(tobaccoPuffsKey, PersistentDataType.INTEGER, 0);
                        int currentCount = puffsLeft / 2;
                        sendMessage(player, "§6[CustomShisha] §7В кальяне: §e" + currentCount + " шт. целых пачек§7 (вдохов осталось: §e" + puffsLeft + "§7). Нажмите §eShift + ПКМ§7 ещё раз, чтобы забрать.");
                        player.getWorld().playSound(display.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
                    }
                    return;
                }

                if (isCustomTobacco(handItem)) {
                    int currentCount = display.getPersistentDataContainer().getOrDefault(tobaccoCountKey, PersistentDataType.INTEGER, 0);

                    if (currentCount >= 16) {
                        sendMessage(player, "§6[CustomShisha] §cКальян забит под завязку (максимум 16 штук)!");
                        return;
                    }

                    String recipeKey = findRecipeKeyForTobacco(handItem);
                    if (recipeKey == null) recipeKey = "berry_tobacco";

                    if (currentCount > 0) {
                        String existingKey = display.getPersistentDataContainer().get(tobaccoKey, PersistentDataType.STRING);
                        if (existingKey != null && !existingKey.equals(recipeKey)) {
                            sendMessage(player, "§6[CustomShisha] §cВ кальяне уже забит другой сорт табака!");
                            return;
                        }
                    }

                    currentCount++;
                    int puffs = currentCount * 2;

                    if (player.getGameMode() != GameMode.CREATIVE) {
                        handItem.setAmount(handItem.getAmount() - 1);
                    }
                    display.getPersistentDataContainer().set(flavorKey, PersistentDataType.STRING, "Заправлено");
                    display.getPersistentDataContainer().set(tobaccoKey, PersistentDataType.STRING, recipeKey);
                    display.getPersistentDataContainer().set(tobaccoCountKey, PersistentDataType.INTEGER, currentCount);
                    display.getPersistentDataContainer().set(tobaccoPuffsKey, PersistentDataType.INTEGER, puffs);

                    NamespacedKey effKey = new NamespacedKey(CustomShishaPlugin.getInstance(), "item_effects");
                    ItemMeta tobMeta = handItem.getItemMeta();
                    if (tobMeta != null) {
                        String tobEffects = tobMeta.getPersistentDataContainer().get(effKey, PersistentDataType.STRING);
                        if (tobEffects != null) {
                            display.getPersistentDataContainer().set(tobaccoEffectsKey, PersistentDataType.STRING, tobEffects);
                        }
                    }

                    sendMessage(player, "§6[CustomShisha] §dВы добавили табак в кальян! (§e" + currentCount + "/16§d)");
                    player.getWorld().playSound(display.getLocation(), Sound.BLOCK_SAND_PLACE, 1.0f, 1.2f);

                    logAction(player, "TOBACCO", "Забил кальян сортом: " + recipeKey + " (всего: " + currentCount + "/16) на X: " + clickedBlock.getX() + ", Y: " + clickedBlock.getY() + ", Z: " + clickedBlock.getZ());
                    return;
                }

                if (!player.isSneaking() && handItem.getType().isAir()) {
                    float currentYaw = display.getLocation().getYaw();
                    Location loc = display.getLocation();
                    loc.setYaw(currentYaw + 45.0f);
                    display.teleport(loc);
                    player.getWorld().playSound(display.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.5f);
                    return;
                }

                if (isMouthpiece) {
                    if (display.getPersistentDataContainer().has(flavorKey, PersistentDataType.STRING)) {
                        int puffsLeft = display.getPersistentDataContainer().getOrDefault(tobaccoPuffsKey, PersistentDataType.INTEGER, 0);
                        if (puffsLeft <= 0) {
                            sendMessage(player, "§6[CustomShisha] §cЭтот кальян полностью прогорел! Очистите его перед заправкой.");
                            return;
                        }

                        if (!activeTubes.containsKey(uuid)) {
                            int count = 0;
                            for (Entity nearby : display.getNearbyEntities(0.5, 0.5, 0.5)) {
                                if (nearbiesIsHookahTube(nearby)) count++;
                            }
                            if (count >= 4) {
                                sendMessage(player, "§6[CustomShisha] §cК этому кальяну уже подключено максимум трубок (4/4)!");
                                return;
                            }
                            Location anchorLoc = display.getLocation().add(0.0, 0.3, 0.0);
                            Slime anchor = anchorLoc.getWorld().spawn(anchorLoc, Slime.class, slime -> {
                                slime.setSize(0);
                                slime.setInvisible(true);
                                slime.setSilent(true);
                                slime.setAI(false);
                                slime.setInvulnerable(true);
                                slime.setGravity(false);
                                slime.addScoreboardTag("hookah_tube");
                            });
                            anchor.setLeashHolder(player);
                            activeTubes.put(uuid, anchor);
                            hookahLocations.put(uuid, display.getLocation());
                            connectedHookahs.put(uuid, display);
                            sendMessage(player, "§6[CustomShisha] §aМундштук подключен! Зажмите и УДЕРЖИВАЙТЕ ПКМ для вдоха дыма.");
                            player.getWorld().playSound(display.getLocation(), "entity.leash_knot.place", 1.0f, 1.0f);

                            logAction(player, "MOUTHPIECE", "Подключил мундштук к кальяну на X: " + display.getLocation().getBlockX() + ", Y: " + display.getLocation().getBlockY() + ", Z: " + display.getLocation().getBlockZ());
                        } else {
                            sendMessage(player, "§6[CustomShisha] §cВы уже подключены к этому кальяну.");
                        }
                    } else {
                        sendMessage(player, "§6[CustomShisha] §cЭтот кальян пуст! Заправьте его кастомным табаком.");
                    }
                }
            }
        }
    }

    private boolean nearbiesIsHookahTube(Entity nearby) {
        return nearby instanceof Slime slime && slime.getScoreboardTags().contains("hookah_tube") && slime.isLeashed();
    }

    private String findRecipeKeyForTobacco(ItemStack item) {
        if (!item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        Component displayNameComp = meta.displayName();
        String displayLegacy = displayNameComp != null ? LegacyComponentSerializer.legacySection().serialize(displayNameComp) : "";

        Iterator<org.bukkit.inventory.Recipe> iterator = org.bukkit.Bukkit.recipeIterator();
        String pluginNamespace = CustomShishaPlugin.getInstance().getName().toLowerCase();

        while (iterator.hasNext()) {
            org.bukkit.inventory.Recipe recipe = iterator.next();
            if (recipe instanceof org.bukkit.Keyed keyed) {
                if (keyed.getKey().getNamespace().equals(pluginNamespace)) {
                    ItemStack res = recipe.getResult();
                    if (res.getType() == item.getType() && res.hasItemMeta()) {
                        Component resNameComp = res.getItemMeta().displayName();
                        String resLegacy = resNameComp != null ? LegacyComponentSerializer.legacySection().serialize(resNameComp) : "";
                        if (resLegacy.equals(displayLegacy)) {
                            return keyed.getKey().getKey();
                        }
                    }
                }
            }
        }
        return "berry_tobacco";
    }

    private void handleStartInhale(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (cooldowns.containsKey(uuid) && now - cooldowns.get(uuid) < 3000) {
            sendActionBar(player, "§c* Вы ещё не готовы сделать новую затяжку... *");
            inhaleStartTimes.remove(uuid);
            return;
        }
        inhaleStartTimes.put(uuid, now);

        org.bukkit.scheduler.BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !inhaleStartTimes.containsKey(uuid)) {
                    this.cancel();
                    return;
                }
                long passed = System.currentTimeMillis() - inhaleStartTimes.get(uuid);
                if (passed % 200 < 100) {
                    BlockDisplay display = connectedHookahs.get(uuid);
                    if (display != null && display.isValid()) {
                        Location loc = display.getLocation();
                        player.getWorld().playSound(loc, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.5f, 0.8f);
                        player.getWorld().playSound(loc, Sound.BLOCK_LAVA_AMBIENT, 0.3f, 1.6f);
                    }
                }
                if (passed < 1500) {
                    sendActionBar(player, "§7Затяжка: §6§lENGAGED §7[●●●] §aДержите кнопку! Эффект копится...");
                } else if (passed < 3200) {
                    sendActionBar(player, "§7Затяжка: §c§lTIRED §7[○●●] §eПередержано! Дым начинает горчить...");
                } else {
                    sendActionBar(player, "§7Затяжка: §4§lDISENGAGED §7[○○○] §cИспорчено! Сейчас закашляетесь...");
                }
            }
        }.runTaskTimer(CustomShishaPlugin.getInstance(), 0L, 4L);
        inhaleTasks.put(uuid, task);
    }

    private void handleReleaseInhale(Player player, long duration) {
        UUID uuid = player.getUniqueId();

        if (inhaleTasks.containsKey(uuid)) {
            inhaleTasks.remove(uuid).cancel();
        }
        inhaleStartTimes.remove(uuid);
        lastClickTimes.remove(uuid);

        BlockDisplay display = connectedHookahs.get(uuid);
        if (display == null || !display.isValid()) return;

        int puffs = display.getPersistentDataContainer().getOrDefault(tobaccoPuffsKey, PersistentDataType.INTEGER, 0);
        if (puffs > 0) {
            puffs--;
            if (puffs <= 0) {
                display.getPersistentDataContainer().remove(flavorKey);
                display.getPersistentDataContainer().remove(tobaccoKey);
                display.getPersistentDataContainer().remove(tobaccoEffectsKey);
                display.getPersistentDataContainer().remove(tobaccoCountKey);
                display.getPersistentDataContainer().remove(tobaccoPuffsKey);

                sendMessage(player, "§6[CustomShisha] §cТабак в кальяне полностью прогорел!");
                player.getWorld().playSound(display.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f);
            } else {
                display.getPersistentDataContainer().set(tobaccoPuffsKey, PersistentDataType.INTEGER, puffs);
                int newCount = puffs / 2;
                display.getPersistentDataContainer().set(tobaccoCountKey, PersistentDataType.INTEGER, newCount);
            }
        }

        cooldowns.put(uuid, System.currentTimeMillis());
        final int smokeTicks;
        String statusMessage;

        if (duration < 1500) {
            statusMessage = "§6§lENGAGED (Идеально)";
            smokeTicks = 70;
            disengagedCounters.put(uuid, 0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));

            ItemStack mouthpieceItem = player.getInventory().getItemInMainHand();
            applyEffectsFromItem(player, mouthpieceItem);
            applyEffectsFromDisplay(player, display);

        } else if (duration < 3200) {
            statusMessage = "§c§lTIRED (Горький дым)";
            smokeTicks = 35;
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 140, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 100, 0));
        } else {
            statusMessage = "§4§lDISENGAGED (Угарный газ!)";
            smokeTicks = 0;
            int badSmokes = disengagedCounters.getOrDefault(uuid, 0) + 1;
            disengagedCounters.put(uuid, badSmokes);

            if (player.getHealth() > 1.0) {
                player.setHealth(player.getHealth() - 1.0);
            }

            if (badSmokes >= 10) {
                forceFaintPlayer(player);
                disengagedCounters.put(uuid, 0);
            } else if (badSmokes >= 5) {
                sendMessage(player, "§6[CustomShisha] §4Ваши лёгкие забиты угарным газом! Наступает сильное потемнение.");
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 1200, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 300, 1));
            } else {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 240, 2));
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
            }

            new org.bukkit.scheduler.BukkitRunnable() {
                int coughCount = 0;
                @Override
                public void run() {
                    if (!player.isOnline() || coughCount >= 3) {
                        this.cancel();
                        return;
                    }
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 1.3f);
                    org.bukkit.util.Vector look = player.getEyeLocation().getDirection().normalize();
                    Location mouth = player.getEyeLocation().add(look.multiply(0.3));
                    for (int i = 0; i < 15; i++) {
                        double dx = (look.getX() * 0.1) + (Math.random() - 0.5) * 0.2;
                        double dy = (look.getY() * 0.1) + (Math.random() - 0.5) * 0.2;
                        double dz = (look.getZ() * 0.1) + (Math.random() - 0.5) * 0.2;
                        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, mouth, 0, dx, dy, dz, 0.5);
                    }
                    coughCount++;
                }
            }.runTaskTimer(CustomShishaPlugin.getInstance(), 0L, 8L);
        }

        sendActionBar(player, "§7Выдох! Фаза: " + statusMessage);

        if (smokeTicks > 0) {
            new org.bukkit.scheduler.BukkitRunnable() {
                int ticksPassed = 0;
                @Override
                public void run() {
                    if (!player.isOnline() || !activeTubes.containsKey(uuid)) {
                        this.cancel();
                        return;
                    }
                    org.bukkit.util.Vector lookDirection = player.getEyeLocation().getDirection().normalize();
                    Location mouthLoc = player.getEyeLocation().add(lookDirection.clone().multiply(0.3));
                    if (ticksPassed % 2 == 0) {
                        for (int i = 0; i < 4; i++) {
                            double spreadFactor = 0.035;
                            double offsetX = (Math.random() - 0.5) * spreadFactor;
                            double offsetY = (Math.random() - 0.5) * (spreadFactor * 0.6) + 0.015;
                            double offsetZ = (Math.random() - 0.5) * spreadFactor;
                            double dx = (lookDirection.getX() * 0.04) + offsetX;
                            double dy = (lookDirection.getY() * 0.04) + offsetY;
                            double dz = (lookDirection.getZ() * 0.04) + offsetZ;
                            Location spawnLoc = mouthLoc.clone().add(
                                    (Math.random() - 0.5) * 0.02,
                                    (Math.random() - 0.5) * 0.02,
                                    (Math.random() - 0.5) * 0.02
                            );
                            player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, spawnLoc, 0, dx, dy, dz, 1.0);
                        }
                    }
                    ticksPassed++;
                    if (ticksPassed >= smokeTicks) {
                        this.cancel();
                    }
                }
            }.runTaskTimer(CustomShishaPlugin.getInstance(), 0L, 1L);
        }
    }

    private void applyEffectsFromItem(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        NamespacedKey effKey = new NamespacedKey(CustomShishaPlugin.getInstance(), "item_effects");
        String effectsData = item.getItemMeta().getPersistentDataContainer().get(effKey, PersistentDataType.STRING);
        parseAndApplyEffects(player, effectsData);
    }

    private void applyEffectsFromDisplay(Player player, BlockDisplay display) {
        String effectsData = display.getPersistentDataContainer().get(tobaccoEffectsKey, PersistentDataType.STRING);
        parseAndApplyEffects(player, effectsData);
    }

    private void parseAndApplyEffects(Player player, String effectsData) {
        if (effectsData == null || effectsData.isEmpty()) return;

        String[] effects = effectsData.split(";");
        for (String effStr : effects) {
            String[] parts = effStr.split(",");
            if (parts.length == 3) {
                try {
                    String rawName = parts[0].toLowerCase().trim();
                    int duration = Integer.parseInt(parts[1].trim());
                    int amplifier = Integer.parseInt(parts[2].trim());

                    String mappedName = switch (rawName) {
                        case "jump", "jump_boost" -> "jump_boost";
                        case "fast_digging", "haste" -> "haste";
                        case "slow_digging", "mining_fatigue" -> "mining_fatigue";
                        case "increase_damage", "strength" -> "strength";
                        case "confusion", "nausea" -> "nausea";
                        case "damage_resistance", "resistance" -> "resistance";
                        case "night_vision" -> "night_vision";
                        case "fire_resistance" -> "fire_resistance";
                        case "speed" -> "speed";
                        case "slowness" -> "slowness";
                        default -> rawName;
                    };

                    PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(mappedName));
                    if (type == null) {
                        type = Registry.EFFECT.get(NamespacedKey.minecraft(rawName));
                    }

                    if (type != null) {
                        player.addPotionEffect(new PotionEffect(type, duration, amplifier));
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public static void forceFaintPlayer(Player player) {
        UUID uuid = player.getUniqueId();

        Component titleComp = LegacyComponentSerializer.legacySection().deserialize("§4§lОБМОРОК");
        Component subComp = LegacyComponentSerializer.legacySection().deserialize("§cВы потеряли сознание от угарного газа!");
        player.showTitle(net.kyori.adventure.title.Title.title(titleComp, subComp));

        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 6000, 4, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 6000, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 6000, 6, false, false));

        faintedPlayers.put(uuid, 0);

        Entity anchor = activeTubes.remove(uuid);
        if (anchor instanceof Slime slime && slime.isValid()) {
            slime.setLeashHolder(null);
            anchor.remove();
        }

        if (player.isInsideVehicle()) {
            faintedSeatViews.put(uuid, player.getLocation().clone());
        } else {
            forceLayDownFainted(player);
        }

        org.bukkit.scheduler.BukkitTask faintTask = new org.bukkit.scheduler.BukkitRunnable() {
            int timer = 0;
            @Override
            public void run() {
                if (!player.isOnline() || !faintedPlayers.containsKey(uuid)) {
                    this.cancel();
                    return;
                }
                timer += 20;
                if (timer >= 6000) {
                    forceWakeUpPlayer(player, "§aВы постепенно пришли в себя после обморока.");
                    this.cancel();
                }
            }
        }.runTaskTimer(CustomShishaPlugin.getInstance(), 20L, 20L);

        faintedTasks.put(uuid, faintTask);

        Player p = org.bukkit.Bukkit.getPlayer(uuid);
        if (p != null) {
            CustomShishaListener listenerInstance = new CustomShishaListener();
            Location loc = p.getLocation();
            listenerInstance.logAction(p, "FAINT", "Игрок потерял сознание от передозировки угарного газа на X: " + loc.getBlockX() + ", Y: " + loc.getBlockY() + ", Z: " + loc.getBlockZ());
        }
    }

    private static void forceLayDownFainted(Player player) {
        UUID uuid = player.getUniqueId();
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        player.setSneaking(false);
        player.setSprinting(false);

        org.bukkit.Bukkit.dispatchCommand(player, "stand");
        org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "gsit stand " + player.getName());

        org.bukkit.Bukkit.getScheduler().runTaskLater(CustomShishaPlugin.getInstance(), () -> {
            if (!player.isOnline() || !faintedPlayers.containsKey(uuid)) return;

            Location loc = player.getLocation();
            if (loc.getBlock().getRelative(0, -1, 0).getType().isAir()) {
                for (int i = 1; i <= 10; i++) {
                    Block b = loc.getBlock().getRelative(0, -i, 0);
                    if (!b.getType().isAir() && b.getType().isSolid()) {
                        loc.setY(b.getY() + 1.0);
                        player.teleport(loc);
                        break;
                    }
                }
            }

            org.bukkit.Bukkit.dispatchCommand(player, "lay");
            org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "gsit lay " + player.getName());
        }, 8L);
    }

    public static void forceWakeUpPlayer(Player player, String message) {
        UUID uuid = player.getUniqueId();
        faintedPlayers.remove(uuid);
        faintedSeatViews.remove(uuid);
        if (faintedTasks.containsKey(uuid)) {
            faintedTasks.remove(uuid).cancel();
        }

        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.HUNGER);
        player.removePotionEffect(PotionEffectType.SLOWNESS);

        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "gsit stand " + player.getName());
        org.bukkit.Bukkit.dispatchCommand(player, "stand");

        if (!message.isEmpty()) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§6[CustomShisha] " + message));
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    @EventHandler
    public void onSlotChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (inhaleStartTimes.containsKey(uuid)) {
            long duration = System.currentTimeMillis() - inhaleStartTimes.get(uuid);
            handleReleaseInhale(player, duration);
        }
        if (activeTubes.containsKey(uuid)) {
            ItemStack nextItem = player.getInventory().getItem(event.getNewSlot());
            if (!isCustomMouthpiece(nextItem)) {
                removePlayerTube(player);
                sendMessage(player, "§6[CustomShisha] §7Трубка отсоединилась, так как вы убрали мундштук из рук.");
            }
        }
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (inhaleStartTimes.containsKey(uuid)) {
            long duration = System.currentTimeMillis() - inhaleStartTimes.get(uuid);
            handleReleaseInhale(player, duration);
        }
        if (activeTubes.containsKey(uuid)) {
            if (isCustomMouthpiece(event.getItemDrop().getItemStack())) {
                removePlayerTube(player);
                sendMessage(player, "§6[CustomShisha] §7Трубка отсоединилась, так как вы выбросили мундштук.");
                player.getWorld().playSound(player.getLocation(), "entity.leash_knot.break", 1.0f, 1.2f);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (activeTubes.containsKey(uuid)) {
            if (event.getSlotType() == InventoryType.SlotType.QUICKBAR || event.getClickedInventory() == player.getInventory()) {
                ItemStack clickedItem = event.getCurrentItem();
                if (isCustomMouthpiece(clickedItem)) {
                    if (event.getSlot() == player.getInventory().getHeldItemSlot()) {
                        if (inhaleStartTimes.containsKey(uuid)) {
                            long duration = System.currentTimeMillis() - inhaleStartTimes.get(uuid);
                            handleReleaseInhale(player, duration);
                        }
                        removePlayerTube(player);
                        sendMessage(player, "§6[CustomShisha] §7Трубка отсоединилась, так как вы переместили мундштук в инвентаре.");
                        player.getWorld().playSound(player.getLocation(), "entity.leash_knot.break", 1.0f, 1.2f);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (faintedPlayers.containsKey(uuid)) {
            forceWakeUpPlayer(event.getPlayer(), "");
        }
        cleanPlayerState(uuid);
    }

    @EventHandler
    public void onBarrierBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.BARRIER) {
            Location blockCenter = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
            for (Entity entity : event.getBlock().getWorld().getNearbyEntities(blockCenter, 0.6, 0.6, 0.6)) {
                if (entity instanceof BlockDisplay display && display.getPersistentDataContainer().has(hookahKey, PersistentDataType.BYTE)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    private void removePlayerTube(Player player) {
        UUID uuid = player.getUniqueId();
        Location hLoc = hookahLocations.get(uuid);
        if (hLoc != null && player.isOnline()) {
            logAction(player, "MOUTHPIECE", "Отсоединил мундштук от кальяна на X: " + hLoc.getBlockX() + ", Y: " + hLoc.getBlockY() + ", Z: " + hLoc.getBlockZ());
        }
        cleanPlayerState(uuid);
    }

    private void cleanPlayerState(UUID uuid) {
        hookahLocations.remove(uuid);
        connectedHookahs.remove(uuid);
        inhaleStartTimes.remove(uuid);
        lastClickTimes.remove(uuid);
        tobaccoExtractClicks.remove(uuid);
        if (inhaleTasks.containsKey(uuid)) {
            inhaleTasks.remove(uuid).cancel();
        }
        Entity anchor = activeTubes.remove(uuid);
        if (anchor instanceof Slime slime && slime.isValid()) {
            slime.setLeashHolder(null);
            anchor.remove();
        }
    }
}