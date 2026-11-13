package com.vkh.autobuild;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Works out HOW to place a block so that it ends up with the schematic's orientation:
 * which neighbour to click, where on its face, and which yaw/pitch to look at.
 * It asks the block itself (Block#getPlacementState) with a temporary player rotation,
 * so it handles stairs, slabs, logs, observers, pistons, furnaces, signs, etc. generically.
 */
final class PlacementSolver {
    private PlacementSolver() {}

    record Plan(BlockPos neighbor, Direction side, Vec3d hit, float yaw, float pitch) {}

    private static final Set<String> ORIENT = Set.of(
        "facing", "axis", "half", "type", "orientation", "rotation", "face", "attachment", "part");

    /** True when block and its orientation-related properties equal the schematic's. */
    static boolean orientMatches(BlockState want, BlockState got) {
        if (want.getBlock() != got.getBlock()) return false;
        boolean slab = Util.id(want).endsWith("_slab");
        for (Property<?> p : want.getProperties()) {
            String n = p.getName();
            if (!ORIENT.contains(n) || !got.contains(p)) continue;
            if (n.equals("type") && !slab) continue; // chest/other "type" depends on neighbours
            if (!String.valueOf(want.get(p)).equals(String.valueOf(got.get(p)))) return false;
        }
        return true;
    }

    static Plan solve(BlockPos target, BlockState want) {
        ClientPlayerEntity p = mc.player;
        World w = mc.world;
        ItemStack stack = new ItemStack(want.getBlock().asItem());
        boolean fine = want.getProperties().stream().anyMatch(pr -> pr.getName().equals("rotation"));
        int yawCount = fine ? 16 : 4;
        float oy = p.getYaw(), op = p.getPitch();
        float[] pitches = {0f, 90f, -90f};
        Block block = want.getBlock();
        try {
            for (Direction d : Direction.values()) {
                BlockPos nb = target.offset(d);
                BlockState ns = w.getBlockState(nb);
                if (!Util.canClickOn(w, nb, ns)) continue;
                Direction side = d.getOpposite();
                double[] hys = side.getAxis().isHorizontal() ? new double[] {0.25, 0.75} : new double[] {0.5};
                Vec3d c = Vec3d.ofCenter(nb).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
                for (double hy : hys) {
                    Vec3d hit = side.getAxis().isHorizontal() ? new Vec3d(c.x, target.getY() + hy, c.z) : c;
                    BlockHitResult hr = new BlockHitResult(hit, side, nb, false);
                    for (int yi = 0; yi < yawCount; yi++) {
                        float yaw = -180f + yi * (360f / yawCount);
                        for (float pitch : pitches) {
                            p.setYaw(yaw);
                            p.setPitch(pitch);
                            ItemPlacementContext ctx = new ItemPlacementContext(p, Hand.MAIN_HAND, stack, hr);
                            if (!ctx.canPlace()) continue;
                            BlockState got = block.getPlacementState(ctx);
                            if (got == null || !orientMatches(want, got)) continue;
                            if (!got.canPlaceAt(w, target)) continue;
                            return new Plan(nb, side, hit, yaw, pitch);
                        }
                    }
                }
            }
        } finally {
            p.setYaw(oy);
            p.setPitch(op);
        }
        return null;
    }

    /** Does at least one neighbour exist that we can click on? */
    static boolean hasSupport(BlockPos target) {
        World w = mc.world;
        for (Direction d : Direction.values()) {
            BlockPos nb = target.offset(d);
            if (Util.canClickOn(w, nb, w.getBlockState(nb))) return true;
        }
        return false;
    }
}
