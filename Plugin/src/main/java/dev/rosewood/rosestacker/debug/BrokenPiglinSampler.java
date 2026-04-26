package dev.rosewood.rosestacker.debug;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Debug build only — periodically scans every online player's surroundings
 * and counts ZombifiedPiglins by anger state.
 * <p>
 * Issue #843 v3: distinguishes multiple "stuck" failure modes so we can tell
 * <em>which</em> path is broken. Logs format:
 * <pre>
 *   [#843 SAMPLE] near &lt;player&gt; world=&lt;world&gt; sound=N
 *                 healthy=H stuck=S (no_uuid=A no_target=B dead_target=C)
 * </pre>
 *
 * <p>Plus, every 15 seconds, dumps detailed state of one stuck piglin near each
 * player (if any) so we can inspect the exact in-memory state.
 */
public final class BrokenPiglinSampler {

    private static final int SCAN_RADIUS = 30;
    private static final long PERIOD_TICKS = 60L;          // 3 seconds
    private static final long DUMP_INTERVAL_MS = 15_000L;  // 15 seconds

    private final Plugin plugin;
    private BukkitRunnable task;
    private long lastDumpAt = 0L;

    public BrokenPiglinSampler(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (this.task != null) return;
        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                boolean shouldDump = (now - lastDumpAt) >= DUMP_INTERVAL_MS;
                if (shouldDump) lastDumpAt = now;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    int total = 0;
                    int sound = 0;
                    int healthy = 0;
                    int noUuid = 0;
                    int noTarget = 0;
                    int deadTarget = 0;
                    int other = 0;

                    PigZombie sampleStuck = null;

                    for (Entity e : player.getNearbyEntities(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
                        if (!(e instanceof PigZombie pz)) continue;
                        total++;

                        AngerProbe.AngerState state = AngerProbe.classify(pz);
                        switch (state) {
                            case NOT_ANGRY -> {}
                            case HEALTHY -> { sound++; healthy++; }
                            case NO_UUID -> { sound++; noUuid++; if (sampleStuck == null) sampleStuck = pz; }
                            case NO_TARGET -> { sound++; noTarget++; if (sampleStuck == null) sampleStuck = pz; }
                            case DEAD_TARGET -> { sound++; deadTarget++; if (sampleStuck == null) sampleStuck = pz; }
                            case OTHER -> { sound++; other++; if (sampleStuck == null) sampleStuck = pz; }
                        }
                    }

                    int stuck = noUuid + noTarget + deadTarget + other;
                    if (total == 0) continue;

                    plugin.getLogger().info(String.format(
                            "[#843 SAMPLE] near %s world=%s sound=%d healthy=%d stuck=%d "
                                    + "(no_uuid=%d no_target=%d dead_target=%d other=%d)",
                            player.getName(), player.getWorld().getName(),
                            sound, healthy, stuck,
                            noUuid, noTarget, deadTarget, other));

                    if (shouldDump && sampleStuck != null) {
                        plugin.getLogger().warning("[#843 STUCK-SAMPLE] near " + player.getName()
                                + " " + AngerProbe.dumpDetailed(sampleStuck));
                    }
                }
            }
        };
        this.task.runTaskTimer(plugin, 100L, PERIOD_TICKS);
        plugin.getLogger().info("[#843] BrokenPiglinSampler v3 started: scan radius=" + SCAN_RADIUS
                + " period=" + PERIOD_TICKS + "ticks dump_every=" + (DUMP_INTERVAL_MS / 1000) + "s");
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }
}
