package com.vkh.autobuild;

import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Set;
import java.util.function.ToDoubleFunction;

import static meteordevelopment.meteorclient.MeteorClient.mc;

final class Util {
    private Util() {}

    private static final Set<String> NO_CLICK = Set.of(
        "crafting_table", "lever", "anvil", "chipped_anvil", "damaged_anvil", "note_block", "repeater",
        "comparator", "grindstone", "cartography_table", "loom", "smithing_table", "stonecutter",
        "bell", "cake", "flower_pot", "dragon_egg", "respawn_anchor");

    static String id(BlockState s) {
        return Registries.BLOCK.getId(s.getBlock()).getPath();
    }

    /** Can we right-click this block to place against it without opening a GUI? */
    static boolean canClickOn(World w, BlockPos pos, BlockState s) {
        if (s.isAir() || s.isReplaceable() || !s.getFluidState().isEmpty()) return false;
        if (w.getBlockEntity(pos) != null) return false;
        String n = id(s);
        if (NO_CLICK.contains(n)) return false;
        return !(n.endsWith("_door") || n.endsWith("_trapdoor") || n.endsWith("_fence_gate")
            || n.endsWith("_button") || n.endsWith("_bed") || n.endsWith("shulker_box"));
    }

    static boolean replaceable(BlockState s) {
        return s.isAir() || s.isReplaceable() || !s.getFluidState().isEmpty();
    }

    /** Targets we never try to place by themselves (second halves, fluids, items-less blocks). */
    static boolean skipTarget(BlockState s) {
        if (s.getBlock().asItem() == Items.AIR) return true;
        for (Property<?> p : s.getProperties()) {
            String n = p.getName();
            String v = String.valueOf(s.get(p));
            if (n.equals("half") && v.equals("upper")) return true;
            if (n.equals("part") && v.equals("head")) return true;
        }
        String n = id(s);
        return n.equals("piston_head") || n.equals("moving_piston");
    }

    static boolean isShulkerBlock(BlockState s) {
        return id(s).endsWith("shulker_box");
    }

    static boolean isShulkerStack(ItemStack st) {
        return !st.isEmpty() && Registries.ITEM.getId(st.getItem()).getPath().endsWith("shulker_box");
    }

    static Iterable<ItemStack> contents(ItemStack st) {
        ContainerComponent c = st.get(DataComponentTypes.CONTAINER);
        return c == null ? java.util.List.of() : c.iterateNonEmpty();
    }

    static boolean inReach(BlockPos pos, double reach) {
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= reach * reach;
    }

    static int freeSlots() {
        PlayerInventory inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isEmpty()) n++;
        return n;
    }

    static boolean walkable(World w, BlockPos p) {
        BlockState feet = w.getBlockState(p), head = w.getBlockState(p.up()), below = w.getBlockState(p.down());
        return feet.getCollisionShape(w, p).isEmpty() && feet.getFluidState().isEmpty()
            && head.getCollisionShape(w, p.up()).isEmpty() && head.getFluidState().isEmpty()
            && !below.getCollisionShape(w, p.down()).isEmpty() && below.getFluidState().isEmpty();
    }

    /**
     * Picks the best standing position within reach of {@code near}. {@code bonus} lets the caller
     * reward positions from which many other tasks are reachable (smart route planning).
     */
    static BlockPos findStand(BlockPos near, int rad, double reach, ToDoubleFunction<BlockPos> bonus) {
        World w = mc.world;
        BlockPos best = null;
        double bestScore = -1e18;
        Vec3d pl = mc.player.getPos();
        Vec3d target = Vec3d.ofCenter(near);
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++)
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos p = near.add(dx, dy, dz);
                    if (p.equals(near)) continue;
                    Vec3d eye = new Vec3d(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
                    if (eye.squaredDistanceTo(target) > (reach - 0.4) * (reach - 0.4)) continue;
                    if (!walkable(w, p)) continue;
                    double score = bonus.applyAsDouble(p) - pl.distanceTo(Vec3d.ofBottomCenter(p)) * 0.3;
                    if (score > bestScore) { bestScore = score; best = p; }
                }
        return best;
    }

    /** A free spot next to the player where a shulker box can be placed and opened. */
    static BlockPos findSpot() {
        World w = mc.world;
        BlockPos base = mc.player.getBlockPos();
        BlockPos best = null;
        double bd = 1e18;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = base.add(dx, dy, dz);
                    if (!w.getBlockState(p).isAir() || !w.getBlockState(p.up()).isAir()) continue;
                    if (!w.getBlockState(p.down()).isSideSolidFullSquare(w, p.down(), Direction.UP)) continue;
                    double d = p.getSquaredDistance(base);
                    if (d < bd) { bd = d; best = p; }
                }
            }
        return best;
    }

    /** Selects the hotbar slot with the fastest tool for this block. */
    static void equipBestTool(BlockState s) {
        PlayerInventory inv = mc.player.getInventory();
        int best = -1;
        float speed = 1.0f;
        for (int i = 0; i < 9; i++) {
            float f = inv.getStack(i).getMiningSpeedMultiplier(s);
            if (f > speed) { speed = f; best = i; }
        }
        if (best >= 0) meteordevelopment.meteorclient.utils.player.InvUtils.swap(best, false);
    }
}
