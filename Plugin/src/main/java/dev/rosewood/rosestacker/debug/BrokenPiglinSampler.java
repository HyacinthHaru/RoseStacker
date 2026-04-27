package dev.rosewood.rosestacker.debug;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Issue #843 v4 — anger sampler + active janitor.
 * <p>
 * Periodically scans every online player's surroundings and:
 * <ol>
 *   <li>Counts ZombifiedPiglins by anger state
 *       (healthy / no_uuid / no_target / dead_target / other).</li>
 *   <li>For any "stuck" mob, calls vanilla {@code NeutralMob.stopBeingAngry()}
 *       to reset its anger state. The next time a player attacks the mob,
 *       normal aggro paths re-establish a healthy state.</li>
 * </ol>
 *
 * <p>This is the v4 cure for the 1.21.11 SpearUseGoal friendly-fire scenario:
 * a piglin's spear hits another piglin, victim's target gets locked onto the
 * (eventually killed) attacker piglin, and vanilla never cleans up the
 * resulting "timer&gt;0 + target=dead piglin" state.
 */
public final class BrokenPiglinSampler {

    private static final int SCAN_RADIUS = 30;
    private static final long PERIOD_TICKS = 60L;          // 3 seconds
    private static final long DUMP_INTERVAL_MS = 15_000L;  // 15 seconds

    private final Plugin plugin;
    private BukkitRunnable task;
    private long lastDumpAt = 0L;
    private long totalCleaned = 0L;

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
                    int cleaned = 0;

                    PigZombie sampleStuck = null;
                    String sampleStuckDump = null;

                    for (Entity e : player.getNearbyEntities(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
                        if (!(e instanceof PigZombie pz)) continue;
                        total++;

                        AngerProbe.AngerState state = AngerProbe.classify(pz);
                        boolean isStuck = false;

                        switch (state) {
                            case NOT_ANGRY -> {}
                            case HEALTHY -> { sound++; healthy++; }
                            case NO_UUID -> { sound++; noUuid++; isStuck = true; }
                            case NO_TARGET -> { sound++; noTarget++; isStuck = true; }
                            case DEAD_TARGET -> { sound++; deadTarget++; isStuck = true; }
                            case OTHER -> { sound++; other++; isStuck = true; }
                        }

                        if (isStuck) {
                            // Capture dump BEFORE cleanup so we can see what was stuck
                            if (sampleStuck == null) {
                                sampleStuck = pz;
                                sampleStuckDump = AngerProbe.dumpDetailed(pz);
                            }
                            // Active cleanup: reset to neutral via vanilla stopBeingAngry path
                            if (AngerProbe.forceStopBeingAngry(pz)) {
                                cleaned++;
                            }
                        }
                    }

                    int stuck = noUuid + noTarget + deadTarget + other;
                    if (total == 0) continue;

                    totalCleaned += cleaned;

                    plugin.getLogger().info(String.format(
                            "[#843 SAMPLE] near %s world=%s sound=%d healthy=%d stuck=%d "
                                    + "(no_uuid=%d no_target=%d dead_target=%d other=%d) cleaned=%d total_cleaned=%d",
                            player.getName(), player.getWorld().getName(),
                            sound, healthy, stuck,
                            noUuid, noTarget, deadTarget, other,
                            cleaned, totalCleaned));

                    if (shouldDump && sampleStuckDump != null) {
                        plugin.getLogger().warning("[#843 STUCK-SAMPLE] near " + player.getName()
                                + " " + sampleStuckDump);
                    }
                }
            }
        };
        this.task.runTaskTimer(plugin, 100L, PERIOD_TICKS);
        plugin.getLogger().info("[#843] BrokenPiglinSampler v4 (sampler+janitor) started: scan radius="
                + SCAN_RADIUS + " period=" + PERIOD_TICKS + "ticks dump_every="
                + (DUMP_INTERVAL_MS / 1000) + "s");
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }
}
