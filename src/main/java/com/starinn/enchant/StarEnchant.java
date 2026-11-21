package com.starinn.enchant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import io.papermc.paper.event.entity.EntityKnockbackEvent;

import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

// ===== 内置更新模块（mc-autoupdate） =====
import com.starinn.enchant.update.UpdateCommand;
import com.starinn.enchant.update.UpdateConfig;
import com.starinn.enchant.update.UpdateManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * StarEnchant 扩展附魔插件（Purpur 1.21.11 / Java 21 / 零第三方插件依赖 / 零客户端下发）。
 *
 * 双层附魔体系：
 *  第一层：原版附魔等级突破与互斥解除（纯服务端 addEnchant unsafe + 事件改写，基岩端原生识别）；
 *  第二层：60 个自定义附魔（PDC 存储 + lore 彩色文字行显示 + 服务端事件触发效果）。
 *
 * 本文件为单主类 + 嵌套类结构：
 *  Cfg / CfgData / EnchantRegistry / Def / EnchantStorage / LoreRenderer /
 *  AttributeManager / EffectListener / SmithingListener / AnvilListener /
 *  EnchantTableListener / FishingListener / ChainMining / LootEntries /
 *  ShopGui / CommandHandler / SelfCheck。
 */
public final class StarEnchant extends JavaPlugin {

    private static StarEnchant instance;

    // ---------------------------------------------------------------- 模块实例
    private Cfg cfg;
    private CfgData data;               // 全局配置快照（解析一次，reload 重建）
    private EnchantRegistry registry;
    private EnchantStorage storage;
    private LoreRenderer loreRenderer;
    private AttributeManager attributeManager;
    private EffectListener.ChainMining chainMining;
    private EffectListener effectListener;
    private SmithingListener smithingListener;
    private AnvilListener anvilListener;
    private EnchantTableListener tableListener;
    private FishingListener fishingListener;
    private ShopGui shopGui;
    private CommandHandler commandHandler;
    private BukkitTask maintenanceTask;

    // 内置更新模块（开机自检 + 每周四 12:00 定时，失败不影响主功能）
    private UpdateManager updateManager;

    // 自检统计（红线⑥：任一模块失败只降级该功能，绝不导致插件启动失败）
    private int checkNormal;
    private int checkDegraded;

    public static StarEnchant inst() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        bootstrap();
        // 内置更新：自检一次 + 每周四 12:00 定时检查。任何异常只关闭更新功能，不影响插件主体。
        try {
            UpdateConfig uc = new UpdateConfig(
                    "https://raw.githubusercontent.com/ChaosSurvivalProject/StarEnchant/main/updates/latest.json",
                    getDescription().getVersion(),
                    "1.21.11",
                    "stable", true, true);
            updateManager = new UpdateManager(this, uc);
            updateManager.checkAndSchedule(() -> updateManager.check(false));
            if (getCommand("starenchant") != null) {
                getCommand("starenchant").setExecutor(new UpdateCommand(updateManager));
                getCommand("starenchant").setTabCompleter(new UpdateCommand(updateManager));
            }
        } catch (Throwable t) {
            getLogger().warning("[星穹旅驿] 更新模块初始化失败（不影响主功能）：" + t.getMessage());
        }
    }

    /** 启动装配：配置 -> 注册表 -> 自检 -> 监听器 -> 周期任务。任何一步异常只关闭对应功能。 */
    private synchronized void bootstrap() {
        checkNormal = 0;
        checkDegraded = 0;
        cfg = new Cfg(this);
        registry = new EnchantRegistry(this);
        registry.load();
        try {
            data = CfgData.load(cfg, this);
        } catch (RuntimeException ex) {
            // 理论上不会到这：CfgData 内部分节容错。极端情况给出最小可用快照并降级。
            data = new CfgData();
            markDegraded("全局配置快照解析", "意外异常：" + ex.getMessage());
        }
        storage = new EnchantStorage(this);
        storage.initKeys();
        loreRenderer = new LoreRenderer(this);
        chainMining = new EffectListener.ChainMining(this);
        attributeManager = new AttributeManager(this);
        effectListener = new EffectListener(this);
        smithingListener = new SmithingListener(this);
        anvilListener = new AnvilListener(this);
        tableListener = new EnchantTableListener(this);
        fishingListener = new FishingListener(this);
        shopGui = new ShopGui(this);
        commandHandler = new CommandHandler(this);

        new SelfCheck(this).runAll();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(effectListener, this);
        if (data.smithingEnabled) pm.registerEvents(smithingListener, this);
        if (data.anvilEnabled) pm.registerEvents(anvilListener, this);
        if (data.tableEnabled) pm.registerEvents(tableListener, this);
        if (registry.anyEnabled()) pm.registerEvents(fishingListener, this);
        if (data.shopEnabled) pm.registerEvents(shopGui, this);
        if (data.craftTransferEnabled) pm.registerEvents(effectListener.craftListener(), this);

        if (getCommand("enchant") != null) {
            getCommand("enchant").setExecutor(commandHandler);
            getCommand("enchant").setTabCompleter(commandHandler);
        } else {
            markDegraded("命令注册", "plugin.yml 缺 commands.enchant 段");
        }

        maintenanceTask = getServer().getScheduler().runTaskTimer(this,
                () -> attributeManager.maintenance(),
                data.checkInterval, data.checkInterval);

        for (Player p : getServer().getOnlinePlayers()) {
            attributeManager.scheduleSync(p);
        }
        getLogger().info("[星穹旅驿] 附魔插件自检完成：正常 " + checkNormal
                + " 项 / 降级 " + checkDegraded + " 项");
    }

    @Override
    public void onDisable() {
        try {
            if (maintenanceTask != null) maintenanceTask.cancel();
            HandlerList.unregisterAll(this);
            getServer().getScheduler().cancelTasks(this);
            if (attributeManager != null) attributeManager.clearAllOnline();
            if (chainMining != null) chainMining.shutdown();
            if (storage != null) storage.clearCache();
            if (effectListener != null) effectListener.clearAllState();
        } catch (RuntimeException ex) {
            getLogger().warning("[星穹旅驿] 停用清理出现异常（不影响卸载）：" + ex.getMessage());
        }
        getLogger().info("[星穹旅驿] StarEnchant 已停用。");
    }

    /** /enchant reload：完整重建（热加载配置）。 */
    public void reloadPlugin() {
        try {
            if (maintenanceTask != null) maintenanceTask.cancel();
            HandlerList.unregisterAll(this);
            getServer().getScheduler().cancelTasks(this);
            attributeManager.clearAllOnline();
            chainMining.shutdown();
            storage.clearCache();
            effectListener.clearAllState();
        } catch (RuntimeException ignored) {
            // 重建前的清理失败不阻断重载
        }
        reloadConfig();
        bootstrap();
    }

    // ---------------------------------------------------------------- 对外 API（活动任务发放接口）

    /** 创建一本自定义附魔书（不放进背包）。附魔无效或未启用时返回 null。 */
    public static ItemStack createEnchantBook(String enchantId, int level) {
        StarEnchant se = instance;
        if (se == null || !se.storage.ready()) return null;
        return se.loreRenderer.buildBook(enchantId, level);
    }

    /**
     * 活动任务发放接口：给玩家发放一本自定义附魔书（sources 必须包含 event）。
     * 跨服（Velocity 场景）可由服主的任务系统在对应服务器内调用，或走 /enchant give 命令。
     */
    public static boolean grantEnchantBook(Player target, String enchantId, int level) {
        StarEnchant se = instance;
        if (se == null || target == null || !se.data.eventGrantEnabled) return false;
        ItemStack book = createEnchantBook(enchantId, level);
        if (book == null) return false;
        Def def = se.registry.get(enchantId);
        if (def == null || !def.enabled || !def.sources.contains("event")) return false;
        if (!target.getInventory().addItem(book).isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), book);
        }
        target.sendMessage(se.data.grantMessage.replace("{book}",
                def.color + def.name + " " + LoreRenderer.roman(Math.min(level, def.maxLevel))));
        return true;
    }

    /**
     * 碎片亲和倍率接口：返回玩家最近一次挖掘的碎片掉率倍率（未超时的记录）。
     * 服主现有碎片掉落系统可在挖掘事件链内读取；无有效记录返回 1.0（即不打折也不放大）。
     */
    public double getLastShardAffinityMultiplier(UUID playerId) {
        return effectListener.shardAffinity(playerId);
    }

    // ---------------------------------------------------------------- 模块访问器
    public Cfg cfg() { return cfg; }
    public CfgData data() { return data; }
    public EnchantRegistry registry() { return registry; }
    public EnchantStorage storage() { return storage; }
    public LoreRenderer lore() { return loreRenderer; }
    public AttributeManager attributes() { return attributeManager; }
    public EffectListener.ChainMining chains() { return chainMining; }
    public ShopGui shop() { return shopGui; }

    /** 供连锁挖掘调度器复用自管挖掘流程（不触发辅助特效）。 */
    void breakForChain(Player p, Block b, ItemStack tool, Map<String, Integer> ench) {
        effectListener.manualBreakCore(p, b, tool, ench, false);
    }

    EffectListener effectListener() {
        return effectListener;
    }

    public boolean anyEnabled() {
        return registry.anyEnabled() && storage.ready();
    }

    // ---------------------------------------------------------------- 自检计数与日志
    void markNormal(String item) {
        checkNormal++;
        if (data != null && data.debug) getLogger().info("[自检·正常] " + item);
    }

    void markDegraded(String item, String reason) {
        checkDegraded++;
        getLogger().warning("[自检·降级] " + item + " —— " + reason);
    }

    void debug(String msg) {
        if (data != null && data.debug) getLogger().info("[调试] " + msg);
    }

    // ================================================================ 配置读取器
    /** 类型化读取包装；缺键/类型不符抛 CfgError，由调用方按模块降级。 */
    static final class CfgError extends Exception {
        CfgError(String message) { super(message); }
    }

    static final class Cfg {
        private final StarEnchant plugin;
        Cfg(StarEnchant plugin) { this.plugin = plugin; }

        FileConfiguration raw() { return plugin.getConfig(); }

        boolean has(String path) { return raw().contains(path); }

        String str(String path) throws CfgError {
            if (!raw().isString(path)) throw new CfgError("缺少字符串配置 " + path);
            return raw().getString(path);
        }

        boolean bool(String path) throws CfgError {
            if (!raw().isBoolean(path)) throw new CfgError("缺少布尔配置 " + path);
            return raw().getBoolean(path);
        }

        int integer(String path) throws CfgError {
            if (!raw().isInt(path)) throw new CfgError("缺少整数配置 " + path);
            return raw().getInt(path);
        }

        double dbl(String path) throws CfgError {
            // 兼容整数写法：YAML 里 90 会被解析为 Integer，isDouble() 会误报缺失
            Object v = raw().get(path);
            if (!(v instanceof Number)) throw new CfgError("缺少数值配置 " + path);
            return ((Number) v).doubleValue();
        }

        List<String> strList(String path) throws CfgError {
            List<String> list = raw().getStringList(path);
            if (list.isEmpty()) throw new CfgError("缺少列表配置 " + path);
            return list;
        }

        List<String> strListOrEmpty(String path) {
            return raw().getStringList(path);
        }

        Material mat(String path) throws CfgError {
            String name = str(path);
            Material m = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (m == null || m.isAir()) throw new CfgError("非法材料名 " + path + " = " + name);
            return m;
        }

        Set<Material> matSet(String path) throws CfgError {
            Set<Material> out = new HashSet<>();
            for (String name : strList(path)) {
                Material m = Material.matchMaterial(name == null ? "" : name.trim().toUpperCase(Locale.ROOT));
                if (m == null) throw new CfgError(path + " 中存在非法材料名: " + name);
                out.add(m);
            }
            return out;
        }

        ConfigurationSection section(String path) throws CfgError {
            ConfigurationSection s = raw().getConfigurationSection(path);
            if (s == null) throw new CfgError("缺少配置节 " + path);
            return s;
        }
    }

    // ================================================================ 全局配置快照
    /** 一次性解析全部全局配置；每一节失败只降级对应模块。 */
    static final class CfgData {
        // general
        boolean debug = false;
        long checkInterval = 40L;
        long dayStart = 0L;
        long dayEnd = 12000L;
        String dawnWorld = "";
        String duskWorld = "";
        List<String> riftWorlds = Collections.emptyList();
        int toolDamagePerBreak = 1;
        int shardWindowSeconds = 5;
        double reduceCapPercent = 90.0;
        // storage
        String namespace = "starinn";
        String enchKey = "ench";
        String factionKey = "faction";
        boolean storageConfigOk = true;
        // book
        Material bookMaterial = Material.ENCHANTED_BOOK;
        String bookDisplayName = "";
        String bookDescLine = "";
        // vanilla-plus
        boolean vanillaPlusEnabled = false;
        Map<Enchantment, Integer> levelCaps = Collections.emptyMap();
        boolean conflictEnabled = false;
        boolean groupWeaponTrinity = false;
        boolean groupProtectionSuite = false;
        boolean groupFortuneSilk = false;
        // enchanting table
        boolean tableEnabled = false;
        double tableUpgradeChance = 0;
        int tableUpgradeMaxStep = 0;
        double tableConflictExtraChance = 0;
        // smithing
        boolean smithingEnabled = false;
        List<SmithingRecipeData> smithingRecipes = Collections.emptyList();
        // anvil
        boolean anvilEnabled = false;
        int anvilBookCostLevels = 0;
        boolean anvilMergeUnconflict = false;
        int anvilMaxMergeCost = 0;
        // craft transfer
        boolean craftTransferEnabled = false;
        Set<Material> craftTransferMaterials = Collections.emptySet();
        // loot
        boolean lootEnabled = false;
        double lootRollChance = 0;
        List<LootPoolEntry> lootPools = Collections.emptyList();
        // fishing
        List<Material> treasurePool = Collections.emptyList();
        double treasureBookChance = 0;
        // event grant
        boolean eventGrantEnabled = false;
        String grantMessage = "";
        // materials
        Set<Material> oreBlocks = Collections.emptySet();
        Set<Material> rawOreBlocks = Collections.emptySet();
        Set<Material> stoneBlocks = Collections.emptySet();
        Map<Material, Material> smeltMap = Collections.emptyMap();
        // tool speed
        boolean miningUseHaste = false;
        double hastePercentPerLevel = 10.0;
        // performance
        int chainPerTick = 6;
        int chainQueueLimit = 512;
        int prospectorScanPerTick = 128;
        int homingScanTicks = 2;
        boolean cacheEnabled = true;
        boolean timberIncludeLeaves = false;
        // commands
        int listPerPage = 8;
        boolean tabComplete = true;
        // faction & shop
        String factionDawn = "dawn";
        String factionDusk = "dusk";
        String factionNone = "none";
        boolean shopEnabled = false;
        String shopTitle = "";
        int shopPriceLevels = 0;
        int shopBookLevel = 1;
        String shopLockedLore = "";
        List<String> shopDawnItems = Collections.emptyList();
        List<String> shopDuskItems = Collections.emptyList();

        static CfgData load(Cfg cfg, StarEnchant plugin) {
            CfgData d = new CfgData();
            // ---- general
            try {
                d.debug = cfg.bool("general.debug-log");
                d.checkInterval = Math.max(1L, cfg.integer("general.check-interval-ticks"));
                d.dayStart = cfg.integer("general.day-start");
                d.dayEnd = cfg.integer("general.day-end");
                d.dawnWorld = cfg.str("general.dawn-world");
                d.duskWorld = cfg.str("general.dusk-world");
                d.riftWorlds = cfg.strList("general.rift-worlds");
                d.toolDamagePerBreak = Math.max(0, cfg.integer("general.tool-damage-per-break"));
                d.shardWindowSeconds = Math.max(1, cfg.integer("general.shard-api-window-seconds"));
                d.reduceCapPercent = Math.min(100.0, Math.max(0.0, cfg.dbl("general.reduce-cap-percent")));
                plugin.markNormal("general 配置节");
            } catch (CfgError e) {
                // 世界名/时段缺失时保持安全值，只降级相关附魔判定
                plugin.markDegraded("general 配置节", e.getMessage());
            }
            // ---- storage
            try {
                d.namespace = cfg.str("storage.namespace");
                d.enchKey = cfg.str("storage.ench-key");
                d.factionKey = cfg.str("storage.faction-key");
                plugin.markNormal("storage 配置节");
            } catch (CfgError e) {
                d.storageConfigOk = false;
                plugin.markDegraded("存储（全部自定义附魔功能）", e.getMessage());
            }
            // ---- book
            try {
                d.bookMaterial = cfg.mat("book.material");
                d.bookDisplayName = cfg.str("book.display-name");
                d.bookDescLine = cfg.str("book.desc-line");
                plugin.markNormal("附魔书配置");
            } catch (CfgError e) {
                plugin.markDegraded("附魔书配置", e.getMessage() + "（发放与商店不可用）");
                d.shopEnabled = false;
            }
            // ---- vanilla-plus
            try {
                d.vanillaPlusEnabled = cfg.bool("vanilla-plus.enabled");
                Map<Enchantment, Integer> caps = new HashMap<>();
                ConfigurationSection sec = cfg.section("vanilla-plus.level-caps");
                for (String k : sec.getKeys(false)) {
                    int cap = sec.getInt(k);
                    Enchantment ench = lookupEnchant(k);
                    if (ench == null) {
                        plugin.markDegraded("原版附魔上限项 " + k, "无法识别的原版附魔名");
                        continue;
                    }
                    if (cap < ench.getMaxLevel() || cap > 255) {
                        plugin.markDegraded("原版附魔上限项 " + k,
                                "上限 " + cap + " 非法（须 >= 原版 " + ench.getMaxLevel() + " 且 <= 255）");
                        continue;
                    }
                    caps.put(ench, cap);
                }
                d.levelCaps = caps;
                d.conflictEnabled = cfg.bool("vanilla-plus.conflict-groups.enabled");
                d.groupWeaponTrinity = cfg.bool("vanilla-plus.conflict-groups.weapon-trinity");
                d.groupProtectionSuite = cfg.bool("vanilla-plus.conflict-groups.protection-suite");
                d.groupFortuneSilk = cfg.bool("vanilla-plus.conflict-groups.fortune-silk-touch");
                plugin.markNormal("原版扩展（等级上限 " + caps.size() + " 项）");
            } catch (CfgError e) {
                d.vanillaPlusEnabled = false;
                plugin.markDegraded("原版扩展", e.getMessage());
            }
            // ---- enchanting table
            try {
                d.tableEnabled = cfg.bool("enchanting-table.enabled") && d.vanillaPlusEnabled;
                d.tableUpgradeChance = clampPct(cfg.dbl("enchanting-table.upgrade-chance-percent"));
                d.tableUpgradeMaxStep = Math.max(1, cfg.integer("enchanting-table.upgrade-max-step"));
                d.tableConflictExtraChance = clampPct(cfg.dbl("enchanting-table.conflict-extra-chance-percent"));
                plugin.markNormal("附魔台升级模块");
            } catch (CfgError e) {
                d.tableEnabled = false;
                plugin.markDegraded("附魔台升级模块", e.getMessage());
            }
            // ---- smithing
            try {
                d.smithingEnabled = cfg.bool("smithing.enabled");
                List<SmithingRecipeData> recipes = new ArrayList<>();
                for (Map<?, ?> map : cfg.raw().getMapList("smithing.recipes")) {
                    SmithingRecipeData r = SmithingRecipeData.parse(map, plugin);
                    if (r != null) recipes.add(r);
                }
                d.smithingRecipes = recipes;
                plugin.markNormal("锻造台模块（配方 " + recipes.size() + " 条）");
            } catch (CfgError e) {
                d.smithingEnabled = false;
                plugin.markDegraded("锻造台模块", e.getMessage());
            }
            // ---- anvil
            try {
                d.anvilEnabled = cfg.bool("anvil.enabled");
                d.anvilBookCostLevels = Math.max(0, cfg.integer("anvil.book-cost-levels"));
                d.anvilMergeUnconflict = cfg.bool("anvil.merge-unconflict");
                d.anvilMaxMergeCost = Math.max(1, cfg.integer("anvil.max-merge-cost"));
                plugin.markNormal("铁砧模块");
            } catch (CfgError e) {
                d.anvilEnabled = false;
                plugin.markDegraded("铁砧模块", e.getMessage());
            }
            // ---- craft transfer
            try {
                d.craftTransferEnabled = cfg.bool("craft-transfer.enabled");
                if (d.craftTransferEnabled) {
                    d.craftTransferMaterials = cfg.matSet("craft-transfer.materials");
                }
                plugin.markNormal("合成传递模块");
            } catch (CfgError e) {
                d.craftTransferEnabled = false;
                plugin.markDegraded("合成传递模块", e.getMessage());
            }
            // ---- loot
            try {
                d.lootEnabled = cfg.bool("loot.enabled");
                d.lootRollChance = clampPct(cfg.dbl("loot.roll-chance-percent"));
                List<LootPoolEntry> pools = new ArrayList<>();
                for (Map<?, ?> map : cfg.raw().getMapList("loot.pools")) {
                    LootPoolEntry p = LootPoolEntry.parse(map, plugin);
                    if (p != null) pools.add(p);
                }
                d.lootPools = pools;
                plugin.markNormal("裂缝战利品模块（池 " + pools.size() + " 条）");
            } catch (CfgError e) {
                d.lootEnabled = false;
                plugin.markDegraded("裂缝战利品模块", e.getMessage());
            }
            // ---- fishing
            try {
                d.treasurePool = new ArrayList<>(cfg.matSet("fishing.treasure-pool"));
                d.treasureBookChance = clampPct(cfg.dbl("fishing.treasure-book-chance-percent"));
                plugin.markNormal("钓鱼产出池");
            } catch (CfgError e) {
                d.treasurePool = Collections.emptyList();
                plugin.markDegraded("钓鱼产出池", e.getMessage());
            }
            // ---- event grant
            try {
                d.eventGrantEnabled = cfg.bool("event-grant.enabled");
                d.grantMessage = cfg.str("event-grant.grant-message");
                plugin.markNormal("活动任务发放接口");
            } catch (CfgError e) {
                d.eventGrantEnabled = false;
                plugin.markDegraded("活动任务发放接口", e.getMessage());
            }
            // ---- materials
            try {
                d.oreBlocks = cfg.matSet("materials.ore-blocks");
                d.rawOreBlocks = cfg.matSet("materials.raw-ores");
                d.stoneBlocks = cfg.matSet("materials.stone-blocks");
                Map<Material, Material> smelt = new HashMap<>();
                ConfigurationSection sm = cfg.section("materials.smelt-map");
                for (String k : sm.getKeys(false)) {
                    Material from = Material.matchMaterial(k.toUpperCase(Locale.ROOT));
                    Material to = Material.matchMaterial(String.valueOf(sm.get(k)).toUpperCase(Locale.ROOT));
                    if (from == null || to == null) {
                        plugin.markDegraded("熔炼映射 " + k, "存在非法材料名");
                        continue;
                    }
                    smelt.put(from, to);
                }
                d.smeltMap = smelt;
                plugin.markNormal("方块分类与熔炼映射表");
            } catch (CfgError e) {
                plugin.markDegraded("方块分类表", e.getMessage() + "（富矿/熔炼/采石按空表处理）");
            }
            // ---- tool speed
            try {
                String mode = cfg.str("tool-speed.mode").trim().toLowerCase(Locale.ROOT);
                if (mode.equals("haste")) d.miningUseHaste = true;
                else if (!mode.equals("attribute")) {
                    plugin.markDegraded("挖掘速度模式", "mode 只允许 attribute 或 haste，按 attribute 处理");
                }
                d.hastePercentPerLevel = Math.max(1.0, cfg.dbl("tool-speed.haste-percent-per-level"));
                plugin.markNormal("挖掘速度实现模式（" + (d.miningUseHaste ? "haste" : "attribute") + "）");
            } catch (CfgError e) {
                plugin.markDegraded("挖掘速度实现模式", e.getMessage());
            }
            // ---- performance
            try {
                d.chainPerTick = Math.max(1, cfg.integer("performance.chain-per-tick"));
                d.chainQueueLimit = Math.max(8, cfg.integer("performance.chain-queue-limit"));
                d.prospectorScanPerTick = Math.max(8, cfg.integer("performance.prospector-scan-per-tick"));
                d.homingScanTicks = Math.max(1, cfg.integer("performance.homing-scan-ticks"));
                d.cacheEnabled = cfg.bool("performance.cache-enabled");
                d.timberIncludeLeaves = cfg.bool("performance.timber-include-leaves");
                plugin.markNormal("性能参数");
            } catch (CfgError e) {
                plugin.markDegraded("性能参数", e.getMessage() + "，按内置保守上限继续");
            }
            // ---- commands
            try {
                d.listPerPage = Math.max(1, cfg.integer("commands.list-per-page"));
                d.tabComplete = cfg.bool("commands.tab-complete");
                plugin.markNormal("命令参数");
            } catch (CfgError e) {
                plugin.markDegraded("命令参数", e.getMessage());
            }
            // ---- faction & shop
            try {
                d.factionDawn = cfg.str("faction.dawn-id");
                d.factionDusk = cfg.str("faction.dusk-id");
                d.factionNone = cfg.str("faction.none-id");
                d.shopEnabled = cfg.bool("shop.enabled");
                d.shopTitle = cfg.str("shop.title");
                d.shopPriceLevels = Math.max(0, cfg.integer("shop.price-levels"));
                d.shopBookLevel = Math.max(1, cfg.integer("shop.book-level"));
                d.shopLockedLore = cfg.str("shop.locked-lore");
                d.shopDawnItems = cfg.strList("shop.dawn-items");
                d.shopDuskItems = cfg.strList("shop.dusk-items");
                plugin.markNormal("阵营与商店");
            } catch (CfgError e) {
                d.shopEnabled = false;
                plugin.markDegraded("阵营商店", e.getMessage());
            }
            return d;
        }

        static Enchantment lookupEnchant(String name) {
            String n = name.trim().toLowerCase(Locale.ROOT);
            Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(n));
            return e;
        }

        static double clampPct(double v) {
            if (v < 0) return 0;
            return Math.min(v, 100.0);
        }

        /** 白天判定：[day-start, day-end) 内为白天；边界倒置时自动按补集处理。 */
        boolean isDay(World world) {
            long t = world.getTime() % 24000L;
            if (dayStart <= dayEnd) {
                return t >= dayStart && t < dayEnd;
            }
            return !(t >= dayEnd && t < dayStart);
        }

        boolean isDawnWorld(World w) {
            return w != null && !dawnWorld.isEmpty() && w.getName().equals(dawnWorld);
        }

        boolean isDuskWorld(World w) {
            return w != null && !duskWorld.isEmpty() && w.getName().equals(duskWorld);
        }

        boolean isRiftWorld(World w) {
            return w != null && riftWorlds.contains(w.getName());
        }
    }

    // ================================================================ 附魔分类
    /** 八大类；lore 颜色按类别约定（颜色本身每项独立可配）。 */
    enum Category {
        WEAPON("武器"), ARMOR("防具"), TOOL("工具"), BOW("弓弩"),
        SHIELD("盾牌"), FISHING("钓鱼"), LEGEND("传说"), FACTION("阵营");
        final String cn;
        Category(String cn) { this.cn = cn; }
    }

    // ================================================================ 附魔定义
    /** 单个自定义附魔的静态骨架 + 加载后的配置字段。 */
    static final class Def {
        final String id;
        final Category category;
        final String fallbackName;     // 代码内置名（仅用于日志；显示名以 config 为准）
        final String[] scaleKeys;      // 需按 数值×等级 使用的配置键
        final String[] flatKeys;       // 固定值配置键
        final String triggerDesc;      // 触发方式描述（/enchant info 用）

        boolean valid;
        boolean enabled;
        int maxLevel;
        String name;
        String color;
        Set<String> sources;
        Map<String, Double> scale = Collections.emptyMap();
        Map<String, Double> flat = Collections.emptyMap();

        Def(String id, Category category, String fallbackName,
            String[] scaleKeys, String[] flatKeys, String triggerDesc) {
            this.id = id;
            this.category = category;
            this.fallbackName = fallbackName;
            this.scaleKeys = scaleKeys;
            this.flatKeys = flatKeys;
            this.triggerDesc = triggerDesc;
        }

        /** 按等级取缩放值（数值×等级）。键缺失返回 0（加载校验保证不会走到）。 */
        double sc(String key, int level) {
            Double v = scale.get(key);
            return v == null ? 0.0 : v * level;
        }

        double fl(String key) {
            Double v = flat.get(key);
            return v == null ? 0.0 : v;
        }

        int level(Map<String, Integer> map) {
            Integer v = map.get(id);
            return v == null ? 0 : v;
        }

        int clampLevel(int lv) {
            return Math.max(1, Math.min(maxLevel, lv));
        }

        boolean allowsSource(String src) {
            return sources != null && sources.contains(src);
        }
    }

    // ================================================================ 附魔注册表
    /** 60 个自定义附魔的定义与配置装载。定义块由 tools/gen_meta.py 生成。 */
    static final class EnchantRegistry {
        private static final List<Def> RAW = new ArrayList<>();
        private final StarEnchant plugin;
        private final LinkedHashMap<String, Def> defs = new LinkedHashMap<>();
        private final Map<String, Def> byDisplayName = new HashMap<>();
        private int loadedValid;
        private int loadedInvalid;
        private boolean anyEnabled;

        EnchantRegistry(StarEnchant plugin) {
            this.plugin = plugin;
        }

        static void def_(String id, Category cat, String name,
                         String[] scaleKeys, String[] flatKeys, String trigger) {
            RAW.add(new Def(id, cat, name, scaleKeys, flatKeys, trigger));
        }

        static {
            loadDefs();
        }
        private static void loadDefs() {
// ==================== 以下 60 条由 tools/gen_meta.py 生成,勿手改 ====================
        def_("blaze", Category.WEAPON, "燃焰", new String[]{"fire-seconds-per-level", "monster-damage-percent-per-level"}, new String[]{}, "EntityDamageByEntityEvent 命中生物");
        def_("rift", Category.WEAPON, "裂痕", new String[]{"knockback-percent-per-level", "kill-drop-chance-percent-per-level"}, new String[]{}, "EntityDamageByEntityEvent 命中生物 + EntityDeathEvent 击杀");
        def_("sharp_edge", Category.WEAPON, "疾锋", new String[]{"extra-hit-chance-percent-per-level", "extra-hit-damage-percent"}, new String[]{}, "EntityDamageByEntityEvent 命中生物(服务端等价实现)");
        def_("echo", Category.WEAPON, "回响", new String[]{"chance-percent-per-level"}, new String[]{"restore-food", "restore-health"}, "EntityDeathEvent 击杀怪物");
        def_("drain", Category.WEAPON, "汲取", new String[]{"xp-percent-per-level"}, new String[]{"xp-base-per-monster"}, "EntityDeathEvent 击杀怪物");
        def_("shield_break", Category.WEAPON, "碎盾", new String[]{}, new String[]{"ban-seconds", "blocked-damage-penalty-percent"}, "EntityDamageByEntityEvent 命中且目标正举盾(PVP)");
        def_("cleave", Category.WEAPON, "横扫", new String[]{"aoe-damage-percent-per-level"}, new String[]{"radius-blocks"}, "EntityDamageByEntityEvent 命中生物");
        def_("bloodlust", Category.WEAPON, "嗜血", new String[]{"damage-percent-per-stack-per-level"}, new String[]{"max-stacks", "decay-seconds"}, "连续击杀(EntityDeathEvent 叠层, EntityDamageByEntityEvent 增伤)");
        def_("fracture", Category.WEAPON, "碎骨", new String[]{"ignore-armor-percent-per-level"}, new String[]{}, "EntityDamageByEntityEvent 命中生物");
        def_("night_bite", Category.WEAPON, "噬夜", new String[]{"damage-percent-per-level"}, new String[]{}, "玩家在渊域世界且黑夜时造成伤害");
        def_("sun_brand", Category.WEAPON, "烙日", new String[]{"damage-percent-per-level"}, new String[]{}, "玩家在炽壤世界且白天时造成伤害");
        def_("executioner", Category.WEAPON, "处决", new String[]{"damage-percent-per-level"}, new String[]{"threshold-health-percent"}, "EntityDamageByEntityEvent 命中生命低于阈值的生物");
        def_("chain", Category.WEAPON, "连锁", new String[]{"damage-percent-per-level"}, new String[]{"window-seconds"}, "EntityDeathEvent 击杀 → 窗口期内下次命中增伤");
        def_("blood_pact", Category.WEAPON, "血契", new String[]{"heal-percent-per-level"}, new String[]{}, "EntityDamageByEntityEvent 造成伤害");
        def_("tough", Category.ARMOR, "韧甲", new String[]{"wear-reduce-percent-per-level"}, new String[]{}, "PlayerItemDamageEvent 装备耐久损耗");
        def_("quench", Category.ARMOR, "淬火", new String[]{}, new String[]{"burn-time-reduce-percent"}, "EntityCombustEvent 点燃 + 燃烧期玩家装备免损");
        def_("bedrock_stance", Category.ARMOR, "磐石", new String[]{"knockback-reduce-percent-per-level"}, new String[]{}, "EntityKnockbackEvent 被击退");
        def_("revive", Category.ARMOR, "复苏", new String[]{}, new String[]{"threshold-health-percent", "absorb-seconds", "absorb-amplifier", "cooldown-seconds"}, "玩家生命低于阈值时(EntityDamageEvent 后检测)");
        def_("shade", Category.ARMOR, "潜影", new String[]{"speed-percent"}, new String[]{"hide-radius-blocks"}, "玩家潜行且在渊域世界且黑夜(定时检测)");
        def_("featherweight", Category.ARMOR, "轻羽", new String[]{"speed-percent-per-level"}, new String[]{}, "穿戴时(定时检测)");
        def_("vitality", Category.ARMOR, "生机", new String[]{"health-per-level"}, new String[]{}, "穿戴时(属性修正)");
        def_("thorns_edge", Category.ARMOR, "棘刺", new String[]{"reflect-percent-per-level"}, new String[]{}, "被近战命中(EntityDamageByEntityEvent)");
        def_("void_ward", Category.ARMOR, "渊护", new String[]{"damage-reduce-percent-per-level"}, new String[]{}, "玩家在渊域世界时受到伤害(EntityDamageEvent)");
        def_("sun_ward", Category.ARMOR, "日照", new String[]{"damage-reduce-percent-per-level"}, new String[]{}, "玩家在炽壤世界时受到伤害(EntityDamageEvent)");
        def_("deep_breath", Category.ARMOR, "深息", new String[]{"air-reduce-percent-per-level"}, new String[]{}, "水下时(定时检测)");
        def_("steadfast", Category.ARMOR, "屹立", new String[]{"fall-reduce-percent-per-level"}, new String[]{}, "落地摔落(EntityDamageEvent CAUSE=FALL)");
        def_("shard_affinity", Category.TOOL, "碎片亲和", new String[]{"mult-per-level"}, new String[]{}, "BlockBreakEvent 挖掘(提供倍率记录,实际掉落由服主现有系统读取)");
        def_("rich_vein", Category.TOOL, "富矿", new String[]{"chance-percent-per-level"}, new String[]{}, "BlockBreakEvent 挖掘矿石类方块(材料表可配)");
        def_("smelt", Category.TOOL, "熔炼", new String[]{}, new String[]{"xp-multiplier", "xp-base"}, "BlockBreakEvent 挖掘矿石类方块(映射表可配)");
        def_("rock_break", Category.TOOL, "断岩", new String[]{"speed-percent-per-level"}, new String[]{}, "BlockBreakEvent 挖掘(属性修正,可切药水效果模式)");
        def_("spread", Category.TOOL, "蔓延", new String[]{"max-blocks-per-level"}, new String[]{}, "潜行挖掘方块(分帧处理)");
        def_("timber", Category.TOOL, "伐木", new String[]{"max-blocks-per-level"}, new String[]{}, "砍伐原木(相连树扫描,分帧处理)");
        def_("deep_dig", Category.TOOL, "深掘", new String[]{"depth-per-level"}, new String[]{}, "向下挖掘(分帧处理)");
        def_("magnet", Category.TOOL, "磁引", new String[]{}, new String[]{"collect-radius-blocks"}, "挖掘产生掉落物");
        def_("prospector", Category.TOOL, "勘探", new String[]{"radius-blocks-per-level"}, new String[]{"show-seconds"}, "挖掘方块(分帧扫描+粒子,仅触发者可见)");
        def_("swift_repair", Category.TOOL, "速修", new String[]{"chance-percent-per-level"}, new String[]{"repair-amount", "xp-cost-levels"}, "BlockBreakEvent 挖掘");
        def_("quarry", Category.TOOL, "采石", new String[]{"speed-percent-per-level"}, new String[]{}, "挖掘石头类方块(材料表可配)");
        def_("fine_gather", Category.TOOL, "精收", new String[]{"chance-percent-per-level"}, new String[]{}, "BlockBreakEvent 挖掘非矿石方块");
        def_("star_chase", Category.BOW, "追星", new String[]{"speed-percent-per-level", "damage-percent-per-level"}, new String[]{}, "EntityShootBowEvent + ProjectileHitEvent");
        def_("void_split", Category.BOW, "裂空", new String[]{"aoe-damage-percent-per-level"}, new String[]{"radius-blocks"}, "ProjectileHitEvent 箭矢命中生物");
        def_("pierce_shadow", Category.BOW, "贯影", new String[]{}, new String[]{"pierce-count"}, "ProjectileHitEvent 箭矢命中生物");
        def_("split_arrow", Category.BOW, "裂矢", new String[]{"extra-arrows-per-level"}, new String[]{"spread-degrees"}, "EntityShootBowEvent 射箭");
        def_("homing", Category.BOW, "寻星", new String[]{"radius-blocks-per-level"}, new String[]{"max-ticks", "turn-factor"}, "射箭后定时刻追踪最近生物(有时限)");
        def_("blast_tip", Category.BOW, "爆镞", new String[]{"damage-percent-per-level"}, new String[]{"radius-blocks", "explosion-power"}, "ProjectileHitEvent 箭矢命中");
        def_("frost_bind", Category.BOW, "霜缚", new String[]{"slow-seconds-per-level"}, new String[]{"slow-amplifier"}, "ProjectileHitEvent 箭矢命中生物");
        def_("quick_draw", Category.BOW, "速射", new String[]{"threshold-reduce-percent-per-level"}, new String[]{}, "EntityShootBowEvent 拉弓(服务端按力度判定)");
        def_("bulwark", Category.SHIELD, "壁垒", new String[]{"extra-reduce-percent-per-level"}, new String[]{}, "挡格成功(EntityDamageByEntityEvent,举盾判定)");
        def_("reflect", Category.SHIELD, "反射", new String[]{"damage-percent-per-level"}, new String[]{}, "挡格箭矢成功");
        def_("bash", Category.SHIELD, "盾击", new String[]{"damage-percent-per-level"}, new String[]{"window-seconds"}, "挡格成功后(EntityDamageByEntityEvent)");
        def_("angler", Category.FISHING, "垂钓", new String[]{"wait-reduce-percent-per-level", "rarity-bonus-percent-per-level"}, new String[]{}, "PlayerFishEvent 抛竿/收竿");
        def_("treasure_seeker", Category.FISHING, "寻宝", new String[]{"treasure-chance-percent-per-level"}, new String[]{}, "PlayerFishEvent 钓到物品");
        def_("endless", Category.LEGEND, "不灭", new String[]{}, new String[]{}, "被动(PlayerItemDamageEvent)");
        def_("soulbound", Category.LEGEND, "魂契", new String[]{}, new String[]{}, "玩家死亡(PlayerDeathEvent)");
        def_("beacon_light", Category.LEGEND, "灯塔之辉", new String[]{"damage-percent-per-level", "damage-reduce-percent-per-level", "speed-percent-per-level"}, new String[]{}, "被动(定时检测)");
        def_("dawn_light", Category.FACTION, "昼辉", new String[]{"damage-percent-per-level"}, new String[]{}, "玩家在炽壤世界且白天造成伤害");
        def_("night_hunt", Category.FACTION, "夜猎", new String[]{"damage-percent-per-level"}, new String[]{}, "玩家在渊域世界且黑夜造成伤害");
        def_("star_vein_dawn", Category.FACTION, "星脉·昼", new String[]{"speed-percent-per-level"}, new String[]{}, "炽壤白天挖掘(属性/效果修正)");
        def_("star_vein_dusk", Category.FACTION, "星脉·夜", new String[]{"speed-percent-per-level"}, new String[]{}, "渊域黑夜挖掘(属性/效果修正)");
        def_("oath_guard_dawn", Category.FACTION, "守誓·昼", new String[]{"damage-reduce-percent-per-level"}, new String[]{}, "炽壤白天受伤(EntityDamageEvent)");
        def_("oath_guard_dusk", Category.FACTION, "守誓·夜", new String[]{"damage-reduce-percent-per-level"}, new String[]{}, "渊域黑夜受伤(EntityDamageEvent)");
// ==================== 生成结束 ====================
        }

        void load() {
            defs.clear();
            byDisplayName.clear();
            loadedValid = 0;
            loadedInvalid = 0;
            anyEnabled = false;
            Cfg cfg = plugin.cfg();
            for (Def raw : RAW) {
                Def d = raw;
                d.valid = false;
                String base = "enchants." + d.id;
                try {
                    d.enabled = cfg.bool(base + ".enabled");
                    d.maxLevel = cfg.integer(base + ".max-level");
                    if (d.maxLevel < 1 || d.maxLevel > 255) {
                        throw new CfgError("max-level 超出合法范围 1~255");
                    }
                    d.name = cfg.str(base + ".display-name");
                    d.color = cfg.str(base + ".color");
                    if (!d.color.matches("^§[0-9a-fA-F]$")) {
                        throw new CfgError("color 必须是单个原版颜色码（§0~§f），禁止自定义字体/格式");
                    }
                    Set<String> srcs = new LinkedHashSet<>();
                    for (String s : cfg.strListOrEmpty(base + ".sources")) {
                        if (s != null && !s.isBlank()) srcs.add(s.trim().toLowerCase(Locale.ROOT));
                    }
                    if (srcs.isEmpty()) throw new CfgError("sources 获取途径白名单不能为空");
                    d.sources = srcs;
                    ConfigurationSection v = cfg.section(base + ".values");
                    Map<String, Double> scale = new HashMap<>();
                    Map<String, Double> flat = new HashMap<>();
                    for (String k : d.scaleKeys) {
                        if (!v.isDouble(k) && !v.isInt(k)) {
                            throw new CfgError("values 缺少数值键 " + k);
                        }
                        scale.put(k, v.getDouble(k));
                    }
                    for (String k : d.flatKeys) {
                        if (!v.isDouble(k) && !v.isInt(k)) {
                            throw new CfgError("values 缺少数值键 " + k);
                        }
                        flat.put(k, v.getDouble(k));
                    }
                    d.scale = scale;
                    d.flat = flat;
                } catch (Exception ex) {
                    d.valid = false;
                    loadedInvalid++;
                    plugin.markDegraded("自定义附魔 " + d.id + "（" + d.fallbackName + "）",
                            "配置缺失或非法：" + ex.getMessage());
                    continue;
                }
                Def dup = byDisplayName.get(d.name);
                if (dup != null) {
                    loadedInvalid++;
                    plugin.markDegraded("自定义附魔 " + d.id + "（" + d.name + "）",
                            "显示名与 " + dup.id + " 重复，lore 匹配将无法区分，本项关闭");
                    continue;
                }
                d.valid = true;
                defs.put(d.id, d);
                byDisplayName.put(d.name, d);
                loadedValid++;
                if (d.enabled) anyEnabled = true;
            }
            if (loadedInvalid == 0) {
                plugin.markNormal("60 个自定义附魔配置完整（有效 " + loadedValid + " 个）");
            }
        }

        /** 全部定义（保持注册顺序 = 类别顺序，lore 行序与此一致）。 */
        Collection<Def> all() {
            return defs.values();
        }

        Def get(String id) {
            return id == null ? null : defs.get(id.trim().toLowerCase(Locale.ROOT));
        }

        Def getByDisplayName(String name) {
            return byDisplayName.get(name);
        }

        boolean anyEnabled() {
            return anyEnabled;
        }

        /** 自检降级某附魔后重算“是否仍有启用项”。 */
        void refreshEnabled() {
            anyEnabled = false;
            for (Def d : defs.values()) {
                if (d.enabled) {
                    anyEnabled = true;
                    return;
                }
            }
        }

        int loadedValid() { return loadedValid; }
        int loadedInvalid() { return loadedInvalid; }
        int totalDefs() { return RAW.size(); }
    }

    // ================================================================ 锻造配方数据
    /** config smithing.recipes 的单条配方。 */
    static final class SmithingRecipeData {
        final String label;
        final Material template;
        final Set<Material> equipment;   // 空集 = ANY 任意装备
        final Material mineral;
        final int mineralAmount;
        final String addEnchant;
        final int addLevels;

        SmithingRecipeData(String label, Material template, Set<Material> equipment,
                           Material mineral, int mineralAmount, String addEnchant, int addLevels) {
            this.label = label;
            this.template = template;
            this.equipment = equipment;
            this.mineral = mineral;
            this.mineralAmount = mineralAmount;
            this.addEnchant = addEnchant;
            this.addLevels = addLevels;
        }

        boolean anyEquipment() {
            return equipment.isEmpty();
        }

        /** 从 yaml map 解析；非法返回 null 并记降级。 */
        static SmithingRecipeData parse(Map<?, ?> map, StarEnchant plugin) {
            try {
                String label = map.get("name") == null ? "未命名配方" : String.valueOf(map.get("name"));
                Material template = needMat(map.get("template"), "smithing.recipes[" + label + "].template");
                Set<Material> equip = new HashSet<>();
                Object eq = map.get("equipment");
                if (eq instanceof String s) {
                    if (!s.trim().equalsIgnoreCase("ANY")) {
                        equip.add(needMat(s, label + ".equipment"));
                    }
                } else if (eq instanceof List<?> list) {
                    for (Object o : list) {
                        equip.add(needMat(o, label + ".equipment"));
                    }
                } else {
                    throw new CfgError(label + ".equipment 缺失（ANY 或材料列表）");
                }
                Material mineral = needMat(map.get("mineral"), label + ".mineral");
                int amount = 1;
                Object amt = map.get("mineral-amount");
                if (amt instanceof Number n) amount = Math.max(1, n.intValue());
                String addEnch = map.get("add-enchant") == null ? "" : String.valueOf(map.get("add-enchant")).trim().toLowerCase(Locale.ROOT);
                Def d = plugin.registry().get(addEnch);
                if (d == null || !d.valid) throw new CfgError(label + ".add-enchant 引用了不存在的附魔 id: " + addEnch);
                if (!d.allowsSource("smithing")) {
                    plugin.debug("锻造配方 " + label + " 的目标附魔 " + addEnch + " 未开放 smithing 途径，仍按配置执行（发放来源白名单约束掉落/商店，不约束配方）");
                }
                int levels = 1;
                Object lv = map.get("add-levels");
                if (lv instanceof Number n) levels = Math.max(1, n.intValue());
                return new SmithingRecipeData(label, template, equip, mineral, amount, addEnch, levels);
            } catch (CfgError e) {
                plugin.markDegraded("锻造配方", e.getMessage() + "，该条配方已跳过");
                return null;
            } catch (RuntimeException e) {
                plugin.markDegraded("锻造配方", "解析异常：" + e.getMessage() + "，该条配方已跳过");
                return null;
            }
        }

        static Material needMat(Object v, String where) throws CfgError {
            if (v == null) throw new CfgError(where + " 缺失");
            Material m = Material.matchMaterial(String.valueOf(v).trim().toUpperCase(Locale.ROOT));
            if (m == null || m.isAir()) throw new CfgError(where + " 存在非法材料名: " + v);
            return m;
        }
    }

    // ================================================================ 掉落池条目
    /** config loot.pools 的单条池。 */
    static final class LootPoolEntry {
        final String enchant;
        final int weight;
        final int minLevel;
        final int maxLevel;
        final Set<String> mobs;

        LootPoolEntry(String enchant, int weight, int minLevel, int maxLevel, Set<String> mobs) {
            this.enchant = enchant;
            this.weight = weight;
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
            this.mobs = mobs;
        }

        static LootPoolEntry parse(Map<?, ?> map, StarEnchant plugin) {
            try {
                String ench = map.get("enchant") == null ? "" : String.valueOf(map.get("enchant")).trim().toLowerCase(Locale.ROOT);
                Def d = plugin.registry().get(ench);
                if (d == null || !d.valid) throw new CfgError("loot.pools 引用了不存在的附魔 id: " + ench);
                int w = 1;
                Object weight = map.get("weight");
                if (weight instanceof Number n) w = Math.max(1, n.intValue());
                int min = 1;
                int max = 1;
                Object mn = map.get("min-level");
                Object mx = map.get("max-level");
                if (mn instanceof Number n) min = Math.max(1, n.intValue());
                if (mx instanceof Number n) max = Math.max(min, n.intValue());
                min = d.clampLevel(min);
                max = d.clampLevel(Math.min(max, d.maxLevel));
                Set<String> mobs = new HashSet<>();
                Object mb = map.get("mobs");
                if (mb instanceof List<?> list) {
                    for (Object o : list) {
                        if (o != null && !String.valueOf(o).isBlank()) {
                            mobs.add(String.valueOf(o).trim().toUpperCase(Locale.ROOT));
                        }
                    }
                }
                return new LootPoolEntry(ench, w, min, max, mobs);
            } catch (CfgError e) {
                plugin.markDegraded("裂缝战利品池", e.getMessage() + "，该条已跳过");
                return null;
            } catch (RuntimeException e) {
                plugin.markDegraded("裂缝战利品池", "解析异常：" + e.getMessage() + "，该条已跳过");
                return null;
            }
        }
    }

    // ================================================================ PDC 存储
    /**
     * 自定义附魔的唯一存储：物品 PDC 单键 starinn:ench（内容为 附魔id->等级 映射的序列化串），
     * 随物品本体跨服（Velocity 转服）、跨世界、跨容器流动，零数据库零数据包。
     * 解析结果按原始串缓存（同一数据只解析一次），满足高频事件的读取性能要求。
     */
    static final class EnchantStorage {
        static final Map<String, Integer> EMPTY = Collections.emptyMap();
        private static final int CACHE_LIMIT = 200_000;

        private final StarEnchant plugin;
        private NamespacedKey enchKey;
        private NamespacedKey factionKey;
        private NamespacedKey arrowKey;
        private NamespacedKey recipeHitKey;
        private NamespacedKey spawnKey;
        private boolean ready;
        private final ConcurrentHashMap<String, Map<String, Integer>> parseCache = new ConcurrentHashMap<>();

        EnchantStorage(StarEnchant plugin) {
            this.plugin = plugin;
        }

        void initKeys() {
            CfgData d = plugin.data();
            if (!d.storageConfigOk) {
                ready = false;
                return;
            }
            try {
                enchKey = new NamespacedKey(d.namespace, d.enchKey);
                factionKey = new NamespacedKey(d.namespace, d.factionKey);
                arrowKey = new NamespacedKey(d.namespace, "arrow_snapshot");
                recipeHitKey = new NamespacedKey(d.namespace, "recipe_hit");
                spawnKey = new NamespacedKey(d.namespace, "spawn_owner");
                ready = true;
                plugin.markNormal("PDC 存储键（" + d.namespace + ":" + d.enchKey + "）");
            } catch (IllegalArgumentException e) {
                ready = false;
                plugin.markDegraded("PDC 存储（全部自定义附魔功能）",
                        "命名空间/键名含非法字符：" + e.getMessage());
            }
        }

        boolean ready() { return ready; }
        NamespacedKey arrowKey() { return arrowKey; }
        NamespacedKey recipeHitKey() { return recipeHitKey; }
        NamespacedKey spawnKey() { return spawnKey; }

        // ---------------- 物品附魔读写
        Map<String, Integer> read(ItemStack item) {
            if (!ready || item == null || !item.hasItemMeta()) return EMPTY;
            String raw;
            try {
                raw = item.getItemMeta().getPersistentDataContainer()
                        .get(enchKey, PersistentDataType.STRING);
            } catch (RuntimeException e) {
                plugin.debug("PDC 读取异常 " + item.getType() + ": " + e);
                return EMPTY;
            }
            if (raw == null || raw.isEmpty()) return EMPTY;
            return parse(raw);
        }

        int level(ItemStack item, String enchantId) {
            if (item == null) return 0;
            Integer v = read(item).get(enchantId);
            return v == null ? 0 : v;
        }

        boolean hasAny(ItemStack item) {
            return !read(item).isEmpty();
        }

        /** 写入并同步 lore。item 必须可持有 meta（空气或无 meta 方块返回 false）。 */
        boolean write(ItemStack item, Map<String, Integer> map) {
            if (!ready || item == null) return false;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return false;
            String s = serialize(map);
            if (s.isEmpty()) {
                meta.getPersistentDataContainer().remove(enchKey);
            } else {
                meta.getPersistentDataContainer().set(enchKey, PersistentDataType.STRING, s);
            }
            plugin.lore().render(meta, map);
            item.setItemMeta(meta);
            return true;
        }

        /** 给物品追加等级（锻造/命令路径）。返回新等级；未变更返回 0。 */
        int addLevels(ItemStack item, Def def, int inc) {
            if (def == null || !def.valid || !def.enabled || inc <= 0) return 0;
            Map<String, Integer> cur = read(item);
            int have = cur.getOrDefault(def.id, 0);
            int next = def.clampLevel(have == 0 ? inc : have + inc);
            if (have == next) return 0;
            Map<String, Integer> out = new LinkedHashMap<>(cur);
            out.put(def.id, next);
            return write(item, out) ? next : 0;
        }

        /** 附魔书 -> 装备：新附魔按书的等级应用；已有则 +1（封顶 max-level）。 */
        boolean applyBook(ItemStack target, Map<String, Integer> bookMap) {
            if (target == null || bookMap.isEmpty()) return false;
            EnchantRegistry reg = plugin.registry();
            Map<String, Integer> cur = read(target);
            Map<String, Integer> out = new LinkedHashMap<>(cur);
            boolean changed = false;
            for (Map.Entry<String, Integer> en : bookMap.entrySet()) {
                Def d = reg.get(en.getKey());
                if (d == null || !d.valid || !d.enabled) continue;
                int have = out.getOrDefault(d.id, 0);
                int next = have == 0 ? d.clampLevel(en.getValue()) : d.clampLevel(have + 1);
                if (next != have) {
                    out.put(d.id, next);
                    changed = true;
                }
            }
            return changed && write(target, out);
        }

        /** 两件装备的自定义附魔合并（同 id 取较高等级）。 */
        Map<String, Integer> mergeMax(Map<String, Integer> a, Map<String, Integer> b) {
            if (b.isEmpty()) return a;
            if (a.isEmpty()) return b;
            Map<String, Integer> out = new LinkedHashMap<>(a);
            for (Map.Entry<String, Integer> en : b.entrySet()) {
                out.merge(en.getKey(), en.getValue(), Math::max);
            }
            return out;
        }

        // ---------------- 序列化与缓存
        String serialize(Map<String, Integer> map) {
            if (map.isEmpty()) return "";
            StringBuilder sb = new StringBuilder(map.size() * 16);
            for (Map.Entry<String, Integer> en : map.entrySet()) {
                Integer lv = en.getValue();
                if (lv == null || lv <= 0) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(en.getKey()).append('=').append(lv);
            }
            return sb.toString();
        }

        private Map<String, Integer> parse(String raw) {
            if (parseCache.size() > CACHE_LIMIT) {
                parseCache.clear(); // 简单防膨胀：超上限直接清空（下轮重建，成本可控）
            }
            return parseCache.computeIfAbsent(raw, s -> {
                Map<String, Integer> out = new LinkedHashMap<>();
                for (String part : s.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0 || eq == part.length() - 1) continue;
                    String id = part.substring(0, eq);
                    Def d = plugin.registry().get(id);
                    if (d == null) continue; // 已下架/非法 id 自动忽略
                    try {
                        int lv = Integer.parseInt(part.substring(eq + 1).trim());
                        if (lv > 0) out.put(id, d.clampLevel(lv));
                    } catch (NumberFormatException ignored) {
                        // 数据损坏片段跳过，不影响其他附魔
                    }
                }
                return Collections.unmodifiableMap(out);
            });
        }

        void clearCache() {
            parseCache.clear();
        }

        // ---------------- 箭矢快照：射击瞬间固化 附魔+基准伤害，命中阶段回放
        /** 格式：owner|序列化附魔|原伤害|穿透余数|quickdraw补伤 */
        void tagArrow(Projectile proj, UUID owner, Map<String, Integer> enchants,
                      double damage, int pierceLeft, double quickDrawExtra) {
            if (!ready || proj == null) return;
            String s = owner + "|" + serialize(enchants) + "|"
                    + damage + "|" + pierceLeft + "|" + quickDrawExtra;
            proj.getPersistentDataContainer().set(arrowKey, PersistentDataType.STRING, s);
        }

        String[] arrowSnapshot(Entity entity) {
            if (!ready || !(entity instanceof Projectile)) return null;
            try {
                String s = entity.getPersistentDataContainer()
                        .get(arrowKey, PersistentDataType.STRING);
                if (s == null || s.isEmpty()) return null;
                String[] parts = s.split("\\|", 5);
                return parts.length == 5 ? parts : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        // ---------------- 掉落物归属（magnet 用）
        void tagSpawnOwner(Item item, UUID owner) {
            if (!ready || item == null) return;
            item.getPersistentDataContainer().set(spawnKey, PersistentDataType.STRING, owner.toString());
        }

        String spawnOwner(Item item) {
            if (!ready || item == null) return null;
            try {
                return item.getPersistentDataContainer().get(spawnKey, PersistentDataType.STRING);
            } catch (RuntimeException e) {
                return null;
            }
        }

        // ---------------- 锻造成品标记（取出结算识别）
        void markRecipeHit(ItemStack item, int recipeIndex) {
            if (!ready || item == null) return;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            meta.getPersistentDataContainer().set(recipeHitKey, PersistentDataType.INTEGER, recipeIndex);
            item.setItemMeta(meta);
        }

        /** 实体（箭矢/掉落物）走 Entity 自身的 PDC。 */

        int recipeHitIndex(ItemStack item) {
            if (!ready || item == null || !item.hasItemMeta()) return -1;
            try {
                Integer v = item.getItemMeta().getPersistentDataContainer()
                        .get(recipeHitKey, PersistentDataType.INTEGER);
                return v == null ? -1 : v;
            } catch (RuntimeException e) {
                return -1;
            }
        }

        void clearRecipeHit(ItemStack item) {
            if (!ready || item == null) return;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            meta.getPersistentDataContainer().remove(recipeHitKey);
            item.setItemMeta(meta);
        }

        // ---------------- 玩家阵营（写在玩家 PDC，跨服同步由 Velocity 转档保证）
        String getFaction(Player p) {
            if (!ready || p == null) return null;
            try {
                return p.getPersistentDataContainer().get(factionKey, PersistentDataType.STRING);
            } catch (RuntimeException e) {
                return null;
            }
        }

        boolean setFaction(Player p, String faction) {
            if (!ready || p == null) return false;
            try {
                if (faction == null || faction.isEmpty()) {
                    p.getPersistentDataContainer().remove(factionKey);
                } else {
                    p.getPersistentDataContainer().set(factionKey, PersistentDataType.STRING, faction);
                }
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

    }

    // ================================================================ lore 渲染
    /**
     * 把自定义附魔渲染为 lore 末尾的彩色文字行（§c燃焰 III 形式）。
     * 仅使用原版颜色码，禁止自定义字体 —— 保证 Geyser 基岩端可读。
     */
    static final class LoreRenderer {
        private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        private final StarEnchant plugin;

        LoreRenderer(StarEnchant plugin) {
            this.plugin = plugin;
        }

        /** 在原 lore 基础上重建自定义行（幂等：重复渲染不叠加）。 */
        void render(ItemMeta meta, Map<String, Integer> map) {
            List<String> prefixes = prefixes();
            List<String> old = meta.hasLore() ? meta.getLore() : null;
            List<String> out = new ArrayList<>(old == null ? map.size() : old.size() + map.size());
            if (old != null) {
                for (String line : old) {
                    if (line != null && !isCustomLine(line, prefixes)) {
                        out.add(line);
                    }
                }
            }
            EnchantRegistry reg = plugin.registry();
            for (Def d : reg.all()) {
                Integer lv = map.get(d.id);
                if (lv != null && lv > 0) {
                    out.add(d.color + d.name + " " + roman(d.clampLevel(lv)));
                }
            }
            meta.setLore(out);
        }

        private List<String> prefixes() {
            List<String> list = new ArrayList<>();
            for (Def d : plugin.registry().all()) {
                list.add(d.color + d.name + " ");
            }
            return list;
        }

        private boolean isCustomLine(String line, List<String> prefixes) {
            for (String p : prefixes) {
                if (line.startsWith(p)) {
                    String tail = line.substring(p.length());
                    if (!tail.isEmpty() && tail.matches("^[IVX0-9]+$")) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 等级罗马数字：1~10 用罗马，更高显示阿拉伯数字（原版客户端上限即 X）。 */
        static String roman(int n) {
            if (n < 1) return "I";
            if (n <= 10) return ROMAN[n - 1];
            return String.valueOf(n);
        }

        /** 构造自定义附魔书（附魔有效且启用才行；sources 白名单由调用处约束）。 */
        ItemStack buildBook(String enchantId, int level) {
            EnchantStorage st = plugin.storage();
            if (!st.ready()) return null;
            Def d = plugin.registry().get(enchantId);
            if (d == null || !d.valid || !d.enabled) return null;
            CfgData data = plugin.data();
            ItemStack book = new ItemStack(data.bookMaterial);
            ItemMeta meta = book.getItemMeta();
            if (meta == null) return null;
            meta.setDisplayName(data.bookDisplayName);
            List<String> lore = new ArrayList<>(2);
            if (!data.bookDescLine.isEmpty()) lore.add(data.bookDescLine);
            meta.setLore(lore);
            book.setItemMeta(meta);
            st.write(book, Collections.singletonMap(d.id, d.clampLevel(level)));
            return book;
        }

        /** 是否为承载自定义附魔的“书”（材质匹配且带 PDC 数据）。 */
        boolean looksLikeBook(ItemStack item) {
            return item != null && item.getType() == plugin.data().bookMaterial && plugin.storage().hasAny(item);
        }

        /** 单行展示文本（命令输出用）。 */
        String displayLine(Def d, int lv) {
            return d.color + d.name + " " + roman(d.clampLevel(lv));
        }
    }

    // ================================================================ 属性与状态管理
    /**
     * 穿戴类附魔（生机/轻羽/潜影/灯塔/挖掘加速/深息）的落地点：
     * 用“期望值 vs 实际 modifier”的差量同步，保证不叠加、不残留。
     * 由维护任务按 check-interval-ticks 周期统一刷新（低频、玩家数可控）。
     */
    static final class AttributeManager {
        private final StarEnchant plugin;
        private NamespacedKey kVitality;
        private NamespacedKey kSpeed;
        private NamespacedKey kMining;
        private final Map<UUID, Long> lastSync = new ConcurrentHashMap<>();

        AttributeManager(StarEnchant plugin) {
            this.plugin = plugin;
        }

        private void ensureKeys() {
            CfgData d = plugin.data();
            if (kVitality == null && d.storageConfigOk) {
                try {
                    kVitality = new NamespacedKey(d.namespace, "se_vitality");
                    kSpeed = new NamespacedKey(d.namespace, "se_speed");
                    kMining = new NamespacedKey(d.namespace, "se_mining");
                } catch (IllegalArgumentException e) {
                    kVitality = null;
                }
            }
        }

        /** 维护周期入口。 */
        void maintenance() {
            CfgData d = plugin.data();
            if (!plugin.anyEnabled()) return;
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    syncPlayer(p, now);
                } catch (RuntimeException e) {
                    plugin.debug("属性同步异常 " + p.getName() + ": " + e);
                }
            }
            // 过期状态清扫
            EffectListener eff = plugin.effectListener();
            if (eff != null) eff.sweepExpired(now);
        }

        void scheduleSync(Player p) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    syncPlayer(p, System.currentTimeMillis());
                } catch (RuntimeException ignored) {
                    // 单玩家同步失败只影响该玩家当次外观
                }
            });
        }

        private void syncPlayer(Player p, long now) {
            ensureKeys();
            if (kVitality == null || !plugin.storage().ready()) return;
            CfgData d = plugin.data();
            EnchantStorage st = plugin.storage();
            EffectListener eff = plugin.effectListener();
            Map<String, Integer> armor = eff.armorMap(p);
            Map<String, Integer> tool = eff.mainToolMap(p);
            EnchantRegistry reg = plugin.registry();

            // ---- 生机：最大生命
            double hpAdd = 0;
            Def vitality = reg.get("vitality");
            if (vitality != null && vitality.enabled) {
                int lv = armor.getOrDefault("vitality", 0);
                if (lv > 0) hpAdd = vitality.sc("health-per-level", lv);
            }
            applyModifier(p, Attribute.MAX_HEALTH, kVitality,
                    hpAdd, AttributeModifier.Operation.ADD_NUMBER);

            // ---- 移速：轻羽 +（潜影条件）
            double speedPct = 0;
            Def feather = reg.get("featherweight");
            if (feather != null && feather.enabled) {
                int lv = armor.getOrDefault("featherweight", 0);
                if (lv > 0) speedPct += feather.sc("speed-percent-per-level", lv);
            }
            if (eff.isShadeActive(p)) {
                Def shade = reg.get("shade");
                if (shade != null && shade.enabled) {
                    speedPct += shade.fl("speed-percent") * armor.getOrDefault("shade", 0);
                }
            }
            applyModifier(p, Attribute.MOVEMENT_SPEED, kSpeed,
                    speedPct / 100.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);

            // ---- 挖掘速度：断岩 + 采石 + 灯塔 + 星脉（按世界与时段）
            double miningPct = 0;
            int rb = tool.getOrDefault("rock_break", 0);
            Def rockBreak = reg.get("rock_break");
            if (rb > 0 && rockBreak != null && rockBreak.enabled) miningPct += rockBreak.sc("speed-percent-per-level", rb);
            int qy = tool.getOrDefault("quarry", 0);
            Def quarry = reg.get("quarry");
            if (qy > 0 && quarry != null && quarry.enabled) miningPct += quarry.sc("speed-percent-per-level", qy);
            int bl = tool.getOrDefault("beacon_light", 0);
            int blArmor = armor.getOrDefault("beacon_light", 0);
            int blLv = Math.max(bl, blArmor);
            Def beacon = reg.get("beacon_light");
            if (blLv > 0 && beacon != null && beacon.enabled) miningPct += beacon.sc("speed-percent-per-level", blLv);
            World w = p.getWorld();
            if (w != null) {
                boolean day = d.isDay(w);
                Def svd = reg.get("star_vein_dawn");
                if (svd != null && svd.enabled && d.isDawnWorld(w) && day) {
                    int lv = Math.max(tool.getOrDefault("star_vein_dawn", 0), armor.getOrDefault("star_vein_dawn", 0));
                    if (lv > 0) miningPct += svd.sc("speed-percent-per-level", lv);
                }
                Def svk = reg.get("star_vein_dusk");
                if (svk != null && svk.enabled && d.isDuskWorld(w) && !day) {
                    int lv = Math.max(tool.getOrDefault("star_vein_dusk", 0), armor.getOrDefault("star_vein_dusk", 0));
                    if (lv > 0) miningPct += svk.sc("speed-percent-per-level", lv);
                }
            }
            if (d.miningUseHaste) {
                // haste 模式：按配置系数换算迅捷药水效果等级（基岩端兼容更好）
                if (miningPct > 0) {
                    int amp = (int) Math.max(0, Math.ceil(miningPct / d.hastePercentPerLevel) - 1);
                    int dur = (int) (d.checkInterval * 2L);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, dur, amp, true, false, false));
                }
            } else {
                plugin.debug("挖掘速度同步 " + p.getName() + " 主手=" + p.getInventory().getItemInMainHand().getType()
                        + " tool=" + tool + " pct=" + miningPct);
                applyModifier(p, Attribute.BLOCK_BREAK_SPEED, kMining,
                        miningPct / 100.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
            }

            // ---- 深息：模拟氧气消耗减免（周期回补空气）
            int db = armor.getOrDefault("deep_breath", 0);
            Def deepBreath = reg.get("deep_breath");
            if (db > 0 && deepBreath != null && deepBreath.enabled) {
                Block eye = p.getLocation().getBlock();
                boolean underwater = eye.getType() == Material.WATER
                        || p.getLocation().add(0, 1, 0).getBlock().isLiquid();
                if (underwater && p.getRemainingAir() < p.getMaximumAir()) {
                    double recover = p.getMaximumAir() * deepBreath.sc("air-reduce-percent-per-level", db)
                            / 100.0 * d.checkInterval / 20.0;
                    p.setRemainingAir((int) Math.min(p.getMaximumAir(), p.getRemainingAir() + Math.max(1, recover)));
                }
            }
            lastSync.put(p.getUniqueId(), now);
        }

        private void applyModifier(Player p, Attribute attr, NamespacedKey key,
                                   double amount, AttributeModifier.Operation op) {
            AttributeInstance inst = p.getAttribute(attr);
            if (inst == null) return;
            boolean exists = false;
            for (AttributeModifier m : inst.getModifiers()) {
                if (key.equals(m.getKey())) {
                    exists = true;
                    break;
                }
            }
            if (amount <= 0) {
                if (exists) inst.removeModifier(key);
                return;
            }
            if (exists) {
                AttributeModifier cur = inst.getModifier(key);
                if (cur != null && Math.abs(cur.getAmount() - amount) < 1.0E-6) {
                    return; // 值未变，不动属性，避免无谓的网络重同步
                }
                inst.removeModifier(key);
            }
            inst.addModifier(new AttributeModifier(key, amount, op));
        }

        /** 清掉本插件在在线/离线玩家身上的所有属性修正（reload / disable）。 */
        void clearAllOnline() {
            CfgData d = plugin.data();
            if (!d.storageConfigOk) return;
            Set<String> keys = new HashSet<>(Arrays.asList("se_vitality", "se_speed", "se_mining"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                clearPlayer(p, keys);
            }
            lastSync.clear();
        }

        private void clearPlayer(Player p, Set<String> keys) {
            for (Attribute attr : new Attribute[]{Attribute.MAX_HEALTH, Attribute.MOVEMENT_SPEED, Attribute.BLOCK_BREAK_SPEED}) {
                AttributeInstance inst = p.getAttribute(attr);
                if (inst == null) continue;
                for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                    NamespacedKey mk = m.getKey();
                    if (mk != null && mk.getNamespace().equals(plugin.data().namespace) && keys.contains(mk.getKey())) {
                        inst.removeModifier(m);
                    }
                }
            }
        }
    }

    // ================================================================ 启动自检
    /** 红线⑥/§9：任一失败只降级对应功能；汇总打印正常/降级计数。 */
    static final class SelfCheck {
        private final StarEnchant plugin;

        SelfCheck(StarEnchant plugin) {
            this.plugin = plugin;
        }

        void runAll() {
            checkPdc();
            checkConfigCore();
            checkEnchantCoverage();
            checkWorlds();
            checkVanillaPlus();
            checkFactionSymmetry();
            checkSmithRefs();
            checkLootRefs();
            checkShopRefs();
            checkBookMaterial();
        }

        private void checkPdc() {
            try {
                ItemStack probe = new ItemStack(Material.IRON_SWORD);
                ItemMeta meta = probe.getItemMeta();
                if (meta == null) {
                    plugin.markDegraded("PDC 可用性", "物品 meta 不可用，自定义附魔全部功能关闭");
                    return;
                }
                NamespacedKey key = new NamespacedKey("starinn_probe", "pdc_probe");
                meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "ok");
                String back = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                if (!"ok".equals(back)) {
                    plugin.markDegraded("PDC 可用性", "PDC 读回校验失败");
                    return;
                }
                meta.getPersistentDataContainer().remove(key);
                plugin.markNormal("PDC 可用性");
            } catch (RuntimeException e) {
                plugin.markDegraded("PDC 可用性", "异常：" + e.getMessage());
            }
        }

        private void checkConfigCore() {
            if (plugin.cfg().has("enchants")) {
                plugin.markNormal("config.yml 结构");
            } else {
                plugin.markDegraded("config.yml 结构", "缺少 enchants 根节，按出厂默认继续（建议 restore 默认文件）");
            }
        }

        private void checkEnchantCoverage() {
            EnchantRegistry reg = plugin.registry();
            plugin.markNormal("自定义附魔注册（有效 " + reg.loadedValid() + "/" + reg.totalDefs() + "）");
        }

        private void checkWorlds() {
            CfgData d = plugin.data();
            if (d.dawnWorld.isEmpty() || Bukkit.getWorld(d.dawnWorld) == null) {
                plugin.markDegraded("炽壤世界判定",
                        "配置 world \"" + d.dawnWorld + "\" 当前不存在（世界未加载或名字不符），"
                                + "烙日/日照/昼系阵营附魔在该世界加载前不会触发");
            } else {
                plugin.markNormal("炽壤世界判定");
            }
            if (d.duskWorld.isEmpty() || Bukkit.getWorld(d.duskWorld) == null) {
                plugin.markDegraded("渊域世界判定",
                        "配置 world \"" + d.duskWorld + "\" 当前不存在，渊系附魔在该世界加载前不会触发");
            } else {
                plugin.markNormal("渊域世界判定");
            }
            boolean anyRift = false;
            for (String w : d.riftWorlds) {
                if (Bukkit.getWorld(w) != null) {
                    anyRift = true;
                    break;
                }
            }
            if (!d.riftWorlds.isEmpty() && !anyRift) {
                plugin.markDegraded("裂缝世界列表", "rift-worlds 中的世界当前均不存在，怪物掉落附魔书暂停");
            } else {
                plugin.markNormal("裂缝世界列表");
            }
        }

        private void checkVanillaPlus() {
            CfgData d = plugin.data();
            if (!d.vanillaPlusEnabled) {
                plugin.markNormal("原版扩展（按配置关闭）");
                return;
            }
            if (d.levelCaps.isEmpty()) {
                plugin.markDegraded("原版扩展·等级上限", "level-caps 为空或全部非法，仅保留互斥解除与附魔台功能");
            } else {
                plugin.markNormal("原版扩展·等级上限（" + d.levelCaps.size() + " 项）");
            }
        }

        /** 阵营专属 6 个必须数值精确对称（仅触发世界/时段不同）。 */
        private void checkFactionSymmetry() {
            EnchantRegistry reg = plugin.registry();
            String[][] pairs = {
                    {"dawn_light", "night_hunt"},
                    {"star_vein_dawn", "star_vein_dusk"},
                    {"oath_guard_dawn", "oath_guard_dusk"},
            };
            for (String[] pr : pairs) {
                Def a = reg.get(pr[0]);
                Def b = reg.get(pr[1]);
                if (a == null || !a.valid || b == null || !b.valid) {
                    continue; // 单项无效时已在注册阶段逐项降级
                }
                boolean ok = a.maxLevel == b.maxLevel
                        && a.enabled == b.enabled
                        && a.scale.equals(b.scale)
                        && a.flat.equals(b.flat);
                if (ok) {
                    plugin.markNormal("阵营对称：" + a.name + " / " + b.name);
                } else {
                    plugin.markDegraded("阵营对称：" + pr[0] + " / " + pr[1],
                            "数值不对称（红线要求仅世界与时段不同），两者已临时关闭以保持公平");
                    a.enabled = false;
                    b.enabled = false;
                    plugin.registry().refreshEnabled();
                }
            }
        }

        private void checkSmithRefs() {
            CfgData d = plugin.data();
            if (!d.smithingEnabled) {
                plugin.markNormal("锻造台（按配置关闭）");
                return;
            }
            if (d.smithingRecipes.isEmpty()) {
                plugin.markDegraded("锻造台配方", "enabled 但无任何有效配方");
            } else {
                plugin.markNormal("锻造台配方（" + d.smithingRecipes.size() + " 条）");
            }
        }

        private void checkLootRefs() {
            CfgData d = plugin.data();
            if (!d.lootEnabled) {
                plugin.markNormal("裂缝战利品（按配置关闭）");
                return;
            }
            if (d.lootPools.isEmpty()) {
                plugin.markDegraded("裂缝战利品", "enabled 但掉落池为空");
            } else {
                plugin.markNormal("裂缝战利品（" + d.lootPools.size() + " 条池）");
            }
        }

        private void checkShopRefs() {
            CfgData d = plugin.data();
            if (!d.shopEnabled) {
                plugin.markNormal("阵营商店（按配置关闭）");
                return;
            }
            List<String> all = new ArrayList<>(d.shopDawnItems);
            all.addAll(d.shopDuskItems);
            List<String> bad = new ArrayList<>();
            for (String id : all) {
                Def def = plugin.registry().get(id);
                if (def == null || !def.valid || !def.enabled || !def.allowsSource("shop")) {
                    bad.add(id);
                }
            }
            if (!bad.isEmpty()) {
                plugin.markDegraded("阵营商店条目", "以下 id 无效/未启用/未开放 shop 途径：" + bad);
            }
            if (all.isEmpty()) {
                plugin.markDegraded("阵营商店条目", "商店清单为空");
            } else if (bad.isEmpty()) {
                plugin.markNormal("阵营商店条目（" + all.size() + " 项）");
            }
        }

        private void checkBookMaterial() {
            CfgData d = plugin.data();
            if (new ItemStack(d.bookMaterial).getItemMeta() == null) {
                plugin.markDegraded("附魔书材质", "book.material 无法承载 meta/lore，发放与商店降级");
            } else {
                plugin.markNormal("附魔书材质");
            }
        }

    }

    // ================================================================ 效果监听器
    /**
     * 第二层 60 个自定义附魔的全部服务端触发逻辑。
     * 性能约定：
     *  - 高频事件先做“零成本早退”（无附魔直接 return）；
     *  - PDC 解析结果按原始串缓存；玩家装备按 tick 缓存快照；
     *  - 连锁挖掘/勘探粒子全部主线程分帧，不占单刻大块时间；
     *  - 全部逻辑主线程执行，无异步访问世界（异步事件本插件不监听）。
     */
    static final class EffectListener implements Listener {
        private final StarEnchant plugin;

        // ---- 装备/手持快照缓存（按 tick 失效）
        private static final class Snap {
            long tick;
            Map<String, Integer> main = EnchantStorage.EMPTY;
            Map<String, Integer> off = EnchantStorage.EMPTY;
            Map<String, Integer> helm = EnchantStorage.EMPTY;
            Map<String, Integer> chest = EnchantStorage.EMPTY;
            Map<String, Integer> legs = EnchantStorage.EMPTY;
            Map<String, Integer> boots = EnchantStorage.EMPTY;
            Map<String, Integer> weapon = null;   // main+off 合并
            Map<String, Integer> armor = null;    // 四件合并
        }
        private final Map<UUID, Snap> snapCache = new ConcurrentHashMap<>();

        // ---- 战斗状态（内存态；跨服场景同服内有效，死亡即清）
        private final Map<UUID, Integer> bloodlustStacks = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastCombatMs = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastKillMs = new ConcurrentHashMap<>();
        private final Map<UUID, Long> shieldBanUntilTick = new ConcurrentHashMap<>();
        private final Map<UUID, Long> bashUntilTick = new ConcurrentHashMap<>();
        private final Map<UUID, Long> reviveCdUntilMs = new ConcurrentHashMap<>();
        private final Map<UUID, double[]> shardAffinity = new ConcurrentHashMap<>();
        private final Map<UUID, double[]> pendingKnockBoost = new ConcurrentHashMap<>();
        private final Map<UUID, BukkitTask> arrowTasks = new ConcurrentHashMap<>();
        private final Map<UUID, List<ItemStack>> soulKeep = new ConcurrentHashMap<>();

        private final CraftTransfer craftTransfer;

        /** 矿石并集（勘探/富矿/熔炼判定），随监听器重建（reload）重新计算。 */
        private final Set<Material> oreUnion;

        EffectListener(StarEnchant plugin) {
            this.plugin = plugin;
            this.craftTransfer = new CraftTransfer(plugin);
            CfgData d = plugin.data();
            Set<Material> u = new HashSet<>(d.oreBlocks);
            u.addAll(d.rawOreBlocks);
            this.oreUnion = Collections.unmodifiableSet(u);
        }

        Listener craftListener() {
            return craftTransfer;
        }

        // ---------------- 小工具
        private CfgData data() {
            return plugin.data();
        }

        private EnchantRegistry reg() {
            return plugin.registry();
        }

        private EnchantStorage st() {
            return plugin.storage();
        }

        /** 取附魔等级：定义必须存在、有效、启用，否则 0（调用处据此跳过）。 */
        int lv(String id, Map<String, Integer> map) {
            Def d = reg().get(id);
            if (d == null || !d.valid || !d.enabled) return 0;
            return map.getOrDefault(id, 0);
        }

        /** 同上并给出定义对象（避免二次查询）。 */
        Def defOf(String id) {
            Def d = reg().get(id);
            return (d == null || !d.valid || !d.enabled) ? null : d;
        }

        private boolean roll(double percent) {
            if (percent <= 0) return false;
            return ThreadLocalRandom.current().nextDouble(100.0) < Math.min(100.0, percent);
        }

        private Player resolvePlayerAttacker(Entity damager) {
            if (damager instanceof Player p) {
                return p;
            }
            if (damager instanceof Projectile pr && pr.getShooter() instanceof Player sp) {
                return sp;
            }
            return null;
        }

        // ---------------- 快照读取
        private Snap snap(Player p) {
            long tick = Bukkit.getCurrentTick();
            if (!data().cacheEnabled) {
                Snap fresh = new Snap();
                fillSnap(p, fresh);
                return fresh;
            }
            Snap s = snapCache.get(p.getUniqueId());
            if (s == null || s.tick != tick) {
                if (s == null) {
                    s = new Snap();
                }
                fillSnap(p, s);
                s.tick = tick;
                snapCache.put(p.getUniqueId(), s);
            }
            return s;
        }

        private void fillSnap(Player p, Snap s) {
            PlayerInventory inv = p.getInventory();
            s.main = st().read(inv.getItemInMainHand());
            s.off = st().read(inv.getItemInOffHand());
            s.helm = st().read(inv.getHelmet());
            s.chest = st().read(inv.getChestplate());
            s.legs = st().read(inv.getLeggings());
            s.boots = st().read(inv.getBoots());
            s.weapon = null;
            s.armor = null;
        }

        Map<String, Integer> weaponMap(Player p) {
            Snap s = snap(p);
            if (s.weapon == null) {
                s.weapon = st().mergeMax(s.main, s.off);
            }
            return s.weapon;
        }

        Map<String, Integer> armorMap(Player p) {
            Snap s = snap(p);
            if (s.armor == null) {
                Map<String, Integer> a = st().mergeMax(s.helm, s.chest);
                Map<String, Integer> b = st().mergeMax(s.legs, s.boots);
                s.armor = st().mergeMax(a, b);
            }
            return s.armor;
        }

        Map<String, Integer> mainToolMap(Player p) {
            return snap(p).main;
        }

        void invalidateSnap(Player p) {
            snapCache.remove(p.getUniqueId());
        }

        // ---------------- 状态查询（AttributeManager 共用）
        boolean isShadeActive(Player p) {
            int lv = lv("shade", armorMap(p));
            if (lv <= 0 || !p.isSneaking()) return false;
            CfgData d = data();
            return d.isDuskWorld(p.getWorld()) && !d.isDay(p.getWorld());
        }

        double shardAffinity(UUID playerId) {
            double[] rec = shardAffinity.get(playerId);
            if (rec == null) return 1.0;
            if (System.currentTimeMillis() > rec[1]) {
                shardAffinity.remove(playerId);
                return 1.0;
            }
            return rec[0];
        }

        /** 周期清扫过期状态。 */
        void sweepExpired(long now) {
            long tick = Bukkit.getCurrentTick();
            bloodlustStacks.entrySet().removeIf(en -> {
                Long t = lastCombatMs.get(en.getKey());
                return t == null || now - t > decayMs();
            });
            lastKillMs.entrySet().removeIf(en -> now - en.getValue() > chainWindowMs());
            shieldBanUntilTick.entrySet().removeIf(en -> tick > en.getValue());
            bashUntilTick.entrySet().removeIf(en -> tick > en.getValue());
            reviveCdUntilMs.entrySet().removeIf(en -> now > en.getValue());
            shardAffinity.entrySet().removeIf(en -> now > en.getValue()[1]);
            pendingKnockBoost.entrySet().removeIf(en -> tick > en.getValue()[1]);
        }

        private long decayMs() {
            Def d = defOf("bloodlust");
            return d == null ? 10_000L : (long) (d.fl("decay-seconds") * 1000);
        }

        private long chainWindowMs() {
            Def d = defOf("chain");
            return d == null ? 5_000L : (long) (d.fl("window-seconds") * 1000);
        }

        // ---------------- 主伤害事件：武器端全部增伤/触发
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onDamageByEntity(EntityDamageByEntityEvent e) {
            if (!plugin.anyEnabled()) return;
            if (!(e.getEntity() instanceof LivingEntity victim)) return;
            Player attacker = resolvePlayerAttacker(e.getDamager());

            if (attacker != null && attacker != victim && !attacker.isDead()) {
                Map<String, Integer> w = weaponMap(attacker);
                if (!w.isEmpty()) {
                    applyWeaponEffects(e, victim, attacker, w);
                }
            }
            // ---- 受击方（玩家）：防御减免、挡格系、反伤、碎盾惩罚
            if (victim instanceof Player vp) {
                applyIncomingPlayer(vp, e);
                applyShieldBlock(vp, e);
                applyThorns(vp, e, attacker);
                applyShieldBreakPenalty(vp, e);
            }
        }

        private void applyWeaponEffects(EntityDamageByEntityEvent e, LivingEntity victim,
                                       Player attacker, Map<String, Integer> w) {
            CfgData d = data();
            double base = e.getDamage();
            double total = base;
            long nowMs = System.currentTimeMillis();
            Def def;
            int l;

            // 燃焰：点燃对所有生物生效；增伤只对怪物（与规范「目标燃烧；对怪物伤害+」一致）
            if ((def = defOf("blaze")) != null && (l = w.getOrDefault("blaze", 0)) > 0 && victim instanceof Mob) {
                int ticks = (int) Math.round(def.sc("fire-seconds-per-level", l) * 20);
                victim.setFireTicks(Math.max(victim.getFireTicks(), ticks));
                if (victim instanceof Monster) {
                    total += base * def.sc("monster-damage-percent-per-level", l) / 100.0;
                }
            }
            // 噬夜：渊域黑夜增伤
            if ((def = defOf("night_bite")) != null && (l = w.getOrDefault("night_bite", 0)) > 0
                    && d.isDuskWorld(attacker.getWorld()) && !d.isDay(attacker.getWorld())) {
                total += base * def.sc("damage-percent-per-level", l) / 100.0;
            }
            // 烙日：炽壤白天增伤
            if ((def = defOf("sun_brand")) != null && (l = w.getOrDefault("sun_brand", 0)) > 0
                    && d.isDawnWorld(attacker.getWorld()) && d.isDay(attacker.getWorld())) {
                total += base * def.sc("damage-percent-per-level", l) / 100.0;
            }
            // 处决：目标低血增伤
            if ((def = defOf("executioner")) != null && (l = w.getOrDefault("executioner", 0)) > 0) {
                double maxHp = attrValue(victim, Attribute.MAX_HEALTH);
                if (maxHp > 0 && victim.getHealth() <= maxHp * def.fl("threshold-health-percent") / 100.0) {
                    total += base * def.sc("damage-percent-per-level", l) / 100.0;
                }
            }
            // 嗜血：层数增伤
            if ((def = defOf("bloodlust")) != null && (l = w.getOrDefault("bloodlust", 0)) > 0) {
                int stacks = bloodlustStacks.getOrDefault(attacker.getUniqueId(), 0);
                if (stacks > 0) {
                    total += base * def.sc("damage-percent-per-stack-per-level", l) * stacks / 100.0;
                }
            }
            // 连锁：击杀后窗口内下一击增伤（命中即消耗）
            if ((def = defOf("chain")) != null && (l = w.getOrDefault("chain", 0)) > 0) {
                Long kill = lastKillMs.get(attacker.getUniqueId());
                if (kill != null && nowMs - kill <= chainWindowMs()) {
                    total += base * def.sc("damage-percent-per-level", l) / 100.0;
                    lastKillMs.remove(attacker.getUniqueId());
                }
            }
            // 盾击：挡格成功后窗口内近战增伤（消耗）
            if ((def = defOf("bash")) != null && (l = w.getOrDefault("bash", 0)) > 0) {
                Long until = bashUntilTick.get(attacker.getUniqueId());
                if (until != null && Bukkit.getCurrentTick() <= until) {
                    total += base * def.sc("damage-percent-per-level", l) / 100.0;
                    bashUntilTick.remove(attacker.getUniqueId());
                }
            }
            // 碎骨：无视部分护甲（按原版护甲减伤公式回补）
            if ((def = defOf("fracture")) != null && (l = w.getOrDefault("fracture", 0)) > 0) {
                double armor = attrValue(victim, Attribute.ARMOR);
                if (armor > 0) {
                    double reduction = Math.min(20.0, Math.max(armor / 5.0, armor - total / 2.0)) / 25.0;
                    total += total * reduction * def.sc("ignore-armor-percent-per-level", l) / 100.0;
                }
            }
            // 阵营专属：昼辉 / 夜猎
            if ((def = defOf("dawn_light")) != null && (l = w.getOrDefault("dawn_light", 0)) > 0
                    && d.isDawnWorld(attacker.getWorld()) && d.isDay(attacker.getWorld())) {
                total += base * def.sc("damage-percent-per-level", l) / 100.0;
            }
            if ((def = defOf("night_hunt")) != null && (l = w.getOrDefault("night_hunt", 0)) > 0
                    && d.isDuskWorld(attacker.getWorld()) && !d.isDay(attacker.getWorld())) {
                total += base * def.sc("damage-percent-per-level", l) / 100.0;
            }
            // 灯塔之辉：全局增伤
            int beaconLv = Math.max(w.getOrDefault("beacon_light", 0), armorMap(attacker).getOrDefault("beacon_light", 0));
            if ((def = defOf("beacon_light")) != null && beaconLv > 0) {
                total += base * def.sc("damage-percent-per-level", beaconLv) / 100.0;
            }
            if (total != base) {
                e.setDamage(Math.max(0.0, total));
            }

            // 碎盾：目标正举盾 → 标记禁用（惩罚伤害在受击分支结算）
            if ((def = defOf("shield_break")) != null && (l = w.getOrDefault("shield_break", 0)) > 0
                    && victim instanceof Player vv && isBlockingShield(vv)) {
                shieldBanUntilTick.put(vv.getUniqueId(), (long) Bukkit.getCurrentTick()
                        + Math.round(def.fl("ban-seconds") * 20));
            }
            // 裂痕：击退加成（写入本刻待结算的击退系数，由 EntityKnockbackEvent 消费）
            if ((def = defOf("rift")) != null && (l = w.getOrDefault("rift", 0)) > 0
                    && !(victim instanceof Player)) {
                pendingKnockBoost.put(victim.getUniqueId(), new double[]{
                        def.sc("knockback-percent-per-level", l) / 100.0, Bukkit.getCurrentTick() + 2});
            }
            // 横扫：半径内其他生物承受本次伤害比例
            if ((def = defOf("cleave")) != null && (l = w.getOrDefault("cleave", 0)) > 0) {
                double r = def.fl("radius-blocks");
                double amt = total * def.sc("aoe-damage-percent-per-level", l) / 100.0;
                if (amt > 0) {
                    for (Entity en : victim.getNearbyEntities(r, r, r)) {
                        if (en instanceof LivingEntity le && le != victim && le.isValid() && !le.isDead()
                                && !(le instanceof Player)) {
                            le.damage(amt);
                        }
                    }
                }
            }
            // 疾锋：攻击冷却的服务端等效实现——概率追加一次迅捷连击
            if ((def = defOf("sharp_edge")) != null && (l = w.getOrDefault("sharp_edge", 0)) > 0) {
                double chance = def.sc("extra-hit-chance-percent-per-level", l);
                if (roll(chance)) {
                    double extra = total * def.fl("extra-hit-damage-percent") / 100.0;
                    UUID vid = victim.getUniqueId();
                    Location vloc = victim.getLocation();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Entity ent = Bukkit.getEntity(vid);
                        if (ent instanceof LivingEntity le && le.isValid() && !le.isDead()) {
                            le.damage(extra);
                            vloc.getWorld().spawnParticle(Particle.CRIT, vloc.add(0, 1, 0), 6, 0.2, 0.4, 0.2, 0.05);
                        }
                    });
                }
            }
            // 血契：吸血
            if ((def = defOf("blood_pact")) != null && (l = w.getOrDefault("blood_pact", 0)) > 0) {
                double heal = e.getDamage() * def.sc("heal-percent-per-level", l) / 100.0;
                if (heal > 0) {
                    double maxHp = attrValue(attacker, Attribute.MAX_HEALTH);
                    attacker.setHealth(Math.min(maxHp, attacker.getHealth() + heal));
                }
            }
            // 战斗计时（嗜血续层窗口）
            lastCombatMs.put(attacker.getUniqueId(), nowMs);
        }

        private double attrValue(LivingEntity entity, Attribute attr) {
            AttributeInstance inst = entity.getAttribute(attr);
            return inst == null ? 0.0 : Math.max(0.0, inst.getValue());
        }

        /** 举盾判定：主/副手持盾且处于举盾状态（1.21 服务端挡格行为）。 */
        private Map<String, Integer> blockingShield(Player vp) {
            if (!vp.isHandRaised()) return null;
            PlayerInventory inv = vp.getInventory();
            ItemStack shield = inv.getItemInOffHand();
            if (shield.getType() == Material.SHIELD) {
                return snap(vp).off;
            }
            if (inv.getItemInMainHand().getType() == Material.SHIELD) {
                return snap(vp).main;
            }
            return null;
        }

        private boolean isBlockingShield(Player vp) {
            return blockingShield(vp) != null;
        }

        // ---------------- 防御端（玩家受击）
        /** void_ward/sun_ward/守誓/灯塔 的受伤减免 + 复苏触发。DBE 与通用伤害共用。 */
        private void applyIncomingPlayer(Player vp, EntityDamageEvent e) {
            Map<String, Integer> armor = armorMap(vp);
            if (armor.isEmpty()) return;
            CfgData d = data();
            Def def;
            int l;
            double pct = 0;
            World w = vp.getWorld();
            if ((def = defOf("void_ward")) != null && (l = armor.getOrDefault("void_ward", 0)) > 0 && d.isDuskWorld(w)) {
                pct += def.sc("damage-reduce-percent-per-level", l) / 100.0;
            }
            if ((def = defOf("sun_ward")) != null && (l = armor.getOrDefault("sun_ward", 0)) > 0 && d.isDawnWorld(w)) {
                pct += def.sc("damage-reduce-percent-per-level", l) / 100.0;
            }
            boolean day = d.isDay(w);
            if ((def = defOf("oath_guard_dawn")) != null && (l = armor.getOrDefault("oath_guard_dawn", 0)) > 0
                    && d.isDawnWorld(w) && day) {
                pct += def.sc("damage-reduce-percent-per-level", l) / 100.0;
            }
            if ((def = defOf("oath_guard_dusk")) != null && (l = armor.getOrDefault("oath_guard_dusk", 0)) > 0
                    && d.isDuskWorld(w) && !day) {
                pct += def.sc("damage-reduce-percent-per-level", l) / 100.0;
            }
            if ((def = defOf("beacon_light")) != null && (l = armor.getOrDefault("beacon_light", 0)) > 0) {
                pct += def.sc("damage-reduce-percent-per-level", l) / 100.0;
            }
            if (pct > 0) {
                pct = Math.min(pct, d.reduceCapPercent / 100.0);
                e.setDamage(Math.max(0.0, e.getDamage() * (1.0 - pct)));
            }
            // 复苏：生命低于阈值时给予吸收（有冷却）
            if ((def = defOf("revive")) != null && (l = armor.getOrDefault("revive", 0)) > 0) {
                double maxHp = attrValue(vp, Attribute.MAX_HEALTH);
                long now = System.currentTimeMillis();
                Long cd = reviveCdUntilMs.get(vp.getUniqueId());
                if (maxHp > 0 && (vp.getHealth() - e.getDamage()) < maxHp * def.fl("threshold-health-percent") / 100.0
                        && (cd == null || now >= cd)) {
                    vp.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,
                            (int) Math.round(def.fl("absorb-seconds") * 20), (int) def.fl("absorb-amplifier"),
                            true, false, false));
                    vp.playSound(vp.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.7f, 1.6f);
                    reviveCdUntilMs.put(vp.getUniqueId(), now + (long) (def.fl("cooldown-seconds") * 1000));
                }
            }
        }

        /** 屹立：摔落减免（仅通用伤害事件会走到，无实体伤害源）。 */
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onGenericDamage(EntityDamageEvent e) {
            if (!plugin.anyEnabled()) return;
            if (e instanceof EntityDamageByEntityEvent) return;
            if (!(e.getEntity() instanceof Player vp)) return;
            EntityDamageEvent.DamageCause cause = e.getCause();
            Def def;
            int l;
            Map<String, Integer> armor = null;
            if (cause == EntityDamageEvent.DamageCause.FALL || cause == EntityDamageEvent.DamageCause.FLY_INTO_WALL) {
                armor = armorMap(vp);
                if ((def = defOf("steadfast")) != null && (l = armor.getOrDefault("steadfast", 0)) > 0) {
                    e.setDamage(Math.max(0.0, e.getDamage()
                            * (1.0 - def.sc("fall-reduce-percent-per-level", l) / 100.0)));
                }
            }
            applyIncomingPlayer(vp, e);
        }

        /** 壁垒/反射/盾击记录 + 碎盾惩罚（挡格语义的补偿结算）。 */
        private void applyShieldBlock(Player vp, EntityDamageByEntityEvent e) {
            Map<String, Integer> shield = blockingShield(vp);
            if (shield == null) return;
            Def def;
            int l;
            if ((def = defOf("bulwark")) != null && (l = shield.getOrDefault("bulwark", 0)) > 0) {
                e.setDamage(Math.max(0.0, e.getDamage()
                        * (1.0 - def.sc("extra-reduce-percent-per-level", l) / 100.0)));
            }
            if ((def = defOf("reflect")) != null && (l = shield.getOrDefault("reflect", 0)) > 0
                    && e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof LivingEntity shooter
                    && shooter.isValid() && !shooter.isDead()) {
                double amt = e.getDamage() * def.sc("damage-percent-per-level", l) / 100.0;
                if (amt > 0) {
                    shooter.damage(amt);
                    vp.getWorld().spawnParticle(Particle.END_ROD, shooter.getLocation().add(0, 1.2, 0), 4, 0.1, 0.2, 0.1, 0.02);
                }
            }
            // 成功挡格 → 盾击窗口
            Def bash = defOf("bash");
            int bl;
            if (bash != null && (bl = shield.getOrDefault("bash", 0)) > 0) {
                bashUntilTick.put(vp.getUniqueId(), (long) Bukkit.getCurrentTick()
                        + Math.round(bash.fl("window-seconds") * 20));
            }
        }

        /** 棘刺：被近战命中反弹。 */
        private void applyThorns(Player vp, EntityDamageByEntityEvent e, Player attacker) {
            if (attacker == null || attacker == vp) return;
            if (!(e.getDamager() instanceof LivingEntity la)) return; // 近战实体
            Map<String, Integer> armor = armorMap(vp);
            Def def;
            int l;
            if ((def = defOf("thorns_edge")) != null && (l = armor.getOrDefault("thorns_edge", 0)) > 0) {
                double amt = e.getDamage() * def.sc("reflect-percent-per-level", l) / 100.0;
                if (amt > 0 && la.isValid() && !la.isDead()) {
                    la.damage(amt);
                }
            }
        }

        /** 碎盾惩罚：禁用期内举盾不再获得保护（按配置比例补偿伤害）。 */
        private void applyShieldBreakPenalty(Player vp, EntityDamageByEntityEvent e) {
            Long ban = shieldBanUntilTick.get(vp.getUniqueId());
            if (ban == null || Bukkit.getCurrentTick() > ban) return;
            if (!isBlockingShield(vp)) return;
            Def def = defOf("shield_break");
            if (def == null) return;
            double penalty = def.fl("blocked-damage-penalty-percent");
            if (penalty > 0) {
                e.setDamage(e.getDamage() * (1.0 + penalty / 100.0));
            }
        }

        /** 磐石（减免被击退）与裂痕（攻击方击退加成）在此汇合。
         *  用 Paper 版事件（Bukkit 版在 1.21.11 已弃用并触发性能警告）。 */
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onKnockback(EntityKnockbackEvent e) {
            if (!plugin.anyEnabled()) return;
            Vector kv = e.getKnockback();
            double scale = 1.0;
            double[] boost = pendingKnockBoost.remove(e.getEntity().getUniqueId());
            if (boost != null && Bukkit.getCurrentTick() <= boost[1]) {
                scale += boost[0];
            }
            if (e.getEntity() instanceof Player vp) {
                Map<String, Integer> armor = armorMap(vp);
                Def def;
                int l;
                if ((def = defOf("bedrock_stance")) != null && (l = armor.getOrDefault("bedrock_stance", 0)) > 0) {
                    scale -= def.sc("knockback-reduce-percent-per-level", l) / 100.0;
                }
            }
            if (scale != 1.0 && kv != null) {
                e.setKnockback(kv.multiply(Math.max(0.0, scale)));
            }
        }

        // ---------------- 死亡：回响/汲取/裂痕掉录/嗜血叠层/连锁标记/裂缝战利品
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
        public void onDeath(EntityDeathEvent e) {
            if (!plugin.anyEnabled()) return;
            LivingEntity dead = e.getEntity();
            // 裂缝战利品 / 怪物掉落池
            dropLootBook(e, dead);

            Player killer = dead.getKiller();
            if (killer == null) return;
            Map<String, Integer> w = weaponMap(killer);
            if (w.isEmpty()) return;
            CfgData d = data();
            long now = System.currentTimeMillis();
            Def def;
            int l;
            if ((def = defOf("echo")) != null && (l = w.getOrDefault("echo", 0)) > 0
                    && roll(def.sc("chance-percent-per-level", l))) {
                killer.setFoodLevel(Math.min(20, killer.getFoodLevel() + (int) def.fl("restore-food")));
                killer.setHealth(Math.min(attrValue(killer, Attribute.MAX_HEALTH),
                        killer.getHealth() + def.fl("restore-health")));
            }
            if ((def = defOf("drain")) != null && (l = w.getOrDefault("drain", 0)) > 0) {
                double pct = def.sc("xp-percent-per-level", l) / 100.0;
                int exp = e.getDroppedExp();
                if (exp > 0) {
                    e.setDroppedExp((int) Math.round(exp * (1.0 + pct)));
                } else {
                    int bonus = (int) Math.round(def.fl("xp-base-per-monster") * pct);
                    if (bonus > 0) {
                        spawnOrbs(dead.getLocation(), bonus);
                    }
                }
            }
            if ((def = defOf("rift")) != null && (l = w.getOrDefault("rift", 0)) > 0
                    && roll(def.sc("kill-drop-chance-percent-per-level", l)) && !e.getDrops().isEmpty()) {
                ItemStack sample = e.getDrops().get(ThreadLocalRandom.current().nextInt(e.getDrops().size()));
                ItemStack extra = sample.clone();
                extra.setAmount(Math.min(sample.getMaxStackSize(), Math.max(1, sample.getAmount())));
                e.getDrops().add(extra);
            }
            if (defOf("bloodlust") != null && w.getOrDefault("bloodlust", 0) > 0) {
                int stacks = bloodlustStacks.getOrDefault(killer.getUniqueId(), 0) + 1;
                bloodlustStacks.put(killer.getUniqueId(), Math.min(stacks, (int) defOf("bloodlust").fl("max-stacks")));
            }
            if (defOf("chain") != null && w.getOrDefault("chain", 0) > 0) {
                lastKillMs.put(killer.getUniqueId(), now);
            }
            lastCombatMs.put(killer.getUniqueId(), now);
        }

        private void dropLootBook(EntityDeathEvent e, LivingEntity dead) {
            CfgData d = data();
            if (!d.lootEnabled || d.lootPools.isEmpty() || !d.isRiftWorld(dead.getWorld())) return;
            if (!roll(d.lootRollChance)) return;
            String type = dead.getType().name();
            int totalWeight = 0;
            List<LootPoolEntry> eligible = new ArrayList<>();
            for (LootPoolEntry p : d.lootPools) {
                Def def = reg().get(p.enchant);
                if (def == null || !def.valid || !def.enabled || !def.allowsSource("loot")) continue;
                if (!p.mobs.isEmpty() && !p.mobs.contains(type)) continue;
                eligible.add(p);
                totalWeight += p.weight;
            }
            if (eligible.isEmpty()) return;
            int pick = ThreadLocalRandom.current().nextInt(totalWeight);
            for (LootPoolEntry p : eligible) {
                pick -= p.weight;
                if (pick < 0) {
                    int lvPick = p.minLevel + ThreadLocalRandom.current().nextInt(p.maxLevel - p.minLevel + 1);
                    ItemStack book = plugin.lore().buildBook(p.enchant, lvPick);
                    if (book != null) {
                        e.getDrops().add(book);
                    }
                    return;
                }
            }
        }

        private void spawnOrbs(Location loc, int exp) {
            if (exp <= 0) return;
            loc.getWorld().spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(exp));
        }

        // ---------------- 耐久：韧甲/不灭/淬火
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onItemDamage(PlayerItemDamageEvent e) {
            if (!plugin.anyEnabled()) return;
            Map<String, Integer> ench = st().read(e.getItem());
            if (ench.isEmpty()) return;
            Def def;
            int l;
            if (lv("endless", ench) > 0) {
                e.setCancelled(true);
                return;
            }
            if ((def = defOf("quench")) != null && (l = ench.getOrDefault("quench", 0)) > 0
                    && isArmorPiece(e.getItem().getType()) && e.getPlayer().getFireTicks() > 0) {
                e.setCancelled(true); // 燃烧期间该装备免疫损耗
                return;
            }
            if ((def = defOf("tough")) != null && (l = ench.getOrDefault("tough", 0)) > 0) {
                double keep = 1.0 - def.sc("wear-reduce-percent-per-level", l) / 100.0;
                int nd = (int) Math.max(0, Math.round(e.getDamage() * keep));
                if (nd <= 0) {
                    e.setCancelled(true);
                } else if (nd != e.getDamage()) {
                    e.setDamage(nd);
                }
            }
        }

        private boolean isArmorPiece(Material m) {
            String n = m.name();
            return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
        }

        /** 不可破坏/不可连锁目标黑名单（深掘与连锁复用）。 */
        static final Set<Material> NON_BREAKABLE = Set.of(
                Material.BEDROCK, Material.BARRIER, Material.LIGHT, Material.WATER,
                Material.LAVA, Material.END_PORTAL, Material.END_GATEWAY, Material.NETHER_PORTAL,
                Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
                Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID, Material.JIGSAW);

        private boolean deepDigTarget(Material m) {
            return !m.isAir() && m.isSolid() && !NON_BREAKABLE.contains(m);
        }

        /** 淬火：点燃时长按比例缩短。 */
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onCombust(EntityCombustEvent e) {
            if (!plugin.anyEnabled()) return;
            if (!(e.getEntity() instanceof Player vp)) return;
            Def def = defOf("quench");
            if (def == null) return;
            int l = armorMap(vp).getOrDefault("quench", 0);
            if (l <= 0) return;
            double keep = 1.0 - def.fl("burn-time-reduce-percent") / 100.0;
            e.setDuration(Math.max(0f, (float) (e.getDuration() * keep)));
        }

        /** 潜影：渊域黑夜潜行时怪物不优先锁定。 */
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onTarget(EntityTargetEvent e) {
            if (!plugin.anyEnabled()) return;
            if (!(e.getTarget() instanceof Player p) || !(e.getEntity() instanceof Mob mob)) return;
            if (!isShadeActive(p)) return;
            Def def = defOf("shade");
            if (def == null) return;
            double r = def.fl("hide-radius-blocks");
            if (mob.getLocation().distanceSquared(p.getLocation()) <= r * r) {
                e.setCancelled(true);
            }
        }

        // ---------------- 弓弩：发射（追星/裂矢/速射/寻星）
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onShoot(EntityShootBowEvent e) {
            if (!plugin.anyEnabled()) return;
            if (!(e.getEntity() instanceof Player p)) return;
            ItemStack bow = p.getInventory().getItem(e.getHand() == null
                    ? org.bukkit.inventory.EquipmentSlot.HAND : e.getHand());
            Map<String, Integer> w = st().read(bow);
            if (w.isEmpty()) return;
            Entity projE = e.getProjectile();
            if (!(projE instanceof AbstractArrow arrow)) return;
            CfgData d = data();
            Def def;
            int l;
            double origDamage = arrow.getDamage();

            // 速射：拉弓未满但达到阈值时按满弓结算（提升箭矢伤害到满弓值）
            if ((def = defOf("quick_draw")) != null && (l = w.getOrDefault("quick_draw", 0)) > 0) {
                float force = e.getForce();
                double threshold = 1.0 - def.sc("threshold-reduce-percent-per-level", l) / 100.0;
                if (force > 0.05f && force < 0.999f && force >= threshold) {
                    arrow.setDamage(origDamage / force);
                    origDamage = arrow.getDamage();
                }
            }
            // 追星：箭速与伤害
            if ((def = defOf("star_chase")) != null && (l = w.getOrDefault("star_chase", 0)) > 0) {
                arrow.setVelocity(arrow.getVelocity().multiply(1.0 + def.sc("speed-percent-per-level", l) / 100.0));
                arrow.setDamage(origDamage * (1.0 + def.sc("damage-percent-per-level", l) / 100.0));
            }
            // 快照：命中阶段回放（固化为发射瞬间的附魔状态）
            st().tagArrow(arrow, p.getUniqueId(), w, arrow.getDamage(),
                    defOf("pierce_shadow") != null && w.getOrDefault("pierce_shadow", 0) > 0
                            ? (int) defOf("pierce_shadow").fl("pierce-count") : 0, 0);

            // 裂矢：额外箭矢（带散布，子箭不再分裂）
            if ((def = defOf("split_arrow")) != null && (l = w.getOrDefault("split_arrow", 0)) > 0) {
                int extra = (int) Math.round(def.sc("extra-arrows-per-level", l));
                double spread = def.fl("spread-degrees");
                Vector base = arrow.getVelocity();
                for (int i = 0; i < extra; i++) {
                    Vector v = rotate(base, Math.toRadians(spread) * ThreadLocalRandom.current().nextDouble(-1, 1),
                            Math.toRadians(spread) * ThreadLocalRandom.current().nextDouble(-1, 1));
                    Location spawnLoc = p.getEyeLocation().add(base.clone().normalize().multiply(0.8));
                    Arrow child = spawnLoc.getWorld().spawn(spawnLoc, Arrow.class, a -> {
                        a.setShooter(p);
                        a.setVelocity(v);
                        a.setDamage(arrow.getDamage());
                        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    });
                    st().tagArrow(child, p.getUniqueId(), w, child.getDamage(),
                            defOf("pierce_shadow") != null && w.getOrDefault("pierce_shadow", 0) > 0
                                    ? (int) defOf("pierce_shadow").fl("pierce-count") : 0, 1);
                }
            }
            // 寻星：定时刻追踪最近生物（有最长时限，仅箭矢存活期间）
            if ((def = defOf("homing")) != null && (l = w.getOrDefault("homing", 0)) > 0) {
                startHoming(arrow, p, (int) Math.round(def.sc("radius-blocks-per-level", l)),
                        (int) def.fl("max-ticks"), def.fl("turn-factor"));
            }
        }

        /** 绕“右向量/上向量”做小幅旋转，得到散布方向（不修改输入向量）。 */
        private Vector rotate(Vector v, double yawRad, double pitchRad) {
            Vector dir = v.clone().normalize();
            Vector worldUp = new Vector(0, 1, 0);
            Vector right = dir.clone().crossProduct(worldUp);
            if (right.lengthSquared() < 1.0E-6) {
                right = new Vector(1, 0, 0);
            } else {
                right.normalize();
            }
            Vector up = right.clone().crossProduct(dir).normalize();
            Vector out = dir.clone()
                    .add(right.clone().multiply(Math.sin(yawRad)))
                    .add(up.clone().multiply(Math.sin(pitchRad)));
            return out.normalize().multiply(v.length());
        }

        private void startHoming(AbstractArrow arrow, Player shooter, int radius, int maxTicks, double turnFactor) {
            UUID arrowId = arrow.getUniqueId();
            BukkitTask old = arrowTasks.get(arrowId);
            if (old != null) old.cancel();
            int period = Math.max(1, data().homingScanTicks);
            int steps = Math.max(1, maxTicks / period);
            final int[] state = {0};
            Location start = arrow.getLocation();
            BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                state[0]++;
                if (state[0] > steps || !arrow.isValid() || arrow.isDead()
                        || arrow.getLocation().distanceSquared(start) > 62500.0) {
                    BukkitTask mine = arrowTasks.remove(arrowId);
                    if (mine != null) mine.cancel();
                    return;
                }
                LivingEntity best = null;
                double bestD = Double.MAX_VALUE;
                for (Entity en : arrow.getLocation().getWorld().getNearbyEntities(
                        arrow.getLocation(), radius, radius, radius)) {
                    if (!(en instanceof LivingEntity le) || le == shooter || le instanceof Player
                            || !le.isValid() || le.isDead()) continue;
                    double dist = le.getLocation().distanceSquared(arrow.getLocation());
                    if (dist < bestD) {
                        bestD = dist;
                        best = le;
                    }
                }
                if (best == null) return;
                Vector vel = arrow.getVelocity();
                double speed = Math.max(0.4, vel.length());
                Vector want = best.getEyeLocation().subtract(arrow.getLocation()).toVector().normalize();
                Vector blended = vel.clone().normalize().multiply(1.0 - turnFactor).add(want.multiply(turnFactor)).normalize();
                arrow.setVelocity(blended.multiply(speed));
            }, period, period);
            arrowTasks.put(arrowId, t);
        }

        // ---------------- 弓弩：命中（裂空/爆镞/霜缚/贯影）
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onProjectileHit(ProjectileHitEvent e) {
            if (!plugin.anyEnabled()) return;
            Projectile proj = e.getEntity();
            String[] snap = st().arrowSnapshot(proj);
            if (snap == null) return;
            UUID owner;
            try {
                owner = UUID.fromString(snap[0]);
            } catch (IllegalArgumentException ex) {
                return;
            }
            if (!(proj instanceof AbstractArrow arrow)) return;
            BukkitTask t = arrowTasks.remove(proj.getUniqueId());
            if (t != null) t.cancel();
            Map<String, Integer> w = st().parse(snap[1]);
            if (w.isEmpty()) return;
            Entity hitE = e.getHitEntity();
            LivingEntity hit = hitE instanceof LivingEntity le ? le : null;
            double arrowDamage = arrow.getDamage();
            Def def;
            int l;

            // 贯影：穿透后继续飞行（重新实体化一支衰减箭）
            int pierceLeft = parseIntSafe(snap[3], 0);
            if (hit != null && !(hit instanceof Player) && hit != Bukkit.getEntity(owner)
                    && pierceLeft > 0 && (def = defOf("pierce_shadow")) != null
                    && (l = w.getOrDefault("pierce_shadow", 0)) > 0) {
                Vector vel = arrow.getVelocity().multiply(0.85);
                Location loc = arrow.getLocation().add(vel.clone().normalize().multiply(0.4));
                Entity ownerEntity = Bukkit.getEntity(owner);
                Player ownerPlayer = (ownerEntity instanceof Player op && op.isOnline()) ? op : null;
                Arrow child = loc.getWorld().spawn(loc, Arrow.class, a -> {
                    if (ownerPlayer != null) {
                        a.setShooter(ownerPlayer);
                    }
                    a.setVelocity(vel);
                    a.setDamage(arrowDamage);
                    a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                });
                st().tagArrow(child, owner, w, arrowDamage, pierceLeft - 1, parseIntSafe(snap[4], 0));
                arrow.remove();
            }

            Location center = hit != null ? hit.getLocation() : (e.getHitBlock() != null
                    ? e.getHitBlock().getLocation().add(0.5, 0.5, 0.5) : proj.getLocation());

            // 裂空：命中生物附近溅射
            if (hit != null && (def = defOf("void_split")) != null && (l = w.getOrDefault("void_split", 0)) > 0) {
                double r = def.fl("radius-blocks");
                double amt = arrowDamage * def.sc("aoe-damage-percent-per-level", l) / 100.0;
                aoeDamage(center, r, amt, hit, owner);
            }
            // 爆镞：命中即小爆炸（不破坏方块）
            if ((def = defOf("blast_tip")) != null && (l = w.getOrDefault("blast_tip", 0)) > 0) {
                double r = def.fl("radius-blocks");
                float power = (float) def.fl("explosion-power");
                center.getWorld().createExplosion(center.getX(), center.getY(), center.getZ(), power, false, false);
                double amt = arrowDamage * def.sc("damage-percent-per-level", l) / 100.0;
                aoeDamage(center, r, amt, null, owner);
            }
            // 霜缚：命中生物减速
            if (hit != null && !(hit instanceof Player)
                    && (def = defOf("frost_bind")) != null && (l = w.getOrDefault("frost_bind", 0)) > 0) {
                hit.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                        (int) Math.round(def.sc("slow-seconds-per-level", l) * 20),
                        (int) def.fl("slow-amplifier"), true, true, true));
            }
        }

        private void aoeDamage(Location center, double radius, double amount, LivingEntity exclude, UUID owner) {
            if (amount <= 0 || radius <= 0) return;
            for (Entity en : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (!(en instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
                if (le instanceof Player) continue;
                if (le == exclude || le.getUniqueId().equals(owner)) continue;
                le.damage(amount);
            }
        }

        private int parseIntSafe(String s, int fallback) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception ex) {
                return fallback;
            }
        }

        // ================================================================ 挖掘体系

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onBlockBreak(BlockBreakEvent e) {
            if (!plugin.anyEnabled()) return;
            Player p = e.getPlayer();
            ItemStack tool = p.getInventory().getItemInMainHand();
            Map<String, Integer> w = st().read(tool);
            if (w.isEmpty()) return; // 零成本早退：手持物品没有自定义附魔
            CfgData d = data();
            Block b = e.getBlock();
            Material bt = b.getType();
            boolean isOre = oreUnion.contains(bt);
            Def def;
            int l;
            long now = System.currentTimeMillis();

            // 碎片亲和：只记录倍率接口，实际掉落由服主系统读取
            if ((def = defOf("shard_affinity")) != null && (l = w.getOrDefault("shard_affinity", 0)) > 0) {
                shardAffinity.put(p.getUniqueId(), new double[]{
                        def.sc("mult-per-level", l), now + d.shardWindowSeconds * 1000L});
            }
            // 速修：概率耗经验修耐久
            if ((def = defOf("swift_repair")) != null && (l = w.getOrDefault("swift_repair", 0)) > 0
                    && roll(def.sc("chance-percent-per-level", l))) {
                int cost = (int) Math.max(0, def.fl("xp-cost-levels"));
                if (p.getLevel() >= cost) {
                    p.giveExpLevels(-cost);
                    // 同上：耐久读写字段按显式组件走，先验证材质有耐久，避免无组件时抛异常
                    if (tool.getItemMeta() instanceof Damageable dm
                            && !dm.isUnbreakable() && tool.getType().getMaxDurability() > 0) {
                        int cur0 = dm.hasDamage() ? dm.getDamage() : 0;
                        int nd = Math.max(0, cur0 - (int) def.fl("repair-amount"));
                        if (nd != cur0 || dm.hasDamage()) {
                            dm.setDamage(nd);
                            tool.setItemMeta(dm);
                            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.4f, 1.8f);
                        }
                    }
                }
            }
            // 勘探：分帧粒子显示矿石轮廓
            if ((def = defOf("prospector")) != null && (l = w.getOrDefault("prospector", 0)) > 0) {
                startProspector(p, b.getLocation(), (int) Math.round(def.sc("radius-blocks-per-level", l)),
                        (int) Math.round(def.fl("show-seconds") * 20));
            }
            // 连锁：蔓延（潜行）/ 伐木（原木）/ 深掘（非潜行向下）
            if ((def = defOf("spread")) != null && (l = w.getOrDefault("spread", 0)) > 0 && p.isSneaking()) {
                int cap = (int) Math.round(def.sc("max-blocks-per-level", l));
                plugin.chains().enqueue(p, tool.clone(), w, ChainMining.KIND_SPREAD, bt,
                        neighbors(b, 6, cap), cap);
            } else if ((def = defOf("timber")) != null && (l = w.getOrDefault("timber", 0)) > 0
                    && Tag.LOGS.isTagged(bt)) {
                int cap = (int) Math.round(def.sc("max-blocks-per-level", l));
                plugin.chains().enqueue(p, tool.clone(), w, ChainMining.KIND_TIMBER, bt,
                        neighbors(b, 26, cap), cap);
            } else if ((def = defOf("deep_dig")) != null && (l = w.getOrDefault("deep_dig", 0)) > 0
                    && !p.isSneaking() && b.getY() > b.getWorld().getMinHeight()) {
                int depth = (int) Math.round(def.sc("depth-per-level", l));
                List<Location> down = new ArrayList<>(depth);
                for (int i = 1; i <= depth; i++) {
                    Block below = b.getWorld().getBlockAt(b.getX(), b.getY() - i, b.getZ());
                    if (!deepDigTarget(below.getType())) break;
                    down.add(below.getLocation());
                }
                if (!down.isEmpty()) {
                    plugin.chains().enqueue(p, tool.clone(), w, ChainMining.KIND_DEEP, bt, down, depth);
                }
            }

            // 需要接管掉落的附魔：熔炼（有映射）/ 富矿 / 精收 / 磁引
            boolean manual = (lv("magnet", w) > 0)
                    || (isOre && (lv("smelt", w) > 0 && d.smeltMap.containsKey(bt) || lv("rich_vein", w) > 0))
                    || (!isOre && lv("fine_gather", w) > 0);
            if (manual) {
                e.setCancelled(true);
                manualBreakCore(p, b, tool, w, false);
            }
        }

        private List<Location> neighbors(Block b, int reach, int cap) {
            List<Location> out = new ArrayList<>();
            int r = reach == 6 ? 1 : 1;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        if (reach == 6 && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                        Block n = b.getWorld().getBlockAt(b.getX() + dx, b.getY() + dy, b.getZ() + dz);
                        if (!n.getType().isAir()) {
                            out.add(n.getLocation());
                            if (out.size() >= cap * 6L) return out; // 候选适度冗余即可
                        }
                    }
                }
            }
            return out;
        }

        /**
         * 自管挖掘：取消原版掉落，接管 smelt/rich_vein/fine_gather/magnet，并补偿音效与耐久。
         * 连锁挖掘也走这里（allowAux=false：速修/勘探只随玩家直接挖掘触发）。
         */
        void manualBreakCore(Player p, Block block, ItemStack tool, Map<String, Integer> ench, boolean allowAux) {
            CfgData d = data();
            Material bt = block.getType();
            if (bt.isAir()) return;
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            List<ItemStack> drops = new ArrayList<>(block.getDrops(tool, p));
            boolean isOre = oreUnion.contains(bt);
            Def def;
            int l;

            // 熔炼替换
            if ((def = defOf("smelt")) != null && (l = ench.getOrDefault("smelt", 0)) > 0 && d.smeltMap.containsKey(bt)) {
                Material to = d.smeltMap.get(bt);
                int totalCount = 0;
                List<ItemStack> smelted = new ArrayList<>(drops.size());
                for (ItemStack it : drops) {
                    ItemStack nu = new ItemStack(to, it.getAmount());
                    smelted.add(nu);
                    totalCount += it.getAmount();
                }
                drops = smelted;
                int exp = (int) Math.round(def.fl("xp-base") * def.fl("xp-multiplier") * Math.max(1, totalCount));
                if (exp > 0) {
                    spawnOrbs(center, exp);
                }
            }
            // 富矿：概率额外 1 个
            if ((def = defOf("rich_vein")) != null && (l = ench.getOrDefault("rich_vein", 0)) > 0
                    && isOre && !drops.isEmpty() && roll(def.sc("chance-percent-per-level", l))) {
                ItemStack first = drops.get(0);
                ItemStack extra = new ItemStack(first.getType(), Math.min(first.getMaxStackSize(), Math.max(1, first.getAmount())));
                drops.add(extra);
            }
            // 精收：非矿石概率双倍
            if ((def = defOf("fine_gather")) != null && (l = ench.getOrDefault("fine_gather", 0)) > 0
                    && !isOre && roll(def.sc("chance-percent-per-level", l))) {
                List<ItemStack> doubled = new ArrayList<>(drops.size() * 2);
                for (ItemStack it : drops) {
                    doubled.add(it);
                    doubled.add(it.clone());
                }
                drops = doubled;
            }

            block.setType(Material.AIR, false);
            center.getWorld().playEffect(center, Effect.STEP_SOUND, bt);

            if (lv("magnet", ench) > 0) {
                for (ItemStack it : drops) {
                    var leftover = p.getInventory().addItem(it);
                    for (ItemStack rest : leftover.values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), rest);
                    }
                }
            } else {
                for (ItemStack it : drops) {
                    center.getWorld().dropItemNaturally(center, it);
                }
            }
            wearTool(p, tool, ench);
        }

        /** 自管挖掘流程的耐久补偿（尊重 不灭/淬火烧毁期/韧甲）。 */
        private void wearTool(Player p, ItemStack tool, Map<String, Integer> ench) {
            if (lv("endless", ench) > 0) return;
            if (lv("quench", ench) > 0 && p.getFireTicks() > 0) return;
            CfgData d = data();
            int per = d.toolDamagePerBreak;
            Def def;
            int l;
            if ((def = defOf("tough")) != null && (l = ench.getOrDefault("tough", 0)) > 0) {
                per = (int) Math.max(0, Math.round(per * (1.0 - def.sc("wear-reduce-percent-per-level", l) / 100.0)));
            }
            if (per <= 0) return;
            ItemStack cur = p.getInventory().getItemInMainHand();
            if (cur.getType() != tool.getType() || !(cur.getItemMeta() instanceof Damageable dm)) return;
            if (dm.isUnbreakable()) return;
            // 1.20.5+ 的 Bukkit meta 视图只反映显式组件：合成/复制出的工具常无 max_damage 组件，
            // 直接 getMaxDamage() 会抛 IllegalStateException（实机挖铁矿时复现）。上限一律取材质基础值。
            int maxD = cur.getType().getMaxDurability();
            if (maxD <= 0) return;
            int nd = (dm.hasDamage() ? dm.getDamage() : 0) + per;
            if (maxD > 0 && nd >= maxD) {
                p.getInventory().setItemInMainHand(null);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.8f, 1.0f);
            } else {
                dm.setDamage(nd);
                cur.setItemMeta(dm);
                p.getInventory().setItemInMainHand(cur);
            }
        }

        /** 勘探：分帧按 y 层扫描（每刻一层），仅向触发玩家显示粒子轮廓。 */
        private void startProspector(Player p, Location center, int radius, int totalTicks) {
            if (radius <= 0 || totalTicks <= 0) return;
            World world = center.getWorld();
            final int r = Math.min(radius, 32);
            final int[] state = {0, 0}; // 层进度, 已用刻数
            final BukkitTask[] holder = new BukkitTask[1];
            holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                state[1]++;
                if (!p.isOnline() || state[1] > totalTicks || state[0] > 2 * r || world == null) {
                    if (holder[0] != null) holder[0].cancel();
                    prospectorTasks.remove(p.getUniqueId());
                    return;
                }
                int dy = -r + state[0];
                int x0 = center.getBlockX(), y0 = center.getBlockY(), z0 = center.getBlockZ();
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        Block b = world.getBlockAt(x0 + dx, y0 + dy, z0 + dz);
                        if (oreUnion.contains(b.getType())) {
                            p.spawnParticle(Particle.CRIT, b.getLocation().add(0.5, 0.5, 0.5), 1);
                        }
                    }
                }
                state[0]++;
            }, 0L, 1L);
            BukkitTask old = prospectorTasks.put(p.getUniqueId(), holder[0]);
            if (old != null) old.cancel();
        }

        private final Map<UUID, BukkitTask> prospectorTasks = new ConcurrentHashMap<>();

        // ---------------- 魂契 / 上下线 / 状态清理
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onPlayerDeath(PlayerDeathEvent e) {
            if (!plugin.anyEnabled()) return;
            Player p = e.getPlayer();
            List<ItemStack> keep = new ArrayList<>();
            Iterator<ItemStack> it = e.getDrops().iterator();
            while (it.hasNext()) {
                ItemStack drop = it.next();
                if (drop != null && lv("soulbound", st().read(drop)) > 0) {
                    keep.add(drop.clone());
                    it.remove();
                }
            }
            if (!keep.isEmpty()) {
                soulKeep.put(p.getUniqueId(), keep);
            }
            // 死亡即清零战斗状态
            bloodlustStacks.remove(p.getUniqueId());
            lastKillMs.remove(p.getUniqueId());
            lastCombatMs.remove(p.getUniqueId());
            shieldBanUntilTick.remove(p.getUniqueId());
            bashUntilTick.remove(p.getUniqueId());
            reviveCdUntilMs.remove(p.getUniqueId());
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onRespawn(PlayerRespawnEvent e) {
            List<ItemStack> keep = soulKeep.remove(e.getPlayer().getUniqueId());
            if (keep == null || keep.isEmpty()) return;
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (ItemStack it : keep) {
                    var leftover = p.getInventory().addItem(it);
                    for (ItemStack rest : leftover.values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), rest);
                    }
                }
                attributesSync(p);
            });
        }

        private void attributesSync(Player p) {
            plugin.attributes().scheduleSync(p);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent e) {
            attributesSync(e.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            snapCache.remove(id);
            bloodlustStacks.remove(id);
            lastKillMs.remove(id);
            lastCombatMs.remove(id);
            shieldBanUntilTick.remove(id);
            bashUntilTick.remove(id);
            reviveCdUntilMs.remove(id);
            shardAffinity.remove(id);
            BukkitTask pt = prospectorTasks.remove(id);
            if (pt != null) pt.cancel();
            // 魂契暂存物品若因下线未归还：放回背包，放不下降生在最后位置
            List<ItemStack> keep = soulKeep.remove(id);
            if (keep != null && !keep.isEmpty()) {
                Player p = e.getPlayer();
                for (ItemStack it : keep) {
                    var leftover = p.getInventory().addItem(it);
                    for (ItemStack rest : leftover.values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), rest);
                    }
                }
            }
        }

        /** 插件停用/重载：清掉一切内存态与任务。 */
        void clearAllState() {
            for (BukkitTask t : arrowTasks.values()) t.cancel();
            for (BukkitTask t : prospectorTasks.values()) t.cancel();
            arrowTasks.clear();
            prospectorTasks.clear();
            snapCache.clear();
            bloodlustStacks.clear();
            lastKillMs.clear();
            lastCombatMs.clear();
            shieldBanUntilTick.clear();
            bashUntilTick.clear();
            reviveCdUntilMs.clear();
            shardAffinity.clear();
            pendingKnockBoost.clear();
            soulKeep.clear();
        }

        // ================================================================ 合成 PDC 传递
        /** 可选模块：合成结果与输入格同材料时传递自定义附魔（默认关闭）。 */
        static final class CraftTransfer implements Listener {
            private final StarEnchant plugin;

            CraftTransfer(StarEnchant plugin) {
                this.plugin = plugin;
            }

            @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
            public void onCraft(CraftItemEvent e) {
                if (!plugin.data().craftTransferEnabled || !plugin.anyEnabled()) return;
                org.bukkit.inventory.CraftingInventory inv = e.getInventory();
                ItemStack result = inv.getResult();
                if (result == null) return;
                Map<String, Integer> merged = EnchantStorage.EMPTY;
                for (ItemStack in : inv.getMatrix()) {
                    if (in == null) continue;
                    if (!plugin.data().craftTransferMaterials.contains(in.getType())) continue;
                    Map<String, Integer> m = plugin.storage().read(in);
                    if (!m.isEmpty()) {
                        merged = plugin.storage().mergeMax(merged, m);
                    }
                }
                if (merged.isEmpty()) return;
                ItemStack out = result.clone();
                plugin.storage().write(out, merged);
                inv.setResult(out);
            }
        }

        // ================================================================ 连锁挖掘调度
        /**
         * spread/timber/deep_dig 共用分帧队列：每刻最多处理 chain-per-tick 格，
         * 距离/在线/方块类型校验失败即丢弃，防复制防卡服。
         */
        static final class ChainMining {
            static final int KIND_SPREAD = 0;
            static final int KIND_TIMBER = 1;
            static final int KIND_DEEP = 2;

            private static final class Job {
                final UUID playerId;
                final ItemStack tool;
                final Map<String, Integer> ench;
                final int kind;
                final Material expect;
                final Deque<Location> queue = new ArrayDeque<>();
                final Set<Location> seen = new HashSet<>();
                int remaining;
                int touched;

                Job(UUID playerId, ItemStack tool, Map<String, Integer> ench, int kind, Material expect, int cap) {
                    this.playerId = playerId;
                    this.tool = tool;
                    this.ench = ench;
                    this.kind = kind;
                    this.expect = expect;
                    this.remaining = cap;
                }
            }

            private final StarEnchant plugin;
            private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
            private BukkitTask task;

            ChainMining(StarEnchant plugin) {
                this.plugin = plugin;
            }

            void enqueue(Player p, ItemStack tool, Map<String, Integer> ench,
                         int kind, Material expect, List<Location> seeds, int cap) {
                if (seeds.isEmpty() || cap <= 0) return;
                CfgData d = plugin.data();
                Job job = jobs.get(p.getUniqueId());
                if (job == null) {
                    job = new Job(p.getUniqueId(), tool, ench, kind, expect, cap);
                    jobs.put(p.getUniqueId(), job);
                }
                for (Location loc : seeds) {
                    if (job.remaining <= 0) break;
                    if (job.seen.add(loc)) {
                        job.queue.addLast(loc);
                    }
                }
                // 队列总量硬上限
                int overflow = job.queue.size() - d.chainQueueLimit;
                for (int i = 0; i < overflow && !job.queue.isEmpty(); i++) {
                    job.queue.pollLast();
                }
                ensureTask();
            }

            private void ensureTask() {
                if (task != null) return;
                task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
            }

            private void tick() {
                int budget = plugin.data().chainPerTick;
                Iterator<Map.Entry<UUID, Job>> it = jobs.entrySet().iterator();
                while (it.hasNext() && budget > 0) {
                    Map.Entry<UUID, Job> en = it.next();
                    Job job = en.getValue();
                    Player p = Bukkit.getPlayer(job.playerId);
                    if (p == null || !p.isOnline() || !p.isValid()) {
                        it.remove();
                        continue;
                    }
                    boolean done = processJob(p, job, budget);
                    budget -= job.touched;
                    job.touched = 0;
                    if (done) it.remove();
                }
                if (jobs.isEmpty() && task != null) {
                    BukkitTask t = task;
                    task = null;
                    t.cancel();
                }
            }

            private boolean processJob(Player p, Job job, int budget) {
                int used = 0;
                while (used < budget && job.remaining > 0 && !job.queue.isEmpty()) {
                    Location loc = job.queue.pollFirst();
                    Block b = loc.getBlock();
                    if (!matches(p, job, b)) {
                        continue;
                    }
                    job.touched++;
                    used++;
                    job.remaining--;
                    plugin.breakForChain(p, b, job.tool, job.ench);
                    expand(p, job, b);
                }
                return job.remaining <= 0 || job.queue.isEmpty();
            }

            private boolean matches(Player p, Job job, Block b) {
                if (b.getType().isAir() || EffectListener.NON_BREAKABLE.contains(b.getType())) return false;
                if (!b.getWorld().getUID().equals(p.getWorld().getUID())) return false;
                if (b.getLocation().distanceSquared(p.getLocation()) > 48.0 * 48.0) return false;
                Material bt = b.getType();
                return switch (job.kind) {
                    case KIND_SPREAD -> bt == job.expect;
                    case KIND_TIMBER -> isTimberBlock(bt);
                    default -> true; // DEEP：任意可挖方块
                };
            }

            private boolean isTimberBlock(Material bt) {
                if (Tag.LOGS.isTagged(bt)) return true;
                return plugin.data().timberIncludeLeaves && Tag.LEAVES.isTagged(bt);
            }

            private void expand(Player p, Job job, Block broken) {
                CfgData d = plugin.data();
                if (job.remaining <= 0) return;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            if (job.kind == KIND_SPREAD && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                            if (job.kind == KIND_DEEP && (dx != 0 || dz != 0)) continue;
                            Block n = broken.getWorld().getBlockAt(broken.getX() + dx, broken.getY() + dy, broken.getZ() + dz);
                            Location nl = n.getLocation();
                            if (job.seen.size() >= d.chainQueueLimit) return;
                            if (job.seen.add(nl) && !n.getType().isAir()) {
                                job.queue.addLast(nl);
                            }
                        }
                    }
                }
            }

            void shutdown() {
                if (task != null) {
                    task.cancel();
                    task = null;
                }
                jobs.clear();
            }

        }
    }

    // ================================================================ 锻造台监听
    /**
     * 自定义配方匹配（模板槽物品 + 装备槽 + 材料槽数量），命中后把结果替换为
     * 「保留 PDC 并升级指定自定义附魔」的产物；取出成品时手动结算消耗
     * （材料按配置数量扣除、模板不消耗可复用）。零数据包零资源包。
     */
    static final class SmithingListener implements Listener {
        private final StarEnchant plugin;

        SmithingListener(StarEnchant plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onPrepare(PrepareSmithingEvent e) {
            if (!plugin.data().smithingEnabled || !plugin.anyEnabled()) return;
            SmithingInventory inv = e.getInventory();
            ItemStack tpl = inv.getInputTemplate();
            ItemStack equip = inv.getInputEquipment();
            ItemStack mineral = inv.getInputMineral();
            if (equip == null || tpl == null) return;
            int idx = matchRecipe(tpl, equip, mineral);
            if (idx < 0) return;
            SmithingRecipeData recipe = plugin.data().smithingRecipes.get(idx);
            Def def = plugin.registry().get(recipe.addEnchant);
            if (def == null || !def.valid) return;

            ItemStack base = e.getResult() != null ? e.getResult().clone() : equip.clone();
            // 原版产物（如冶炼装备）不带 PDC：回填装备已有的自定义附魔
            Map<String, Integer> eqMap = plugin.storage().read(equip);
            if (e.getResult() != null && !eqMap.isEmpty()) {
                plugin.storage().write(base, eqMap);
            }
            boolean applied = false;
            if (def.enabled) {
                applied = plugin.storage().addLevels(base, def, recipe.addLevels) > 0;
            }
            plugin.storage().markRecipeHit(base, idx);
            e.setResult(base);
            plugin.debug("锻造预览命中配方[" + idx + "]" + recipe.label + (applied ? " 已升级 " + def.id : "（已满级/未启用）"));
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onTake(InventoryClickEvent e) {
            if (!plugin.data().smithingEnabled) return;
            Inventory top = e.getView().getTopInventory();
            if (top.getType() != InventoryType.SMITHING) return;
            int resultSlot = top.getSize() - 1;
            if (e.getRawSlot() != resultSlot) return;
            ItemStack res = top.getItem(resultSlot);
            if (res == null) return;
            int idx = plugin.storage().recipeHitIndex(res);
            if (idx < 0 || idx >= plugin.data().smithingRecipes.size()) return;
            SmithingRecipeData recipe = plugin.data().smithingRecipes.get(idx);
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;

            // 手动结算：成品入包、材料扣除、装备位清空、模板保留复用
            ItemStack give = res.clone();
            plugin.storage().clearRecipeHit(give);
            var leftover = p.getInventory().addItem(give);
            for (ItemStack rest : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            ItemStack mineral = top.getItem(2);
            if (mineral != null) {
                int remain = mineral.getAmount() - recipe.mineralAmount;
                if (remain > 0) {
                    mineral.setAmount(remain);
                    top.setItem(2, mineral);
                } else {
                    top.setItem(2, null);
                }
            }
            top.setItem(1, null);
            top.setItem(resultSlot, null);
            plugin.debug("锻造取出结算完成：" + recipe.label);
        }

        private int matchRecipe(ItemStack tpl, ItemStack equip, ItemStack mineral) {
            List<SmithingRecipeData> recipes = plugin.data().smithingRecipes;
            for (int i = 0; i < recipes.size(); i++) {
                SmithingRecipeData r = recipes.get(i);
                if (tpl.getType() != r.template) continue;
                if (!r.anyEquipment() && !r.equipment.contains(equip.getType())) continue;
                if (mineral == null || mineral.getType() != r.mineral) continue;
                if (mineral.getAmount() < r.mineralAmount) continue;
                return i;
            }
            return -1;
        }
    }

    // ================================================================ 铁砧监听
    /**
     * PDC 跨铁砧传递（不丢失自定义附魔）+ 附魔书应用 + 按 conflict-groups 解除互斥合并。
     * 原版在“冲突/超最大费用/附魔书无原版附魔”时 result 为 null，本监听接管构造。
     */
    static final class AnvilListener implements Listener {
        private final StarEnchant plugin;

        AnvilListener(StarEnchant plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onPrepare(PrepareAnvilEvent e) {
            if (!plugin.data().anvilEnabled || !plugin.anyEnabled()) return;
            AnvilInventory inv = e.getInventory();
            ItemStack left = inv.getItem(0);
            ItemStack right = inv.getItem(1);
            if (left == null || right == null) {
                // 单件也可能需要 PDC 保留（原版重命名/修耐久产物不带 PDC）
                ItemStack single = left != null ? left : right;
                if (single != null && e.getResult() != null && plugin.storage().hasAny(single)) {
                    ItemStack base = e.getResult().clone();
                    plugin.storage().write(base, plugin.storage().read(single));
                    e.setResult(base);
                }
                return;
            }
            EnchantStorage st = plugin.storage();
            ItemStack res = e.getResult();
            boolean lBook = plugin.lore().looksLikeBook(left);
            boolean rBook = plugin.lore().looksLikeBook(right);

            if (lBook ^ rBook) {
                // ---- 情况一：附魔书 + 装备（自定义书原版必然判空，这里接管）
                ItemStack book = lBook ? left : right;
                ItemStack equip = lBook ? right : left;
                if (equip.getType().getMaxDurability() <= 0 && !isEnchantableMaterial(equip.getType())) {
                    return; // 不可附魔的物品
                }
                ItemStack base = res != null ? res.clone() : equip.clone();
                if (res != null) {
                    Map<String, Integer> carry = st.read(equip);
                    if (!carry.isEmpty()) st.write(base, carry);
                }
                boolean changed = st.applyBook(base, st.read(book));
                if (changed || res == null) {
                    inv.setRepairCostAmount(Math.max(1, plugin.data().anvilBookCostLevels));
                    e.setResult(base);
                }
                return;
            }

            // ---- 情况二：装备+装备 或 书+书：合并
            Map<String, Integer> lm = st.read(left);
            Map<String, Integer> rm = st.read(right);
            if (res != null) {
                Map<String, Integer> merged = st.mergeMax(lm, rm);
                if (!merged.isEmpty()) {
                    ItemStack base = res.clone();
                    st.write(base, merged);
                    e.setResult(base);
                }
                return;
            }
            // 原版拒绝（多为互斥冲突）→ 按配置的互斥解除规则接管合并
            if (!plugin.data().anvilMergeUnconflict) return;
            if (!left.hasItemMeta() || !right.hasItemMeta()) return;
            ItemMeta lm0 = left.getItemMeta();
            ItemMeta rm0 = right.getItemMeta();
            if (lm0 == null || rm0 == null) return;
            if (!isEnchantableMaterial(left.getType()) || !isEnchantableMaterial(right.getType())) return;
            ItemStack base = left.clone();
            ItemMeta bm = base.getItemMeta();
            if (bm == null) return;
            int added = 0;
            int cost = repairCost(left) + repairCost(right);
            for (Map.Entry<Enchantment, Integer> en : rm0.getEnchants().entrySet()) {
                Enchantment ench = en.getKey();
                int lv = en.getValue();
                int have = bm.hasEnchant(ench) ? bm.getEnchantLevel(ench) : 0;
                if (bm.hasEnchants() && !groupAllows(bm, ench)) {
                    continue; // 真正互斥且配置未解除：跳过（原版行为）
                }
                if (lv > have) {
                    bm.addEnchant(ench, lv, true); // unsafe：绕过等级/冲突校验（配置允许时）
                    added++;
                }
            }
            Map<String, Integer> merged = st.mergeMax(lm, rm);
            if (bm instanceof org.bukkit.inventory.meta.Repairable rp) {
                rp.setRepairCost(Math.max(1, Math.min(plugin.data().anvilMaxMergeCost, cost + added)));
            }
            base.setItemMeta(bm);
            st.write(base, merged);
            inv.setRepairCostAmount(Math.max(1, Math.min(plugin.data().anvilMaxMergeCost, cost + Math.max(1, added))));
            e.setResult(base);
        }

        /** 待加入 ench 是否与 bm 上现有附魔冲突；若与冲突方同属一个启用的互斥组则放行。 */
        private boolean groupAllows(ItemMeta bm, Enchantment ench) {
            CfgData d = plugin.data();
            if (!d.conflictEnabled) {
                return !bm.hasConflictingEnchant(ench);
            }
            for (Enchantment existing : bm.getEnchants().keySet()) {
                if (existing.conflictsWith(ench) && !inSameEnabledGroup(existing, ench)) {
                    return false;
                }
            }
            return true;
        }

        private boolean inSameEnabledGroup(Enchantment a, Enchantment b) {
            CfgData d = plugin.data();
            if (d.groupWeaponTrinity && inGroup(a, b, Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS)) return true;
            if (d.groupProtectionSuite && inGroup(a, b, Enchantment.PROTECTION, Enchantment.FIRE_PROTECTION,
                    Enchantment.BLAST_PROTECTION, Enchantment.PROJECTILE_PROTECTION)) return true;
            if (d.groupFortuneSilk && inGroup(a, b, Enchantment.FORTUNE, Enchantment.SILK_TOUCH)) return true;
            return false;
        }

        private boolean inGroup(Enchantment a, Enchantment b, Enchantment... members) {
            int hit = 0;
            for (Enchantment m : members) {
                if (m.equals(a) || m.equals(b)) hit++;
            }
            return hit == 2;
        }

        private int repairCost(ItemStack item) {
            if (item != null && item.getItemMeta() instanceof org.bukkit.inventory.meta.Repairable rp) {
                return rp.getRepairCost();
            }
            return 0;
        }

        private boolean isEnchantableMaterial(Material m) {
            String n = m.name();
            return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE")
                    || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_HELMET")
                    || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                    || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("SHIELD")
                    || n.equals("FISHING_ROD") || n.equals("SHEARS") || n.equals("MACE");
        }
    }

    // ================================================================ 附魔台升级（第一层）
    /** EnchantItemEvent：把原版附魔随机拉升到配置的等级上限，并可附加同组互斥附魔。 */
    static final class EnchantTableListener implements Listener {
        private final StarEnchant plugin;

        EnchantTableListener(StarEnchant plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onEnchant(EnchantItemEvent e) {
            if (!plugin.data().tableEnabled || !plugin.anyEnabled()) return;
            Map<Enchantment, Integer> m = e.getEnchantsToAdd();
            if (m.isEmpty()) return;
            CfgData d = plugin.data();
            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            for (Map.Entry<Enchantment, Integer> en : m.entrySet()) {
                Integer cap = d.levelCaps.get(en.getKey());
                int lv = en.getValue();
                if (cap != null && lv < cap && rnd.nextDouble(100.0) < d.tableUpgradeChance) {
                    int step = 1 + rnd.nextInt(d.tableUpgradeMaxStep);
                    en.setValue(Math.min(cap, lv + step));
                }
            }
            if (d.conflictEnabled && d.tableConflictExtraChance > 0
                    && rnd.nextDouble(100.0) < d.tableConflictExtraChance) {
                Enchantment extra = pickGroupExtra(m, d);
                if (extra != null && !m.containsKey(extra)) {
                    Integer cap = d.levelCaps.get(extra);
                    int max = cap != null ? cap : extra.getMaxLevel();
                    m.put(extra, 1 + rnd.nextInt(Math.max(1, Math.min(max, 3))));
                }
            }
        }

        private Enchantment pickGroupExtra(Map<Enchantment, Integer> m, CfgData d) {
            List<Enchantment> pool = new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> en : m.entrySet()) {
                Enchantment key = en.getKey();
                if (d.groupWeaponTrinity) {
                    collect(pool, key, Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS);
                }
                if (d.groupProtectionSuite) {
                    collect(pool, key, Enchantment.PROTECTION, Enchantment.FIRE_PROTECTION,
                            Enchantment.BLAST_PROTECTION, Enchantment.PROJECTILE_PROTECTION);
                }
                if (d.groupFortuneSilk) {
                    collect(pool, key, Enchantment.FORTUNE, Enchantment.SILK_TOUCH);
                }
            }
            if (pool.isEmpty()) return null;
            return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }

        private void collect(List<Enchantment> pool, Enchantment key, Enchantment... group) {
            for (Enchantment g : group) {
                if (!g.equals(key)) {
                    pool.add(g);
                }
            }
        }
    }

    // ================================================================ 钓鱼监听
    /** angler：缩短咬钩等待（FishHook 原生 API）+ 稀有度；treasure_seeker：宝藏概率。 */
    static final class FishingListener implements Listener {
        private final StarEnchant plugin;

        FishingListener(StarEnchant plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onFish(PlayerFishEvent e) {
            if (!plugin.anyEnabled()) return;
            CfgData d = plugin.data();
            Player p = e.getPlayer();
            ItemStack rod = p.getInventory().getItem(
                    e.getHand() == null ? org.bukkit.inventory.EquipmentSlot.HAND : e.getHand());
            Map<String, Integer> w = plugin.storage().read(rod);
            if (w.isEmpty()) return;
            EffectListener eff = plugin.effectListener();
            Def def;
            int l;

            if (e.getState() == PlayerFishEvent.State.FISHING) {
                // 咬钩等待时间 -(15×等级)%
                if (eff != null && (def = eff.defOf("angler")) != null
                        && (l = w.getOrDefault("angler", 0)) > 0 && e.getHook() != null) {
                    FishHook hook = e.getHook();
                    try {
                        int t = hook.getTimeUntilBite();
                        double reduce = def.sc("wait-reduce-percent-per-level", l) / 100.0;
                        hook.setTimeUntilBite(Math.max(1, (int) Math.round(t * (1.0 - reduce))));
                    } catch (IllegalArgumentException ignored) {
                        // 某些时点不允许缩短：本次按原版节奏
                    }
                }
                return;
            }
            if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
                double chance = 0;
                if ((def = eff == null ? null : eff.defOf("angler")) != null
                        && (l = w.getOrDefault("angler", 0)) > 0) {
                    chance += def.sc("rarity-bonus-percent-per-level", l);
                }
                if ((def = eff == null ? null : eff.defOf("treasure_seeker")) != null
                        && (l = w.getOrDefault("treasure_seeker", 0)) > 0) {
                    chance += def.sc("treasure-chance-percent-per-level", l);
                }
                if (chance <= 0) return;
                double r1 = ThreadLocalRandom.current().nextDouble(100.0);
                if (r1 >= Math.min(100.0, chance)) return;
                // 宝藏掷中：物品池随机
                if (!d.treasurePool.isEmpty()) {
                    Material m = d.treasurePool.get(ThreadLocalRandom.current().nextInt(d.treasurePool.size()));
                    ItemStack loot = new ItemStack(m, 1);
                    p.getWorld().dropItemNaturally(p.getLocation(), loot);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 1.5f);
                }
                // 小概率附带产出「垂钓/寻宝」附魔书（钓鱼途径唯一产出点）
                if (ThreadLocalRandom.current().nextDouble(100.0) < d.treasureBookChance) {
                    String id = ThreadLocalRandom.current().nextBoolean() ? "angler" : "treasure_seeker";
                    Def bookDef = plugin.registry().get(id);
                    if (bookDef != null && bookDef.enabled && bookDef.allowsSource("fishing")) {
                        ItemStack book = plugin.lore().buildBook(id, 1);
                        if (book != null) {
                            p.getWorld().dropItemNaturally(p.getLocation(), book);
                        }
                    }
                }
            }
        }
    }

    // ================================================================ 阵营商店
    /** 只卖 6 个阵营专属附魔，按玩家阵营过滤；货币为经验等级（原生 API，零 Vault）。 */
    static final class ShopGui implements Listener {
        private final StarEnchant plugin;

        ShopGui(StarEnchant plugin) {
            this.plugin = plugin;
        }

        private static final class ShopHolder implements InventoryHolder {
            Inventory inventory;
            final Map<Integer, String> slotToEnchant;
            final UUID viewer;

            ShopHolder(Inventory inventory, Map<Integer, String> slotToEnchant, UUID viewer) {
                this.inventory = inventory;
                this.slotToEnchant = slotToEnchant;
                this.viewer = viewer;
            }

            @Override
            public Inventory getInventory() {
                return inventory;
            }
        }

        void open(Player p) {
            CfgData d = plugin.data();
            String faction = plugin.storage().getFaction(p);
            List<String> items;
            String tag;
            if (faction != null && faction.equals(d.factionDawn)) {
                items = d.shopDawnItems;
                tag = "§6（黎明·炽壤）";
            } else if (faction != null && faction.equals(d.factionDusk)) {
                items = d.shopDuskItems;
                tag = "§5（暮夜·渊域）";
            } else {
                items = Collections.emptyList();
                tag = "";
            }
            Map<Integer, String> slotMap = new HashMap<>();
            int size = items.isEmpty() ? 9 : 27;
            ShopHolder holder = new ShopHolder(null, slotMap, p.getUniqueId());
            Inventory inv = Bukkit.createInventory(holder, size, d.shopTitle + (tag.isEmpty() ? "" : " " + tag));
            holder.inventory = inv;
            if (items.isEmpty()) {
                ItemStack barrier = new ItemStack(Material.BARRIER);
                ItemMeta bm = barrier.getItemMeta();
                if (bm != null) {
                    bm.setDisplayName("§c未登记阵营");
                    bm.setLore(Collections.singletonList(d.shopLockedLore));
                    barrier.setItemMeta(bm);
                }
                inv.setItem(4, barrier);
                p.openInventory(inv);
                return;
            }
            int[] slots = {11, 13, 15};
            for (int i = 0; i < items.size() && i < slots.length; i++) {
                String id = items.get(i);
                Def def = plugin.registry().get(id);
                int slot = slots[i];
                ItemStack book = def == null ? new ItemStack(Material.BARRIER)
                        : plugin.lore().buildBook(id, d.shopBookLevel);
                if (book == null) {
                    book = new ItemStack(Material.BARRIER);
                }
                ItemMeta meta = book.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add("§e售价：经验等级 ×" + d.shopPriceLevels);
                    meta.setLore(lore);
                    book.setItemMeta(meta);
                }
                inv.setItem(slot, book);
                slotMap.put(slot, id);
            }
            p.openInventory(inv);
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onClick(InventoryClickEvent e) {
            Inventory top = e.getView().getTopInventory();
            if (!(top.getHolder() instanceof ShopHolder sh) || !sh.viewer.equals(e.getWhoClicked().getUniqueId())) {
                return;
            }
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            String id = sh.slotToEnchant.get(e.getSlot());
            if (id == null) return;
            CfgData d = plugin.data();
            Def def = plugin.registry().get(id);
            if (def == null || !def.enabled) return;
            if (p.getLevel() < d.shopPriceLevels) {
                p.sendMessage("§c经验等级不足：需要 §e" + d.shopPriceLevels + " §c级。");
                return;
            }
            ItemStack book = plugin.lore().buildBook(id, d.shopBookLevel);
            if (book == null) return;
            p.giveExpLevels(-d.shopPriceLevels);
            var leftover = p.getInventory().addItem(book);
            for (ItemStack rest : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            p.sendMessage(def.color + "已购入 " + plugin.lore().displayLine(def, d.shopBookLevel)
                    + "§7（可在铁砧应用于装备）");
        }
    }

    // ================================================================ 命令处理
    /** /enchant list|info|give|reload|shop|faction（权限见 plugin.yml）。 */
    static final class CommandHandler implements CommandExecutor, TabCompleter {
        private static final String P_ADMIN = "star.enchant.give";
        private static final String P_RELOAD = "star.enchant.reload";
        private static final String P_FACTION = "star.enchant.setfaction";
        /** 附魔介绍文案（由 README 60 附魔总表的「触发与效果」列同源生成）。 */
        private static final Map<String, String> GUIDE_DESCRIPTIONS = buildGuideDescriptions();
        private final StarEnchant plugin;

        CommandHandler(StarEnchant plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "list" -> doList(sender, args);
                case "info" -> doInfo(sender, args);
                case "guide" -> doGuide(sender);
                case "give" -> doGive(sender, args);
                case "reload" -> doReload(sender);
                case "shop" -> doShop(sender, args);
                case "faction" -> doFaction(sender, args);
                default -> sendHelp(sender);
            }
            return true;
        }

        private void sendHelp(CommandSender s) {
            s.sendMessage("§6§l星穹旅驿 · StarEnchant §ev" + plugin.getDescription().getVersion());
            s.sendMessage("§7/" + "enchant list [类别] [页码] §f— 附魔总览（8 类 60 项）");
            s.sendMessage("§7/enchant info <附魔id> §f— 触发事件/效果数值/获取途径");
            s.sendMessage("§7/enchant guide §f— 领取附魔指南书（原版成书）");
            s.sendMessage("§7/enchant give <玩家> <附魔id> <等级> §f— 发放附魔书（管理员）");
            s.sendMessage("§7/enchant shop §f— 打开阵营商店");
            s.sendMessage("§7/enchant faction <玩家> <" + plugin.data().factionDawn + "|"
                    + plugin.data().factionDusk + "|none> §f— 设置阵营（管理员）");
            s.sendMessage("§7/enchant reload §f— 热加载配置（管理员）");
        }

        private void doList(CommandSender s, String[] args) {
            String catArg = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "all";
            Category filter = null;
            if (!catArg.equals("all")) {
                for (Category c : Category.values()) {
                    if (c.name().toLowerCase(Locale.ROOT).equals(catArg) || c.cn.equals(args[1])) {
                        filter = c;
                        break;
                    }
                }
                if (filter == null) {
                    s.sendMessage("§c未知类别：" + args[1] + "。可用：" + categoriesHelp());
                    return;
                }
            }
            int page = 1;
            if (args.length > 2) {
                try {
                    page = Math.max(1, Integer.parseInt(args[2]));
                } catch (NumberFormatException e) {
                    s.sendMessage("§c页码必须是数字");
                    return;
                }
            }
            List<Def> all = new ArrayList<>();
            for (Def d : plugin.registry().all()) {
                if (filter == null || d.category == filter) all.add(d);
            }
            int per = plugin.data().listPerPage;
            int pages = Math.max(1, (all.size() + per - 1) / per);
            page = Math.min(page, pages);
            s.sendMessage("§6附魔列表 §7[" + (filter == null ? "全部" : filter.cn) + "] §7第 "
                    + page + "/" + pages + " 页 §8（共 " + all.size() + " 项）");
            int from = (page - 1) * per;
            for (int i = from; i < Math.min(from + per, all.size()); i++) {
                Def d = all.get(i);
                s.sendMessage("  " + d.color + d.name + " §8" + d.id + " §7I-"
                        + LoreRenderer.roman(d.maxLevel) + (d.enabled ? "" : " §c[已关闭]"));
            }
            if (page < pages) {
                s.sendMessage("§7下一页：/enchant list " + (filter == null ? "all" : filter.name()) + " " + (page + 1));
            }
        }

        private void doInfo(CommandSender s, String[] args) {
            if (args.length < 2) {
                s.sendMessage("§c用法：/enchant info <附魔id>");
                return;
            }
            Def d = plugin.registry().get(args[1]);
            if (d == null) {
                s.sendMessage("§c找不到附魔 id：" + args[1] + "（/enchant list 查看全部）");
                return;
            }
            s.sendMessage(d.color + "§l" + d.name + " §8(" + d.id + ")");
            s.sendMessage("§7类别：§f" + d.category.cn + " §7| 等级：§fI-" + LoreRenderer.roman(d.maxLevel)
                    + " §7| 状态：§f" + (d.enabled ? "启用" : "关闭"));
            s.sendMessage("§7触发：§f" + d.triggerDesc);
            s.sendMessage("§7获取途径：§f" + (d.sources == null ? "（未配置）" : String.join(" · ", d.sources)));
            s.sendMessage("§7效果数值（per-level 键 × 等级生效）：");
            for (Map.Entry<String, Double> en : d.scale.entrySet()) {
                StringBuilder sb = new StringBuilder("  §a" + en.getKey() + " §7= " + fmt(en.getValue()) + " §8×等级");
                sb.append("  §f→");
                for (int lv = 1; lv <= d.maxLevel; lv++) {
                    sb.append(' ').append(lv).append("级:").append(fmt(en.getValue() * lv));
                }
                s.sendMessage(sb.toString());
            }
            for (Map.Entry<String, Double> en : d.flat.entrySet()) {
                s.sendMessage("  §b" + en.getKey() + " §7= " + fmt(en.getValue()) + " §8（固定值）");
            }
        }

        private String fmt(double v) {
            if (v == Math.floor(v) && !Double.isInfinite(v)) {
                return String.valueOf((long) v);
            }
            return String.format(Locale.ROOT, "%.2f", v);
        }

        private static Map<String, String> buildGuideDescriptions() {
            Map<String, String> m = new HashMap<>();
            put("blaze", "目标燃烧(等级×2)秒；对怪物伤害+(8×等级)%");
            put("rift", "击退距离+(50×等级)%；击杀怪物(15×等级)%概率额外掉落1个该怪物掉落物");
            put("sharp_edge", "攻击冷却-(8×等级)%：以服务端等效的「迅捷连击」实现——命中后(8×等级)%概率追加一次额外攻击");
            put("echo", "(20×等级)%概率恢复2饥饿值+1点生命");
            put("drain", "怪物经验掉落+(25×等级)%");
            put("shield_break", "目标盾牌禁用4秒(禁用期内其挡格视为无效)(默认关闭)");
            put("cleave", "2格范围内其他生物受到本次伤害(20×等级)%");
            put("bloodlust", "每层叠加+(5×等级)%伤害，上限5层，脱战10秒清零");
            put("fracture", "无视目标护甲值(10×等级)%");
            put("night_bite", "伤害+(7×等级)%");
            put("sun_brand", "伤害+(7×等级)%");
            put("executioner", "目标生命低于20%时伤害+(30×等级)%");
            put("chain", "击杀后5秒内下次攻击伤害+(25×等级)%");
            put("blood_pact", "吸取本次伤害(5×等级)%转化为自身生命");
            put("tough", "耐久损耗-(20×等级)%");
            put("quench", "燃烧期间穿戴装备免疫耐久损耗；自身燃烧时间-50%");
            put("bedrock_stance", "被击退距离-(40×等级)%");
            put("revive", "获得吸收I持续8秒，冷却60秒");
            put("shade", "移速+15%；半径8格内怪物不优先锁定该玩家");
            put("featherweight", "移速+(4×等级)%");
            put("vitality", "最大生命+(2×等级)点(每级+1颗心)");
            put("thorns_edge", "反弹(15×等级)%伤害给攻击者");
            put("void_ward", "受伤-(5×等级)%");
            put("sun_ward", "受伤-(5×等级)%");
            put("deep_breath", "氧气消耗-(40×等级)%");
            put("steadfast", "摔落伤害-(30×等级)%");
            put("shard_affinity", "炽阳碎片掉率×(1.5×等级)倍；若服主未做碎片系统请保持关闭(默认关闭)");
            put("rich_vein", "(25×等级)%概率额外掉落1个");
            put("smelt", "直接掉落烧炼后产物，经验减半");
            put("rock_break", "挖掘速度+(15×等级)%");
            put("spread", "连锁挖掘相邻同类方块，上限(3×等级)个(默认关闭)");
            put("timber", "连锁砍伐整棵相连树木，上限(8×等级)格");
            put("deep_dig", "连锁向下挖掘(2×等级)格(默认关闭)");
            put("magnet", "掉落物直接进入玩家背包(满则落脚下)");
            put("prospector", "显示周围(8×等级)格内矿石轮廓3秒");
            put("swift_repair", "(10×等级)%概率消耗经验修复装备2点耐久");
            put("quarry", "对石头类方块速度+(25×等级)%");
            put("fine_gather", "(20×等级)%概率掉落物×2");
            put("star_chase", "箭速+(20×等级)%；箭矢伤害+(8×等级)%");
            put("void_split", "1.5格范围内其他生物受到本次伤害(30×等级)%");
            put("pierce_shadow", "箭矢穿透1个生物继续飞行");
            put("split_arrow", "额外发射(等级)支箭(带散布角度)");
            put("homing", "箭矢轻微追踪半径(4×等级)格内最近生物");
            put("blast_tip", "小范围爆炸(不破坏方块)，范围内伤害(40×等级)%");
            put("frost_bind", "目标获得减速I持续(2×等级)秒");
            put("quick_draw", "未拉满但达到(85×等级)%阈值时按满弓结算");
            put("bulwark", "格挡减免额外+(8×等级)%");
            put("reflect", "反弹箭矢，对原射手造成(50×等级)%伤害");
            put("bash", "成功格挡后3秒内下次近战伤害+(20×等级)%");
            put("angler", "咬钩等待时间-(15×等级)%；钓鱼稀有度+1每级(按 +10%×等级 宝藏概率实现)");
            put("treasure_seeker", "宝藏概率+(20×等级)%");
            put("endless", "装备永不损耗耐久");
            put("soulbound", "该装备不掉落，复活时归还");
            put("beacon_light", "全伤害+(3×等级)%、受伤-(3×等级)%、挖掘速度+(5×等级)%");
            put("dawn_light", "伤害+(6×等级)%");
            put("night_hunt", "伤害+(6×等级)%");
            put("star_vein_dawn", "挖掘速度+(12×等级)%");
            put("star_vein_dusk", "挖掘速度+(12×等级)%");
            put("oath_guard_dawn", "受伤-(5×等级)%");
            put("oath_guard_dusk", "受伤-(5×等级)%");
            return Collections.unmodifiableMap(m);
        }

        /** /enchant guide —— 领取记载全部自定义附魔的原版成书。 */
        private void doGuide(CommandSender s) {
            if (!(s instanceof Player p)) {
                s.sendMessage("§c附魔指南是实体书，只能由玩家领取");
                return;
            }
            ItemStack book = buildGuideBook();
            var leftover = p.getInventory().addItem(book);
            for (ItemStack rest : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            p.sendMessage("§6[星穹旅驿] §e已获得《星穹旅驿·附魔指南》§7，翻页可查阅全部自定义附魔");
        }

        private ItemStack buildGuideBook() {
            ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
            BookMeta meta = (BookMeta) book.getItemMeta();
            if (meta == null) return book;
            meta.setTitle("星穹旅驿 · 附魔指南");
            meta.setAuthor("StarInn");
            List<String> pages = new ArrayList<>();
            pages.add("§6§l星穹旅驿 · 附魔指南\n\n"
                    + "§7本书记载本服全部 §f60 §7种自定义附魔，\n"
                    + "§7每项包含四项信息：\n"
                    + "  §f介绍 §7— 触发条件与效果\n"
                    + "  §f适用物品 §7— 可附魔的装备类型\n"
                    + "  §f最大等级 §7— 可强化到的最高级\n"
                    + "  §f获取方式 §7— 锻造/附魔/商店/掉落/活动\n\n"
                    + "§8————————————\n"
                    + "§7快捷查询：§f/enchant info <附魔id>\n"
                    + "§7附魔总览：§f/enchant list [类别]");
            pages.add("§6§l目录 · 八大类别\n\n"
                    + "§e武器类 §7— 剑 / 斧\n"
                    + "§e防具类 §7— 头盔 / 胸甲 / 护腿 / 靴子\n"
                    + "§e工具类 §7— 镐 / 斧 / 铲\n"
                    + "§e弓弩类 §7— 弓 / 弩\n"
                    + "§e盾牌类 §7— 盾牌\n"
                    + "§e钓鱼类 §7— 钓竿\n"
                    + "§e传说级 §7— 任意装备\n"
                    + "§e阵营专属 §7— 黎明 / 暮夜\n\n"
                    + "§7同类附魔连续排列，逐页翻阅即可。");
            for (Def d : plugin.registry().all()) {
                String desc = GUIDE_DESCRIPTIONS.getOrDefault(d.id, d.triggerDesc);
                pages.add(d.color + "§l" + d.name + " §8" + d.id + "\n"
                        + (d.enabled ? "" : "§c[本项已关闭]\n")
                        + "§7介绍：§f" + desc + "\n\n"
                        + "§7适用物品：§f" + applicableItems(d) + "\n"
                        + "§7最大等级：§f" + LoreRenderer.roman(d.maxLevel) + "\n"
                        + "§7获取方式：§f" + sourceNames(d.sources));
            }
            meta.setPages(pages);
            book.setItemMeta(meta);
            return book;
        }

        private String applicableItems(Def d) {
            if (d.category == Category.FACTION) {
                return switch (d.id) {
                    case "star_vein_dawn", "star_vein_dusk" -> "镐、斧、铲（工具）";
                    default -> "武器、防具";
                };
            }
            return switch (d.category) {
                case WEAPON -> "剑、斧（近战武器）";
                case ARMOR -> "头盔、胸甲、护腿、靴子";
                case TOOL -> "镐、斧、铲";
                case BOW -> "弓、弩";
                case SHIELD -> "盾牌";
                case FISHING -> "钓竿";
                case LEGEND -> "任意装备";
                case FACTION -> "阵营专属装备";
            };
        }

        private String sourceNames(Set<String> sources) {
            if (sources == null || sources.isEmpty()) return "（未配置）";
            List<String> out = new ArrayList<>();
            for (String src : sources) {
                out.add(switch (src) {
                    case "smithing" -> "锻造台";
                    case "enchanting" -> "附魔台";
                    case "loot" -> "怪物掉落";
                    case "fishing" -> "钓鱼";
                    case "shop" -> "阵营商店";
                    case "event" -> "活动发放";
                    default -> src;
                });
            }
            return String.join(" · ", out);
        }

        private void doGive(CommandSender s, String[] args) {
            // 仅用权限节点判定：控制台与 RCON(RemoteConsoleCommandSender) 均有 star.enchant.give
            if (!s.hasPermission(P_ADMIN)) {
                s.sendMessage("§c没有权限（" + P_ADMIN + "）");
                return;
            }
            if (args.length < 4) {
                s.sendMessage("§c用法：/enchant give <玩家> <附魔id> <等级>");
                return;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                s.sendMessage("§c玩家不在线：" + args[1]);
                return;
            }
            Def d = plugin.registry().get(args[2]);
            if (d == null || !d.valid) {
                s.sendMessage("§c无效附魔 id：" + args[2]);
                return;
            }
            if (!d.enabled) {
                s.sendMessage("§c该附魔当前已关闭（config enabled=false），拒绝发放");
                return;
            }
            int lv;
            try {
                lv = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                s.sendMessage("§c等级必须是数字");
                return;
            }
            lv = d.clampLevel(lv);
            ItemStack book = plugin.lore().buildBook(d.id, lv);
            if (book == null) {
                s.sendMessage("§c生成附魔书失败（存储不可用？）");
                return;
            }
            var leftover = target.getInventory().addItem(book);
            for (ItemStack rest : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), rest);
            }
            target.sendMessage("§6[星穹旅驿] §e获得附魔书：" + plugin.lore().displayLine(d, lv));
            s.sendMessage("§a已向 " + target.getName() + " 发放 " + plugin.lore().displayLine(d, lv));
        }

        private void doReload(CommandSender s) {
            if (!s.hasPermission(P_RELOAD)) {
                s.sendMessage("§c没有权限（" + P_RELOAD + "）");
                return;
            }
            plugin.reloadPlugin();
            s.sendMessage("§a[星穹旅驿] 配置已热加载：正常 " + plugin.checkNormal() + " 项 / 降级 " + plugin.checkDegraded() + " 项");
        }

        private void doShop(CommandSender s, String[] args) {
            if (!plugin.data().shopEnabled) {
                s.sendMessage("§c阵营商店当前已关闭");
                return;
            }
            Player p;
            if (args.length > 1) {
                if (!s.hasPermission(P_FACTION)) {
                    s.sendMessage("§c只有管理员可以打开他人商店视图");
                    return;
                }
                p = Bukkit.getPlayerExact(args[1]);
                if (p == null) {
                    s.sendMessage("§c玩家不在线：" + args[1]);
                    return;
                }
            } else if (s instanceof Player pl) {
                p = pl;
            } else {
                s.sendMessage("§c控制台不能打开商店");
                return;
            }
            plugin.shop().open(p);
        }

        private void doFaction(CommandSender s, String[] args) {
            if (!s.hasPermission(P_FACTION)) {
                s.sendMessage("§c没有权限（" + P_FACTION + "）");
                return;
            }
            if (args.length < 3) {
                s.sendMessage("§c用法：/enchant faction <玩家> <" + plugin.data().factionDawn
                        + "|" + plugin.data().factionDusk + "|none>");
                return;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                s.sendMessage("§c玩家不在线：" + args[1]);
                return;
            }
            String f = args[2].toLowerCase(Locale.ROOT);
            CfgData d = plugin.data();
            String stored;
            if (f.equals("none") || f.equals(d.factionNone)) {
                stored = null;
            } else if (f.equals(d.factionDawn)) {
                stored = d.factionDawn;
            } else if (f.equals(d.factionDusk)) {
                stored = d.factionDusk;
            } else {
                s.sendMessage("§c未知阵营：" + f);
                return;
            }
            if (plugin.storage().setFaction(target, stored)) {
                target.sendMessage("§6[星穹旅驿] §e你的阵营已变更为："
                        + (stored == null ? "无阵营" : stored.equals(d.factionDawn) ? "§6黎明" : "§5暮夜"));
                s.sendMessage("§a已设置 " + target.getName() + " 阵营为 " + (stored == null ? "none" : stored));
            } else {
                s.sendMessage("§c写入玩家 PDC 失败");
            }
        }

        private String categoriesHelp() {
            StringBuilder sb = new StringBuilder();
            for (Category c : Category.values()) {
                if (sb.length() > 0) sb.append('/');
                sb.append(c.name().toLowerCase(Locale.ROOT)).append('(').append(c.cn).append(')');
            }
            return sb.toString();
        }

        // ---------------- 自动补全
        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!plugin.data().tabComplete) return Collections.emptyList();
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 1) {
                return prefix(args[0], Arrays.asList(
                        "list", "info", "guide", "give", "reload", "shop", "faction", "help"));
            }
            switch (args.length) {
                case 2: {
                    // 第 2 个参数（整条命令的第 3 个词）：give/faction/shop 都是玩家名
                    switch (sub) {
                        case "list":
                            return prefix(args[1], allCategoryNames());
                        case "info":
                            return prefix(args[1], enchantIds());
                        case "give":
                        case "faction":
                        case "shop": {
                            List<String> names = new ArrayList<>();
                            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                            return prefix(args[1], names);
                        }
                        default:
                            return Collections.emptyList();
                    }
                }
                case 3: {
                    // 第 3 个参数：give 是附魔 id，faction 是阵营值
                    switch (sub) {
                        case "give":
                            return prefix(args[2], enchantIds());
                        case "faction":
                            CfgData d = plugin.data();
                            return prefix(args[2], Arrays.asList(d.factionDawn, d.factionDusk, "none"));
                        default:
                            return Collections.emptyList();
                    }
                }
                case 4:
                    // 第 4 个参数：give 的等级
                    if (sub.equals("give")) {
                        List<String> out = new ArrayList<>();
                        for (int i = 1; i <= 10; i++) out.add(String.valueOf(i));
                        return out;
                    }
                    return Collections.emptyList();
                default:
                    return Collections.emptyList();
            }
        }

        private List<String> allCategoryNames() {
            List<String> out = new ArrayList<>();
            out.add("all");
            for (Category c : Category.values()) out.add(c.name().toLowerCase(Locale.ROOT));
            return out;
        }

        private List<String> enchantIds() {
            List<String> out = new ArrayList<>();
            for (Def d : plugin.registry().all()) out.add(d.id);
            return out;
        }

        private List<String> prefix(String typed, List<String> options) {
            String t = typed.toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String o : options) {
                if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
            }
            return out;
        }
    }

    /** 自检统计访问（reload 后展示）。 */
    public int checkNormal() { return checkNormal; }
    public int checkDegraded() { return checkDegraded; }
}
