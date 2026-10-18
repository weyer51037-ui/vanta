package dev.vibe.economy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Put items in the top slots, click Confirm to sell. Unsellable items are returned. */
public final class SellGui implements Listener {

    private static final int ITEM_SLOTS = 45;   // slots 0-44 are free for items
    private static final int CANCEL = 45;
    private static final int TOTAL = 49;
    private static final int CONFIRM = 53;

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final VibeEconomy plugin;

    public SellGui(VibeEconomy plugin) { this.plugin = plugin; }

    private ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ItemSpec.c(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(ItemSpec.c(s));
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    public void open(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 54, ItemSpec.c("&a&lSell Items"));
        h.inv = inv;
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = ITEM_SLOTS; i < 54; i++) inv.setItem(i, filler);
        inv.setItem(CANCEL, button(Material.RED_STAINED_GLASS_PANE, "&c&lCancel", "&7Close and get your items back."));
        inv.setItem(CONFIRM, button(Material.LIME_STAINED_GLASS_PANE, "&a&lConfirm Sale", "&7Sell everything sellable above."));
        refreshTotal(inv);
        p.openInventory(inv);
    }

    private void refreshTotal(Inventory inv) {
        double total = 0;
        int unsellable = 0;
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            double v = plugin.stackValue(it);
            if (v < 0) unsellable += it.getAmount();
            else total += v;
        }
        List<String> lore = new ArrayList<>();
        lore.add("&7Click &aConfirm &7to sell.");
        if (unsellable > 0) lore.add("&c" + unsellable + " item(s) can't be sold and will be returned.");
        inv.setItem(TOTAL, button(Material.GOLD_INGOT, "&eTotal: &a" + plugin.money(Math.round(total * 100.0) / 100.0),
                lore.toArray(new String[0])));
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // block anything that could pull the bottom-row buttons
        if (e.getClick() == ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }

        boolean inTop = e.getClickedInventory() == top;
        int slot = e.getRawSlot();
        if (inTop && slot >= ITEM_SLOTS && slot < 54) {
            e.setCancelled(true);
            if (slot == CONFIRM) confirm(p, top);
            else if (slot == CANCEL) p.closeInventory();
            return;
        }
        if (e.getClick() == ClickType.NUMBER_KEY && inTop && slot >= ITEM_SLOTS) { e.setCancelled(true); return; }

        // normal item movement is allowed; update the total on the next tick
        Bukkit.getScheduler().runTask(plugin, () -> refreshTotal(top));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= ITEM_SLOTS && raw < 54) { e.setCancelled(true); return; }
        }
        Bukkit.getScheduler().runTask(plugin, () -> refreshTotal(top));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof Holder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        // give back anything still in the item slots
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            inv.setItem(i, null);
            for (ItemStack extra : p.getInventory().addItem(it).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), extra);
            }
        }
    }

    private void confirm(Player p, Inventory inv) {
        double total = 0;
        int count = 0;
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            double v = plugin.stackValue(it);
            if (v < 0) continue;
            total += v;
            count += it.getAmount();
            inv.setItem(i, null);
        }
        total = Math.round(total * 100.0) / 100.0;
        if (count == 0) {
            plugin.msg(p, "&cNothing sellable in the menu.");
            return;
        }
        plugin.deposit(p.getUniqueId(), total);
        plugin.msg(p, "&aSold &f" + count + " items&a for &f" + plugin.money(total) + "&a. Balance: &f"
                + plugin.money(plugin.getBalance(p.getUniqueId())));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        p.closeInventory(); // close handler returns any unsellable items
    }

    /** Called on shutdown so nobody loses items sitting in the menu. */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        }
    }
}
