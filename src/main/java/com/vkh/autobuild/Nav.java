package com.vkh.autobuild;

import net.minecraft.util.math.BlockPos;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Navigation through Baritone chat commands (works with any Baritone build that has the # prefix). */
public final class Nav {
    private Nav() {}

    public static boolean baritonePresent() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void send(String msg) {
        if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatMessage(msg);
    }

    public static void goTo(BlockPos p) {
        send("#goto " + p.getX() + " " + p.getY() + " " + p.getZ());
    }

    public static void cancel() {
        send("#cancel");
    }

    /** Baritone must not place/break blocks on its own while we build. */
    public static void lockBlocks(boolean lock) {
        send("#set allowPlace " + (!lock));
        send("#set allowBreak " + (!lock));
    }
}
