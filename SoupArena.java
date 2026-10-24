package cz.souparena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.*;

public class SoupArena extends JavaPlugin implements Listener, CommandExecutor {

    // ================= NASTAVENÍ =================
    static final int UHC_COUNTDOWN = 30;          // sekundy v kleci
    static final int UHC_SECONDS = 1800;          // 30 minut
    static final int UHC_START_BORDER = 800;      // průměr borderu na začátku
    static final int UHC_END_BORDER = 80;         // průměr borderu na konci
    static final int UHC_SPAWN_RADIUS = 250;      // v jakém okruhu se hráči rozmístí
    static final double SOUP_HEAL = 7.0;          // 3.5 srdce
    static final int DUEL_HALF = 15;              // arena 31x31
    static final int DUEL_GRASS_LAYERS = 8;       // počet vrstev grass blocků
    // =============================================

    enum UhcState { IDLE, COUNTDOWN, RUNNING, DEATHMATCH, ENDING }

    static class Duel {
        int slot; Player a, b; boolean started, ended; Location center;
    }

    World lobby, duelWorld, uhcWorld;
    NamespacedKey npcKey;

    UhcState uhcState = UhcState.IDLE;
    final Set<UUID> uhcAlive = new HashSet<>(), uhcAll = new HashSet<>(), uhcQueue = new HashSet<>();
    final Map<UUID, String> lastHit = new HashMap<>();
    final List<Location> cages = new ArrayList<>();
    BukkitTask uhcTask; BossBar uhcBar; int uhcTick;

    final Map<UUID, Duel> duels = new HashMap<>();
    final List<UUID> duelQueue = new ArrayList<>();
    final boolean[] slotUsed = new boolean[16];

    static Component c(String s) { return LegacyComponentSerializer.legacySection().deserialize(s); }
    static void title(Player p, String t, String s) {
        p.showTitle(Title.title(c(t), c(s), Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(900), Duration.ofMillis(200))));
    }
    static double maxHp(Player p) { return p.getAttribute(Attribute.MAX_HEALTH).getValue(); }
    static void deleteDir(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteDir(x);
        f.delete();
    }

    static class VoidGen extends ChunkGenerator {
        @Override public Location getFixedSpawnLocation(World w, Random r) { return new Location(w, 0.5, 65, 0.5); }
    }

    // ================= ŽIVOTNÍ CYKLUS =================
    @Override public void onEnable() {
        npcKey = new NamespacedKey(this, "npc");
        File[] old = Bukkit.getWorldContainer().listFiles();
        if (old != null) for (File f : old) if (f.isDirectory() && f.getName().startsWith("uhc_")) deleteDir(f);

        lobby = voidWorld("lobby");
        duelWorld = voidWorld("duels");
        buildLobby();
        spawnNpcs();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("uhc").setExecutor(this);
        getCommand("lobby").setExecutor(this);
        getLogger().info("SoupArena zapnut.");
    }

    @Override public void onDisable() {
        if (uhcWorld != null) finishUhc(true);
    }

    World voidWorld(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) {
            w = new WorldCreator(name).environment(World.Environment.NORMAL)
                    .generator(new VoidGen()).generateStructures(false).createWorld();
        }
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setTime(6000);
        w.setDifficulty(Difficulty.NORMAL);
        return w;
    }

    Location lobbySpawn() { return new Location(lobby, 0.5, 65, 7.5, 180, 0); }

    void buildLobby() {
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            boolean edge = Math.abs(x) == 12 || Math.abs(z) == 12;
            Material m = edge ? Material.SEA_LANTERN : ((x + z) % 2 == 0 ? Material.STONE_BRICKS : Material.POLISHED_ANDESITE);
            lobby.getBlockAt(x, 64, z).setType(m, false);
        }
        lobby.getBlockAt(-5, 65, 0).setType(Material.GOLD_BLOCK, false);
        lobby.getBlockAt(5, 65, 0).setType(Material.DIAMOND_BLOCK, false);
    }

    void spawnNpcs() {
        for (ArmorStand s : lobby.getEntitiesByClass(ArmorStand.class))
            if (s.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) s.remove();

        spawnNpc(new Location(lobby, -4.5, 66, 0.5, 0, 0), "§6§lUHC SOUP RUNS §7(klikni)", "uhc");
        spawnNpc(new Location(lobby, 5.5, 66, 0.5, 0, 0), "§b§l1v1 DUELS §7(klikni)", "duels");
    }

    void spawnNpc(Location loc, String name, String type) {
        lobby.spawn(loc, ArmorStand.class, a -> {
            a.setArms(true); a.setBasePlate(false); a.setGravity(false); a.setInvulnerable(true);
            a.customName(c(name)); a.setCustomNameVisible(true);
            a.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, type);
            var eq = a.getEquipment();
            if (type.equals("uhc")) {
                eq.setItemInMainHand(new ItemStack(Material.MUSHROOM_STEW));
                eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            } else {
                eq.setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
                eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                eq.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
                eq.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
                eq.setBoots(new ItemStack(Material.NETHERITE_BOOTS));
            }
        });
    }

    void sendToLobby(Player p) {
        p.getInventory().clear();
        for (var ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(maxHp(p));
        p.setFoodLevel(20); p.setSaturation(20); p.setFireTicks(0); p.setFallDistance(0);
        p.teleport(lobbySpawn());
    }

    // ================= PŘÍKAZY =================
    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("lobby")) {
            if (!(s instanceof Player p)) return true;
            if (uhcAlive.contains(p.getUniqueId()) || duels.containsKey(p.getUniqueId())) {
                p.sendMessage(c("§cTeď nemůžeš odejít, hra běží."));
                return true;
            }
            duelQueue.remove(p.getUniqueId()); uhcQueue.remove(p.getUniqueId());
            sendToLobby(p);
            return true;
        }
        if (!s.hasPermission("souparena.admin")) { s.sendMessage(c("§cNemáš oprávnění.")); return true; }
        if (args.length == 0) { s.sendMessage(c("§e/uhc start [force] | /uhc stop")); return true; }
        if (args[0].equalsIgnoreCase("start")) startUhc(s, args.length > 1 && args[1].equalsIgnoreCase("force"));
        else if (args[0].equalsIgnoreCase("stop")) {
            if (uhcState == UhcState.IDLE) s.sendMessage(c("§cUHC neběží."));
            else endUhc(null);
        }
        return true;
    }

    // ================= UHC =================
    void uhcMsg(String msg) {
        for (UUID u : uhcAll) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendMessage(c(msg)); }
    }

    void startUhc(CommandSender s, boolean force) {
        if (uhcState != UhcState.IDLE) { s.sendMessage(c("§cUHC už běží.")); return; }
        List<Player> list = new ArrayList<>();
        for (UUID u : uhcQueue) { Player p = Bukkit.getPlayer(u); if (p != null && p.getWorld().equals(lobby)) list.add(p); }
        if (list.isEmpty()) list.addAll(lobby.getPlayers());
        if (list.size() < 2 && !force) { s.sendMessage(c("§cPotřeba aspoň 2 hráčů (nebo /uhc start force).")); return; }
        if (list.isEmpty()) return;

        uhcState = UhcState.COUNTDOWN;
        Bukkit.broadcast(c("§6[UHC] §eGeneruji mapu, může to pár sekund trvat..."));
        uhcWorld = new WorldCreator("uhc_" + System.currentTimeMillis()).environment(World.Environment.NORMAL).createWorld();
        if (uhcWorld == null) { uhcState = UhcState.IDLE; s.sendMessage(c("§cNepodařilo se vytvořit svět.")); return; }
        uhcWorld.setGameRule(GameRule.NATURAL_REGENERATION, false);
        uhcWorld.setDifficulty(Difficulty.NORMAL);
        uhcWorld.setPVP(true);
        uhcWorld.setTime(1000);
        uhcWorld.getWorldBorder().setCenter(0, 0);
        uhcWorld.getWorldBorder().setSize(UHC_START_BORDER);

        uhcBar = Bukkit.createBossBar("§6UHC", BarColor.YELLOW, BarStyle.SOLID);
        Random r = new Random();
        List<Location> used = new ArrayList<>();
        for (Player p : list) {
            Location spot = null;
            for (int i = 0; i < 200 && spot == null; i++) {
                int x = r.nextInt(UHC_SPAWN_RADIUS * 2 + 1) - UHC_SPAWN_RADIUS;
                int z = r.nextInt(UHC_SPAWN_RADIUS * 2 + 1) - UHC_SPAWN_RADIUS;
                int y = uhcWorld.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block top = uhcWorld.getBlockAt(x, y, z);
                if (top.isLiquid() || top.getType() == Material.ICE) continue;
                Location l = new Location(uhcWorld, x + 0.5, y + 1, z + 0.5);
                boolean ok = true;
                for (Location o : used) if (o.distanceSquared(l) < 3600) ok = false;
                if (ok) spot = l;
            }
            if (spot == null) {
                int x = r.nextInt(401) - 200, z = r.nextInt(401) - 200;
                spot = new Location(uhcWorld, x + 0.5, uhcWorld.getHighestBlockYAt(x, z) + 1, z + 0.5);
            }
            used.add(spot);
            cage(spot, true);
            cages.add(spot);

            UUID id = p.getUniqueId();
            uhcQueue.remove(id); duelQueue.remove(id);
            uhcAll.add(id); uhcAlive.add(id);
            p.getInventory().clear();
            for (var ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(maxHp(p)); p.setFoodLevel(20); p.setSaturation(20);
            p.teleport(spot);
            uhcBar.addPlayer(p);
        }
        uhcMsg("§6[UHC] §eHra začne za §c" + UHC_COUNTDOWN + " §esekund!");
        uhcTick = 0;
        uhcTask = Bukkit.getScheduler().runTaskTimer(this, this::uhcSecond, 20, 20);
    }

    void cage(Location c0, boolean on) {
        World w = c0.getWorld();
        int bx = c0.getBlockX(), by = c0.getBlockY(), bz = c0.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 4; dy++) {
            boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
            boolean floor = dy == -1, roof = dy == 4;
            Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
            if (on) b.setType((floor || roof || wall) ? Material.GLASS : Material.AIR, false);
            else if (floor) b.setType(Material.DIRT, false);
            else if (wall || roof) b.setType(Material.AIR, false);
        }
    }

    void uhcSecond() {
        uhcTick++;
        if (uhcState == UhcState.COUNTDOWN) {
            int rem = UHC_COUNTDOWN - uhcTick;
            uhcBar.setTitle("§6UHC začíná za §c" + Math.max(rem, 0) + "s");
            uhcBar.setProgress(Math.max(0, Math.min(1, rem / (double) UHC_COUNTDOWN)));
            if (rem <= 0) { releasePlayers(); return; }
            if (rem <= 10 || rem % 10 == 0) {
                for (UUID u : uhcAll) { Player p = Bukkit.getPlayer(u); if (p != null) title(p, "§c" + rem, "§7Připrav se..."); }
            }
        } else if (uhcState == UhcState.RUNNING) {
            int rem = UHC_SECONDS - uhcTick;
            uhcBar.setTitle("§6UHC §7| zbývá §e" + String.format("%02d:%02d", rem / 60, rem % 60)
                    + " §7| živých: §a" + uhcAlive.size());
            uhcBar.setProgress(Math.max(0, Math.min(1, rem / (double) UHC_SECONDS)));
            if (rem == 600 || rem == 300 || rem == 60 || rem == 30 || (rem <= 10 && rem > 0))
                uhcMsg("§6[UHC] §eDo deathmatche zbývá §c" + rem + " §esekund.");
            if (rem <= 0) startDeathmatch();
        } else if (uhcState == UhcState.DEATHMATCH) {
            uhcBar.setTitle("§c§lDEATHMATCH §7| živých: §a" + uhcAlive.size());
            uhcBar.setProgress(1);
        }
    }

    void releasePlayers() {
        for (Location l : cages) cage(l, false);
        cages.clear();
        uhcState = UhcState.RUNNING;
        uhcTick = 0;
        for (UUID u : uhcAlive) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Material.MUSHROOM_STEW));
            title(p, "§a§lSTART!", "§7Najdi si vybavení");
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1f);
        }
        uhcWorld.getWorldBorder().setSize(UHC_END_BORDER, UHC_SECONDS - 120);
        uhcMsg("§6[UHC] §aKlece zmizely! Border se zmenšuje. Hodně štěstí!");
    }

    void startDeathmatch() {
        if (uhcAlive.size() <= 1) { checkUhcWin(); return; }
        uhcState = UhcState.DEATHMATCH;
        World w = uhcWorld;
        w.getWorldBorder().setSize(300);
        int y = 250;
        for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) {
            boolean edge = Math.abs(dx) == 16 || Math.abs(dz) == 16;
            for (int dy = 0; dy <= 21; dy++) {
                Block b = w.getBlockAt(dx, y + dy, dz);
                if (dy == 0) b.setType(Material.BEDROCK, false);
                else if (dy == 21 || edge) b.setType(Material.BARRIER, false);
                else b.setType(Material.AIR, false);
            }
        }
        int n = uhcAlive.size(), i = 0;
        for (UUID u : uhcAlive) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            double ang = 2 * Math.PI * i++ / n;
            Location l = new Location(w, Math.cos(ang) * 10 + 0.5, y + 1, Math.sin(ang) * 10 + 0.5);
            l.setDirection(new org.bukkit.util.Vector(-Math.cos(ang), 0, -Math.sin(ang)));
            p.teleport(l);
            p.setHealth(maxHp(p)); p.setFoodLevel(20); p.setSaturation(20);
            title(p, "§c§lDEATHMATCH", "§7Poslední přeživší vyhrává!");
        }
        uhcMsg("§6[UHC] §cČas vypršel! Deathmatch začíná!");
    }

    void eliminate(Player p, boolean quit) {
        if (!uhcAlive.remove(p.getUniqueId())) return;
        Location l = p.getLocation();
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType() != Material.AIR) l.getWorld().dropItemNaturally(l, it);
        p.getInventory().clear();
        String killer = lastHit.remove(p.getUniqueId());
        uhcMsg("§6[UHC] §c" + p.getName() + (quit ? " §7se odpojil" : " §7byl eliminován" + (killer != null ? " hráčem §e" + killer : ""))
                + "§7. Zbývá §a" + uhcAlive.size() + "§7.");
        if (!quit) {
            p.setHealth(maxHp(p));
            p.setGameMode(GameMode.SPECTATOR);
            l.getWorld().strikeLightningEffect(l);
            title(p, "§c§lEliminován", "§7Sleduješ hru");
        }
        checkUhcWin();
    }

    void checkUhcWin() {
        if (uhcState == UhcState.COUNTDOWN && uhcAlive.size() < 2) { endUhc(null); return; }
        if ((uhcState == UhcState.RUNNING || uhcState == UhcState.DEATHMATCH) && uhcAlive.size() <= 1) {
            Player w = null;
            for (UUID u : uhcAlive) w = Bukkit.getPlayer(u);
            endUhc(w);
        }
    }

    void endUhc(Player winner) {
        if (uhcState == UhcState.ENDING || uhcState == UhcState.IDLE) return;
        uhcState = UhcState.ENDING;
        if (uhcTask != null) uhcTask.cancel();
        if (winner != null) {
            Bukkit.broadcast(c("§6§l[UHC] §e" + winner.getName() + " §avyhrál UHC Soup Runs!"));
            title(winner, "§6§lVÍTĚZ!", "§eGratulujeme!");
            winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        } else Bukkit.broadcast(c("§6[UHC] §eHra byla ukončena."));
        Bukkit.getScheduler().runTaskLater(this, () -> finishUhc(false), winner != null ? 200 : 20);
    }

    /** Vrátí hráče do lobby a úplně smaže UHC svět (reset mapy). */
    void finishUhc(boolean sync) {
        if (uhcWorld == null) return;
        World w = uhcWorld;
        for (Player p : new ArrayList<>(w.getPlayers())) sendToLobby(p);
        File f = w.getWorldFolder();
        Bukkit.unloadWorld(w, false);
        if (uhcBar != null) { uhcBar.removeAll(); uhcBar = null; }
        uhcAlive.clear(); uhcAll.clear(); lastHit.clear(); cages.clear();
        uhcWorld = null;
        uhcState = UhcState.IDLE;
        if (sync) deleteDir(f);
        else Bukkit.getScheduler().runTaskAsynchronously(this, () -> deleteDir(f));
    }

    // ================= DUELY =================
    void joinDuel(Player p) {
        UUID id = p.getUniqueId();
        if (duels.containsKey(id)) return;
        if (duelQueue.remove(id)) { p.sendMessage(c("§b[Duels] §7Opustil jsi frontu.")); return; }
        duelQueue.add(id);
        p.sendMessage(c("§b[Duels] §aJsi ve frontě. Čekáš na soupeře... §7(klikni znovu pro odchod)"));
        tryStartDuel();
    }

    void tryStartDuel() {
        while (duelQueue.size() >= 2) {
            int slot = -1;
            for (int i = 0; i < slotUsed.length; i++) if (!slotUsed[i]) { slot = i; break; }
            if (slot < 0) return;
            Player a = Bukkit.getPlayer(duelQueue.remove(0));
            Player b = Bukkit.getPlayer(duelQueue.remove(0));
            if (a == null || b == null) {
                if (a != null) duelQueue.add(a.getUniqueId());
                if (b != null) duelQueue.add(b.getUniqueId());
                continue;
            }
            startDuel(a, b, slot);
        }
    }

    void startDuel(Player a, Player b, int slot) {
        slotUsed[slot] = true;
        Duel d = new Duel();
        d.slot = slot; d.a = a; d.b = b;
        int cx = slot * 200, cz = 0;
        d.center = new Location(duelWorld, cx + 0.5, 80, cz + 0.5);
        buildArena(cx, cz);
        duels.put(a.getUniqueId(), d); duels.put(b.getUniqueId(), d);

        int surface = 60 + DUEL_GRASS_LAYERS;
        prepareDuelist(a, new Location(duelWorld, cx - 12 + 0.5, surface + 1, cz + 0.5, -90, 0));
        prepareDuelist(b, new Location(duelWorld, cx + 12 + 0.5, surface + 1, cz + 0.5, 90, 0));

        new BukkitRunnable() {
            int n = 5;
            @Override public void run() {
                if (d.ended) { cancel(); return; }
                if (n > 0) {
                    title(a, "§e" + n, "§7Souboj začíná..."); title(b, "§e" + n, "§7Souboj začíná...");
                    n--;
                } else {
                    d.started = true;
                    title(a, "§c§lFIGHT!", ""); title(b, "§c§lFIGHT!", "");
                    cancel();
                }
            }
        }.runTaskTimer(this, 0, 20);
    }

    void prepareDuelist(Player p, Location l) {
        p.setGameMode(GameMode.SURVIVAL);
        for (var ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
        p.setHealth(maxHp(p)); p.setFoodLevel(20); p.setSaturation(20);
        p.teleport(l);
        PlayerInventory inv = p.getInventory();
        inv.clear();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
        inv.setItem(0, sword);
        inv.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
        inv.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
        inv.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
        inv.setBoots(new ItemStack(Material.NETHERITE_BOOTS));
        // 3 stacky obsidianu, 3 stacky crystalů, 6 stacků perel
        inv.setItem(1, new ItemStack(Material.OBSIDIAN, 64));
        inv.setItem(2, new ItemStack(Material.END_CRYSTAL, 64));
        inv.setItem(3, new ItemStack(Material.ENDER_PEARL, 16));
        inv.setItem(9, new ItemStack(Material.OBSIDIAN, 64));
        inv.setItem(10, new ItemStack(Material.OBSIDIAN, 64));
        inv.setItem(11, new ItemStack(Material.END_CRYSTAL, 64));
        inv.setItem(12, new ItemStack(Material.END_CRYSTAL, 64));
        for (int i = 13; i <= 17; i++) inv.setItem(i, new ItemStack(Material.ENDER_PEARL, 16));
    }

    void buildArena(int cx, int cz) {
        int top = 60 + DUEL_GRASS_LAYERS;
        int R = DUEL_HALF;
        for (int dx = -R - 1; dx <= R + 1; dx++) for (int dz = -R - 1; dz <= R + 1; dz++) {
            boolean edge = Math.abs(dx) == R + 1 || Math.abs(dz) == R + 1;
            for (int y = 60; y <= 100; y++) {
                Block b = duelWorld.getBlockAt(cx + dx, y, cz + dz);
                Material m;
                if (y == 60) m = Material.BEDROCK;
                else if (y == 100 || edge) m = Material.BARRIER;
                else if (y <= top) m = Material.GRASS_BLOCK;
                else m = Material.AIR;
                b.setType(m, false);
            }
        }
    }

    void endDuel(Duel d, Player winner, Player loser) {
        if (d.ended) return;
        d.ended = true;
        duels.remove(d.a.getUniqueId()); duels.remove(d.b.getUniqueId());
        if (winner != null && winner.isOnline()) {
            title(winner, "§6§lVYHRÁL JSI!", "§7Gratulace");
            winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        if (loser != null && loser.isOnline()) {
            loser.setHealth(maxHp(loser));
            loser.setGameMode(GameMode.SPECTATOR);
            title(loser, "§c§lPROHRÁL JSI", "§7Příště to vyjde");
        }
        Bukkit.broadcast(c("§b[Duels] §e" + (winner != null ? winner.getName() : "?") + " §7porazil §c" + (loser != null ? loser.getName() : "?")));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player p : new Player[]{d.a, d.b}) if (p.isOnline()) sendToLobby(p);
            for (Entity e : duelWorld.getNearbyEntities(d.center, 25, 30, 25)) if (!(e instanceof Player)) e.remove();
            slotUsed[d.slot] = false;
            tryStartDuel();
        }, 60);
    }

    Player other(Duel d, Player p) { return d.a.equals(p) ? d.b : d.a; }

    // ================= EVENTY =================
    @EventHandler public void onJoin(PlayerJoinEvent e) { sendToLobby(e.getPlayer()); }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer(); UUID id = p.getUniqueId();
        uhcQueue.remove(id); duelQueue.remove(id);
        if (uhcAlive.contains(id)) eliminate(p, true);
        Duel d = duels.get(id);
        if (d != null) endDuel(d, other(d, p), p);
    }

    @EventHandler public void onNpcClick(PlayerInteractAtEntityEvent e) {
        if (!(e.getRightClicked() instanceof ArmorStand s)) return;
        String type = s.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
        if (type == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (type.equals("uhc")) {
            if (uhcState != UhcState.IDLE) { p.sendMessage(c("§6[UHC] §cHra právě běží, počkej na další.")); return; }
            if (uhcQueue.remove(p.getUniqueId())) p.sendMessage(c("§6[UHC] §7Odhlášen z UHC."));
            else { uhcQueue.add(p.getUniqueId()); p.sendMessage(c("§6[UHC] §aPřihlášen! Čekej, až OP spustí hru. §7(" + uhcQueue.size() + " hráčů)")); }
        } else joinDuel(p);
    }

    @EventHandler public void onManipulate(PlayerArmorStandManipulateEvent e) {
        if (e.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) e.setCancelled(true);
    }

    // --- lobby ochrana ---
    boolean canBuildLobby(Player p) { return p.isOp() && p.getGameMode() == GameMode.CREATIVE; }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        World w = e.getBlock().getWorld();
        if (w.equals(lobby) && !canBuildLobby(e.getPlayer())) e.setCancelled(true);
        else if (w.equals(uhcWorld) && (uhcState == UhcState.COUNTDOWN || uhcState == UhcState.ENDING)) e.setCancelled(true);
    }

    @EventHandler public void onPlace(BlockPlaceEvent e) {
        World w = e.getBlock().getWorld();
        if (w.equals(lobby) && !canBuildLobby(e.getPlayer())) e.setCancelled(true);
        else if (w.equals(uhcWorld) && (uhcState == UhcState.COUNTDOWN || uhcState == UhcState.ENDING)) e.setCancelled(true);
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) {
        if (e.getPlayer().getWorld().equals(lobby)) e.setCancelled(true);
    }

    @EventHandler public void onFood(FoodLevelChangeEvent e) {
        World w = e.getEntity().getWorld();
        if (w.equals(lobby) || w.equals(duelWorld)) e.setCancelled(true);
    }

    @EventHandler public void onPortal(PlayerPortalEvent e) {
        if (e.getFrom().getWorld().equals(uhcWorld)) e.setCancelled(true);
    }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo(), from = e.getFrom();
        if (to == null) return;
        Player p = e.getPlayer();
        if (p.getWorld().equals(lobby) && to.getY() < 40) { e.setTo(lobbySpawn()); return; }
        boolean freeze = (uhcState == UhcState.COUNTDOWN && uhcAlive.contains(p.getUniqueId()));
        Duel d = duels.get(p.getUniqueId());
        if (d != null && !d.started) freeze = true;
        if (freeze && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
            e.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
        }
    }

    // --- damage ---
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Entity dmg = e.getDamager();
        if (dmg instanceof Projectile pr && pr.getShooter() instanceof Entity s) dmg = s;
        if (dmg instanceof Player) lastHit.put(v.getUniqueId(), dmg.getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        World w = p.getWorld();
        if (w.equals(lobby)) { e.setCancelled(true); return; }
        if (w.equals(duelWorld)) {
            Duel d = duels.get(p.getUniqueId());
            if (d == null || !d.started || d.ended) { e.setCancelled(true); return; }
            if (e.getFinalDamage() >= p.getHealth()) { e.setCancelled(true); endDuel(d, other(d, p), p); }
            return;
        }
        if (w.equals(uhcWorld)) {
            if (uhcState == UhcState.COUNTDOWN || uhcState == UhcState.ENDING || !uhcAlive.contains(p.getUniqueId())) {
                e.setCancelled(true); return;
            }
            if (e.getFinalDamage() >= p.getHealth()) { e.setCancelled(true); eliminate(p, false); }
        }
    }

    // --- polévky ---
    @EventHandler public void onSoup(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.MUSHROOM_STEW || e.getHand() == null) return;
        Player p = e.getPlayer();
        if (!uhcAlive.contains(p.getUniqueId())) return;
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) return;
        e.setCancelled(true);
        if (uhcState == UhcState.COUNTDOWN) return;
        double max = maxHp(p);
        if (p.getHealth() >= max) return;
        p.setHealth(Math.min(max, p.getHealth() + SOUP_HEAL));
        p.getInventory().setItem(e.getHand(), new ItemStack(Material.BOWL));
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 1f);
    }
}
