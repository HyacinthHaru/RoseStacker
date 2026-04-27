package dev.rosewood.rosestacker.debug;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.PigZombie;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection-based probe and setter for a NeutralMob's persistentAngerTarget.
 * <p>
 * RoseStacker fix for issue #843. Handles two API generations:
 * <ul>
 *   <li><b>1.21.10 and earlier</b>: {@code setPersistentAngerTarget(UUID)} /
 *       {@code getPersistentAngerTarget(): UUID}</li>
 *   <li><b>1.21.11+</b>: {@code setPersistentAngerTarget(EntityReference<LivingEntity>)} /
 *       {@code getPersistentAngerTarget(): EntityReference<LivingEntity>}, with
 *       {@code EntityReference.of(UUID)} as a UUID-only constructor.</li>
 * </ul>
 * <p>
 * Detects the right API per server at first use and caches the resolved methods.
 * Logs once on detection so users can confirm the fix is wired correctly.
 */
public final class AngerProbe {

    private AngerProbe() {}

    private static final Logger LOG = Logger.getLogger("RoseStacker-AngerProbe");

    private static volatile Method getHandleMethod;

    private static volatile Method getPersistentAngerTargetMethod;
    private static volatile Method setPersistentAngerTargetMethod;
    private static volatile Method stopBeingAngryMethod;
    private static volatile Method entityReferenceOfUuidMethod;
    private static volatile Method entityReferenceGetUUIDMethod;

    private static volatile boolean uuidApiTried;
    private static volatile boolean uuidApiAvailable;
    private static volatile boolean refApiTried;
    private static volatile boolean refApiAvailable;

    private static volatile boolean apiBannerLogged;

    /**
     * Returns the NMS handle of a Bukkit LivingEntity if it implements NeutralMob.
     */
    private static Object getNeutralMobHandle(LivingEntity entity) {
        if (entity == null) return null;
        try {
            if (getHandleMethod == null) {
                getHandleMethod = entity.getClass().getMethod("getHandle");
                getHandleMethod.setAccessible(true);
            }
            Object handle = getHandleMethod.invoke(entity);
            if (handle == null) return null;

            // Walk class hierarchy looking for NeutralMob interface
            Class<?> c = handle.getClass();
            while (c != null) {
                for (Class<?> iface : c.getInterfaces()) {
                    if (iface.getName().equals("net.minecraft.world.entity.NeutralMob")) {
                        return handle;
                    }
                }
                c = c.getSuperclass();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Detects which setPersistentAngerTarget API the server has.
     * Logs once on first call so users can see what was detected.
     */
    private static void detectApiOnce(Class<?> handleClass) {
        if (!uuidApiTried) {
            try {
                Method m = handleClass.getMethod("setPersistentAngerTarget", UUID.class);
                m.setAccessible(true);
                setPersistentAngerTargetMethod = m;
                uuidApiAvailable = true;
            } catch (NoSuchMethodException ignored) {
                uuidApiAvailable = false;
            } catch (Throwable t) {
                uuidApiAvailable = false;
            }
            uuidApiTried = true;
        }

        if (!uuidApiAvailable && !refApiTried) {
            try {
                Class<?> erClass = Class.forName("net.minecraft.world.entity.EntityReference");

                // Find EntityReference.of(UUID) static method
                Method ofUuid = null;
                for (Method m : erClass.getDeclaredMethods()) {
                    if (!m.getName().equals("of")) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] == UUID.class) {
                        ofUuid = m;
                        break;
                    }
                }
                if (ofUuid == null) {
                    refApiAvailable = false;
                } else {
                    ofUuid.setAccessible(true);
                    entityReferenceOfUuidMethod = ofUuid;

                    // EntityReference.getUUID() — for reads
                    Method getUuid = erClass.getMethod("getUUID");
                    getUuid.setAccessible(true);
                    entityReferenceGetUUIDMethod = getUuid;

                    // setPersistentAngerTarget that takes EntityReference
                    Method setRef = handleClass.getMethod("setPersistentAngerTarget", erClass);
                    setRef.setAccessible(true);
                    setPersistentAngerTargetMethod = setRef;
                    refApiAvailable = true;
                }
            } catch (Throwable t) {
                refApiAvailable = false;
            }
            refApiTried = true;
        }

        if (!apiBannerLogged) {
            if (uuidApiAvailable) {
                LOG.info("[#843] AngerProbe API detected: legacy UUID (setPersistentAngerTarget(UUID))");
            } else if (refApiAvailable) {
                LOG.info("[#843] AngerProbe API detected: 1.21.11+ EntityReference (setPersistentAngerTarget(EntityReference))");
            } else {
                LOG.warning("[#843] AngerProbe could NOT detect setPersistentAngerTarget API on " + handleClass.getName()
                        + " — fix will be a no-op. Please report this with your server version.");
            }
            apiBannerLogged = true;
        }
    }

    /**
     * Reads the persistentAngerTarget UUID. Returns null if entity is not a NeutralMob,
     * if the field is null, or if the API can't be resolved.
     */
    public static UUID getPersistentAngerUUID(LivingEntity entity) {
        Object handle = getNeutralMobHandle(entity);
        if (handle == null) return null;

        try {
            if (getPersistentAngerTargetMethod == null) {
                Method m = handle.getClass().getMethod("getPersistentAngerTarget");
                m.setAccessible(true);
                getPersistentAngerTargetMethod = m;
            }
            Object result = getPersistentAngerTargetMethod.invoke(handle);
            if (result == null) return null;

            // 1.21.10 and earlier: result is a UUID directly
            if (result instanceof UUID u) {
                return u;
            }

            // 1.21.11+: result is an EntityReference; extract UUID via getUUID()
            if (entityReferenceGetUUIDMethod == null) {
                try {
                    entityReferenceGetUUIDMethod = result.getClass().getMethod("getUUID");
                    entityReferenceGetUUIDMethod.setAccessible(true);
                } catch (Throwable t) {
                    return null;
                }
            }
            Object uuid = entityReferenceGetUUIDMethod.invoke(result);
            return uuid instanceof UUID u ? u : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Writes the persistentAngerTarget UUID via whichever API the server provides.
     * No-op if entity is not a NeutralMob or if the API can't be resolved.
     */
    public static void setPersistentAngerUUID(LivingEntity entity, UUID uuid) {
        Object handle = getNeutralMobHandle(entity);
        if (handle == null) return;

        detectApiOnce(handle.getClass());

        try {
            if (uuidApiAvailable) {
                // 1.21.10 style
                setPersistentAngerTargetMethod.invoke(handle, uuid);
                return;
            }

            if (refApiAvailable) {
                // 1.21.11+ style — wrap UUID in an EntityReference
                Object ref = uuid == null ? null : entityReferenceOfUuidMethod.invoke(null, uuid);
                setPersistentAngerTargetMethod.invoke(handle, ref);
            }
        } catch (Throwable t) {
            // Should not happen if detectApiOnce found a method, but log just in case
            LOG.warning("[#843] AngerProbe.setPersistentAngerUUID failed: " + t);
        }
    }

    /**
     * Formats current anger state for logging.
     */
    public static String dump(LivingEntity entity) {
        if (entity == null) return "null entity";
        int timer = -1;
        try {
            if (entity instanceof PigZombie pz) timer = pz.getAnger();
        } catch (Throwable ignored) {}

        UUID uuid = getPersistentAngerUUID(entity);

        String targetStr = "?";
        try {
            if (entity instanceof org.bukkit.entity.Mob m && m.getTarget() != null) {
                targetStr = m.getTarget().getName() + "(" + m.getTarget().getType() + ")";
            } else {
                targetStr = "null";
            }
        } catch (Throwable ignored) {}

        boolean broken = (timer > 0 && uuid == null);

        return String.format("entity=%s timer=%d UUID=%s target=%s%s",
                entity.getUniqueId().toString().substring(0, 8),
                timer,
                uuid == null ? "null" : uuid.toString().substring(0, 8),
                targetStr,
                broken ? " ⚠BROKEN" : "");
    }

    /**
     * Reflectively calls NMS {@code NeutralMob.stopBeingAngry()} to fully reset anger state.
     * Clears timer, UUID, lastHurtByMob, and target in one go via the vanilla path.
     * No-op if the entity is not a NeutralMob.
     */
    public static boolean forceStopBeingAngry(LivingEntity entity) {
        Object handle = getNeutralMobHandle(entity);
        if (handle == null) return false;
        try {
            if (stopBeingAngryMethod == null) {
                Method m = handle.getClass().getMethod("stopBeingAngry");
                m.setAccessible(true);
                stopBeingAngryMethod = m;
            }
            stopBeingAngryMethod.invoke(handle);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns true if the entity is in the bug state (timer > 0 but UUID == null).
     */
    public static boolean isBroken(LivingEntity entity) {
        if (!(entity instanceof PigZombie pz)) return false;
        if (pz.getAnger() <= 0) return false;
        return getPersistentAngerUUID(entity) == null;
    }

    /**
     * Categorisation of an angry piglin's state — used by sampler to distinguish
     * between several possible failure modes that all manifest as "angry but not chasing".
     */
    public enum AngerState {
        /** Timer is 0 — not angry, normal neutral state. */
        NOT_ANGRY,
        /** Timer&gt;0, UUID set, target is alive — fully healthy aggro. */
        HEALTHY,
        /** Timer&gt;0, UUID is null — original issue #843 failure mode. */
        NO_UUID,
        /** Timer&gt;0, UUID set, target field is null — updatePersistentAnger isn't refreshing. */
        NO_TARGET,
        /** Timer&gt;0, UUID set, target is non-null but isAlive==false. */
        DEAD_TARGET,
        /** Other unexpected state (probe failure or unknown). */
        OTHER,
    }

    public static AngerState classify(LivingEntity entity) {
        if (!(entity instanceof PigZombie pz)) return AngerState.OTHER;
        if (pz.getAnger() <= 0) return AngerState.NOT_ANGRY;

        UUID uuid = getPersistentAngerUUID(entity);
        if (uuid == null) return AngerState.NO_UUID;

        org.bukkit.entity.LivingEntity target = null;
        if (entity instanceof org.bukkit.entity.Mob m) {
            target = m.getTarget();
        }

        if (target == null) return AngerState.NO_TARGET;
        if (!target.isValid() || target.isDead()) return AngerState.DEAD_TARGET;
        return AngerState.HEALTHY;
    }

    /**
     * Detailed dump for a piglin's anger state, including target alive/type info.
     * For per-stuck-sample logging in BrokenPiglinSampler.
     */
    public static String dumpDetailed(LivingEntity entity) {
        if (entity == null) return "null entity";

        int timer = -1;
        try {
            if (entity instanceof PigZombie pz) timer = pz.getAnger();
        } catch (Throwable ignored) {}

        UUID uuid = getPersistentAngerUUID(entity);

        org.bukkit.entity.LivingEntity target = null;
        try {
            if (entity instanceof org.bukkit.entity.Mob m) target = m.getTarget();
        } catch (Throwable ignored) {}

        String targetStr;
        if (target == null) {
            targetStr = "null";
        } else {
            String aliveStr = target.isValid() ? "alive" : (target.isDead() ? "dead" : "removed");
            targetStr = String.format("%s(%s,%s)",
                    target.getName(),
                    target.getType(),
                    aliveStr);
        }

        AngerState state = classify(entity);

        org.bukkit.Location loc = entity.getLocation();
        return String.format("entity=%s state=%s timer=%d UUID=%s target=%s pos=(%.1f,%.1f,%.1f)",
                entity.getUniqueId().toString().substring(0, 8),
                state.name(),
                timer,
                uuid == null ? "null" : uuid.toString(),
                targetStr,
                loc.getX(), loc.getY(), loc.getZ());
    }
}
