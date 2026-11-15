package me.arsali.duels;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Duels extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    static final MiniMessage MM = MiniMessage.miniMessage();
    static final Pattern PH = Pattern.compile("\\{(\\w+)\\}");

    enum State { COUNTDOWN, ACTIVE, ENDED }

    record Req(UUID from, String kit, long time) {}

    static class Party {
        UUID leader;
        final List<UUID> members = new ArrayList<>();
    }

    record PInv(Party party, long time) {}

    /** Best tier of a player: gamemode + index in tiers.list (0 = LT5 ... 9 = HT1). */
    record TierInfo(String kit, int tier) {}
    static final TierInfo NO_TIER = new TierInfo("", -1);

    /** Kit selection GUI. mode = duel | queue | ffa | editor */
    static class KitMenu implements InventoryHolder {
        final String mode;
        final UUID target;
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;
        KitMenu(String mode, UUID target) { this.mode = mode; this.target = target; }
        @Override public Inventory getInventory() { return inv; }
    }

    static class SettingsMenu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    /** Admin GUI: drag the kits where you want them in the gamemode menu (/kit slots). */
    static class SlotEditor implements InventoryHolder {
        final Set<String> shown = new HashSet<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    /** Kit editor GUI (/kiteditor): the player's inventory laid out as a menu. */
    static class KitEditorMenu implements InventoryHolder {
        final String kit;
        Inventory inv;
        KitEditorMenu(String kit) { this.kit = kit; }
        @Override public Inventory getInventory() { return inv; }
    }

    class Match {
        final List<Player> t1, t2;
        final String kit;
        final boolean ffa;
        String arena;
        final Set<UUID> specs = new HashSet<>();
        final Set<UUID> alive = new HashSet<>();
        final Set<UUID> left = new HashSet<>();
        State state = State.COUNTDOWN;
        long start = 0;
        boolean teamBattle;          // party Red vs Blue
        boolean ranked;              // ranked 1v1 (Elo)
        final Set<Block> placed = new HashSet<>();                       // blocks placed by players in this match (breakable)
        final Map<Block, BlockData> doorOrig = new LinkedHashMap<>();    // arena door: original blocks
        List<Block> doorBlocks;
        Location doorCenter;
        final Map<Block, BlockData> changes = new LinkedHashMap<>();   // big arenas: original of every changed block
        BoundingBox box;
        World world;
        BlockData[] snap;

        Match(List<Player> t1, List<Player> t2, String kit, boolean ffa) {
            this.t1 = t1; this.t2 = t2; this.kit = kit; this.ffa = ffa;
            for (Player p : t1) alive.add(p.getUniqueId());
            for (Player p : t2) alive.add(p.getUniqueId());
        }
        boolean inTeam1(Player p) {
            for (Player x : t1) if (x.getUniqueId().equals(p.getUniqueId())) return true;
            return false;
        }
        List<Player> team(Player p) {
            if (ffa) return List.of(p);
            return inTeam1(p) ? t1 : t2;
        }
        List<Player> enemy(Player p) {
            if (ffa) {
                List<Player> l = new ArrayList<>();
                for (Player x : t1) if (!x.getUniqueId().equals(p.getUniqueId())) l.add(x);
                return l;
            }
            return inTeam1(p) ? t2 : t1;
        }
        List<Player> all() { List<Player> l = new ArrayList<>(t1); l.addAll(t2); return l; }
    }

    final Map<UUID, Match> matches = new HashMap<>();
    final Map<UUID, Match> watching = new HashMap<>();
    final Set<Match> activeMatches = new HashSet<>();
    final Map<UUID, Req> requests = new HashMap<>();
    final Map<UUID, Scoreboard> boards = new HashMap<>();
    final Set<String> busyArenas = new HashSet<>();
    final Map<UUID, Party> parties = new HashMap<>();
    final Map<UUID, PInv> pinvites = new HashMap<>();
    final Map<UUID, String> queue = new LinkedHashMap<>();
    final Map<UUID, String> editing = new HashMap<>();

    File dataFile;
    YamlConfiguration data;
    NamespacedKey actionKey;
    NamespacedKey kitKey;
    NamespacedKey boardKey;
    NamespacedKey headKey;
    NamespacedKey wandKey;
    boolean statsDirty = false;
    final Map<UUID, UUID> lastHitter = new HashMap<>();
    final Map<UUID, Long> lastHitAt = new HashMap<>();
    final Map<UUID, Long> rankedSince = new HashMap<>();   // players in the queue who are searching RANKED
    final Map<UUID, TierInfo> tierCache = new HashMap<>();
    long tierCacheAt = 0;
    final Map<UUID, String> chatHead = new java.util.concurrent.ConcurrentHashMap<>();   // chat prefix per player (read from the async chat thread)

    // ───────────────────────── setup ─────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        actionKey = new NamespacedKey(this, "action");
        kitKey = new NamespacedKey(this, "kit");
        boardKey = new NamespacedKey(this, "board");
        headKey = new NamespacedKey(this, "ghead");
        wandKey = new NamespacedKey(this, "doorwand");
        dataFile = new File(getDataFolder(), "data.yml");
        data = new YamlConfiguration();
        if (dataFile.exists()) {
            try {
                data.load(dataFile);
            } catch (IOException | InvalidConfigurationException ex) {
                getLogger().severe("data.yml could not be loaded (" + ex.getMessage() + "). Backing it up so nothing is lost.");
                dataFile.renameTo(new File(getDataFolder(), "data.yml.broken-" + System.currentTimeMillis()));
                data = new YamlConfiguration();
            }
        }

        // migrate data written by older versions into data.yml
        boolean migrated = false;
        for (String k : List.of("lobby", "arenas", "kits", "layouts", "settings")) {
            if (!data.contains(k) && getConfig().isSet(k)) { data.set(k, getConfig().get(k)); migrated = true; }
        }
        if (migrated) saveData();
        normalizeLocations();
        Location lb = getLoc("lobby");
        getLogger().info(lb != null
                ? "Lobby loaded: " + lb.getWorld().getName() + " " + lb.getBlockX() + " " + lb.getBlockY() + " " + lb.getBlockZ()
                : "No lobby set yet - use /setlobby");

        Bukkit.getPluginManager().registerEvents(this, this);
        for (String c : List.of("duel", "spectate", "leave", "lobby", "setlobby", "arena", "kit", "party",
                "settings", "kiteditor", "duels", "world", "sb", "stats", "tier")) {
            Objects.requireNonNull(getCommand(c)).setExecutor(this);
            Objects.requireNonNull(getCommand(c)).setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this,
                () -> Bukkit.getOnlinePlayers().forEach(this::update), 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) toLobby(p);
        long every = Math.max(5, getConfig().getLong("leaderboards.update-seconds", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::updateBoards, 100L, every);
        Bukkit.getScheduler().runTaskTimer(this, this::tryRanked, 40L, 40L);      // ranked matchmaking
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (statsDirty) { statsDirty = false; saveData(); } }, 600L, 600L);
    }

    @Override
    public void onDisable() {
        for (Match m : new HashSet<>(activeMatches)) restore(m);
        if (statsDirty) saveData();
    }

    void saveData() {
        try { data.save(dataFile); } catch (IOException ex) { getLogger().warning("Could not save data.yml: " + ex.getMessage()); }
    }

    // ───────────────────────── messaging helpers ─────────────────────────

    void msg(CommandSender s, String mm) { s.sendMessage(MM.deserialize(mm)); }

    String fmt(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    String t(String key, String... kv) {
        String s = getConfig().getString("messages." + key);
        if (s == null) s = "<red>[missing message: " + key + "]";
        return fmt(s, kv);
    }

    void send(CommandSender s, String key, String... kv) { msg(s, t(key, kv)); }

    void sendList(CommandSender s, String path, String... kv) {
        for (String l : getConfig().getStringList(path)) msg(s, fmt(l, kv));
    }

    void title(Player p, String t, String sub) {
        p.showTitle(Title.title(MM.deserialize(t), MM.deserialize(sub),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(400))));
    }

    void titleK(Player p, String key, String... kv) {
        title(p, fmt(getConfig().getString("titles." + key + ".title", ""), kv),
                fmt(getConfig().getString("titles." + key + ".subtitle", ""), kv));
    }

    void sound(Player p, String key) { soundAt(p, p.getLocation(), key); }

    void soundAt(Player p, Location at, String key) {
        for (String line : getConfig().getStringList("sounds." + key)) {
            String[] a = line.trim().split("\\s+");
            if (a.length == 0 || a[0].isEmpty()) continue;
            float vol = 1f, pitch = 1f;
            try {
                if (a.length > 1) vol = Float.parseFloat(a[1]);
                if (a.length > 2) pitch = Float.parseFloat(a[2]);
            } catch (NumberFormatException ignored) { }
            p.playSound(at, a[0], SoundCategory.MASTER, vol, pitch);
        }
    }

    String clock(long s) { return String.format("%02d:%02d", s / 60, s % 60); }

    String label(List<Player> l) {
        if (l.isEmpty()) return "-";
        return l.get(0).getName() + (l.size() > 1 ? " +" + (l.size() - 1) : "");
    }

    String names(List<Player> l) {
        List<String> n = new ArrayList<>();
        for (Player p : l) n.add(p.getName());
        return String.join(", ", n);
    }

    ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(MM.deserialize("<!italic>" + s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    ItemStack glass() {
        Material m = Material.matchMaterial(getConfig().getString("gui.glass", "BLACK_STAINED_GLASS_PANE"));
        if (m == null) m = Material.BLACK_STAINED_GLASS_PANE;
        return named(m, " ");
    }

    // ───────────────────────── locations (saved as plain text, survive restarts) ─────────────────────────

    World worldByName(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null && new File(Bukkit.getWorldContainer(), name).isDirectory()) {
            try { w = Bukkit.createWorld(new WorldCreator(name)); }
            catch (Exception ex) { getLogger().warning("Could not load world " + name + ": " + ex.getMessage()); }
        }
        return w;
    }

    double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException ex) { return 0; }
    }

    Map<String, Object> locMap(Location l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", l.getWorld().getName());
        m.put("x", l.getX());
        m.put("y", l.getY());
        m.put("z", l.getZ());
        m.put("yaw", (double) l.getYaw());
        m.put("pitch", (double) l.getPitch());
        return m;
    }

    Location fromMap(Map<?, ?> m) {
        Object w = m.get("world");
        if (w == null || m.get("x") == null) return null;
        World world = worldByName(String.valueOf(w));
        if (world == null) return null;
        return new Location(world, num(m.get("x")), num(m.get("y")), num(m.get("z")),
                (float) num(m.get("yaw")), (float) num(m.get("pitch")));
    }

    Location toLoc(Object o) {
        if (o instanceof Location l) return l;                     // old format
        if (o instanceof Map<?, ?> m) return fromMap(m);
        if (o instanceof ConfigurationSection cs) return fromMap(cs.getValues(false));
        return null;
    }

    Location getLoc(String path) { return toLoc(data.get(path)); }

    void setLoc(String path, Location l) { data.set(path, locMap(l)); }

    void setFfa(String arena, List<Location> spots) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Location l : spots) out.add(locMap(l));
        data.set("arenas." + arena + ".ffa", out);
    }

    /** Converts old serialized Locations to the plain format (so an unloaded world can never break data.yml). */
    void normalizeLocations() {
        boolean changed = false;
        Location l = getLoc("lobby");
        if (l != null) { setLoc("lobby", l); changed = true; }
        ConfigurationSection as = data.getConfigurationSection("arenas");
        if (as != null) {
            for (String n : as.getKeys(false)) {
                for (String k : List.of("spawn1", "spawn2", "corner1", "corner2")) {
                    Location x = getLoc("arenas." + n + "." + k);
                    if (x != null) { setLoc("arenas." + n + "." + k, x); changed = true; }
                }
                List<Location> f = ffaSpawns(n);
                if (!f.isEmpty()) { setFfa(n, f); changed = true; }
            }
        }
        if (changed) saveData();
    }

    // ───────────────────────── lobby / reset ─────────────────────────

    Location lobby() {
        Location l = getLoc("lobby");
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    void reset(Player p, GameMode gm) {
        p.setGameMode(gm);
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setCooldown(Material.WIND_CHARGE, 0);
    }

    void putItem(Player p, String key, String action, String... kv) {
        String base = "lobby-items." + key;
        Material mat = Material.matchMaterial(getConfig().getString(base + ".material", "STONE"));
        if (mat == null) mat = Material.STONE;
        List<String> lore = new ArrayList<>();
        for (String l : getConfig().getStringList(base + ".lore")) lore.add(fmt(l, kv));
        ItemStack it = named(mat, fmt(getConfig().getString(base + ".name", key), kv), lore.toArray(new String[0]));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        p.getInventory().setItem(getConfig().getInt(base + ".slot", 0), it);
    }

    /** Lobby hotbar. Changes when you are in a party (lectern = FFA, barrier = disband/leave). */
    void lobbyItems(Player p) {
        Party pt = partyOf(p);
        if (pt == null) {
            putItem(p, "queue", "queue");
            putItem(p, "ranked-queue", "ranked");
            putItem(p, "party-create", "party");
        } else if (pt.leader.equals(p.getUniqueId())) {
            putItem(p, "party-ffa", "ffa");
            putItem(p, "party-teams", "teams");
            putItem(p, "party-disband", "disband");
        } else {
            putItem(p, "party-leave", "leaveparty");
        }
        putItem(p, "editor", "editor");
        putItem(p, "settings", "settings");
    }

    void refreshLobby(Player p) {
        if (p == null || !p.isOnline()) return;
        if (busy(p) || editing.containsKey(p.getUniqueId())) return;
        for (int i = 0; i < 36; i++) {      // remove only our tagged lobby items
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && it.hasItemMeta()
                    && it.getItemMeta().getPersistentDataContainer().has(actionKey, PersistentDataType.STRING)) {
                p.getInventory().setItem(i, null);
            }
        }
        lobbyItems(p);
    }

    void refresh(Collection<UUID> ids) {
        for (UUID id : new ArrayList<>(ids)) refreshLobby(Bukkit.getPlayer(id));
    }

    void toLobby(Player p) {
        editing.remove(p.getUniqueId());
        World lw = lobby().getWorld();
        reset(p, worldBuild(lw) ? GameMode.SURVIVAL : GameMode.ADVENTURE);
        p.teleport(lobby());
        lobbyItems(p);
        update(p);
    }

    boolean busy(Player p) {
        return matches.containsKey(p.getUniqueId()) || watching.containsKey(p.getUniqueId());
    }

    boolean anyBusy(List<Player> l) {
        for (Player p : l) if (busy(p)) return true;
        return false;
    }

    // ───────────────────────── kits / arenas ─────────────────────────

    String firstKit() {
        ConfigurationSection s = data.getConfigurationSection("kits");
        if (s == null || s.getKeys(false).isEmpty()) return null;
        return s.getKeys(false).iterator().next();
    }

    ItemStack[] kitBase(String kit) {
        List<?> raw = data.getList("kits." + kit + ".items");
        if (raw == null) return null;
        ItemStack[] arr = new ItemStack[raw.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = raw.get(i) instanceof ItemStack b ? b : null;
        return arr;
    }

    void giveKit(Player p, String kit) {
        ItemStack[] arr = kitBase(kit);
        if (arr == null) return;
        p.getInventory().setContents(arr);
        // player's own saved layout (Kit Editor)
        List<?> lay = data.getList("layouts." + p.getUniqueId() + "." + kit);
        if (lay != null && lay.size() == 36 && arr.length >= 36) {
            ItemStack[] ls = new ItemStack[36];
            for (int i = 0; i < 36; i++) ls[i] = lay.get(i) instanceof ItemStack li ? li : null;
            if (sameMulti(merge(ls, 0, 36), merge(arr, 0, 36))) {
                for (int i = 0; i < 36; i++) p.getInventory().setItem(i, ls[i]);
            }
        }
    }

    List<Location> ffaSpawns(String arena) {
        List<Location> out = new ArrayList<>();
        List<?> raw = data.getList("arenas." + arena + ".ffa");
        if (raw != null) for (Object o : raw) { Location l = toLoc(o); if (l != null) out.add(l); }
        return out;
    }

    String freeArena(boolean ffa, String kit) {
        List<String> allowed = data.getStringList("kits." + kit + ".arenas");   // /kit arena add
        ConfigurationSection s = data.getConfigurationSection("arenas");
        if (s == null) return null;
        List<String> pref = new ArrayList<>(), any = new ArrayList<>();
        for (String n : s.getKeys(false)) {
            if (busyArenas.contains(n)) continue;
            if (!allowed.isEmpty() && !allowed.contains(n)) continue;
            boolean duelOk = getLoc("arenas." + n + ".spawn1") != null && getLoc("arenas." + n + ".spawn2") != null;
            if (ffa && ffaSpawns(n).size() >= 2) pref.add(n);
            else if (duelOk) any.add(n);
        }
        List<String> pool = (ffa && !pref.isEmpty()) ? pref : any;
        if (pool.isEmpty()) return null;
        return pool.get(new Random().nextInt(pool.size()));
    }

    // ───────────────────────── per-world building ─────────────────────────

    String wkey(World w) { return w.getName().replace(".", "_"); }

    /** /world build [world] on|off  -> players may place/break blocks in that world */
    boolean worldBuild(World w) { return w != null && data.getBoolean("worlds." + wkey(w) + ".build", false); }

    // ───────────────────────── settings ─────────────────────────

    static final String[] SETTING_KEYS = {"scoreboard", "nametags", "players", "requests"};
    static final int[] SETTING_SLOTS = {10, 12, 14, 16};

    boolean setting(Player p, String key) {
        return data.getBoolean("settings." + p.getUniqueId() + "." + key, true);
    }

    void openSettings(Player p) {
        SettingsMenu h = new SettingsMenu();
        Inventory inv = Bukkit.createInventory(h, 27, MM.deserialize(getConfig().getString("gui.titles.settings", "Settings")));
        h.inv = inv;
        fillSettings(p, inv);
        p.openInventory(inv);
    }

    void fillSettings(Player p, Inventory inv) {
        ItemStack g = glass();
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, g);
        for (int i = 0; i < SETTING_KEYS.length; i++) {
            String k = SETTING_KEYS[i];
            boolean on = setting(p, k);
            String nm = getConfig().getString("gui.settings." + k + ".name", k);
            String ds = getConfig().getString("gui.settings." + k + ".description", "");
            inv.setItem(SETTING_SLOTS[i], named(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    (on ? "<green>" : "<red>") + "<bold>" + nm + ": " + (on ? "ON" : "OFF"),
                    "<gray>" + ds, "", "<yellow>Click to toggle"));
        }
    }

    void visibility(Player p) {
        Match pm = matches.get(p.getUniqueId());
        Match pw = watching.get(p.getUniqueId());
        Match pMatch = pm != null ? pm : pw;
        boolean hideLobby = !setting(p, "players");
        Party pa = parties.get(p.getUniqueId());
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            Match om = matches.get(o.getUniqueId());
            Match ow = watching.get(o.getUniqueId());
            Match oMatch = om != null ? om : ow;
            boolean see;
            if (pMatch != null) see = pMatch == oMatch;
            else see = oMatch == null && (!hideLobby || (pa != null && pa == parties.get(o.getUniqueId())));
            boolean can = p.canSee(o);
            if (see && !can) p.showPlayer(this, o);
            else if (!see && can) p.hidePlayer(this, o);
        }
    }

    // ───────────────────────── kit editor ─────────────────────────

    boolean sim(ItemStack a, ItemStack b) {
        boolean ea = a == null || a.getType().isAir(), eb = b == null || b.getType().isAir();
        if (ea || eb) return ea && eb;
        return a.isSimilar(b) && a.getAmount() == b.getAmount();
    }

    List<ItemStack> merge(ItemStack[] arr, int from, int to) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = from; i < to && i < arr.length; i++) {
            ItemStack it = arr[i];
            if (it == null || it.getType().isAir()) continue;
            boolean found = false;
            for (ItemStack o : out) {
                if (o.isSimilar(it)) { o.setAmount(o.getAmount() + it.getAmount()); found = true; break; }
            }
            if (!found) out.add(it.clone());
        }
        return out;
    }

    boolean sameMulti(List<ItemStack> x, List<ItemStack> y) {
        if (x.size() != y.size()) return false;
        List<ItemStack> rest = new ArrayList<>(y);
        for (ItemStack a : x) {
            boolean ok = false;
            for (int i = 0; i < rest.size(); i++) {
                ItemStack b = rest.get(i);
                if (a.isSimilar(b) && a.getAmount() == b.getAmount()) { rest.remove(i); ok = true; break; }
            }
            if (!ok) return false;
        }
        return true;
    }

    void editorSave(Player p) {
        UUID id = p.getUniqueId();
        String kit = editing.get(id);
        if (kit == null) { send(p, "editor-not-editing"); return; }
        ItemStack[] base = kitBase(kit);
        ItemStack[] cur = p.getInventory().getContents();
        if (base == null || base.length < 36 || cur.length < 36) {
            send(p, "kit-not-found");
            toLobby(p);
            return;
        }
        boolean ok = sameMulti(merge(cur, 0, 36), merge(base, 0, 36));
        for (int i = 36; i < base.length && i < cur.length && ok; i++) if (!sim(base[i], cur[i])) ok = false;
        if (!ok) { send(p, "editor-bad-layout"); return; }
        data.set("layouts." + id + "." + kit, new ArrayList<>(Arrays.asList(Arrays.copyOfRange(cur, 0, 36))));
        saveData();
        toLobby(p);
        send(p, "editor-saved", "kit", kit);
    }

    void kiteditor(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        UUID id = p.getUniqueId();
        switch (sub) {
            case "save" -> editorSave(p);
            case "cancel" -> {
                if (editing.containsKey(id)) { toLobby(p); send(p, "editor-closed"); }
                else send(p, "editor-not-editing");
            }
            case "reset" -> {
                String kit = editing.get(id);
                if (kit == null) { send(p, "editor-not-editing"); return; }
                data.set("layouts." + id + "." + kit, null);
                saveData();
                giveKit(p, kit);
                send(p, "editor-reset");
            }
            default -> {
                if (busy(p)) { send(p, "editor-busy"); return; }
                openKitMenu(p, "editor", null);
            }
        }
    }

    // ───────────────────────── kit GUI ─────────────────────────

    ItemStack kitIcon(String name, String mode) {
        Material mat = null;
        String ic = data.getString("kits." + name + ".icon");
        if (ic != null) mat = Material.matchMaterial(ic);
        if (mat == null) {
            ItemStack[] raw = kitBase(name);
            if (raw != null) {
                for (ItemStack is : raw) {
                    if (is == null) continue;
                    String n = is.getType().name();
                    if (n.contains("SWORD") || n.contains("AXE") || n.contains("MACE") || n.contains("SPEAR")
                            || n.contains("BOW") || n.contains("TRIDENT")) { mat = is.getType(); break; }
                }
                if (mat == null) {
                    for (ItemStack is : raw) if (is != null && !is.getType().isAir()) { mat = is.getType(); break; }
                }
            }
        }
        if (mat == null) mat = Material.PAPER;
        String pretty = name.substring(0, 1).toUpperCase() + name.substring(1);
        int queued = 0, fighting = 0;
        for (String q : queue.values()) if (q.equals(name)) queued++;
        for (Match m : activeMatches) if (m.kit.equals(name)) fighting += m.all().size();
        List<String> lore = new ArrayList<>();
        for (String l : getConfig().getStringList("gui.lore." + mode)) {
            lore.add(fmt(l, "queued", String.valueOf(queued), "fighting", String.valueOf(fighting)));
        }
        ItemStack icon = named(mat, fmt(getConfig().getString("gui.kit-name", "<yellow><bold>{kit}"), "kit", pretty),
                lore.toArray(new String[0]));
        ItemMeta im = icon.getItemMeta();
        im.getPersistentDataContainer().set(kitKey, PersistentDataType.STRING, name);
        icon.setItemMeta(im);
        // like Minemen: the stack number = players fighting in that gamemode
        if (!mode.equals("slots") && getConfig().getBoolean("gui.amount-shows-fighting", true)) {
            icon.setAmount(Math.max(1, Math.min(64, fighting)));
        }
        return icon;
    }

    // ───────────────────────── stats, levels, leaderboards ─────────────────────────

    int stat(UUID id, String stat) { return data.getInt("stats." + id + "." + stat, 0); }

    void addStat(Player p, String stat, int amount) {
        String base = "stats." + p.getUniqueId();
        data.set(base + ".name", p.getName());
        data.set(base + "." + stat, data.getInt(base + "." + stat, 0) + amount);
        statsDirty = true;
    }

    // ───────────────────────── tiers (LT5 ... HT1 per gamemode) ─────────────────────────

    List<Map<?, ?>> tierList() { return getConfig().getMapList("tiers.list"); }

    /** Highest tier whose min rating is reached (index in tiers.list). */
    int tierIdx(int rating) {
        List<Map<?, ?>> ts = tierList();
        int t = 0;
        for (int i = 0; i < ts.size(); i++) if (rating >= (int) num(ts.get(i).get("min"))) t = i;
        return t;
    }

    /** -1 = no tier in this gamemode. An admin /tier set always wins over the Elo-based tier. */
    int playerTier(UUID id, String kit) {
        int o = data.getInt("stats." + id + ".tier." + kit, -1);
        if (o >= 0) return o;
        if (!data.isSet("stats." + id + ".elo." + kit)) return -1;
        return tierIdx(rating(id, kit));
    }

    TierInfo bestTier(UUID id) {
        if (tierList().isEmpty()) return null;
        Set<String> kits = new LinkedHashSet<>();
        ConfigurationSection es = data.getConfigurationSection("stats." + id + ".elo");
        if (es != null) kits.addAll(es.getKeys(false));
        ConfigurationSection ts = data.getConfigurationSection("stats." + id + ".tier");
        if (ts != null) kits.addAll(ts.getKeys(false));
        TierInfo best = null;
        int bestRating = -1;
        for (String k : kits) {
            int t = playerTier(id, k);
            if (t < 0) continue;
            int r = rating(id, k);
            if (best == null || t > best.tier() || (t == best.tier() && r > bestRating)) { best = new TierInfo(k, t); bestRating = r; }
        }
        return best;
    }

    TierInfo cachedTier(UUID id) {
        long now = System.currentTimeMillis();
        if (now - tierCacheAt > 900) { tierCache.clear(); tierCacheAt = now; }
        return tierCache.computeIfAbsent(id, k -> { TierInfo t = bestTier(k); return t == null ? NO_TIER : t; });
    }

    Map<?, ?> tierDef(int tier) {
        List<Map<?, ?>> ts = tierList();
        return ts.get(Math.max(0, Math.min(tier, ts.size() - 1)));
    }

    String tierTagText(int tier) { return String.valueOf(tierDef(tier).get("tag")); }

    String tierNameColor(int tier) { return String.valueOf(tierDef(tier).get("name-color")); }

    /** "[SPEAR HT3] " */
    String tagPrefix(String kit, int tier) {
        return fmt(getConfig().getString("tiers.tag-format", "<dark_gray>[<white>{kit} {tier}<dark_gray>] "),
                "kit", kit.toUpperCase(), "tier", tierTagText(tier));
    }

    /** One scoreboard team per (gamemode, tier): gives the nametag its [SPEAR HT3] prefix and tier colour. */
    Team tierTeam(Scoreboard sb, TierInfo ti) {
        String name = "T" + Integer.toHexString(Objects.hash(ti.kit(), ti.tier()));
        Team tm = sb.getTeam(name);
        if (tm == null) {
            tm = sb.registerNewTeam(name);
            tm.prefix(MM.deserialize(tagPrefix(ti.kit(), ti.tier())));
            NamedTextColor c = NamedTextColor.NAMES.value(String.valueOf(tierDef(ti.tier()).get("color")));
            tm.color(c != null ? c : NamedTextColor.WHITE);
        }
        return tm;
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        String head = chatHead.get(e.getPlayer().getUniqueId());
        if (head == null) return;
        e.renderer(ChatRenderer.viewerUnaware((source, name, message) -> MM.deserialize(head).append(message)));
    }

    /** /tier set [player] [kit] [LT5..HT1]   /tier clear [player] [kit]   /tier info [player] */
    void tier(Player p, String[] a) {
        String help = "<red>/tier set [player] [kit] [tier] <gray>| /tier clear [player] [kit] | /tier info [player]";
        if (a.length < 2) { msg(p, help); return; }
        Player online = Bukkit.getPlayerExact(a[1]);
        OfflinePlayer op = online != null ? online : Bukkit.getOfflinePlayer(a[1]);
        UUID id = op.getUniqueId();
        List<Map<?, ?>> ts = tierList();
        switch (a[0].toLowerCase()) {
            case "set" -> {
                if (a.length < 4) { msg(p, help); return; }
                String kit = a[2].toLowerCase().replace(".", "");
                if (kitBase(kit) == null) { msg(p, "<red>Kit not found. (/kit list)"); return; }
                int idx = -1;
                List<String> names = new ArrayList<>();
                for (int i = 0; i < ts.size(); i++) {
                    String nm = String.valueOf(ts.get(i).get("name"));
                    names.add(nm);
                    if (nm.equalsIgnoreCase(a[3])) idx = i;
                }
                if (idx < 0) { msg(p, "<red>Tier must be one of: <white>" + String.join(", ", names)); return; }
                data.set("stats." + id + ".tier." + kit, idx);
                data.set("stats." + id + ".name", op.getName() != null ? op.getName() : a[1]);
                saveData();
                tierCacheAt = 0;
                msg(p, "<green>" + a[1] + " is now <white>" + kit.toUpperCase() + " " + tierTagText(idx));
            }
            case "clear" -> {
                if (a.length < 3) { msg(p, help); return; }
                data.set("stats." + id + ".tier." + a[2].toLowerCase().replace(".", ""), null);
                saveData();
                tierCacheAt = 0;
                msg(p, "<green>Manual tier removed. <gray>(the Elo tier applies again)");
            }
            case "info" -> {
                ConfigurationSection es = data.getConfigurationSection("stats." + id + ".elo");
                ConfigurationSection tsx = data.getConfigurationSection("stats." + id + ".tier");
                Set<String> kits = new LinkedHashSet<>();
                if (es != null) kits.addAll(es.getKeys(false));
                if (tsx != null) kits.addAll(tsx.getKeys(false));
                if (kits.isEmpty()) { msg(p, "<yellow>" + a[1] + " has no tiers yet."); return; }
                for (String k : kits) {
                    int t = playerTier(id, k);
                    if (t >= 0) msg(p, "<gray>" + k.toUpperCase() + ": " + tierTagText(t) + " <gray>(" + rating(id, k) + ")"
                            + (data.getInt("stats." + id + ".tier." + k, -1) >= 0 ? " <yellow>[manual]" : ""));
                }
            }
            default -> msg(p, help);
        }
    }

    // ───────────────────────── ranked (Elo per gamemode) ─────────────────────────

    int startRating() { return getConfig().getInt("ranked.start-rating", 1000); }

    int rating(UUID id, String kit) { return data.getInt("stats." + id + ".elo." + kit, startRating()); }

    /** Average of the player's gamemode ratings (shown in tab / scoreboard / global leaderboard). */
    int globalRating(UUID id) {
        ConfigurationSection es = data.getConfigurationSection("stats." + id + ".elo");
        if (es == null || es.getKeys(false).isEmpty()) return startRating();
        int sum = 0, n = 0;
        for (String k : es.getKeys(false)) { sum += es.getInt(k); n++; }
        return sum / n;
    }

    int tierOf(int points) {
        List<Map<?, ?>> rs = getConfig().getMapList("ranked.ranks");
        int tier = 0;
        for (int i = 0; i < rs.size(); i++) if (points >= (int) num(rs.get(i).get("min"))) tier = i;
        return tier;
    }

    String rankName(int points) {
        List<Map<?, ?>> rs = getConfig().getMapList("ranked.ranks");
        if (rs.isEmpty()) return "Unranked";
        return String.valueOf(rs.get(tierOf(points)).get("name"));
    }

    /** {low, high} of the current rank, high = -1 on the last rank. */
    int[] rankRange(int points) {
        List<Map<?, ?>> rs = getConfig().getMapList("ranked.ranks");
        if (rs.isEmpty()) return new int[]{0, -1};
        int t = tierOf(points);
        int lo = (int) num(rs.get(t).get("min"));
        int hi = t + 1 < rs.size() ? (int) num(rs.get(t + 1).get("min")) : -1;
        return new int[]{lo, hi};
    }

    /** The points bar shown above the hotbar. */
    String rankBar(int points, int delta) {
        int[] r = rankRange(points);
        int len = Math.max(5, getConfig().getInt("ranked.bar-length", 20));
        double frac = r[1] < 0 ? 1.0 : Math.max(0, Math.min(1, (points - r[0]) / (double) (r[1] - r[0])));
        int filled = (int) Math.round(frac * len);
        String bar = "<gold>" + getConfig().getString("ranked.bar-filled", "█").repeat(filled)
                + "<dark_gray>" + getConfig().getString("ranked.bar-empty", "░").repeat(len - filled);
        String d = delta > 0 ? "<green>+" + delta : delta < 0 ? "<red>" + delta : "";
        return fmt(getConfig().getString(r[1] < 0 ? "ranked.actionbar-max" : "ranked.actionbar", "{rank} {bar} {points}"),
                "rank", rankName(points), "bar", bar, "points", String.valueOf(points),
                "next", String.valueOf(r[1]), "delta", d);
    }

    /** The bar fills up smoothly from the old points to the new points. */
    void animateRank(Player p, int from, int to) {
        final int steps = 12;
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (!p.isOnline() || i > steps) { cancel(); return; }
                int shown = (int) Math.round(from + (to - from) * (i / (double) steps));
                p.sendActionBar(MM.deserialize(rankBar(shown, to - from)));
                i++;
            }
        }.runTaskTimer(this, 10L, 3L);
    }

    void setRating(Player p, String kit, int v) {
        data.set("stats." + p.getUniqueId() + ".name", p.getName());
        data.set("stats." + p.getUniqueId() + ".elo." + kit, v);
        statsDirty = true;
    }

    /** Elo, tuned to be HARD: small gains, bigger losses, and the higher your rank the harder it gets. */
    void rankedResult(Match m, Player w, Player l) {
        String kit = m.kit;
        int rw = rating(w.getUniqueId(), kit), rl = rating(l.getUniqueId(), kit);
        double baseK = getConfig().getDouble("ranked.base-k", 20);
        double winMult = getConfig().getDouble("ranked.win-multiplier", 0.6);
        double lossMult = getConfig().getDouble("ranked.loss-multiplier", 1.0);
        double gainDecay = getConfig().getDouble("ranked.gain-decay-per-rank", 0.25);
        double lossGrowth = getConfig().getDouble("ranked.loss-growth-per-rank", 0.10);
        int floor = getConfig().getInt("ranked.min-rating", 0);
        double ew = 1.0 / (1.0 + Math.pow(10, (rl - rw) / 400.0));           // winner's expected score
        double gain = baseK * (1 - ew) * winMult / (1 + tierOf(rw) * gainDecay);
        double loss = baseK * (1.0 - ew) * lossMult * (1 + tierOf(rl) * lossGrowth);   // loser's expected score = 1 - ew
        int newW = rw + Math.max(1, (int) Math.round(gain));
        int newL = Math.max(floor, rl - Math.max(1, (int) Math.round(loss)));
        setRating(w, kit, newW);
        setRating(l, kit, newL);
        announceRank(w, kit, rw, newW);
        announceRank(l, kit, rl, newL);
    }

    void announceRank(Player p, String kit, int from, int to) {
        if (!p.isOnline()) return;
        int d = to - from;
        send(p, d >= 0 ? "ranked-win" : "ranked-loss", "delta", String.valueOf(Math.abs(d)),
                "points", String.valueOf(to), "rank", rankName(to), "kit", kit);
        int t0 = tierOf(from), t1 = tierOf(to);
        if (t1 != t0) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline()) return;
                titleK(p, t1 > t0 ? "rank-up" : "rank-down", "rank", rankName(to));
                sound(p, t1 > t0 ? "rank-up" : "rank-down");
            }, 30L);
        }
        animateRank(p, from, to);
    }

    // ── ranked queue (diamond sword) ──

    void toggleRanked(Player p) {
        UUID id = p.getUniqueId();
        if (queue.containsKey(id)) {                       // already searching: leave
            queue.remove(id);
            rankedSince.remove(id);
            send(p, "queue-left");
            update(p);
            return;
        }
        if (parties.containsKey(id)) { send(p, "queue-party-block"); return; }
        openKitMenu(p, "ranked", null);
    }

    void joinRanked(Player p, String kit) {
        UUID id = p.getUniqueId();
        if (busy(p)) return;
        if (parties.containsKey(id)) { send(p, "queue-party-block"); return; }
        queue.put(id, kit);
        rankedSince.put(id, System.currentTimeMillis());
        send(p, "ranked-searching", "kit", kit);
        update(p);
        tryRanked();
    }

    /** Rating window that widens the longer you wait. */
    int rankedWindow(UUID id) {
        Long since = rankedSince.get(id);
        long waited = since == null ? 0 : (System.currentTimeMillis() - since) / 1000;
        int step = Math.max(1, getConfig().getInt("ranked.window-step-seconds", 10));
        int w = getConfig().getInt("ranked.search-window", 150) + (int) (waited / step) * getConfig().getInt("ranked.window-growth", 25);
        return Math.min(w, getConfig().getInt("ranked.max-window", 600));
    }

    void tryRanked() {
        List<UUID> ids = new ArrayList<>();
        for (UUID id : queue.keySet()) if (rankedSince.containsKey(id)) ids.add(id);
        if (ids.size() < 2) return;
        Set<UUID> used = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            UUID a = ids.get(i);
            if (used.contains(a) || !queue.containsKey(a)) continue;
            Player pa = Bukkit.getPlayer(a);
            if (pa == null || busy(pa)) continue;
            String kit = queue.get(a);
            for (int j = i + 1; j < ids.size(); j++) {
                UUID b = ids.get(j);
                if (used.contains(b) || !queue.containsKey(b) || !kit.equals(queue.get(b))) continue;
                Player pb = Bukkit.getPlayer(b);
                if (pb == null || busy(pb)) continue;
                int ra = rating(a, kit), rb = rating(b, kit);
                int diff = Math.abs(ra - rb);
                if (diff > rankedWindow(a) || diff > rankedWindow(b)) continue;
                if (freeArena(false, kit) == null) return;           // keep them queued until an arena is free
                used.add(a); used.add(b);
                queue.remove(a); queue.remove(b);
                rankedSince.remove(a); rankedSince.remove(b);
                send(pa, "queue-found"); sound(pa, "queue-found");
                send(pb, "queue-found"); sound(pb, "queue-found");
                send(pa, "ranked-vs", "opponent", pb.getName(), "rank", rankName(rb), "points", String.valueOf(rb));
                send(pb, "ranked-vs", "opponent", pa.getName(), "rank", rankName(ra), "points", String.valueOf(ra));
                Match m = new Match(new ArrayList<>(List.of(pa)), new ArrayList<>(List.of(pb)), kit, false);
                m.ranked = true;
                launch(m);
                break;
            }
        }
    }

    void stats(Player p, String[] a) {
        Player tg = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : p;
        if (tg == null) { send(p, "player-not-found"); return; }
        UUID id = tg.getUniqueId();
        int k = stat(id, "kills"), d = stat(id, "deaths"), g = globalRating(id);
        send(p, "stats-line", "player", tg.getName(), "rank", rankName(g), "points", String.valueOf(g),
                "wins", String.valueOf(stat(id, "wins")), "losses", String.valueOf(stat(id, "losses")),
                "kills", String.valueOf(k), "deaths", String.valueOf(d),
                "kd", String.format("%.2f", d == 0 ? (double) k : (double) k / d));
        ConfigurationSection es = data.getConfigurationSection("stats." + id + ".elo");
        if (es != null) {
            for (String kit : es.getKeys(false)) {
                int v = es.getInt(kit);
                send(p, "stats-kit-line", "kit", kit, "points", String.valueOf(v), "rank", rankName(v));
            }
        }
    }

    Player killerOf(EntityDamageEvent e, Player victim) {
        Player k = null;
        if (e instanceof EntityDamageByEntityEvent ed) {
            if (ed.getDamager() instanceof Player pl) k = pl;
            else if (ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player ps) k = ps;
        }
        if (k == null) {
            UUID h = lastHitter.get(victim.getUniqueId());
            Long at = lastHitAt.get(victim.getUniqueId());
            if (h != null && at != null && System.currentTimeMillis() - at < 15_000) k = Bukkit.getPlayer(h);
        }
        return (k == null || k.equals(victim)) ? null : k;
    }

    /** PvPHQ style: "<player> died" above the hotbar for everyone in the match. */
    /** MCPVP style: "<player> was slain by <killer> (4.5♥)" in chat + above the hotbar. */
    void announceDeath(Match m, Player victim, Player killer) {
        String kn = killer != null ? killer.getName() : "";
        String hp = killer != null ? String.format("%.1f", killer.getHealth() / 2.0) : "";
        Component bar = MM.deserialize(t(killer != null ? "death-actionbar-killer" : "death-actionbar",
                "player", victim.getName(), "killer", kn));
        Component chat = MM.deserialize(t(killer != null ? "death-chat-killer" : "death-chat",
                "player", victim.getName(), "killer", kn, "hp", hp));
        List<Player> all = new ArrayList<>(m.all());
        for (UUID id : m.specs) { Player sp = Bukkit.getPlayer(id); if (sp != null) all.add(sp); }
        for (Player x : all) {
            if (!x.isOnline()) continue;
            x.sendActionBar(bar);
            x.sendMessage(chat);
            sound(x, "death");
        }
    }

    String normStat(String s) {
        return switch (s.toLowerCase()) {
            case "wins", "win" -> "wins";
            case "kills", "kill" -> "kills";
            case "deaths", "death" -> "deaths";
            case "lose", "loses", "losses", "loss" -> "losses";
            case "points", "rank", "ranked", "elo", "rating" -> "points";
            default -> null;
        };
    }

    double coord(String s, double base) {
        if (s.startsWith("~")) {
            String r = s.substring(1);
            return r.isEmpty() ? base : base + Double.parseDouble(r);
        }
        return Double.parseDouble(s);
    }

    List<Map.Entry<String, Integer>> top(String stat, String kit, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        ConfigurationSection ss = data.getConfigurationSection("stats");
        if (ss != null) {
            for (String id : ss.getKeys(false)) {
                int v;
                if (stat.equals("points")) {
                    if (kit != null) {
                        if (!ss.isSet(id + ".elo." + kit)) continue;
                        v = ss.getInt(id + ".elo." + kit);
                    } else {
                        if (!ss.isConfigurationSection(id + ".elo")) continue;
                        try { v = globalRating(UUID.fromString(id)); } catch (IllegalArgumentException ex) { continue; }
                    }
                } else {
                    v = ss.getInt(id + "." + stat, 0);
                    if (v <= 0) continue;
                }
                list.add(Map.entry(ss.getString(id + ".name", "?"), v));
            }
        }
        list.sort((x, y) -> y.getValue() - x.getValue());
        return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
    }

    Component boardText(String stat) {
        String kit = null;
        if (stat.startsWith("points:")) { kit = stat.substring(7); stat = "points"; }
        int size = Math.max(1, getConfig().getInt("leaderboards.size", 10));
        List<String> lines = new ArrayList<>();
        String titleKey = kit != null ? "points-kit" : stat;
        lines.add(fmt(getConfig().getString("leaderboards.titles." + titleKey, "<gold><bold>TOP " + stat.toUpperCase()),
                "kit", kit == null ? "" : kit.toUpperCase()));
        lines.add("");
        List<Map.Entry<String, Integer>> t = top(stat, kit, size);
        if (t.isEmpty()) lines.add(getConfig().getString("leaderboards.empty", "<gray>No data yet"));
        int rank = 1;
        for (Map.Entry<String, Integer> en : t) {
            String val = stat.equals("points") ? en.getValue() + " <gray>" + rankName(en.getValue()) : String.valueOf(en.getValue());
            lines.add(fmt(getConfig().getString("leaderboards.line", "<gold>#{rank} <white>{player} <gray>- <yellow>{value}"),
                    "rank", String.valueOf(rank++), "player", en.getKey(), "value", val));
        }
        lines.add("");
        lines.add(fmt(getConfig().getString("leaderboards.footer", "<gray>Updates every {seconds}s"),
                "seconds", String.valueOf(getConfig().getInt("leaderboards.update-seconds", 30))));
        return MM.deserialize(String.join("<newline>", lines));
    }

    TextDisplay findBoard(Location loc, String id) {
        TextDisplay first = null;
        for (Entity en : loc.getWorld().getNearbyEntities(loc, 3, 3, 3)) {
            if (en instanceof TextDisplay td
                    && id.equals(td.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING))) {
                if (first == null) first = td; else td.remove();     // remove duplicates
            }
        }
        return first;
    }

    void updateBoards() {
        ConfigurationSection bs = data.getConfigurationSection("boards");
        if (bs == null) return;
        for (String id : bs.getKeys(false)) {
            Location loc = getLoc("boards." + id + ".loc");
            String stat = data.getString("boards." + id + ".stat", "wins");
            if (loc == null || loc.getWorld() == null) continue;
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            TextDisplay td = findBoard(loc, id);
            if (td == null) {
                td = loc.getWorld().spawn(loc, TextDisplay.class, d -> {
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setAlignment(TextDisplay.TextAlignment.CENTER);
                    d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                    d.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, id);
                });
            }
            td.text(boardText(stat));
        }
    }

    String nextBoardId() {
        int i = 1;
        while (data.isConfigurationSection("boards." + i)) i++;
        return String.valueOf(i);
    }

    String nearestBoard(Location from) {
        ConfigurationSection bs = data.getConfigurationSection("boards");
        if (bs == null) return null;
        String best = null;
        double bd = 64;
        for (String id : bs.getKeys(false)) {
            Location l = getLoc("boards." + id + ".loc");
            if (l == null || !l.getWorld().equals(from.getWorld())) continue;
            double d = l.distanceSquared(from);
            if (d <= bd) { bd = d; best = id; }
        }
        return best;
    }

    /** /sb spawn ~ ~ ~ wins|kills|deaths|lose|points   /sb remove [id]   /sb list */
    void sb(Player p, String[] a) {
        String help = "<red>/sb spawn [x] [y] [z] [wins|kills|deaths|lose|points] <gray>| /sb remove [id] | /sb list";
        if (a.length == 0) { msg(p, help); return; }
        switch (a[0].toLowerCase()) {
            case "spawn" -> {
                Location base = p.getLocation();
                double x = base.getX(), y = base.getY(), z = base.getZ();
                String statArg = null, kitArg = null;
                try {
                    if (a.length == 2 || a.length == 3) {                                  // /sb spawn wins  |  /sb spawn points sumo
                        statArg = a[1]; y += 2;
                        if (a.length == 3) kitArg = a[2];
                    } else if (a.length >= 5) {
                        x = coord(a[1], base.getX()); y = coord(a[2], base.getY()); z = coord(a[3], base.getZ());
                        statArg = a[4];
                        if (a.length >= 6) kitArg = a[5];
                    } else { msg(p, help); return; }
                } catch (NumberFormatException ex) { msg(p, "<red>Bad coordinates. Use numbers or ~"); return; }
                String stat = normStat(statArg);
                if (stat == null) { msg(p, "<red>Stat must be: wins, kills, deaths, lose or points"); return; }
                if (stat.equals("points") && kitArg != null) stat = "points:" + kitArg.toLowerCase();
                String id = nextBoardId();
                setLoc("boards." + id + ".loc", new Location(base.getWorld(), x, y, z));
                data.set("boards." + id + ".stat", stat);
                saveData();
                updateBoards();
                msg(p, "<green>Leaderboard <white>#" + id + " <green>(" + stat + ") spawned. <gray>/sb remove " + id + " to delete it.");
            }
            case "remove" -> {
                String id = a.length > 1 ? a[1] : nearestBoard(p.getLocation());
                if (id == null || !data.isConfigurationSection("boards." + id)) {
                    msg(p, "<red>Leaderboard not found. Stand within 8 blocks of it, or use /sb list.");
                    return;
                }
                Location loc = getLoc("boards." + id + ".loc");
                if (loc != null && loc.getWorld() != null) {
                    TextDisplay td = findBoard(loc, id);
                    if (td != null) td.remove();
                }
                data.set("boards." + id, null);
                saveData();
                msg(p, "<green>Leaderboard #" + id + " removed.");
            }
            case "list" -> {
                ConfigurationSection bs = data.getConfigurationSection("boards");
                if (bs == null || bs.getKeys(false).isEmpty()) { msg(p, "<yellow>No leaderboards yet. <gray>/sb spawn ~ ~ ~ wins"); return; }
                for (String id : bs.getKeys(false)) {
                    Location l = getLoc("boards." + id + ".loc");
                    msg(p, "<yellow>#" + id + " <white>" + data.getString("boards." + id + ".stat") + " <gray>"
                            + (l == null ? "(world not loaded)" : l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
                }
            }
            default -> msg(p, help);
        }
    }

    // ───────────────────────── golden head (UHC) ─────────────────────────

    ItemStack goldenHead(int amount) {
        Material mat = Material.matchMaterial(getConfig().getString("uhc.golden-head.material", "PLAYER_HEAD"));
        if (mat == null) mat = Material.PLAYER_HEAD;
        ItemStack it = named(mat, getConfig().getString("uhc.golden-head.name", "<gold><bold>Golden Head"),
                getConfig().getStringList("uhc.golden-head.lore").toArray(new String[0]));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(headKey, PersistentDataType.STRING, "1");
        it.setItemMeta(meta);
        it.setAmount(Math.max(1, Math.min(64, amount)));
        return it;
    }

    void eatGoldenHead(Player p) {
        Match m = matches.get(p.getUniqueId());
        if (m == null || m.state == State.ENDED || !m.alive.contains(p.getUniqueId())) return;
        int regenSec = getConfig().getInt("uhc.golden-head.regen-seconds", 9);
        int regenLevel = Math.max(1, getConfig().getInt("uhc.golden-head.regen-level", 2));
        int hearts = getConfig().getInt("uhc.golden-head.absorption-hearts", 2);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, regenSec * 20, regenLevel - 1));
        if (hearts > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 120 * 20, Math.max(0, hearts / 2 - 1)));
        ItemStack hand = p.getInventory().getItemInMainHand().clone();
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        sound(p, "golden-head");
    }

    /** UHC kits (/kit setuhc): no natural regeneration. Regen potions (golden head, gapples) still work. */
    @EventHandler
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = matches.get(p.getUniqueId());
        if (m == null || !data.getBoolean("kits." + m.kit + ".uhc", false)) return;
        if (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) e.setCancelled(true);
    }

    // ───────────────────────── post-match items + party team battle ─────────────────────────

    void requeue(Player p) {
        UUID id = p.getUniqueId();
        Match m = matches.get(id);
        if (m == null || m.state != State.ENDED) return;
        String kit = m.kit;
        boolean rk = m.ranked;
        leave(p);                                   // the match is over: no forfeit, just go to the lobby
        if (parties.containsKey(id)) { send(p, "queue-party-block"); return; }
        if (rk) joinRanked(p, kit); else joinQueue(p, kit);
    }

    void startPartyTeams(Player p, String kit) {
        Party pt = partyOf(p);
        if (pt == null || !pt.leader.equals(p.getUniqueId())) { send(p, "party-leader-only"); return; }
        List<Player> all = online(pt);
        if (all.size() < 2) { send(p, "party-ffa-min"); return; }
        if (anyBusy(all)) { send(p, "party-ffa-busy"); return; }
        Collections.shuffle(all);
        List<Player> red = new ArrayList<>(), blue = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) (i % 2 == 0 ? red : blue).add(all.get(i));
        Match m = new Match(red, blue, kit, false);
        m.teamBattle = true;
        launch(m);
    }

    // ───────────────────────── arena door (wall) ─────────────────────────

    boolean isWand(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.STRING);
    }

    void handleWand(Player p, String arena, Block b, boolean first) {
        String key = first ? "pos1" : "pos2";
        setLoc("arenas." + arena + ".door." + key, b.getLocation());
        saveData();
        msg(p, "<green>Door " + key + " set <gray>(" + b.getX() + " " + b.getY() + " " + b.getZ() + ")");
        Location a = getLoc("arenas." + arena + ".door.pos1"), c = getLoc("arenas." + arena + ".door.pos2");
        if (a != null && c != null && a.getWorld() != null && a.getWorld().equals(c.getWorld())) {
            int vol = (int) BoundingBox.of(a.getBlock(), c.getBlock()).getVolume();
            msg(p, "<yellow>Door wall: <white>" + vol + " blocks <gray>- it closes when a match starts and opens at FIGHT!");
        }
    }

    List<Block> doorBlocks(String arena) {
        Location a = getLoc("arenas." + arena + ".door.pos1"), b = getLoc("arenas." + arena + ".door.pos2");
        if (a == null || b == null || a.getWorld() == null || !a.getWorld().equals(b.getWorld())) return List.of();
        BoundingBox bx = BoundingBox.of(a.getBlock(), b.getBlock());
        if (bx.getVolume() > 8000) return List.of();
        List<Block> out = new ArrayList<>();
        World w = a.getWorld();
        for (int x = (int) bx.getMinX(); x < (int) bx.getMaxX(); x++)
            for (int y = (int) bx.getMinY(); y < (int) bx.getMaxY(); y++)
                for (int z = (int) bx.getMinZ(); z < (int) bx.getMaxZ(); z++)
                    out.add(w.getBlockAt(x, y, z));
        return out;
    }

    void closeDoor(Match m) {
        if (!getConfig().getBoolean("arena-door.enabled", true)) return;
        List<Block> bl = doorBlocks(m.arena);
        if (bl.isEmpty()) return;
        Material mat = Material.matchMaterial(getConfig().getString("arena-door.material", "IRON_BARS"));
        if (mat == null || !mat.isBlock()) mat = Material.IRON_BARS;
        for (Block b : bl) {
            m.doorOrig.put(b, b.getBlockData());
            if (m.snap == null && m.box != null && inBox(m, b)) m.changes.putIfAbsent(b, b.getBlockData());
            b.setType(mat, false);
        }
        m.doorBlocks = bl;
        Block first = bl.get(0), last = bl.get(bl.size() - 1);
        m.doorCenter = new Location(first.getWorld(), (first.getX() + last.getX()) / 2.0 + 0.5,
                (first.getY() + last.getY()) / 2.0 + 0.5, (first.getZ() + last.getZ()) / 2.0 + 0.5);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player x : m.all()) if (x.isOnline()) soundAt(x, m.doorCenter, "door-close");
        }, 10L);
    }

    void openDoor(Match m) {
        if (m.doorBlocks == null || m.doorBlocks.isEmpty()) return;
        for (Block b : m.doorBlocks) b.setType(Material.AIR, false);
        for (Player x : m.all()) if (x.isOnline()) soundAt(x, m.doorCenter, "door-open");
        for (UUID id : m.specs) { Player sp = Bukkit.getPlayer(id); if (sp != null) soundAt(sp, m.doorCenter, "door-open"); }
    }

    /** Arena blocks are unbreakable, except blocks placed in the match and block types added with /arena attribute. */
    boolean canBreak(Player p, Block b) {
        if (!canBuild(p, b)) return false;
        Match m = matches.get(p.getUniqueId());
        if (m == null || admin(p)) return true;
        if (m.box == null || !inBox(m, b)) return true;              // outside the arena box: world rules already allowed it
        if (m.placed.contains(b)) return true;                        // placed during this match
        if (data.getStringList("arenas." + m.arena + ".breakable").contains(b.getType().name())) return true;
        p.sendActionBar(MM.deserialize(t("break-denied-arena")));
        return false;
    }

    // ───────────────────────── kit editor menu ─────────────────────────

    int guiSlot(int invSlot) { return invSlot < 9 ? 27 + invSlot : invSlot - 9; }   // hotbar is the bottom row

    ItemStack[] layoutFor(Player p, String kit, boolean defaults) {
        ItemStack[] base = kitBase(kit);
        ItemStack[] out = new ItemStack[36];
        for (int i = 0; i < 36 && i < base.length; i++) out[i] = base[i] == null ? null : base[i].clone();
        if (!defaults) {
            List<?> lay = data.getList("layouts." + p.getUniqueId() + "." + kit);
            if (lay != null && lay.size() == 36) {
                ItemStack[] ls = new ItemStack[36];
                for (int i = 0; i < 36; i++) ls[i] = lay.get(i) instanceof ItemStack li ? li : null;
                if (sameMulti(merge(ls, 0, 36), merge(base, 0, 36))) return ls;
            }
        }
        return out;
    }

    void fillEditor(Player p, Inventory inv, String kit, boolean defaults) {
        ItemStack g = glass();
        for (int i = 0; i < 54; i++) inv.setItem(i, null);
        for (int i = 36; i < 54; i++) inv.setItem(i, g);
        ItemStack[] cur = layoutFor(p, kit, defaults);
        for (int s = 0; s < 36; s++) inv.setItem(guiSlot(s), cur[s]);
        ItemStack[] base = kitBase(kit);
        int[] armorGui = {40, 39, 38, 37};                              // boots, leggings, chestplate, helmet (preview)
        for (int i = 0; i < 4 && base != null && base.length > 36 + i; i++) {
            if (base[36 + i] != null) inv.setItem(armorGui[i], base[36 + i]);
        }
        if (base != null && base.length > 40 && base[40] != null) inv.setItem(42, base[40]);
        inv.setItem(47, named(Material.LIME_CONCRETE, getConfig().getString("gui.editor.save", "<green><bold>SAVE"),
                getConfig().getString("gui.editor.save-lore", "<gray>Save this layout")));
        inv.setItem(49, named(Material.YELLOW_CONCRETE, getConfig().getString("gui.editor.reset", "<yellow><bold>RESET"),
                getConfig().getString("gui.editor.reset-lore", "<gray>Back to the default layout")));
        inv.setItem(51, named(Material.RED_CONCRETE, getConfig().getString("gui.editor.cancel", "<red><bold>CANCEL"),
                getConfig().getString("gui.editor.cancel-lore", "<gray>Close without saving")));
    }

    void startEditor(Player p, String kit) {
        if (busy(p)) return;
        ItemStack[] base = kitBase(kit);
        if (base == null || base.length < 36) { send(p, "kit-not-found"); return; }
        queue.remove(p.getUniqueId());
        KitEditorMenu h = new KitEditorMenu(kit);
        Inventory inv = Bukkit.createInventory(h, 54,
                MM.deserialize(fmt(getConfig().getString("gui.titles.kit-editor", "<gold><bold>Editing {kit}"), "kit", kit)));
        h.inv = inv;
        fillEditor(p, inv, kit, false);
        p.openInventory(inv);
        send(p, "editor-start", "kit", kit);
    }

    void editorSaveGui(Player p, KitEditorMenu h, Inventory top) {
        ItemStack[] base = kitBase(h.kit);
        if (base == null || base.length < 36) { send(p, "kit-not-found"); return; }
        ItemStack[] cur = new ItemStack[36];
        for (int s = 0; s < 36; s++) cur[s] = top.getItem(guiSlot(s));
        if (!sameMulti(merge(cur, 0, 36), merge(base, 0, 36))) { send(p, "editor-bad-layout"); return; }
        data.set("layouts." + p.getUniqueId() + "." + h.kit, new ArrayList<>(Arrays.asList(cur)));
        saveData();
        p.closeInventory();
        sound(p, "click");
        send(p, "editor-saved", "kit", h.kit);
    }

    List<String> kitNames() {
        List<String> kits = new ArrayList<>();
        ConfigurationSection ks = data.getConfigurationSection("kits");
        if (ks != null) for (String k : ks.getKeys(false)) if (data.getList("kits." + k + ".items") != null) kits.add(k);
        return kits;
    }

    int menuRows() {
        int r = getConfig().getInt("gui.menu-rows", 0);
        return r >= 1 && r <= 6 ? r : 0;
    }

    int autoRows(List<String> kits) {
        if (menuRows() > 0) return menuRows();
        int rows = Math.min(6, (kits.size() + 6) / 7 + 2);
        for (String k : kits) {
            int sl = data.getInt("kits." + k + ".slot", -1);
            if (sl >= 0) rows = Math.max(rows, Math.min(6, sl / 9 + 1));
        }
        return Math.max(3, rows);
    }

    /** slot -> kit. Kits with a custom /kit slot go there, the others fill the free spots. */
    Map<Integer, String> layout(List<String> kits, int rows) {
        Map<Integer, String> out = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>();
        for (String k : kits) {
            int sl = data.getInt("kits." + k + ".slot", -1);
            if (sl >= 0 && sl < rows * 9 && !out.containsKey(sl)) out.put(sl, k); else rest.add(k);
        }
        if (out.isEmpty()) {                       // default: centered rows, glass around
            int per = 7, idx = 0, kitRows = (rest.size() + per - 1) / per;
            for (int r = 0; r < kitRows && 1 + r < rows; r++) {
                int n = Math.min(per, rest.size() - idx);
                int startCol = 1 + (per - n) / 2;
                for (int c = 0; c < n; c++) out.put((1 + r) * 9 + startCol + c, rest.get(idx++));
            }
            return out;
        }
        for (String k : rest) {
            int found = -1;
            for (int sl = 0; sl < rows * 9 && found < 0; sl++) {
                int row = sl / 9, col = sl % 9;
                boolean inner = row >= 1 && row <= rows - 2 && col >= 1 && col <= 7;
                if (inner && !out.containsKey(sl)) found = sl;
            }
            for (int sl = 0; sl < rows * 9 && found < 0; sl++) if (!out.containsKey(sl)) found = sl;
            if (found >= 0) out.put(found, k);
        }
        return out;
    }

    void openKitMenu(Player p, String mode, UUID target) {
        List<String> kits = kitNames();
        if (kits.isEmpty()) { send(p, "no-kits"); return; }
        int rows = autoRows(kits);
        KitMenu h = new KitMenu(mode, target);
        Inventory inv = Bukkit.createInventory(h, rows * 9,
                MM.deserialize(getConfig().getString("gui.titles." + mode, "<dark_gray>Select a kit")));
        h.inv = inv;
        ItemStack g = glass();
        for (int i = 0; i < rows * 9; i++) inv.setItem(i, g);   // glass on all sides
        for (Map.Entry<Integer, String> en : layout(kits, rows).entrySet()) {
            inv.setItem(en.getKey(), kitIcon(en.getValue(), mode));
            h.slots.put(en.getKey(), en.getValue());
        }
        p.openInventory(inv);
    }

    String kitTag(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(kitKey, PersistentDataType.STRING);
    }

    /** /kit slots - drag the kits where you want them, close the menu to save. */
    void openSlotEditor(Player p) {
        List<String> kits = kitNames();
        if (kits.isEmpty()) { send(p, "no-kits"); return; }
        int rows = menuRows() > 0 ? menuRows() : 6;
        SlotEditor h = new SlotEditor();
        Inventory inv = Bukkit.createInventory(h, rows * 9, MM.deserialize("<gold><bold>Drag the kits, then close"));
        h.inv = inv;
        for (Map.Entry<Integer, String> en : layout(kits, rows).entrySet()) {
            inv.setItem(en.getKey(), kitIcon(en.getValue(), "slots"));
            h.shown.add(en.getValue());
        }
        p.openInventory(inv);
        msg(p, "<yellow>Drag each kit to the slot you want, then close the menu to save. <gray>(slot 0 = top-left)");
    }

    void kitSlot(Player p, String kitArg, String slotArg) {
        String kit = kitArg.toLowerCase().replace(".", "");
        if (kitBase(kit) == null) { msg(p, "<red>Kit not found. (/kit list)"); return; }
        int max = menuRows() > 0 ? menuRows() * 9 : 54;
        int slot;
        try { slot = Integer.parseInt(slotArg); }
        catch (NumberFormatException ex) { msg(p, "<red>Slot must be a number (0-" + (max - 1) + ")."); return; }
        if (slot < 0 || slot >= max) { msg(p, "<red>Slot must be between 0 and " + (max - 1) + "."); return; }
        for (String k : kitNames()) {
            if (!k.equals(kit) && data.getInt("kits." + k + ".slot", -1) == slot) data.set("kits." + k + ".slot", null);
        }
        data.set("kits." + kit + ".slot", slot);
        saveData();
        msg(p, "<green>Kit <white>" + kit + " <green>is now in menu slot <white>" + slot + " <gray>(0 = top-left, row 2 starts at 9)");
    }

    void choose(Player p, KitMenu h, String kit) {
        sound(p, "click");
        switch (h.mode) {
            case "duel" -> {
                Player tg = Bukkit.getPlayer(h.target);
                if (tg == null) { send(p, "player-offline"); return; }
                sendRequest(p, tg, kit);
            }
            case "queue" -> joinQueue(p, kit);
            case "ranked" -> joinRanked(p, kit);
            case "ffa" -> startPartyFfa(p, kit);
            case "teams" -> startPartyTeams(p, kit);
            case "editor" -> startEditor(p, kit);
            default -> { }
        }
    }

    // ───────────────────────── queue ─────────────────────────

    void toggleQueue(Player p) {
        UUID id = p.getUniqueId();
        if (queue.remove(id) != null) { send(p, "queue-left"); refreshLobby(p); update(p); return; }
        if (parties.containsKey(id)) { send(p, "queue-party-block"); return; }
        openKitMenu(p, "queue", null);
    }

    void joinQueue(Player p, String kit) {
        UUID id = p.getUniqueId();
        if (busy(p)) return;
        if (parties.containsKey(id)) { send(p, "queue-party-block"); return; }
        rankedSince.remove(id);
        UUID found = null;
        for (Map.Entry<UUID, String> en : queue.entrySet()) {
            if (!en.getKey().equals(id) && en.getValue().equals(kit) && !rankedSince.containsKey(en.getKey())) {
                Player o = Bukkit.getPlayer(en.getKey());
                if (o != null && !busy(o)) { found = en.getKey(); break; }
            }
        }
        if (found != null) {
            if (freeArena(false, kit) == null) { send(p, "queue-no-arena"); return; }
            Player o = Bukkit.getPlayer(found);
            queue.remove(found);
            queue.remove(id);
            send(o, "queue-found"); sound(o, "queue-found");
            send(p, "queue-found"); sound(p, "queue-found");
            start(List.of(o), List.of(p), kit);
            return;
        }
        queue.put(id, kit);
        send(p, "queue-searching", "kit", kit);
        refreshLobby(p);
        update(p);
    }

    // ───────────────────────── arena regen ─────────────────────────

    void snapshot(Match m, ConfigurationSection s) {
        Location c1 = getLoc("arenas." + m.arena + ".corner1"), c2 = getLoc("arenas." + m.arena + ".corner2");
        if (c1 == null || c2 == null || c1.getWorld() == null || !c1.getWorld().equals(c2.getWorld())) return;
        BoundingBox box = BoundingBox.of(c1.getBlock(), c2.getBlock());
        if (box.getVolume() > getConfig().getInt("general.max-arena-volume", 300000)) {
            getLogger().info("Arena " + m.arena + " is bigger than general.max-arena-volume: using tracked regen (only changed blocks are restored).");
            m.box = box; m.world = c1.getWorld();      // building stays allowed inside the region
            return;
        }
        World w = c1.getWorld();
        BlockData[] snap = new BlockData[(int) box.getVolume()];
        int i = 0;
        for (int x = (int) box.getMinX(); x < (int) box.getMaxX(); x++)
            for (int y = (int) box.getMinY(); y < (int) box.getMaxY(); y++)
                for (int z = (int) box.getMinZ(); z < (int) box.getMaxZ(); z++)
                    snap[i++] = w.getBlockAt(x, y, z).getBlockData();
        m.box = box; m.world = w; m.snap = snap;
    }

    void restore(Match m) {
        for (Map.Entry<Block, BlockData> en : m.doorOrig.entrySet()) en.getKey().setBlockData(en.getValue(), false);
        m.doorOrig.clear();
        if (m.world == null || m.box == null) return;
        if (m.snap != null) {                                   // small arena: full snapshot
            int i = 0;
            for (int x = (int) m.box.getMinX(); x < (int) m.box.getMaxX(); x++)
                for (int y = (int) m.box.getMinY(); y < (int) m.box.getMaxY(); y++)
                    for (int z = (int) m.box.getMinZ(); z < (int) m.box.getMaxZ(); z++) {
                        Block b = m.world.getBlockAt(x, y, z);
                        BlockData d = m.snap[i++];
                        if (!b.getBlockData().equals(d)) b.setBlockData(d, false);
                    }
            m.snap = null;
        } else {                                                // big arena: restore only what changed
            for (Map.Entry<Block, BlockData> en : m.changes.entrySet()) en.getKey().setBlockData(en.getValue(), false);
            m.changes.clear();
        }
        for (Entity e : m.world.getNearbyEntities(m.box, en -> en instanceof Item || en instanceof Projectile)) {
            e.remove();
        }
    }

    boolean inBox(Match m, Block b) {
        return m.box != null && m.box.contains(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
    }

    boolean inAnyRegion(Block b) {
        for (Match m : activeMatches) if (inBox(m, b)) return true;
        return false;
    }

    // ───────────────────────── match flow ─────────────────────────

    void start(List<Player> t1, List<Player> t2, String kit) {
        launch(new Match(new ArrayList<>(t1), new ArrayList<>(t2), kit, false));
    }

    void startFfa(List<Player> all, String kit) {
        launch(new Match(new ArrayList<>(all), new ArrayList<>(), kit, true));
    }

    void launch(Match m) {
        List<Player> everyone = m.all();
        String arena = freeArena(m.ffa, m.kit);
        if (arena == null) {
            for (Player p : everyone) send(p, "no-free-arena");
            return;
        }
        m.arena = arena;
        ConfigurationSection s = Objects.requireNonNull(data.getConfigurationSection("arenas." + arena));
        busyArenas.add(arena);
        activeMatches.add(m);
        for (Player p : everyone) {
            matches.put(p.getUniqueId(), m);
            queue.remove(p.getUniqueId());
            editing.remove(p.getUniqueId());
            lastHitter.remove(p.getUniqueId());
            lastHitAt.remove(p.getUniqueId());
        }
        snapshot(m, s);
        closeDoor(m);                                  // arena door (wall) closes

        if (m.ffa) {
            List<Location> spots = ffaSpawns(arena);                 // /arena ffa [name]
            if (spots.size() < 2) {
                spots = new ArrayList<>();
                spots.add(getLoc("arenas." + arena + ".spawn1"));
                spots.add(getLoc("arenas." + arena + ".spawn2"));
            }
            Collections.shuffle(spots);
            int i = 0;
            for (Player p : m.t1) prepare(p, m.kit, spots.get(i++ % spots.size()));
        } else {
            for (Player p : m.t1) prepare(p, m.kit, getLoc("arenas." + arena + ".spawn1"));
            for (Player p : m.t2) prepare(p, m.kit, getLoc("arenas." + arena + ".spawn2"));
        }

        int secs = Math.max(1, getConfig().getInt("general.countdown-seconds", 5));
        new BukkitRunnable() {
            int c = secs;
            @Override public void run() {
                if (m.state == State.ENDED) { cancel(); return; }
                if (c == 0) {
                    m.state = State.ACTIVE;
                    m.start = System.currentTimeMillis();
                    openDoor(m);                               // the wall opens: FIGHT!
                    for (Player p : m.all()) { titleK(p, "fight"); sound(p, "fight"); }
                    cancel();
                    return;
                }
                for (Player p : m.all()) {
                    titleK(p, m.ffa ? "countdown-ffa" : m.teamBattle ? "countdown-teams" : "countdown",
                            "count", String.valueOf(c), "opponent", m.ffa ? "" : label(m.enemy(p)),
                            "team", m.teamBattle ? (m.inTeam1(p) ? "<red><bold>RED" : "<blue><bold>BLUE") : "");
                    sound(p, "countdown");
                }
                c--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    void prepare(Player p, String kit, Location l) {
        reset(p, GameMode.SURVIVAL);
        p.teleport(l);
        giveKit(p, kit);
        update(p);
    }

    void eliminate(Match m, Player p, boolean quit) {
        if (m.state == State.ENDED) return;
        if (!m.alive.remove(p.getUniqueId())) return;

        if (m.ffa) {
            if (m.alive.size() <= 1) {
                List<Player> win = new ArrayList<>(), lose = new ArrayList<>();
                for (Player x : m.all()) {
                    if (m.alive.contains(x.getUniqueId())) win.add(x); else lose.add(x);
                }
                end(m, win, lose);
            } else {
                for (Player x : m.all()) send(x, "eliminated-ffa", "player", p.getName(), "alive", String.valueOf(m.alive.size()));
                if (!quit) {
                    p.setGameMode(GameMode.SPECTATOR);
                    titleK(p, "eliminated", "player", p.getName());
                    sound(p, "eliminated");
                }
            }
            return;
        }

        List<Player> mine = m.team(p);
        boolean teamDead = true;
        for (Player x : mine) if (m.alive.contains(x.getUniqueId())) { teamDead = false; break; }
        if (teamDead) {
            end(m, m.enemy(p), mine);
        } else {
            for (Player x : m.all()) send(x, "eliminated-team", "player", p.getName());
            if (!quit) {
                p.setGameMode(GameMode.SPECTATOR);
                titleK(p, "eliminated", "player", p.getName());
                sound(p, "eliminated");
            }
        }
    }

    void end(Match m, List<Player> winners, List<Player> losers) {
        if (m.state == State.ENDED) return;
        m.state = State.ENDED;
        long secs = m.start == 0 ? 0 : (System.currentTimeMillis() - m.start) / 1000;
        String wn = names(winners), ln = names(losers);
        double hp = 0;
        for (Player w : winners) if (w.isOnline()) { hp = w.getHealth() / 2.0; break; }
        String[] vars = {"winner", wn, "loser", ln, "time", clock(secs), "kit", m.kit, "hp", String.format("%.1f", hp)};

        for (Player w : winners) {
            if (!w.isOnline() || m.left.contains(w.getUniqueId())) continue;
            titleK(w, "victory", vars);
            sound(w, "victory");
            sendList(w, "messages.duel-summary", vars);
        }
        for (Player l : losers) {
            if (!l.isOnline() || m.left.contains(l.getUniqueId())) continue;
            titleK(l, "defeat", vars);
            sound(l, "defeat");
            sendList(l, "messages.duel-summary", vars);
            l.setGameMode(GameMode.SPECTATOR);
        }
        for (UUID id : m.specs) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null) sendList(sp, "messages.duel-summary", vars);
        }
        for (Player w : winners) {
            addStat(w, "wins", 1);
            if (w.isOnline() && !m.left.contains(w.getUniqueId())) {
                w.getInventory().clear();               // winner: clean inventory + requeue paper + hub barrier
                if (!m.ffa && !m.teamBattle && m.t1.size() == 1 && m.t2.size() == 1) putItem(w, "requeue", "requeue", "kit", m.kit);
                putItem(w, "hub", "hub");
            }
        }
        for (Player l : losers) {
            addStat(l, "losses", 1);
        }
        if (m.ranked && !m.ffa && !m.teamBattle && winners.size() == 1 && losers.size() == 1) rankedResult(m, winners.get(0), losers.get(0));
        long delay = Math.max(1, getConfig().getInt("general.end-delay-seconds", 3)) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> finish(m), delay);
    }

    void finish(Match m) {
        for (Player p : m.all()) {
            if (p.isOnline() && matches.get(p.getUniqueId()) == m) {
                matches.remove(p.getUniqueId());
                toLobby(p);
            }
        }
        for (UUID id : new ArrayList<>(m.specs)) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null && watching.get(id) == m) {
                watching.remove(id);
                toLobby(sp);
            }
        }
        restore(m);
        activeMatches.remove(m);
        busyArenas.remove(m.arena);
    }

    void leave(Player p) {
        UUID id = p.getUniqueId();
        queue.remove(id);
        Match m = matches.get(id);
        if (m != null) {
            m.left.add(id);
            eliminate(m, p, true);
            matches.remove(id);
        }
        Match w = watching.remove(id);
        if (w != null) w.specs.remove(id);
        toLobby(p);
        send(p, "back-to-lobby");
    }

    // ───────────────────────── parties ─────────────────────────

    Party partyOf(Player p) { return parties.get(p.getUniqueId()); }

    List<Player> online(Party pt) {
        List<Player> l = new ArrayList<>();
        for (UUID id : pt.members) {
            Player x = Bukkit.getPlayer(id);
            if (x != null) l.add(x);
        }
        return l;
    }

    void pmsg(Party pt, String key, String... kv) { for (Player x : online(pt)) send(x, key, kv); }

    List<Player> team(Player leader) {
        Party pt = partyOf(leader);
        if (pt == null) return new ArrayList<>(List.of(leader));
        return online(pt);
    }

    void partyLeave(Player p) {
        UUID id = p.getUniqueId();
        Party pt = parties.remove(id);
        if (pt == null) return;
        List<UUID> aff = new ArrayList<>(pt.members);
        pt.members.remove(id);
        pmsg(pt, "party-left", "player", p.getName());
        if (pt.members.size() <= 1) {
            for (UUID m : new ArrayList<>(pt.members)) {
                parties.remove(m);
                Player x = Bukkit.getPlayer(m);
                if (x != null) { send(x, "party-disbanded"); titleK(x, "party-disbanded"); }
            }
            pt.members.clear();
        } else if (pt.leader.equals(id)) {
            pt.leader = pt.members.get(0);
            Player nl = Bukkit.getPlayer(pt.leader);
            pmsg(pt, "party-new-leader", "player", nl != null ? nl.getName() : "?");
        }
        refresh(aff);
    }

    void startPartyFfa(Player p, String kit) {
        Party pt = partyOf(p);
        if (pt == null || !pt.leader.equals(p.getUniqueId())) { send(p, "party-leader-only"); return; }
        List<Player> all = online(pt);
        if (all.size() < 2) { send(p, "party-ffa-min"); return; }
        if (anyBusy(all)) { send(p, "party-ffa-busy"); return; }
        startFfa(all, kit);
    }

    void party(Player p, String[] a) {
        UUID id = p.getUniqueId();
        Party pt = parties.get(id);
        String sub = a.length == 0 ? "list" : a[0].toLowerCase();
        int max = getConfig().getInt("general.party-max", 4);
        switch (sub) {
            case "create" -> {
                if (pt != null) { send(p, "party-already-in"); return; }
                queue.remove(id);
                Party n = new Party();
                n.leader = id;
                n.members.add(id);
                parties.put(id, n);
                send(p, "party-created");
                titleK(p, "party-created");
                sound(p, "party-created");
                refresh(List.of(id));
            }
            case "invite" -> {
                if (pt == null) { send(p, "party-need-create"); return; }
                if (!pt.leader.equals(id)) { send(p, "party-leader-only"); return; }
                if (a.length < 2) { send(p, "party-invite-usage"); return; }
                Player tg = Bukkit.getPlayerExact(a[1]);
                if (tg == null || tg.equals(p)) { send(p, "player-not-found"); return; }
                if (parties.containsKey(tg.getUniqueId())) { send(p, "party-in-other"); return; }
                if (pt.members.size() >= max) { send(p, "party-full"); return; }
                PInv pi = new PInv(pt, System.currentTimeMillis());
                UUID tid = tg.getUniqueId();
                pinvites.put(tid, pi);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (pinvites.get(tid) != pi) return;
                    pinvites.remove(tid);
                    Player s1 = Bukkit.getPlayer(id), t1 = Bukkit.getPlayer(tid);
                    if (s1 != null) send(s1, "party-invite-expired-sender", "player", tg.getName());
                    if (t1 != null) send(t1, "party-invite-expired-target", "player", p.getName());
                }, Math.max(1, getConfig().getLong("general.party-invite-expire-seconds", 60)) * 20L);
                send(p, "party-invite-sent", "player", tg.getName());
                send(tg, "party-invite-received", "player", p.getName());
                sound(tg, "party-invite");
            }
            case "accept" -> {
                PInv inv = pinvites.remove(id);
                if (inv == null || System.currentTimeMillis() - inv.time() > getConfig().getLong("general.party-invite-expire-seconds", 60) * 1000L) { send(p, "party-no-invite"); return; }
                if (pt != null) { send(p, "party-leave-first"); return; }
                Party target = inv.party();
                if (parties.get(target.leader) != target) { send(p, "party-gone"); return; }
                if (target.members.size() >= max) { send(p, "party-full"); return; }
                queue.remove(id);
                target.members.add(id);
                parties.put(id, target);
                pmsg(target, "party-joined", "player", p.getName());
                refresh(target.members);
            }
            case "leave" -> {
                if (pt == null) { send(p, "party-not-in"); return; }
                send(p, "party-left-self");
                partyLeave(p);
            }
            case "kick" -> {
                if (pt == null || !pt.leader.equals(id)) { send(p, "party-leader-only"); return; }
                if (a.length < 2) { send(p, "party-kick-usage"); return; }
                Player tg = Bukkit.getPlayerExact(a[1]);
                if (tg == null || tg.equals(p) || !pt.members.contains(tg.getUniqueId())) { send(p, "party-not-member"); return; }
                send(tg, "party-kicked");
                partyLeave(tg);
            }
            case "disband" -> {
                if (pt == null || !pt.leader.equals(id)) { send(p, "party-leader-only"); return; }
                List<UUID> aff = new ArrayList<>(pt.members);
                for (Player x : online(pt)) { send(x, "party-disbanded"); titleK(x, "party-disbanded"); }
                for (UUID m : aff) parties.remove(m);
                refresh(aff);
            }
            case "ffa" -> {
                if (pt == null || !pt.leader.equals(id)) { send(p, "party-leader-only"); return; }
                if (online(pt).size() < 2) { send(p, "party-ffa-min"); return; }
                openKitMenu(p, "ffa", null);
            }
            case "teams" -> {
                if (pt == null || !pt.leader.equals(id)) { send(p, "party-leader-only"); return; }
                if (online(pt).size() < 2) { send(p, "party-ffa-min"); return; }
                openKitMenu(p, "teams", null);
            }
            case "chat" -> {
                if (pt == null) { send(p, "party-not-in"); return; }
                if (a.length < 2) { send(p, "party-chat-usage"); return; }
                String text = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                Component c = MM.deserialize(t("party-chat-format", "player", p.getName())).append(Component.text(text));
                for (Player x : online(pt)) x.sendMessage(c);
            }
            default -> {
                if (pt == null) { send(p, "party-list-none"); return; }
                List<String> n = new ArrayList<>();
                for (Player x : online(pt)) n.add((x.getUniqueId().equals(pt.leader) ? "★" : "") + x.getName());
                send(p, "party-list", "size", String.valueOf(pt.members.size()), "max", String.valueOf(max),
                        "members", String.join(", ", n));
            }
        }
    }

    // ───────────────────────── commands ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) {
            if (c.getName().equalsIgnoreCase("duels") && a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                s.sendMessage("SimpleDuels config reloaded.");
            } else s.sendMessage("Players only.");
            return true;
        }
        String cn = c.getName().toLowerCase();
        if (List.of("setlobby", "arena", "kit", "world", "duels", "sb", "tier").contains(cn)
                && !p.isOp() && !p.hasPermission("duels.admin")) {
            msg(p, "<red>Admin only. <gray>Server says you are NOT op (isOp=false) and don't have duels.admin. "
                    + "Run <white>op " + p.getName() + " <gray>in the server console.");
            return true;
        }
        switch (cn) {
            case "duel" -> duel(p, a);
            case "spectate" -> spec(p, a);
            case "leave", "lobby" -> leave(p);
            case "party" -> party(p, a);
            case "settings" -> openSettings(p);
            case "kiteditor" -> kiteditor(p, a);
            case "world" -> world(p, a);
            case "sb" -> sb(p, a);
            case "stats" -> stats(p, a);
            case "tier" -> tier(p, a);
            case "setlobby" -> {
                setLoc("lobby", p.getLocation());
                saveData();
                msg(p, "<green>Lobby set!");
            }
            case "arena" -> arena(p, a);
            case "kit" -> kit(p, a);
            case "duels" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    msg(p, "<green>Config reloaded.");
                } else msg(p, "<red>/duels reload");
            }
            default -> { }
        }
        return true;
    }

    void duel(Player p, String[] a) {
        if (a.length == 0) { send(p, "duel-usage"); return; }
        Party mp = partyOf(p);
        if (mp != null && !mp.leader.equals(p.getUniqueId())) { send(p, "duel-party-leader-only"); return; }
        long expire = getConfig().getLong("general.request-expire-seconds", 60) * 1000L;
        if (a[0].equalsIgnoreCase("accept")) {
            Req r = requests.remove(p.getUniqueId());
            if (r == null || System.currentTimeMillis() - r.time() > expire) { send(p, "request-none"); return; }
            Player from = Bukkit.getPlayer(r.from());
            if (from == null) { send(p, "player-offline"); return; }
            List<Player> t1 = team(from), t2 = team(p);
            if (t1.size() != t2.size()) { send(p, "accept-size-mismatch"); return; }
            if (anyBusy(t1) || anyBusy(t2)) { send(p, "someone-busy"); return; }
            start(t1, t2, r.kit());
            return;
        }
        if (a[0].equalsIgnoreCase("deny")) {
            Req r = requests.remove(p.getUniqueId());
            if (r != null) {
                Player f = Bukkit.getPlayer(r.from());
                if (f != null) send(f, "request-declined", "player", p.getName());
            }
            send(p, "request-denied-self");
            return;
        }
        Player tg = Bukkit.getPlayerExact(a[0]);
        if (tg == null || tg.equals(p)) { send(p, "player-not-found"); return; }
        if (a.length > 1) sendRequest(p, tg, a[1].toLowerCase());
        else openKitMenu(p, "duel", tg.getUniqueId());
    }

    void sendRequest(Player p, Player tg, String kit) {
        if (!setting(tg, "requests")) { send(p, "requests-off"); return; }
        Party tp = partyOf(tg);
        if (tp != null && !tp.leader.equals(tg.getUniqueId())) {
            Player lead = Bukkit.getPlayer(tp.leader);
            send(p, "party-challenge-leader", "leader", lead != null ? lead.getName() : "?");
            return;
        }
        List<Player> mine = team(p), theirs = team(tg);
        if (mine.size() != theirs.size()) { send(p, "size-mismatch"); return; }
        if (anyBusy(mine) || anyBusy(theirs)) { send(p, "busy-any"); return; }
        if (kitBase(kit) == null) { send(p, "kit-not-found"); return; }
        Req rq = new Req(p.getUniqueId(), kit, System.currentTimeMillis());
        UUID tgId = tg.getUniqueId(), fromId = p.getUniqueId();
        requests.put(tgId, rq);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (requests.get(tgId) != rq) return;        // accepted, denied or replaced
            requests.remove(tgId);
            Player s1 = Bukkit.getPlayer(fromId), t1 = Bukkit.getPlayer(tgId);
            if (s1 != null) send(s1, "request-expired-sender", "player", tg.getName());
            if (t1 != null) send(t1, "request-expired-target", "player", p.getName());
        }, Math.max(1, getConfig().getLong("general.request-expire-seconds", 60)) * 20L);
        String size = mine.size() > 1 ? " <gray>(" + mine.size() + "v" + mine.size() + ")" : "";
        send(p, "request-sent", "player", tg.getName(), "kit", kit, "size", size);
        send(tg, "request-received", "player", p.getName(), "kit", kit, "size", size);
        sound(tg, "request");
    }

    void spec(Player p, String[] a) {
        if (a.length == 0) { send(p, "spectate-usage"); return; }
        UUID id = p.getUniqueId();
        if (matches.containsKey(id)) { send(p, "spec-while-dueling"); return; }
        Player tg = Bukkit.getPlayerExact(a[0]);
        Match m = tg == null ? null : matches.get(tg.getUniqueId());
        if (m == null) { send(p, "spec-not-dueling"); return; }
        queue.remove(id);
        Match old = watching.remove(id);
        if (old != null) old.specs.remove(id);
        m.specs.add(id);
        watching.put(id, m);
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(tg);
        send(p, "spectating", "player", tg.getName());
    }

    void arena(Player p, String[] a) {
        String help = "<red>/arena [create|spawn1|spawn2|corner1|corner2|ffa|ffaclear|door|attribute|tp|info|delete|list] [name]";
        if (a.length == 0) { msg(p, help); return; }
        String sub = a[0].toLowerCase();
        if (sub.equals("list")) {
            ConfigurationSection s = data.getConfigurationSection("arenas");
            if (s == null) { msg(p, "<yellow>Arenas: <white>none"); return; }
            List<String> out = new ArrayList<>();
            for (String n : s.getKeys(false)) {
                int f = ffaSpawns(n).size();
                out.add(n + (f > 0 ? " <gray>[ffa spawns: " + f + "]<white>" : ""));
            }
            msg(p, "<yellow>Arenas: <white>" + String.join(", ", out));
            return;
        }
        if (a.length < 2) { msg(p, "<red>Give an arena name."); return; }
        String n = a[1].toLowerCase().replace(".", "");
        String path = "arenas." + n;
        if (sub.equals("tp")) {   // teleport to an arena
            Location to = getLoc(path + ".spawn1");
            if (to == null) { List<Location> f = ffaSpawns(n); if (!f.isEmpty()) to = f.get(0); }
            if (to == null) { msg(p, "<red>Arena not found."); return; }
            p.teleport(to);
            msg(p, "<green>Teleported to arena <white>" + n);
            return;
        }
        if (sub.equals("attribute")) {   // /arena attribute [arena] [block]  - block types players may break
            if (!data.isConfigurationSection(path)) { msg(p, "<red>Arena not found."); return; }
            List<String> br = new ArrayList<>(data.getStringList(path + ".breakable"));
            Material mat = null;
            if (a.length >= 3) mat = Material.matchMaterial(a[2]);
            else if (!p.getInventory().getItemInMainHand().getType().isAir()) mat = p.getInventory().getItemInMainHand().getType();
            if (mat == null || !mat.isBlock()) {
                msg(p, "<yellow>Breakable blocks in <white>" + n + "<yellow>: <white>"
                        + (br.isEmpty() ? "none (only blocks placed during the match can be broken)" : String.join(", ", br))
                        + " <gray>| /arena attribute " + n + " [block]");
                return;
            }
            String bn = mat.name();
            boolean added = !br.remove(bn);
            if (added) br.add(bn);
            data.set(path + ".breakable", br.isEmpty() ? null : br);
            saveData();
            msg(p, (added ? "<green>Players can now break <white>" : "<yellow>Unbreakable again: <white>") + bn);
            return;
        }
        if (sub.equals("door")) {        // /arena door [arena]  -> gives the Door Wand.   /arena door [arena] clear
            if (!data.isConfigurationSection(path)) { msg(p, "<red>Arena not found."); return; }
            if (a.length >= 3 && a[2].equalsIgnoreCase("clear")) {
                data.set(path + ".door", null);
                saveData();
                msg(p, "<yellow>Door removed from <white>" + n);
                return;
            }
            ItemStack wand = named(Material.GOLDEN_AXE, "<gold><bold>Door Wand <gray>(" + n + ")",
                    "<gray>Left-click a block = corner 1", "<gray>Right-click a block = corner 2",
                    "<gray>The wall closes when a match starts", "<gray>and opens at FIGHT!");
            ItemMeta wm = wand.getItemMeta();
            wm.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, n);
            wand.setItemMeta(wm);
            p.getInventory().addItem(wand);
            msg(p, "<green>Door wand given. <gray>Select the wall between the two spawns (corner 1 + corner 2).");
            return;
        }
        if (sub.equals("info")) {   // find out why building / regen doesn't work
            Location c1 = getLoc(path + ".corner1"), c2 = getLoc(path + ".corner2"), w1 = getLoc(path + ".spawn1");
            String region = (c1 != null && c2 != null && c1.getWorld().equals(c2.getWorld()))
                    ? (int) BoundingBox.of(c1.getBlock(), c2.getBlock()).getVolume() + " blocks"
                    : "NOT SET (no building, no regen) -> /arena corner1 + corner2";
            msg(p, "<yellow>Arena <white>" + n + " <yellow>| world: <white>" + (w1 != null ? w1.getWorld().getName() : "?")
                    + " <yellow>| region: <white>" + region + " <yellow>| ffa spawns: <white>" + ffaSpawns(n).size()
                    + " <yellow>| world build: <white>" + (w1 != null && worldBuild(w1.getWorld())));
            return;
        }
        switch (sub) {
            case "create" -> {
                setLoc(path + ".spawn1", p.getLocation());
                setLoc(path + ".spawn2", p.getLocation());
            }
            case "spawn1" -> setLoc(path + ".spawn1", p.getLocation());
            case "spawn2" -> setLoc(path + ".spawn2", p.getLocation());
            case "corner1" -> setLoc(path + ".corner1", p.getLocation());
            case "corner2" -> setLoc(path + ".corner2", p.getLocation());
            case "ffa" -> {
                List<Location> l = ffaSpawns(n);
                l.add(p.getLocation());
                setFfa(n, l);
                msg(p, "<green>FFA spawn #" + l.size() + " added to <white>" + n + " <gray>(need at least 2)");
            }
            case "ffaclear" -> data.set(path + ".ffa", null);
            case "delete" -> data.set(path, null);
            default -> { msg(p, help); return; }
        }
        saveData();
        msg(p, "<green>Done.");
        if (sub.startsWith("corner")) {
            Location c1 = getLoc(path + ".corner1");
            Location c2 = getLoc(path + ".corner2");
            if (c1 != null && c2 != null && c1.getWorld() != null && c1.getWorld().equals(c2.getWorld())) {
                BoundingBox box = BoundingBox.of(c1.getBlock(), c2.getBlock());
                msg(p, "<gray>Arena region: <white>" + (int) box.getVolume() + " blocks <gray>(max "
                        + getConfig().getInt("general.max-arena-volume", 300000) + ")"
                        + (box.getVolume() > getConfig().getInt("general.max-arena-volume", 300000)
                                ? " <yellow>- big arena: tracked regen (only changed blocks are restored)" : ""));
            }
        }
    }

    /** /world list | /world [name]  - teleport to any world on the server (to build arenas) */
    void world(Player p, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("list")) {
            List<String> out = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) out.add(w.getName() + " <gray>(" + w.getPlayers().size() + ")" + (worldBuild(w) ? " <gold>[build]" : "") + "<white>");
            msg(p, "<yellow>Worlds: <white>" + String.join(", ", out) + " <gray>| /world [name]");
            return;
        }
        if (a[0].equalsIgnoreCase("build")) {      // /world build [world] [on|off]
            World bw = p.getWorld();
            Boolean flag = null;
            for (int i = 1; i < a.length; i++) {
                if (a[i].equalsIgnoreCase("on")) flag = true;
                else if (a[i].equalsIgnoreCase("off")) flag = false;
                else {
                    World x = Bukkit.getWorld(a[i]);
                    if (x == null) { msg(p, "<red>World not found. /world list"); return; }
                    bw = x;
                }
            }
            boolean on = flag != null ? flag : !worldBuild(bw);
            data.set("worlds." + wkey(bw) + ".build", on);
            saveData();
            for (Player x : bw.getPlayers()) {
                if (!busy(x) && !editing.containsKey(x.getUniqueId()) && x.getGameMode() != GameMode.CREATIVE) {
                    x.setGameMode(on ? GameMode.SURVIVAL : GameMode.ADVENTURE);
                }
            }
            msg(p, "<gold>Placing / breaking blocks in <white>" + bw.getName() + "<gold>: <white>" + (on ? "ON" : "OFF"));
            return;
        }
        if (busy(p)) { msg(p, "<red>Leave your duel first (/lobby)."); return; }
        World w = Bukkit.getWorld(a[0]);
        if (w == null) { msg(p, "<red>World not found. Use /world list"); return; }
        p.teleport(w.getSpawnLocation());
        msg(p, "<green>Teleported to world <white>" + w.getName());
    }

    /** /kit arena add|remove|list [kit] [arena]  - choose which arenas a gamemode can use */
    void kitArena(Player p, String[] a) {
        if (a.length < 3) { msg(p, "<red>/kit arena [add|remove|list] [kit] [arena]"); return; }
        String op = a[1].toLowerCase(), kit = a[2].toLowerCase().replace(".", "");
        if (kitBase(kit) == null) { msg(p, "<red>Kit not found. (/kit list)"); return; }
        String path = "kits." + kit + ".arenas";
        List<String> cur = new ArrayList<>(data.getStringList(path));
        if (op.equals("list")) {
            msg(p, "<yellow>Arenas for <white>" + kit + "<yellow>: <white>" + (cur.isEmpty() ? "all arenas" : String.join(", ", cur)));
            return;
        }
        if (a.length < 4) { msg(p, "<red>/kit arena [add|remove] [kit] [arena]"); return; }
        String ar = a[3].toLowerCase().replace(".", "");
        if (op.equals("add")) {
            if (!data.isConfigurationSection("arenas." + ar)) { msg(p, "<red>Arena not found. (/arena list)"); return; }
            if (!cur.contains(ar)) cur.add(ar);
        } else if (op.equals("remove")) {
            cur.remove(ar);
        } else { msg(p, "<red>/kit arena [add|remove|list] [kit] [arena]"); return; }
        data.set(path, cur.isEmpty() ? null : cur);
        saveData();
        msg(p, "<green>Done. <gray>" + kit + " arenas: <white>" + (cur.isEmpty() ? "all arenas" : String.join(", ", cur)));
    }

    void kit(Player p, String[] a) {
        if (a.length == 0) { msg(p, "<red>/kit [create|load|delete|icon|slot|slots|build|setuhc|goldenhead|info|arena|list] [name]"); return; }
        String sub = a[0].toLowerCase();
        if (a.length >= 3 && a[1].equalsIgnoreCase("slot")) { kitSlot(p, a[0], a[2]); return; }   // /kit sumo slot 11
        if (sub.equals("slot")) {                                                                  // /kit slot sumo 11
            if (a.length < 3) { msg(p, "<red>/kit [name] slot [number]"); return; }
            kitSlot(p, a[1], a[2]);
            return;
        }
        if (sub.equals("slots")) { openSlotEditor(p); return; }
        if (sub.equals("goldenhead")) {                       // /kit goldenhead [amount]
            int amt = 1;
            try { if (a.length > 1) amt = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) { }
            p.getInventory().addItem(goldenHead(amt));
            msg(p, "<green>Golden head given. <gray>Put it in your inventory layout, then /kit create [name].");
            return;
        }
        if (sub.equals("arena")) { kitArena(p, a); return; }
        if (sub.equals("list")) {
            ConfigurationSection s = data.getConfigurationSection("kits");
            msg(p, "<yellow>Kits: <white>" + (s == null ? "none" : String.join(", ", s.getKeys(false))));
            return;
        }
        if (a.length < 2) { msg(p, "<red>Give a kit name."); return; }
        String n = a[1].toLowerCase().replace(".", "");
        switch (sub) {
            case "save", "create" -> {
                data.set("kits." + n + ".items", Arrays.asList(p.getInventory().getContents()));
                saveData();
                msg(p, "<green>Kit <white>" + n + " <green>saved from your inventory (armor + offhand included).");
            }
            case "load" -> giveKit(p, n);
            case "setuhc" -> {                                   // /kit setuhc sumo [off]
                if (kitBase(n) == null) { msg(p, "<red>Kit not found."); return; }
                boolean on = a.length < 3 || !a[2].equalsIgnoreCase("off");
                data.set("kits." + n + ".uhc", on);
                saveData();
                msg(p, "<green>UHC mode for <white>" + n + "<green>: <white>" + (on ? "ON <gray>(no natural regen, golden head heals)" : "OFF"));
            }
            case "build" -> {
                if (kitBase(n) == null) { msg(p, "<red>Kit not found."); return; }
                boolean on = a.length < 3 || !a[2].equalsIgnoreCase("off");     // /kit build sumo off
                data.set("kits." + n + ".build", on);
                saveData();
                msg(p, "<green>Building in <white>" + n + "<green>: <white>" + (on ? "ON" : "OFF"));
            }
            case "info" -> {
                if (kitBase(n) == null) { msg(p, "<red>Kit not found."); return; }
                List<String> ar = data.getStringList("kits." + n + ".arenas");
                msg(p, "<yellow>Kit <white>" + n + " <yellow>| arenas: <white>" + (ar.isEmpty() ? "all" : String.join(", ", ar))
                        + " <yellow>| build: <white>" + data.getBoolean("kits." + n + ".build", true)
                        + " <yellow>| icon: <white>" + data.getString("kits." + n + ".icon", "auto"));
            }
            case "delete" -> {
                data.set("kits." + n, null);
                saveData();
                msg(p, "<green>Kit deleted.");
            }
            case "icon" -> {
                Material hand = p.getInventory().getItemInMainHand().getType();
                if (hand.isAir() || kitBase(n) == null) { msg(p, "<red>Hold an item and use an existing kit name."); return; }
                data.set("kits." + n + ".icon", hand.name());
                saveData();
                msg(p, "<green>Menu icon for <white>" + n + " <green>set to <white>" + hand.name());
            }
            default -> msg(p, "<red>/kit [create|load|delete|icon|slot|slots|build|setuhc|goldenhead|info|arena|list] [name]");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String name = c.getName().toLowerCase();
        if (a.length == 1) {
            switch (name) {
                case "duel" -> { out.addAll(List.of("accept", "deny")); Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName())); }
                case "spectate" -> matches.values().forEach(m -> m.all().forEach(p -> out.add(p.getName())));
                case "arena" -> out.addAll(List.of("create", "spawn1", "spawn2", "corner1", "corner2", "ffa", "ffaclear", "tp", "info", "delete", "list", "door", "attribute"));
                case "kit" -> out.addAll(List.of("create", "load", "delete", "icon", "slot", "slots", "build", "setuhc", "goldenhead", "info", "arena", "list"));
                case "party" -> out.addAll(List.of("create", "invite", "accept", "leave", "kick", "disband", "list", "chat", "ffa", "teams"));
                case "kiteditor" -> out.addAll(List.of("save", "reset", "cancel"));
                case "sb" -> out.addAll(List.of("spawn", "remove", "list"));
                case "tier" -> out.addAll(List.of("set", "clear", "info"));
                case "duels" -> out.add("reload");
                case "world" -> { out.addAll(List.of("list", "build")); Bukkit.getWorlds().forEach(w -> out.add(w.getName())); }
                default -> { }
            }
        } else if (a.length == 2) {
            switch (name) {
                case "duel", "kit" -> {
                    ConfigurationSection k = data.getConfigurationSection("kits");
                    if (k != null) out.addAll(k.getKeys(false));
                }
                case "arena" -> {
                    ConfigurationSection k = data.getConfigurationSection("arenas");
                    if (k != null) out.addAll(k.getKeys(false));
                }
                case "world" -> {
                    if (a[0].equalsIgnoreCase("build")) { out.addAll(List.of("on", "off")); Bukkit.getWorlds().forEach(w -> out.add(w.getName())); }
                }
                case "party" -> {
                    if (a[0].equalsIgnoreCase("invite") || a[0].equalsIgnoreCase("kick"))
                        Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
                default -> { }
            }
        }
        if (name.equals("sb") && a.length >= 2 && a[0].equalsIgnoreCase("spawn")) {
            if (a.length <= 4) out.add("~");
            if (a.length == 2 || a.length == 5) out.addAll(List.of("wins", "kills", "deaths", "lose", "points"));
        }
        if (name.equals("tier")) {
            if (a.length == 2) Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
            if (a.length == 3) out.addAll(kitNames());
            if (a.length == 4 && a[0].equalsIgnoreCase("set")) for (Map<?, ?> td : tierList()) out.add(String.valueOf(td.get("name")));
        }
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }

    // ───────────────────────── scoreboard + tab ─────────────────────────

    static final int LINES = 15;

    Scoreboard board(Player p) {
        return boards.computeIfAbsent(p.getUniqueId(), k -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = sb.registerNewObjective("duels", Criteria.DUMMY, MM.deserialize("DUELS"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            obj.numberFormat(NumberFormat.blank());   // hide the red 1..15 numbers
            for (int i = 0; i < LINES; i++) {
                sb.registerNewTeam("l" + i).addEntry("§" + Integer.toHexString(i) + "§r");
            }
            Team nt = sb.registerNewTeam("nt");
            nt.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            sb.registerNewTeam("tred").color(NamedTextColor.RED);
            sb.registerNewTeam("tblue").color(NamedTextColor.BLUE);
            p.setScoreboard(sb);
            return sb;
        });
    }

    int aliveCount(Match m, List<Player> team) {
        int n = 0;
        for (Player x : team) if (m.alive.contains(x.getUniqueId())) n++;
        return n;
    }

    /** Replaces {vars}; returns null when the line uses a var that doesn't apply. */
    String subst(String line, Map<String, String> v) {
        Matcher mt = PH.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            String val = v.get(mt.group(1));
            if (val == null) return null;
            mt.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    List<String> sbLines(Player p) {
        UUID id = p.getUniqueId();
        Match m = matches.get(id);
        Match w = watching.get(id);
        Map<String, String> v = new HashMap<>();
        v.put("footer", getConfig().getString("general.footer", ""));
        v.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
        v.put("fighting", String.valueOf(matches.size()));
        v.put("ping", String.valueOf(p.getPing()));
        v.put("rank", rankName(globalRating(id)));
        v.put("points", String.valueOf(globalRating(id)));
        TierInfo myTier = cachedTier(id);
        if (myTier.tier() >= 0) v.put("tier", myTier.kit().toUpperCase() + " " + tierTagText(myTier.tier()));
        v.put("wins", String.valueOf(stat(id, "wins")));
        v.put("losses", String.valueOf(stat(id, "losses")));
        v.put("kills", String.valueOf(stat(id, "kills")));
        v.put("deaths", String.valueOf(stat(id, "deaths")));
        String path;
        if (m != null) {
            long s = m.state == State.ACTIVE ? (System.currentTimeMillis() - m.start) / 1000 : 0;
            v.put("time", clock(s));
            v.put("kit", m.kit);
            v.put("mode", m.ranked ? "Ranked" : "Unranked");
            if (m.teamBattle) v.put("team", m.inTeam1(p) ? "<red><bold>RED" : "<blue><bold>BLUE");
            if (m.ffa) {
                path = "scoreboard.ffa";
                v.put("alive", String.valueOf(m.alive.size()));
                v.put("total", String.valueOf(m.t1.size()));
            } else {
                List<Player> en = m.enemy(p);
                v.put("opponent", label(en));
                if (m.t1.size() > 1) {
                    path = "scoreboard.team";
                    v.put("team_alive", String.valueOf(aliveCount(m, m.team(p))));
                    v.put("enemy_alive", String.valueOf(aliveCount(m, en)));
                } else {
                    path = "scoreboard.duel";
                    v.put("you_ping", String.valueOf(p.getPing()));
                    v.put("them_ping", String.valueOf(en.get(0).getPing()));
                }
            }
        } else if (w != null) {
            path = "scoreboard.spectate";
            v.put("kit", w.kit);
            v.put("alive", String.valueOf(w.alive.size()));
            v.put("players", w.ffa ? "FFA (" + w.alive.size() + " alive)" : label(w.t1) + " vs " + label(w.t2));
        } else {
            path = "scoreboard.lobby";
            Party pt = parties.get(id);
            if (pt != null) v.put("party", String.valueOf(pt.members.size()));
            String q = queue.get(id);
            if (q != null) v.put("queue", q + (rankedSince.containsKey(id) ? " <gold>(ranked)" : ""));
        }
        List<String> out = new ArrayList<>();
        for (String line : getConfig().getStringList(path)) {
            String r = subst(line, v);
            if (r != null) out.add(r);
        }
        return out;
    }

    void update(Player p) {
        UUID id = p.getUniqueId();
        Match m = matches.get(id);
        Match w = watching.get(id);
        Scoreboard sb = board(p);
        Objective obj = sb.getObjective("duels");
        if (obj != null) {
            obj.displayName(MM.deserialize(getConfig().getString("scoreboard.title", "DUELS")));
            obj.setDisplaySlot(setting(p, "scoreboard") ? DisplaySlot.SIDEBAR : null);
            List<String> L = sbLines(p);
            for (int i = 0; i < LINES; i++) {
                String entry = "§" + Integer.toHexString(i) + "§r";
                Team tm = sb.getTeam("l" + i);
                if (tm == null) continue;
                if (i < L.size()) {
                    tm.prefix(MM.deserialize(L.get(i)));
                    obj.getScore(entry).setScore(Math.min(L.size(), LINES) - i);
                } else {
                    sb.resetScores(entry);
                }
            }
        }

        // nametags above heads (per viewer): team battle colours > hidden nametags > tier prefix + colour
        Set<String> red = new HashSet<>(), blue = new HashSet<>();
        if (m != null && m.teamBattle) {
            for (Player x : m.t1) red.add(x.getName());
            for (Player x : m.t2) blue.add(x.getName());
        }
        boolean showTags = setting(p, "nametags");
        boolean tiersOn = getConfig().getBoolean("tiers.enabled", true);
        Map<String, String> want = new HashMap<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            String on = o.getName();
            if (red.contains(on)) want.put(on, "tred");
            else if (blue.contains(on)) want.put(on, "tblue");
            else if (!showTags && !o.equals(p)) want.put(on, "nt");
            else if (tiersOn) {
                TierInfo oti = cachedTier(o.getUniqueId());
                if (oti.tier() >= 0) want.put(on, tierTeam(sb, oti).getName());
            }
        }
        for (Team tm : new ArrayList<>(sb.getTeams())) {
            String tn = tm.getName();
            if (!(tn.equals("tred") || tn.equals("tblue") || tn.equals("nt") || tn.startsWith("T"))) continue;
            for (String en : new ArrayList<>(tm.getEntries())) if (!tn.equals(want.get(en))) tm.removeEntry(en);
        }
        for (Map.Entry<String, String> en : want.entrySet()) {
            Team tm = sb.getTeam(en.getValue());
            if (tm != null && !tm.hasEntry(en.getKey())) tm.addEntry(en.getKey());
        }
        visibility(p);

        // TAB
        String online = String.valueOf(Bukkit.getOnlinePlayers().size()), ping = String.valueOf(p.getPing());
        List<String> hl = new ArrayList<>(), fl = new ArrayList<>();
        for (String l : getConfig().getStringList("tab.header")) hl.add(fmt(l, "online", online, "ping", ping));
        for (String l : getConfig().getStringList("tab.footer")) fl.add(fmt(l, "online", online, "ping", ping));
        p.sendPlayerListHeaderAndFooter(MM.deserialize(String.join("<newline>", hl)),
                MM.deserialize(String.join("<newline>", fl)));
        TierInfo mine = tiersOn ? cachedTier(id) : NO_TIER;
        String tabFmt;
        if (mine.tier() >= 0) {                                   // [SPEAR HT3] Name, coloured by tier
            String st = m != null ? "fighting" : w != null ? "spectating" : "lobby";
            tabFmt = fmt(getConfig().getString("tiers.tab-" + st, "{tier}{namecolor}{player}"),
                    "tier", tagPrefix(mine.kit(), mine.tier()), "namecolor", tierNameColor(mine.tier()), "player", p.getName());
            chatHead.put(id, tagPrefix(mine.kit(), mine.tier()) + tierNameColor(mine.tier()) + p.getName() + "<gray>: <white>");
        } else {
            String key = m != null ? "tab.name-fighting" : w != null ? "tab.name-spectating" : "tab.name-lobby";
            tabFmt = fmt(getConfig().getString(key, "{player}"), "player", p.getName(),
                    "rank", rankName(globalRating(id)), "points", String.valueOf(globalRating(id)));
            chatHead.remove(id);
        }
        p.playerListName(MM.deserialize(tabFmt));
    }

    // ───────────────────────── events ─────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        toLobby(p);
        String jm = getConfig().getString("general.join-message", "");
        e.joinMessage(jm.isEmpty() ? null : MM.deserialize(fmt(jm, "player", p.getName())));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            titleK(p, "welcome", "player", p.getName());
            sendList(p, "messages.welcome", "player", p.getName());
            sound(p, "welcome");
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        String qm = getConfig().getString("general.quit-message", "");
        e.quitMessage(qm.isEmpty() ? null : MM.deserialize(fmt(qm, "player", p.getName())));
        UUID id = p.getUniqueId();
        queue.remove(id);
        editing.remove(id);
        Match m = matches.get(id);
        if (m != null) {
            m.left.add(id);
            eliminate(m, p, true);
            matches.remove(id);
        }
        Match w = watching.remove(id);
        if (w != null) w.specs.remove(id);
        partyLeave(p);
        pinvites.remove(id);
        boards.remove(id);
        chatHead.remove(id);
        requests.remove(id);
    }

    // lobby hotbar items (identified by a hidden tag, not by material)
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getClickedBlock() != null && (e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_BLOCK)
                && isWand(e.getItem())) {
            e.setCancelled(true);
            String wa = e.getItem().getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.STRING);
            Player wp = e.getPlayer();
            if (wa != null && (wp.isOp() || wp.hasPermission("duels.admin")))
                handleWand(wp, wa, e.getClickedBlock(), e.getAction() == Action.LEFT_CLICK_BLOCK);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (editing.containsKey(p.getUniqueId())) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        ItemMeta im = it.getItemMeta();
        if (im == null) return;
        if (im.getPersistentDataContainer().has(headKey, PersistentDataType.STRING)) {   // golden head
            e.setCancelled(true);
            eatGoldenHead(p);
            return;
        }
        String act = im.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (act == null) return;
        boolean post = act.equals("requeue") || act.equals("hub");   // items the winner gets after a match
        if (busy(p) != post) return;                                  // lobby items only in lobby, post-match items only after a match
        e.setCancelled(true);
        sound(p, "click");
        switch (act) {
            case "queue", "queueleave" -> toggleQueue(p);
            case "requeue" -> requeue(p);
            case "ranked" -> toggleRanked(p);
            case "hub" -> leave(p);
            case "teams" -> party(p, new String[]{"teams"});
            case "party" -> party(p, new String[]{"create"});
            case "ffa" -> party(p, new String[]{"ffa"});
            case "disband" -> party(p, new String[]{"disband"});
            case "leaveparty" -> party(p, new String[]{"leave"});
            case "editor" -> openKitMenu(p, "editor", null);
            case "settings" -> openSettings(p);
            default -> { }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof KitEditorMenu eh) {
            Inventory top = e.getView().getTopInventory();
            ClickType ck = e.getClick();
            if (e.getClickedInventory() != top || (ck != ClickType.LEFT && ck != ClickType.RIGHT)) { e.setCancelled(true); return; }
            int raw = e.getRawSlot();
            if (raw < 36) return;                                   // moving items between the 36 slots is allowed
            e.setCancelled(true);
            if (raw == 47) editorSaveGui(p, eh, top);
            else if (raw == 49) {
                data.set("layouts." + p.getUniqueId() + "." + eh.kit, null);
                saveData();
                fillEditor(p, top, eh.kit, true);
                sound(p, "click");
                send(p, "editor-reset");
            } else if (raw == 51) { p.closeInventory(); send(p, "editor-closed"); }
            return;
        }
        if (e.getView().getTopInventory().getHolder() instanceof SlotEditor) {
            boolean ok = e.getClickedInventory() == e.getView().getTopInventory()
                    && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT);
            if (!ok) e.setCancelled(true);
            return;
        }
        if (e.getView().getTopInventory().getHolder() instanceof KitMenu h) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            String kit = h.slots.get(e.getSlot());
            if (kit == null) return;
            p.closeInventory();
            choose(p, h, kit);
            return;
        }
        if (e.getView().getTopInventory().getHolder() instanceof SettingsMenu) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            for (int i = 0; i < SETTING_SLOTS.length; i++) {
                if (e.getSlot() == SETTING_SLOTS[i]) {
                    String key = SETTING_KEYS[i];
                    data.set("settings." + p.getUniqueId() + "." + key, !setting(p, key));
                    saveData();
                    sound(p, "click");
                    fillSettings(p, e.getView().getTopInventory());
                    update(p);
                }
            }
            return;
        }
        Match endM = matches.get(p.getUniqueId());
        if (endM != null && endM.state == State.ENDED) { e.setCancelled(true); return; }
        if (endM == null && !editing.containsKey(p.getUniqueId()) && !admin(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder hd = e.getView().getTopInventory().getHolder();
        if (hd instanceof KitMenu || hd instanceof SettingsMenu || hd instanceof SlotEditor || hd instanceof KitEditorMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof KitEditorMenu) { p.setItemOnCursor(null); return; }   // never keep the cursor item
        if (!(e.getView().getTopInventory().getHolder() instanceof SlotEditor h)) return;
        Inventory top = e.getView().getTopInventory();
        ItemStack cur = p.getItemOnCursor();
        if (kitTag(cur) != null) {                      // icon still on the cursor -> put it back in the menu
            p.setItemOnCursor(null);
            int free = top.firstEmpty();
            if (free >= 0) top.setItem(free, cur);
        }
        for (int i = 0; i < p.getInventory().getSize(); i++) {   // never leave icons in the player's inventory
            if (kitTag(p.getInventory().getItem(i)) != null) p.getInventory().setItem(i, null);
        }
        for (String k : h.shown) data.set("kits." + k + ".slot", null);
        for (int i = 0; i < top.getSize(); i++) {
            String k = kitTag(top.getItem(i));
            if (k != null) data.set("kits." + k + ".slot", i);
        }
        saveData();
        msg(p, "<green>Kit positions saved.");
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!matches.containsKey(e.getPlayer().getUniqueId()) && !admin(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Match m = matches.get(e.getPlayer().getUniqueId());
        if (m != null && m.state == State.COUNTDOWN && e.hasChangedPosition()
                && getConfig().getBoolean("general.freeze-countdown", false)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Player d = null;
        if (e.getDamager() instanceof Player pl) d = pl;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl2) d = pl2;
        if (d == null) return;
        Match m = matches.get(v.getUniqueId());
        if (m != null && !m.ffa && m.team(v) == m.team(d)) { e.setCancelled(true); return; }
        if (m != null && !d.equals(v)) { lastHitter.put(v.getUniqueId(), d.getUniqueId()); lastHitAt.put(v.getUniqueId(), System.currentTimeMillis()); }
    }

    boolean hasTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = matches.get(p.getUniqueId());
        if (m == null) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) p.teleport(lobby());
            return;
        }
        if (m.state != State.ACTIVE || !m.alive.contains(p.getUniqueId())) { e.setCancelled(true); return; }
        if (p.getHealth() - e.getFinalDamage() <= 0) {
            // totem in hand -> let vanilla pop it (void / kill still kill)
            if (getConfig().getBoolean("general.totems-pop", true) && hasTotem(p)
                    && e.getCause() != EntityDamageEvent.DamageCause.VOID
                    && e.getCause() != EntityDamageEvent.DamageCause.KILL) return;
            e.setCancelled(true);
            Player killer = killerOf(e, p);
            announceDeath(m, p, killer);
            addStat(p, "deaths", 1);
            if (killer != null && matches.get(killer.getUniqueId()) == m) {
                addStat(killer, "kills", 1);
            }
            eliminate(m, p, false);
        }
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!getConfig().getBoolean("general.wind-charge-reset", true)) return;
        if (e.getEntity() instanceof WindCharge wc && wc.getShooter() instanceof Player p
                && matches.containsKey(p.getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> p.setCooldown(Material.WIND_CHARGE, 0));
        }
    }

    boolean admin(Player p) { return (p.isOp() || p.hasPermission("duels.admin")) && p.getGameMode() == GameMode.CREATIVE; }

    /** null = allowed, otherwise the message key that explains why not. */
    String buildDenied(Player p, Block b) {
        if (admin(p)) return null;
        boolean wb = worldBuild(b.getWorld());
        Match m = matches.get(p.getUniqueId());
        if (m == null) return (wb && !watching.containsKey(p.getUniqueId())) ? null : "build-denied-lobby";
        if (m.state == State.ENDED || !m.alive.contains(p.getUniqueId())) return "build-denied-countdown";
        if (!data.getBoolean("kits." + m.kit + ".build", true)) return "build-denied-kit";
        if (!(inBox(m, b) || wb)) return m.box == null ? "build-denied-no-region" : "build-denied-outside";
        return null;
    }

    boolean canBuild(Player p, Block b) {
        String why = buildDenied(p, b);
        if (why == null) return true;
        p.sendActionBar(MM.deserialize(t(why)));     // tells the player WHY (shown above the hotbar)
        return false;
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (busy(p) || editing.containsKey(p.getUniqueId()) || p.getGameMode() == GameMode.CREATIVE) return;
        p.setGameMode(worldBuild(p.getWorld()) ? GameMode.SURVIVAL : GameMode.ADVENTURE);
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        if (isWand(e.getPlayer().getInventory().getItemInMainHand())) { e.setCancelled(true); return; }
        if (!canBreak(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }
    @EventHandler public void onBucketEmpty(PlayerBucketEmptyEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }
    @EventHandler public void onBucketFill(PlayerBucketFillEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }

    @EventHandler public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> !inAnyRegion(b)); }
    @EventHandler public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> !inAnyRegion(b)); }

    @EventHandler
    public void onFlow(BlockFromToEvent e) {
        if (activeMatches.isEmpty()) return;
        for (Match m : activeMatches) {
            if (inBox(m, e.getBlock()) && !inBox(m, e.getToBlock())) { e.setCancelled(true); return; }
        }
    }

    // ── big arenas: remember the original of every block that changes (restored when the match ends) ──
    Match matchAt(Block b) {
        for (Match m : activeMatches) if (inBox(m, b)) return m;
        return null;
    }

    void track(Block b, BlockData original) {
        Match m = matchAt(b);
        if (m != null && m.snap == null) m.changes.putIfAbsent(b, original);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackBreak(BlockBreakEvent e) {
        track(e.getBlock(), e.getBlock().getBlockData());
        Match pm = matches.get(e.getPlayer().getUniqueId());
        if (pm != null) pm.placed.remove(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackPlace(BlockPlaceEvent e) {
        track(e.getBlock(), e.getBlockReplacedState().getBlockData());
        Match pm = matches.get(e.getPlayer().getUniqueId());
        if (pm != null && inBox(pm, e.getBlock())) pm.placed.add(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackBucketEmpty(PlayerBucketEmptyEvent e) { track(e.getBlock(), e.getBlock().getBlockData()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackBucketFill(PlayerBucketFillEvent e) { track(e.getBlock(), e.getBlock().getBlockData()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackExplode(EntityExplodeEvent e) { for (Block b : e.blockList()) track(b, b.getBlockData()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackBlockExplode(BlockExplodeEvent e) { for (Block b : e.blockList()) track(b, b.getBlockData()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackFlow(BlockFromToEvent e) { if (!activeMatches.isEmpty()) track(e.getToBlock(), e.getToBlock().getBlockData()); }

    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!admin(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onFood(FoodLevelChangeEvent e) { e.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && !admin(p)) e.setCancelled(true);
    }
}
