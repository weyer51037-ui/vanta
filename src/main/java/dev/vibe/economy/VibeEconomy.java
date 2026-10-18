package dev.vibe.economy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class VibeEconomy extends JavaPlugin implements Listener, TabExecutor {

    private final Map<UUID, Double> balances = new HashMap<>();
    private final Map<UUID, Long> shards = new HashMap<>();
    private final Map<Material, double[]> prices = new HashMap<>(); // [sell, buy]
    private final Map<String, Long> killCooldown = new HashMap<>();
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final List<ItemSpec> loot = new ArrayList<>();
    private final Random rnd = new Random();

    private File balanceFile, shardFile, shopFile;
    private YamlConfiguration shopCfg;
    private ShopGui gui;
    private Hud hud;
    private SellGui sellGui;
    private NamespacedKey keyTag;
    private double startingBalance;
    private long lastKeyAll = 0;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startingBalance = getConfig().getDouble("starting-balance", 250.0);
        keyTag = new NamespacedKey(this, "vanta_key");

        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File pricesFile = new File(getDataFolder(), "prices.csv");
        if (!pricesFile.exists()) saveResource("prices.csv", false);
        shopFile = new File(getDataFolder(), "shop.yml");
        if (!shopFile.exists()) saveResource("shop.yml", false);
        loadPrices(pricesFile);

        balanceFile = new File(getDataFolder(), "balances.yml");
        shardFile = new File(getDataFolder(), "shards.yml");
        loadData();

        loadShop();
        gui = new ShopGui(this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        sellGui = new SellGui(this);
        Bukkit.getPluginManager().registerEvents(sellGui, this);
        hud = new Hud(this);
        Bukkit.getPluginManager().registerEvents(hud, this);
        hud.start();

        for (String cmd : new String[]{"balance", "pay", "baltop", "sell", "worth", "shop", "shards", "keyall", "vantakey", "eco", "hud"}) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
                getCommand(cmd).setTabCompleter(this);
            }
        }

        long ticks = Math.max(1, getConfig().getLong("autosave-minutes", 5)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, ticks, ticks);
        getLogger().info("Loaded " + prices.size() + " item prices, " + loot.size() + " key rewards.");
    }

    @Override
    public void onDisable() {
        if (sellGui != null) sellGui.closeAll();
        if (hud != null) hud.stop();
        saveData();
    }

    // ---------------------------------------------------------------- storage

    private void loadPrices(File f) {
        prices.clear();
        int skipped = 0;
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 4) continue;
                Material m = Material.matchMaterial(p[0].trim());
                if (m == null) { skipped++; continue; }
                try {
                    prices.put(m, new double[]{Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim())});
                } catch (NumberFormatException e) { skipped++; }
            }
        } catch (IOException e) {
            getLogger().severe("Could not read prices.csv: " + e.getMessage());
        }
        if (skipped > 0) getLogger().info("Skipped " + skipped + " price rows (items not on this version).");
    }

    private void loadData() {
        balances.clear();
        shards.clear();
        if (balanceFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(balanceFile);
            for (String k : y.getKeys(false)) {
                try { balances.put(UUID.fromString(k), y.getDouble(k)); } catch (IllegalArgumentException ignored) { }
            }
        }
        if (shardFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(shardFile);
            for (String k : y.getKeys(false)) {
                try { shards.put(UUID.fromString(k), y.getLong(k)); } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private void saveData() {
        YamlConfiguration b = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : balances.entrySet()) b.set(e.getKey().toString(), e.getValue());
        YamlConfiguration s = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : shards.entrySet()) s.set(e.getKey().toString(), e.getValue());
        try { b.save(balanceFile); s.save(shardFile); }
        catch (IOException e) { getLogger().severe("Could not save data: " + e.getMessage()); }
    }

    YamlConfiguration shopConfig() { return shopCfg; }

    private void loadShop() {
        shopCfg = YamlConfiguration.loadConfiguration(shopFile);
        loot.clear();
        ConfigurationSection ls = shopCfg.getConfigurationSection("key-loot");
        if (ls != null) {
            for (String id : ls.getKeys(false)) {
                ConfigurationSection s = ls.getConfigurationSection(id);
                if (s != null) loot.add(new ItemSpec(id, s, "money"));
            }
        }
    }

    public void reloadShop() {
        reloadConfig();
        loadShop();
        gui.reload();
    }

    // ---------------------------------------------------------------- money + shards core

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    public double getBalance(UUID id) { return balances.getOrDefault(id, startingBalance); }
    public void setBalance(UUID id, double amt) { balances.put(id, round2(Math.max(0, amt))); }
    public boolean has(UUID id, double amt) { return getBalance(id) + 0.001 >= amt; }
    public void deposit(UUID id, double amt) { setBalance(id, getBalance(id) + amt); }
    public boolean withdraw(UUID id, double amt) {
        if (!has(id, amt)) return false;
        setBalance(id, getBalance(id) - amt);
        return true;
    }

    public int getStreak(UUID id) { return streaks.getOrDefault(id, 0); }

    public long getShards(UUID id) { return shards.getOrDefault(id, 0L); }
    public void setShards(UUID id, long amt) { shards.put(id, Math.max(0, amt)); }
    public void addShards(UUID id, long amt) { setShards(id, getShards(id) + amt); }
    public boolean takeShards(UUID id, long amt) {
        if (getShards(id) < amt) return false;
        setShards(id, getShards(id) - amt);
        return true;
    }

    String money(double v) { return String.format(Locale.US, "$%,.2f", v); }

    void msg(CommandSender s, String text) { s.sendMessage(ItemSpec.c(text)); }

    private Double parseAmount(String s) {
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) return null;
            return round2(v);
        } catch (NumberFormatException e) { return null; }
    }

    private Long parseLong(String s) {
        try {
            long v = Long.parseLong(s);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) { return null; }
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() ? off : null;
    }

    private void giveItem(Player p, ItemStack item) {
        for (ItemStack extra : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), extra);
        }
    }

    // ---------------------------------------------------------------- keys

    public ItemStack createKey(int amount) {
        Material m = Material.matchMaterial("TRIAL_KEY");
        if (m == null) m = Material.TRIPWIRE_HOOK;
        ItemStack it = new ItemStack(m, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ItemSpec.c("&5&lVanta Key"));
        List<String> lore = new ArrayList<>();
        lore.add(ItemSpec.c("&7Right-click to open."));
        lore.add(ItemSpec.c("&7Contains PvP gear, shards and more."));
        meta.setLore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(keyTag, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isKey(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(keyTag, PersistentDataType.BYTE);
    }

    public void keyAll(int amount, String by) {
        for (Player t : Bukkit.getOnlinePlayers()) {
            giveItem(t, createKey(amount));
            t.playSound(t.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        Bukkit.broadcastMessage(ItemSpec.c("&5&l[VANTA] &d" + by + " &fstarted a &5&lKEY ALL&f! Everyone online received &d"
                + amount + "x Vanta Key&f. Right-click it to open!"));
    }

    public long keyAllCooldownLeftSeconds() {
        long cd = getConfig().getLong("keyall-cooldown-minutes", 60) * 60_000L;
        long left = lastKeyAll + cd - System.currentTimeMillis();
        return left > 0 ? left / 1000 : 0;
    }

    private void rollKey(Player p) {
        if (loot.isEmpty()) { addShards(p.getUniqueId(), 10); msg(p, "&5Vanta Key: &f+10 Vanta Shards"); return; }
        int total = 0;
        for (ItemSpec s : loot) total += s.weight;
        int r = rnd.nextInt(total);
        ItemSpec won = loot.get(0);
        for (ItemSpec s : loot) {
            r -= s.weight;
            if (r < 0) { won = s; break; }
        }
        grant(p, won);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        msg(p, "&5&l[VANTA] &fYou opened a Vanta Key and got &d" + won.label() + "&f!");
        if (won.broadcast) {
            Bukkit.broadcastMessage(ItemSpec.c("&5&l[VANTA] &d" + p.getName() + " &fwon &d" + won.label() + " &ffrom a Vanta Key!"));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (!isKey(it)) return;
        e.setCancelled(true);
        it.setAmount(it.getAmount() - 1);
        rollKey(e.getPlayer());
    }

    // ---------------------------------------------------------------- grant (shop purchases + key rewards)

    public void grant(Player p, ItemSpec s) {
        if (s.action != null) {
            switch (s.action) {
                case "KEY" -> giveItem(p, createKey(s.amount));
                case "KEYALL" -> {
                    lastKeyAll = System.currentTimeMillis();
                    keyAll(s.amount, p.getName());
                }
                case "REPAIR_HAND" -> {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getItemMeta() instanceof Damageable d) {
                        d.setDamage(0);
                        hand.setItemMeta(d);
                    }
                }
                default -> getLogger().warning("Unknown action: " + s.action);
            }
        }
        for (String c : s.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
        }
        if (s.shards > 0) addShards(p.getUniqueId(), s.shards);
        if (s.money > 0) deposit(p.getUniqueId(), s.money);
        if (s.givesItem()) giveItem(p, s.build());
    }

    // ---------------------------------------------------------------- PvP rewards

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        balances.putIfAbsent(e.getPlayer().getUniqueId(), startingBalance);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        streaks.put(victim.getUniqueId(), 0);
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;

        // anti-farm checks
        if (getConfig().getBoolean("shards.block-same-ip", true)
                && killer.getAddress() != null && victim.getAddress() != null
                && killer.getAddress().getAddress().equals(victim.getAddress().getAddress())) {
            return;
        }
        long minTicks = getConfig().getLong("shards.min-victim-playtime-minutes", 30) * 60L * 20L;
        if (victim.getStatistic(Statistic.PLAY_ONE_MINUTE) < minTicks) return;

        String key = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("shards.same-victim-cooldown-seconds", 600) * 1000L;
        Long last = killCooldown.get(key);
        if (last != null && now - last < cd) {
            msg(killer, "&7No reward: you killed &f" + victim.getName() + " &7too recently.");
            return;
        }
        killCooldown.put(key, now);

        int streak = streaks.merge(killer.getUniqueId(), 1, Integer::sum);
        long base = getConfig().getLong("shards.per-kill", 5);
        long bonus = Math.min(Math.max(0, streak - 1), getConfig().getLong("shards.max-streak-bonus", 10))
                * getConfig().getLong("shards.streak-bonus", 1);
        long total = base + bonus;
        double cash = getConfig().getDouble("shards.money-per-kill", 100);

        addShards(killer.getUniqueId(), total);
        if (cash > 0) deposit(killer.getUniqueId(), cash);
        msg(killer, "&5+" + total + " Vanta Shards &7(streak " + streak + ")" + (cash > 0 ? " &a+" + money(cash) : ""));
    }

    // ---------------------------------------------------------------- selling

    double stackValue(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return -1;
        double[] p = prices.get(it.getType());
        if (p == null) return -1;
        ItemMeta meta = it.getItemMeta();
        double factor = 1.0;
        if (meta != null) {
            if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()) return -1;
            int max = it.getType().getMaxDurability();
            if (max > 0 && meta instanceof Damageable d) factor = Math.max(0.05, 1.0 - d.getDamage() / (double) max);
        }
        return p[0] * it.getAmount() * factor;
    }

    private void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        double v = stackValue(hand);
        if (v < 0) { msg(p, "&cYou can't sell that item."); return; }
        int amt = hand.getAmount();
        String name = hand.getType().name().toLowerCase().replace('_', ' ');
        p.getInventory().setItemInMainHand(null);
        deposit(p.getUniqueId(), v);
        msg(p, "&aSold &f" + amt + "x " + name + "&a for &f" + money(round2(v)) + "&a. Balance: &f" + money(getBalance(p.getUniqueId())));
    }

    private void sellAll(Player p) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        double total = 0;
        int count = 0;
        for (int i = 0; i < contents.length; i++) {
            double v = stackValue(contents[i]);
            if (v < 0) continue;
            total += v;
            count += contents[i].getAmount();
            contents[i] = null;
        }
        if (count == 0) { msg(p, "&cYou have nothing sellable in your inventory."); return; }
        p.getInventory().setStorageContents(contents);
        deposit(p.getUniqueId(), total);
        msg(p, "&aSold &f" + count + " items&a for &f" + money(round2(total)) + "&a. Balance: &f" + money(getBalance(p.getUniqueId())));
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase()) {
            case "balance": return cmdBalance(s, a);
            case "pay":     return cmdPay(s, a);
            case "baltop":  return cmdBaltop(s);
            case "sell":    return cmdSell(s, a);
            case "worth":   return cmdWorth(s, a);
            case "shop":    return cmdShop(s, a);
            case "shards":  return cmdShards(s, a);
            case "keyall":  return cmdKeyAll(s, a);
            case "vantakey":return cmdVantaKey(s, a);
            case "eco":     return cmdEco(s, a);
            case "hud":     return cmdHud(s);
        }
        return false;
    }

    private boolean cmdBalance(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) { msg(s, "&cConsole must specify a player."); return true; }
            msg(s, "&aBalance: &f" + money(getBalance(p.getUniqueId())) + " &7| &5Shards: &d" + getShards(p.getUniqueId()));
            return true;
        }
        OfflinePlayer t = findPlayer(a[0]);
        if (t == null) { msg(s, "&cPlayer not found."); return true; }
        msg(s, "&a" + t.getName() + "'s balance: &f" + money(getBalance(t.getUniqueId())));
        return true;
    }

    private boolean cmdPay(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return true; }
        if (a.length < 2) { msg(s, "&cUsage: /pay <player> <amount>"); return true; }
        OfflinePlayer t = findPlayer(a[0]);
        if (t == null) { msg(s, "&cPlayer not found."); return true; }
        if (t.getUniqueId().equals(p.getUniqueId())) { msg(s, "&cYou can't pay yourself."); return true; }
        Double amt = parseAmount(a[1]);
        if (amt == null || amt < 1) { msg(s, "&cEnter a valid amount (minimum $1)."); return true; }
        double max = getConfig().getDouble("max-pay", 100000.0);
        if (amt > max) { msg(s, "&cYou can send at most " + money(max) + " at a time."); return true; }
        if (!withdraw(p.getUniqueId(), amt)) { msg(s, "&cYou don't have enough money."); return true; }
        deposit(t.getUniqueId(), amt);
        msg(s, "&aYou paid &f" + t.getName() + " " + money(amt) + "&a. Balance: &f" + money(getBalance(p.getUniqueId())));
        Player on = t.getPlayer();
        if (on != null) msg(on, "&aYou received &f" + money(amt) + "&a from &f" + p.getName() + "&a.");
        return true;
    }

    private boolean cmdBaltop(CommandSender s) {
        int size = getConfig().getInt("baltop-size", 10);
        List<Map.Entry<UUID, Double>> top = balances.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(size).collect(Collectors.toList());
        msg(s, "&6&l--- Richest Players ---");
        int i = 1;
        for (Map.Entry<UUID, Double> e : top) {
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            if (name == null) name = e.getKey().toString().substring(0, 8);
            msg(s, "&e" + i++ + ". &f" + name + " &7- &a" + money(e.getValue()));
        }
        return true;
    }

    private boolean cmdSell(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return true; }
        if (a.length == 0) { sellGui.open(p); return true; }
        if (a[0].equalsIgnoreCase("hand")) sellHand(p);
        else if (a[0].equalsIgnoreCase("all")) sellAll(p);
        else msg(s, "&cUsage: /sell  (menu), /sell hand, /sell all");
        return true;
    }

    private boolean cmdWorth(CommandSender s, String[] a) {
        Material m;
        if (a.length == 0) {
            if (!(s instanceof Player p)) { msg(s, "&cSpecify an item."); return true; }
            m = p.getInventory().getItemInMainHand().getType();
        } else {
            m = Material.matchMaterial(a[0]);
        }
        double[] pr = m == null ? null : prices.get(m);
        if (pr == null) { msg(s, "&cThat item can't be sold."); return true; }
        msg(s, "&6" + m.name().toLowerCase().replace('_', ' ') + " &7- &aSell value: &f" + money(pr[0]));
        return true;
    }

    private boolean cmdShop(CommandSender s, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("vibeeconomy.admin")) { msg(s, "&cNo permission."); return true; }
            reloadShop();
            msg(s, "&aShop reloaded.");
            return true;
        }
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return true; }
        if (a.length > 0) {
            if (!gui.openCategory(p, a[0].toLowerCase())) gui.openMain(p);
        } else {
            gui.openMain(p);
        }
        return true;
    }

    private boolean cmdShards(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) { msg(s, "&cConsole must specify a player."); return true; }
            msg(s, "&5Vanta Shards: &d" + getShards(p.getUniqueId()));
            return true;
        }
        String sub = a[0].toLowerCase();
        if (sub.equals("give") || sub.equals("take") || sub.equals("set")) {
            if (!s.hasPermission("vibeeconomy.admin")) { msg(s, "&cNo permission."); return true; }
            if (a.length < 3) { msg(s, "&cUsage: /shards <give|take|set> <player> <amount>"); return true; }
            OfflinePlayer t = findPlayer(a[1]);
            if (t == null) { msg(s, "&cPlayer not found."); return true; }
            Long amt = parseLong(a[2]);
            if (amt == null) { msg(s, "&cInvalid amount."); return true; }
            UUID id = t.getUniqueId();
            if (sub.equals("give")) addShards(id, amt);
            else if (sub.equals("take")) setShards(id, getShards(id) - amt);
            else setShards(id, amt);
            msg(s, "&a" + t.getName() + " now has &d" + getShards(id) + " &aVanta Shards.");
            return true;
        }
        OfflinePlayer t = findPlayer(a[0]);
        if (t == null) { msg(s, "&cPlayer not found."); return true; }
        msg(s, "&5" + t.getName() + "'s Vanta Shards: &d" + getShards(t.getUniqueId()));
        return true;
    }

    private boolean cmdKeyAll(CommandSender s, String[] a) {
        if (!s.hasPermission("vibeeconomy.admin")) { msg(s, "&cNo permission."); return true; }
        int amount = 1;
        if (a.length > 0) {
            Long v = parseLong(a[0]);
            if (v == null || v > 64) { msg(s, "&cAmount must be 1-64."); return true; }
            amount = v.intValue();
        }
        keyAll(amount, s.getName());
        return true;
    }

    private boolean cmdVantaKey(CommandSender s, String[] a) {
        if (!s.hasPermission("vibeeconomy.admin")) { msg(s, "&cNo permission."); return true; }
        if (a.length < 2 || !a[0].equalsIgnoreCase("give")) { msg(s, "&cUsage: /vantakey give <player> [amount]"); return true; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { msg(s, "&cThat player must be online."); return true; }
        int amount = 1;
        if (a.length >= 3) {
            Long v = parseLong(a[2]);
            if (v == null || v > 64) { msg(s, "&cAmount must be 1-64."); return true; }
            amount = v.intValue();
        }
        giveItem(t, createKey(amount));
        msg(s, "&aGave " + t.getName() + " " + amount + " Vanta Key(s).");
        msg(t, "&5You received &d" + amount + "x Vanta Key&5.");
        return true;
    }

    private boolean cmdHud(CommandSender s) {
        if (!(s instanceof Player p)) { msg(s, "&cPlayers only."); return true; }
        boolean on = hud.toggle(p);
        msg(s, on ? "&aHUD enabled." : "&7HUD hidden. Run /hud again to show it.");
        return true;
    }

    private boolean cmdEco(CommandSender s, String[] a) {
        if (!s.hasPermission("vibeeconomy.admin")) { msg(s, "&cNo permission."); return true; }
        if (a.length < 3) { msg(s, "&cUsage: /eco <give|take|set> <player> <amount>"); return true; }
        OfflinePlayer t = findPlayer(a[1]);
        if (t == null) { msg(s, "&cPlayer not found."); return true; }
        Double amt = parseAmount(a[2]);
        if (amt == null) { msg(s, "&cInvalid amount."); return true; }
        UUID id = t.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "give": deposit(id, amt); break;
            case "take": setBalance(id, getBalance(id) - amt); break;
            case "set":  setBalance(id, amt); break;
            default: msg(s, "&cUsage: /eco <give|take|set> <player> <amount>"); return true;
        }
        msg(s, "&a" + t.getName() + "'s balance is now &f" + money(getBalance(id)));
        return true;
    }

    // ---------------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase();
        String cur = a.length > 0 ? a[a.length - 1].toLowerCase() : "";

        if (name.equals("sell") && a.length == 1) {
            for (String o : new String[]{"hand", "all"}) if (o.startsWith(cur)) out.add(o);
        } else if (name.equals("worth") && a.length == 1) {
            for (Material m : prices.keySet()) {
                String n = m.name().toLowerCase();
                if (n.startsWith(cur)) out.add(n);
                if (out.size() >= 50) break;
            }
        } else if (name.equals("shop") && a.length == 1) {
            for (String k : gui.categoryKeys()) if (k.startsWith(cur)) out.add(k);
            if ("reload".startsWith(cur) && s.hasPermission("vibeeconomy.admin")) out.add("reload");
        } else if ((name.equals("eco") && a.length == 1) || (name.equals("shards") && a.length == 1 && s.hasPermission("vibeeconomy.admin"))) {
            for (String o : new String[]{"give", "take", "set"}) if (o.startsWith(cur)) out.add(o);
            if (name.equals("shards")) addPlayers(out, cur);
        } else if (name.equals("vantakey") && a.length == 1) {
            if ("give".startsWith(cur)) out.add("give");
        } else if ((name.equals("pay") || name.equals("balance") || name.equals("shards")) && a.length == 1
                || (name.equals("eco") || name.equals("shards") || name.equals("vantakey")) && a.length == 2) {
            addPlayers(out, cur);
        }
        return out;
    }

    private void addPlayers(List<String> out, String cur) {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(cur)) out.add(p.getName());
    }
}
