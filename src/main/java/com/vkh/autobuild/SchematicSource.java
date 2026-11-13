package com.vkh.autobuild;

import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reads the schematic the player loaded in Litematica. Uses reflection so the mod
 * compiles without Litematica and only needs it at runtime.
 * Litematica exposes the loaded schematic as a client-side "schematic world" whose
 * block positions are already in real world coordinates.
 */
public final class SchematicSource {
    private static boolean init;
    private static Method getWorld;
    private static Object instance;

    private SchematicSource() {}

    private static void init() {
        if (init) return;
        init = true;
        try {
            Class<?> c = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            getWorld = c.getMethod("getSchematicWorld");
            if (!Modifier.isStatic(getWorld.getModifiers())) {
                Field f = c.getField("INSTANCE");
                instance = f.get(null);
            }
        } catch (Throwable t) {
            getWorld = null;
        }
    }

    public static boolean litematicaPresent() {
        init();
        return getWorld != null;
    }

    /** The schematic world, or null if Litematica is missing or no schematic is loaded. */
    public static World world() {
        init();
        if (getWorld == null) return null;
        try {
            Object o = getWorld.invoke(instance);
            return o instanceof World w ? w : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
