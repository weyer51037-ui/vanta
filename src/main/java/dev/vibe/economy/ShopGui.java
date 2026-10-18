package dev.vibe.economy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ShopGui implements Listener {

    private static final class Cat {
        String key, title, currency;
        Material icon;
        int slot;
        List<String> desc;
        final List<ItemSpec> items = new ArrayList<>();
    }

    private static final class Holder implements InventoryHolder {
        final String category; // null = main menu
        final Map<Integer, String> catSlots = new HashMap<>();
        final Map<Integer, ItemSpec> slots = new HashMap<>();
        Inventory inv;
        Holder(String category) { this.category = category; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final VibeEconomy plugin;
    private final Map<String, Cat> cats = new LinkedHashMap<>();
    private String menuTitle = "&5&lShop";

    public ShopGui(VibeEconomy plugin) {
        this.plugin = plugin;
        reload();
    }

    public List<String> categoryKeys() { return new ArrayList<>(cats.keySet()); }

    public void reload() {
        cats.clear();
        YamlConfiguration y = plugin.shopConfig();
        menuTitle = y.getString("menu-title", "&5&lShop");
        ConfigurationSection cs = y.getConfigurationSection("categories");
        if (cs == null) return;
        int defSlot = 11;
        for (String key : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(key);
            if (c == null) continue;
            Cat cat = new Cat();
            cat.key = key;
            cat.title = c.getString("title", key);
            Material icon = Material.matchMaterial(c.getString("icon", "CHEST"));
            cat.icon = icon == null ? Material.CHEST : icon;
            cat.slot = c.getInt("slot", defSlot);
            defSlot += 2;
            cat.currency = c.getString("currency", "money").toLowerCase(Locale.ROOT);
            cat.desc = c.getStringList("description");
            ConfigurationSection is = c.getConfigurationSection("items");
            if (is != null) {
                for (String id : is.getKeys(false)) {
                    ConfigurationSection s = is.getConfigurationSection(id);
                    if (s != null) cat.items.add(new ItemSpec(id, s, cat.currency));
                }
            }
            cats.put(key, cat);
        }
    }

    // ------------------------------------------------------------ menus

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(" ");
        it.setItemMeta(m);
        return it;
    }

    private ItemStack balanceItem(Player p) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ItemSpec.c("&eYour Balance"));
        List<String> l = new ArrayList<>();
        l.add(ItemSpec.c("&aMoney: &f" + plugin.money(plugin.getBalance(p.getUniqueId()))));
        l.add(ItemSpec.c("&5Vanta Shards: &d" + plugin.getShards(p.getUniqueId())));
        m.setLore(l);
        it.setItemMeta(m);
        return it;
    }

    public void openMain(Player p) {
        Holder h = new Holder(null);
        Inventory inv = Bukkit.createInventory(h, 27, ItemSpec.c(menuTitle));
        h.inv = inv;
        for (int i = 0; i < 27; i++) inv.setItem(i, filler());
        for (Cat cat : cats.values()) {
            ItemStack it = new ItemStack(cat.icon);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(ItemSpec.c(cat.title));
            List<String> l = new ArrayList<>();
            for (String d : cat.desc) l.add(ItemSpec.c(d));
            l.add("");
            l.add(ItemSpec.c("&eClick to open"));
            m.setLore(l);
            m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(m);
            inv.setItem(cat.slot, it);
            h.catSlots.put(cat.slot, cat.key);
        }
        inv.setItem(22, balanceItem(p));
        p.openInventory(inv);
    }

    public boolean openCategory(Player p, String key) {
        Cat cat = cats.get(key);
        if (cat == null) return false;
        Holder h = new Holder(key);
        Inventory inv = Bukkit.createInventory(h, 54, ItemSpec.c(cat.title));
        h.inv = inv;
        int i = 0;
        for (ItemSpec spec : cat.items) {
            if (i >= 45) break; // one page; keep categories at 45 items or fewer
            ItemStack icon = spec.build();
            ItemMeta m = icon.getItemMeta();
            List<String> lore = new ArrayList<>();
            if (m.hasLore()) lore.addAll(m.getLore());
            lore.add("");
            lore.add(priceLine(spec));
            lore.add(ItemSpec.c("&eClick to buy"));
            m.setLore(lore);
            m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            icon.setItemMeta(m);
            inv.setItem(i, icon);
            h.slots.put(i, spec);
            i++;
        }
        for (int s = 45; s < 54; s++) inv.setItem(s, filler());
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName(ItemSpec.c("&7Back"));
        back.setItemMeta(bm);
        inv.setItem(45, back);
        inv.setItem(49, balanceItem(p));
        p.openInventory(inv);
        return true;
    }

    private String priceLine(ItemSpec s) {
        if (s.currency.equals("shards")) return ItemSpec.c("&5Price: &d" + (long) s.price + " Vanta Shards");
        return ItemSpec.c("&aPrice: &f" + plugin.money(s.price));
    }

    // ------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;
        int slot = e.getRawSlot();

        if (h.category == null) {
            String cat = h.catSlots.get(slot);
            if (cat != null) openCategory(p, cat);
            return;
        }
        if (slot == 45) { openMain(p); return; }
        ItemSpec spec = h.slots.get(slot);
        if (spec != null) purchase(p, spec, h.category);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private void purchase(Player p, ItemSpec spec, String categoryKey) {
        UUID id = p.getUniqueId();
        boolean shards = spec.currency.equals("shards");
        double cost = spec.price;

        if (shards ? plugin.getShards(id) < cost : !plugin.has(id, cost)) {
            plugin.msg(p, "&cYou can't afford that. Price: " + (shards ? (long) cost + " Vanta Shards" : plugin.money(cost)));
            return;
        }
        // pre-checks so nobody pays for something that fails
        if ("KEYALL".equals(spec.action)) {
            long left = plugin.keyAllCooldownLeftSeconds();
            if (left > 0) {
                plugin.msg(p, "&cKey All is on cooldown for another &f" + (left / 60) + "m " + (left % 60) + "s&c.");
                return;
            }
        }
        if ("REPAIR_HAND".equals(spec.action)) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR || !(hand.getItemMeta() instanceof Damageable d) || d.getDamage() <= 0) {
                plugin.msg(p, "&cHold a damaged item first.");
                return;
            }
        }

        if (shards) plugin.takeShards(id, (long) cost);
        else plugin.withdraw(id, cost);

        plugin.grant(p, spec);
        plugin.msg(p, "&aPurchased &f" + spec.label() + "&a for &f"
                + (shards ? (long) cost + " Vanta Shards" : plugin.money(cost)) + "&a.");
        // refresh the balance display
        openCategory(p, categoryKey);
    }
}
