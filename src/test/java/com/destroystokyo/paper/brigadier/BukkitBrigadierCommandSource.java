package com.destroystokyo.paper.brigadier;

/**
 * Paper exposed this as an API type on some 1.19.x lines, but it is absent
 * from the local Purpur 1.19.4 API jar used by these tests. The server jar
 * still references it on CommandSourceStack, so provide a minimal test stub
 * to allow the legacy bootstrap environment to load.
 */
public interface BukkitBrigadierCommandSource {
}
