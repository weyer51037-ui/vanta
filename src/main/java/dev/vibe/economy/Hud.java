package dev.vibe.economy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sidebar scoreboard HUD + tab list header/footer for VantaSMP. */
public final class Hud implements Listener {

    private static final int LINES = 11;

    private final VibeEconomy plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private int taskId = -1;

    public Hud(VibeEconomy plugin) { this.plugin = plugin; }

    public void start() {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;
        long period = Math.max(5, plugin.getConfig().getLong("hud.update-ticks", 20));
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, period).getTaskId();
        for (Player p : Bukkit.getOnlinePlayers()) ensure(p);
    }

    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
    }

    public boolean toggle(Player p) {
        UUID id = p.getUniqueId();
        if (hidden.remove(id)) {
            ensure(p);
            return true; // now visible
        }
        hidden.add(id);
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> ensure(e.getPlayer()), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }

    @SuppressWarnings("deprecation")
    private void ensure(Player p) {
        if (hidden.contains(p.getUniqueId()) || !p.isOnline()) return;
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            String title = ItemSpec.c(plugin.getConfig().getString("hud.title", "&5&lVANTASMP"));
            Objective o = sb.registerNewObjective("vantahud", "dummy", title);
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < LINES; i++) {
                String entry = ChatColor.values()[i].toString();
                Team t = sb.registerNewTeam("line" + i);
                t.addEntry(entry);
                o.getScore(entry).setScore(LINES - i);
            }
            boards.put(p.getUniqueId(), sb);
        }
        p.setScoreboard(sb);
        update(p, sb);
    }

    private void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) ensure(p);
    }

    @SuppressWarnings("deprecation")
    private void update(Player p, Scoreboard sb) {
        UUID id = p.getUniqueId();
        int kills = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = p.getStatistic(Statistic.DEATHS);
        String kdr = deaths == 0 ? String.valueOf(kills) : String.format("%.2f", kills / (double) deaths);
        String footer = plugin.getConfig().getString("hud.footer", "&5&lVantaSMP");

        String[] lines = new String[]{
                "&8&m                    ",
                "&dMoney: &f" + plugin.money(plugin.getBalance(id)).replace(".00", ""),
                "&dShards: &f" + plugin.getShards(id),
                "&dStreak: &f" + plugin.getStreak(id),
                "&dKills: &f" + kills + " &7| &dDeaths: &f" + deaths,
                "&dK/D: &f" + kdr,
                " ",
                "&dOnline: &f" + Bukkit.getOnlinePlayers().size(),
                "&dPing: &f" + p.getPing() + "ms",
                "&8&m                    &r",
                footer
        };
        for (int i = 0; i < LINES; i++) {
            Team t = sb.getTeam("line" + i);
            if (t != null) t.setPrefix(ItemSpec.c(lines[i]));
        }

        p.setPlayerListHeaderFooter(
                ItemSpec.c(plugin.getConfig().getString("hud.tab-header", "&5&lVANTASMP")),
                ItemSpec.c(plugin.getConfig().getString("hud.tab-footer", "&7/shop &8| &7/shards &8| &7/sell all")));
    }
}
