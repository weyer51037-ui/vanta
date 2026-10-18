package dev.vibe.economy;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One entry from shop.yml: a shop item, an action, or a key-loot reward. */
public final class ItemSpec {
    public final String id;
    public final Material material;
    public final int amount;
    public final String name;
    public final List<String> lore;
    public final List<String> enchants;
    public final String potion;
    public final List<String> commands;
    public final String action;
    public final double price;
    public final String currency;
    public final int weight;
    public final boolean broadcast;
    public final long shards;
    public final double money;

    public static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    public ItemSpec(String id, ConfigurationSection s, String defaultCurrency) {
        this.id = id;
        Material m = Material.matchMaterial(s.getString("material", "STONE"));
        this.material = (m == null || !m.isItem()) ? Material.BARRIER : m;
        this.amount = Math.max(1, s.getInt("amount", 1));
        this.name = s.getString("name", null);
        this.lore = s.getStringList("lore");
        this.enchants = s.getStringList("enchants");
        this.potion = s.getString("potion", null);
        this.commands = s.getStringList("commands");
        String act = s.getString("action", null);
        this.action = act == null ? null : act.toUpperCase(Locale.ROOT);
        this.price = s.getDouble("price", 0);
        this.currency = s.getString("currency", defaultCurrency).toLowerCase(Locale.ROOT);
        this.weight = Math.max(1, s.getInt("weight", 1));
        this.broadcast = s.getBoolean("broadcast", false);
        this.shards = s.getLong("shards", 0);
        this.money = s.getDouble("money", 0);
    }

    /** True if buying/winning this gives a physical item. */
    public boolean givesItem() {
        return commands.isEmpty() && action == null && shards <= 0 && money <= 0;
    }

    public String label() {
        if (name != null) return ChatColor.stripColor(c(name));
        if (shards > 0) return shards + " Vanta Shards";
        if (money > 0) return String.format(Locale.US, "$%,.0f", money);
        if (action != null) return id;
        return amount + "x " + material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public ItemStack build() {
        ItemStack it = new ItemStack(material, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        String shownName = name != null ? name : (givesItem() ? null : "&f" + id);
        if (shownName != null) meta.setDisplayName(c(shownName));
        if (!lore.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (String line : lore) l.add(c(line));
            meta.setLore(l);
        }
        if (potion != null && meta instanceof PotionMeta pm) {
            PotionType t = Registry.POTION.get(NamespacedKey.minecraft(potion.toLowerCase(Locale.ROOT)));
            if (t != null) pm.setBasePotionType(t);
        }
        for (String e : enchants) {
            String[] parts = e.split(":");
            Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
            if (en == null) continue;
            int lvl = 1;
            if (parts.length > 1) {
                try { lvl = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) { }
            }
            if (meta instanceof EnchantmentStorageMeta esm) esm.addStoredEnchant(en, lvl, true);
            else meta.addEnchant(en, lvl, true);
        }
        it.setItemMeta(meta);
        return it;
    }
}
