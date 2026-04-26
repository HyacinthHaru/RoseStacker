package dev.rosewood.rosestacker.debug;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/**
 * Debug build only — periodically scans every online player's surroundings
 * and counts ZombifiedPiglins in the bug state (timer&gt;0 but persistentAngerTarget==null).
 * <p>
 * Logs format:
 * <pre>
 *   [#843 SAMPLE] near &lt;player&gt; world=&lt;world&gt; broken=N/M (XX%) sound=K
 * </pre>
 *
 * <ul>
 *   <li>{@code N} — broken piglins</li>
 *   <li>{@code M} — total piglins in scan radius</li>
 *   <li>{@code K} — piglins still emitting angry sound (timer&gt;0)</li>
 * </ul>
 *
 * <p>If our hypothesis is correct, on Leaves/Paper 1.21.11 + RoseStacker:
 * the {@code broken} count will rise monotonically over time as piglins are killed
 * and unstacked. On 1.21.10 it should hover near zero (auto-cleanup via counter expiry).
 */
public final class BrokenPiglinSampler {

    private static final int SCAN_RADIUS = 30;
    private static final long PERIOD_TICKS = 60L; // 3 seconds

    private final Plugin plugin;
    private BukkitRunnable task;

    public BrokenPiglinSampler(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (this.task != null) return;
        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    int total = 0;
                    int broken = 0;
                    int sounding = 0;
                    for (Entity e : player.getNearbyEntities(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
                        if (!(e instanceof PigZombie pz)) continue;
                        total++;
                        int anger = pz.getAnger();
                        if (anger > 0) sounding++;
                        if (anger > 0) {
                            UUID uuid = AngerProbe.getPersistentAngerUUID(pz);
                            if (uuid == null) broken++;
                        }
                    }
                    if (total > 0) {
                        int pct = total == 0 ? 0 : (broken * 100 / total);
                        plugin.getLogger().info(String.format(
                                "[#843 SAMPLE] near %s world=%s broken=%d/%d (%d%%) sound=%d",
                                player.getName(), player.getWorld().getName(),
                                broken, total, pct, sounding));
                    }
                }
            }
        };
        this.task.runTaskTimer(plugin, 100L, PERIOD_TICKS); // start after 5s, then every 3s
        plugin.getLogger().info("[#843] BrokenPiglinSampler started: scan radius=" + SCAN_RADIUS
                + " period=" + PERIOD_TICKS + "ticks");
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }
}
