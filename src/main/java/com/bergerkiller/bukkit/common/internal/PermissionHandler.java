package com.bergerkiller.bukkit.common.internal;

import com.bergerkiller.bukkit.common.utils.StringUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * Handles permission checks with *-wildcard support. LuckPerms is the
 * supported permission backend; when present it registers Bukkit super-perms
 * attachments which make {@link CommandSender#hasPermission(String)} fully
 * context-aware (world, server, etc), and it natively expands *-nodes so the
 * recursive wildcard fallback is bypassed.
 */
public class PermissionHandler implements PermissionChecker {

    private boolean hasSuperWildcardSupport = false;

    public void updateDependency(Plugin plugin, String pluginName, boolean enabled) {
        if (pluginName.equals("LuckPerms") && enabled) {
            // LuckPerms expands *-nodes natively, no need for the recursive fallback
            hasSuperWildcardSupport = true;
        }
    }

    @Override
    public boolean handlePermission(CommandSender sender, String permission) {
        // Ensure the Bukkit Permission exists so its default applies to non-permission-plugin
        // scenarios (e.g. console with no LuckPerms loaded, or unconfigured nodes)
        getPermission(permission);
        return sender.hasPermission(permission);
    }

    public org.bukkit.permissions.Permission getPermission(String node) {
        org.bukkit.permissions.Permission perm = Bukkit.getPluginManager().getPermission(node);
        if (perm == null) {
            // Figure out what permission default to use
            // This is done by checking all *-names, and if they exist, using that default

            // ===================================
            // TRUE found or anything else: TRUE
            // NOT_OP AND OP found: TRUE
            // NOT_OP found: NOT_OP
            // OP found: OP
            // otherwise: FALSE
            // ===================================
            PermissionDefaultFinder finder = new PermissionDefaultFinder();
            permCheckWildcard(finder, null, new StringBuilder(node.length()), node.split("\\."), 0);

            // Use permission default FALSE to avoid OP-players having automatic permissions for *-nodes
            perm = new org.bukkit.permissions.Permission(node, finder.getDefault());
            Bukkit.getPluginManager().addPermission(perm);
        }
        return perm;
    }

    public boolean hasPermission(CommandSender sender, String[] permissionNode) {
        if (hasSuperWildcardSupport) {
            return handlePermission(sender, StringUtil.join(".", permissionNode).toLowerCase(Locale.ENGLISH));
        }
        return permCheckWildcard(this, sender, permissionNode);
    }

    public boolean hasPermission(CommandSender sender, String permissionNode) {
        String lowerNode = permissionNode.toLowerCase(Locale.ENGLISH);
        if (handlePermission(sender, lowerNode)) {
            return true;
        }
        // Only if no *-wildcard support is available internally do we check that as well
        return !hasSuperWildcardSupport && permCheckWildcard(this, sender, lowerNode);
    }

    private static boolean permCheckWildcard(PermissionChecker checker, CommandSender sender, String node) {
        return permCheckWildcard(checker, sender, node.split("\\."));
    }

    private static boolean permCheckWildcard(PermissionChecker checker, CommandSender sender, String[] args) {
        // Compute the expected length for the StringBuilder buffer
        int expectedLength = args.length;
        for (String node : args) {
            expectedLength += node.length();
        }
        // Now call the other internal method
        return permCheckWildcard(checker, sender, new StringBuilder(expectedLength), args, 0);
    }

    /**
     * Performs a recursive permission check while taking *-permissions in
     * account
     *
     * @param sender - pass in the sender to check for
     * @param root - use a new buffer
     * @param args - pass in all parts of the permission node
     * @param argIndex - pass in 0
     * @return True if permission is granted, False if not
     */
    private static boolean permCheckWildcard(PermissionChecker checker, CommandSender sender, StringBuilder root, String[] args, int argIndex) {
        // Check the permission
        String rootText = root.toString();
        if (!rootText.isEmpty() && checker.handlePermission(sender, rootText)) {
            return true;
        }
        // End of the sequence?
        if (argIndex >= args.length) {
            return false;
        }
        int rootLength = root.length();
        if (rootLength != 0) {
            root.append('.');
            rootLength++;
        }
        final int newArgIndex = argIndex + 1;
        // Check permission with original name
        root.append(args[argIndex].toLowerCase(Locale.ENGLISH));
        if (permCheckWildcard(checker, sender, root, args, newArgIndex)) {
            return true;
        }

        // Try with *-signs
        root.setLength(rootLength);
        root.append('*');
        return permCheckWildcard(checker, sender, root, args, newArgIndex);
    }

    private static final class PermissionDefaultFinder implements PermissionChecker {

        private boolean hasTRUE, hasOP, hasNOTOP;

        public PermissionDefault getDefault() {
            if (hasTRUE) {
                return PermissionDefault.TRUE;
            } else if (hasOP) {
                return PermissionDefault.OP;
            } else if (hasNOTOP) {
                return PermissionDefault.NOT_OP;
            } else {
                return PermissionDefault.FALSE;
            }
        }

        @Override
        public boolean handlePermission(CommandSender sender, String permission) {
            org.bukkit.permissions.Permission perm = Bukkit.getPluginManager().getPermission(permission);
            if (perm == null) {
                return false;
            }
            switch (perm.getDefault()) {
                case TRUE:
                    this.hasTRUE = true;
                    break;
                case OP:
                    this.hasOP = true;
                    break;
                case NOT_OP:
                    this.hasNOTOP = true;
                    break;
                default:
                    break;
            }
            if (hasOP && hasNOTOP) {
                hasTRUE = true;
            }
            // Quit checking if we found out it's TRUE
            return hasTRUE;
        }
    }
}
