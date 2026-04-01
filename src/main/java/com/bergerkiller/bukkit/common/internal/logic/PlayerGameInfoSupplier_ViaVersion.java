package com.bergerkiller.bukkit.common.internal.logic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bergerkiller.bukkit.common.protocol.PlayerGameInfo;
import org.bukkit.entity.Player;

import com.bergerkiller.mountiplex.logic.TextValueSequence;

/**
 * Detects the game version of the player by communicating with the ViaVersion API.
 * Only used when the ViaVersion plugin is enabled.
 */
public class PlayerGameInfoSupplier_ViaVersion implements Function<Player, PlayerGameInfo> {
    private final Object api;
    private final Method getPlayerVersionMethod;
    private final Entry[] entries;

    public PlayerGameInfoSupplier_ViaVersion() {
        this.api = initViaApi();
        this.getPlayerVersionMethod = findGetPlayerVersionMethod(this.api);

        // Initialize mapping with enough room to spare
        List<?> protocols = getProtocols();
        entries = new Entry[protocols.stream()
                .mapToInt(PlayerGameInfoSupplier_ViaVersion::getProtocolVersionNumber)
                .max().getAsInt() + 1];

        // Store in mapping, ignore from before netty rewrite, those versions are cringe
        protocols.stream()
            .map(Entry::new)
            .filter(e -> e.protocolVersion >= 0) // Filter UNKNOWN
            .filter(e -> TextValueSequence.evaluate(e.minimum, ">=", TextValueSequence.parse("1.8")))
            .forEach(e -> entries[e.protocolVersion] = e);

        // Patch up gaps so we don't need to do dumb null checks
        boolean initial = true;
        for (int i = 0; i < entries.length; i++) {
            if (initial && entries[i] != null) {
                initial = false;
                Arrays.fill(entries, 0, i, entries[i]);
            } else if (!initial && entries[i] == null) {
                entries[i] = entries[i - 1];
            }
        }
    }

    @Override
    public PlayerGameInfo apply(Player player) {
        int protocolVersion = getPlayerVersion(player);
        try {
            return entries[protocolVersion];
        } catch (IndexOutOfBoundsException ex) {
            // This really doesn't happen or shouldn't happen at all
            // We don't care, but provide a fallback
            return entries[entries.length - 1];
        }
    }

    private static Object initViaApi() {
        try {
            return findClass(
                    "com.viaversion.viaversion.api.Via",
                    "us.myles.ViaVersion.api.Via"
            ).getMethod("getAPI").invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to access ViaVersion API", ex);
        }
    }

    private static List<?> getProtocols() {
        try {
            return (List<?>) findClass(
                    "com.viaversion.viaversion.api.protocol.version.ProtocolVersion",
                    "us.myles.ViaVersion.api.protocol.ProtocolVersion"
            ).getMethod("getProtocols").invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to enumerate ViaVersion protocol versions", ex);
        }
    }

    private static Method findGetPlayerVersionMethod(Object api) {
        Class<?> apiType = api.getClass();

        try {
            return apiType.getMethod("getPlayerVersion", Player.class);
        } catch (NoSuchMethodException ex) {
        }

        try {
            return apiType.getMethod("getPlayerVersion", UUID.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("ViaVersion API does not expose a supported getPlayerVersion method", ex);
        }
    }

    private int getPlayerVersion(Player player) {
        try {
            Class<?> parameterType = getPlayerVersionMethod.getParameterTypes()[0];
            Object argument = (parameterType == Player.class) ? player : player.getUniqueId();
            return ((Number) getPlayerVersionMethod.invoke(api, argument)).intValue();
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException("Failed to query ViaVersion player protocol", ex);
        }
    }

    private static int getProtocolVersionNumber(Object version) {
        return ((Number) callMethod(version, "getVersion")).intValue();
    }

    private static Class<?> findClass(String... classNames) {
        for (String className : classNames) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException ex) {
            }
        }
        throw new IllegalStateException("No compatible ViaVersion API classes were found");
    }

    private static Object callMethod(Object instance, String methodName) {
        try {
            return instance.getClass().getMethod(methodName).invoke(instance);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to call ViaVersion method " + methodName, ex);
        }
    }

    private static class Entry implements PlayerGameInfo {
        public final int protocolVersion;
        public final TextValueSequence minimum;
        public final TextValueSequence maximum;

        @SuppressWarnings("unchecked")
        public Entry(Object version) {
            this.protocolVersion = getProtocolVersionNumber(version);
            String name = (String) callMethod(version, "getName");
            if ((Boolean) callMethod(version, "isVersionWildcard")) {
                String str = name.substring(0, name.length() - 2);
                this.minimum = TextValueSequence.parse(str);
                this.maximum = TextValueSequence.parse(str + ".9");
            } else if ((Boolean) callMethod(version, "isRange")) {
                List<TextValueSequence> list = ((Set<String>) callMethod(version, "getIncludedVersions")).stream()
                        .map(TextValueSequence::parse)
                        .sorted()
                        .collect(Collectors.toList());
                this.minimum = list.get(0);
                this.maximum = list.get(list.size() - 1);
            } else {
                this.minimum = this.maximum = TextValueSequence.parse(name);
            }
        }

        @Override
        public String version() {
            return this.maximum.toString();
        }

        @Override
        public boolean evaluateVersion(String operand, TextValueSequence rightSide) {
            int len = operand.length();
            if (len == 0 || len > 2) {
                return false;
            }
            char first = operand.charAt(0);
            char second = (len == 2) ? operand.charAt(1) : ' ';
            if (first == '>') {
                // [1.12.1, 1.12.2] > 1.12.1 = true
                int comp = this.minimum.compareTo(rightSide);
                if (second == '=') {
                    return comp >= 0;
                } else {
                    return comp > 0;
                }
            } else if (first == '<') {
                // [1.12.1, 1.12.2] < 1.12.2 = true
                int comp = this.maximum.compareTo(rightSide);
                if (second == '=') {
                    return comp <= 0;
                } else {
                    return comp < 0;
                }
            } else if (first == '=' && second == '=') {
                if (this.minimum == this.maximum) {
                    return this.minimum.equals(rightSide);
                } else {
                    return this.minimum.compareTo(rightSide) >= 0
                            && this.maximum.compareTo(rightSide) <= 0;
                }
            } else if (first == '!' && second == '=') {
                if (this.minimum == this.maximum) {
                    return !this.minimum.equals(rightSide);
                } else {
                    return this.minimum.compareTo(rightSide) < 0
                            || this.maximum.compareTo(rightSide) > 0;
                }
            } else {
                return false;
            }
        }
    }
}
