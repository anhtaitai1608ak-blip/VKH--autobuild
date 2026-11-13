package com.vkh.autobuild;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Material manager. Analyses what is inside shulker boxes (in the inventory or placed in the world),
 * walks to them, opens them and moves the needed items into the inventory.
 */
final class Restocker {
    enum Result { RUNNING, DONE, FAILED }

    private enum Phase { PLACE_MOVE, PLACE_DO, PLACE_WAIT, GOTO, OPEN, WAIT_OPEN, TAKE, CLOSE, MINE, PICKUP }

    private final Map<BlockPos, Map<Item, Integer>> catalog = new HashMap<>();
    private final Set<BlockPos> bad = new HashSet<>();
    private final Consumer<String> log;

    private Set<Item> wanted = Set.of();
    private int takeStacks = 3, hotbarSlot = 8;
    private boolean useInv = true, useWorld = true;
    private Phase phase;
    private BlockPos box;
    private boolean temp;
    private int timer, invSlot, useSlot;
    private final Deque<Integer> queue = new ArrayDeque<>();

    Restocker(Consumer<String> log) {
        this.log = log;
    }

    void reset() {
        catalog.clear();
        bad.clear();
        phase = null;
    }

    boolean start(Set<Item> wanted, int takeStacks, boolean useInv, boolean useWorld, int hotbarSlot) {
        this.wanted = wanted;
        this.takeStacks = takeStacks;
        this.useInv = useInv;
        this.useWorld = useWorld;
        this.hotbarSlot = hotbarSlot;
        return pick();
    }

    private boolean pick() {
        box = null;
        temp = false;
        timer = 0;
        queue.clear();
        if (useInv) {
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.getInventory().getStack(i);
                if (!Util.isShulkerStack(s)) continue;
                for (ItemStack in : Util.contents(s)) {
                    if (wanted.contains(in.getItem())) {
                        invSlot = i;
                        phase = Phase.PLACE_MOVE;
                        log.accept("Shulker in inventory (slot " + i + ") has " + in.getName().getString());
                        return true;
                    }
                }
            }
        }
        if (useWorld) {
            BlockPos best = null;
            double bd = 1e18;
            BlockPos pp = mc.player.getBlockPos();
            for (Map.Entry<BlockPos, Map<Item, Integer>> e : catalog.entrySet()) {
                BlockPos p = e.getKey();
                if (bad.contains(p) || !Util.isShulkerBlock(mc.world.getBlockState(p))) continue;
                boolean has = false;
                for (Item it : wanted) if (e.getValue().getOrDefault(it, 0) > 0) has = true;
                if (!has) continue;
                double d = p.getSquaredDistance(pp);
                if (d < bd) { bd = d; best = p; }
            }
            if (best == null) { // unknown shulkers: go and look inside
                BlockPos.Mutable m = new BlockPos.Mutable();
                for (int x = -24; x <= 24; x++)
                    for (int z = -24; z <= 24; z++)
                        for (int y = -10; y <= 10; y++) {
                            m.set(pp.getX() + x, pp.getY() + y, pp.getZ() + z);
                            if (catalog.containsKey(m) || bad.contains(m)) continue;
                            if (!mc.world.getChunkManager().isChunkLoaded(m.getX() >> 4, m.getZ() >> 4)) continue;
                            if (!Util.isShulkerBlock(mc.world.getBlockState(m))) continue;
                            double d = m.getSquaredDistance(pp);
                            if (d < bd) { bd = d; best = m.toImmutable(); }
                        }
            }
            if (best != null) {
                box = best;
                phase = Phase.GOTO;
                return true;
            }
        }
        return false;
    }

    private Result fail(boolean markBad) {
        if (markBad && box != null && !temp) bad.add(box);
        Nav.cancel();
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
        return pick() ? Result.RUNNING : Result.FAILED;
    }

    Result tick() {
        if (phase == null) return Result.FAILED;
        switch (phase) {
            case PLACE_MOVE -> {
                ItemStack s = mc.player.getInventory().getStack(invSlot);
                BlockPos spot = Util.findSpot();
                if (!Util.isShulkerStack(s) || spot == null) {
                    log.accept("No free spot to place the shulker.");
                    useInv = false;
                    return fail(false);
                }
                box = spot;
                if (invSlot > 8) {
                    InvUtils.move().from(invSlot).toHotbar(hotbarSlot);
                    useSlot = hotbarSlot;
                } else useSlot = invSlot;
                phase = Phase.PLACE_DO;
                timer = 3;
            }
            case PLACE_DO -> {
                if (--timer > 0) return Result.RUNNING;
                InvUtils.swap(useSlot, false);
                BlockPos below = box.down();
                Vec3d hit = new Vec3d(box.getX() + 0.5, box.getY(), box.getZ() + 0.5);
                Rotations.rotate(Rotations.getYaw(hit), Rotations.getPitch(hit), 100, () -> {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
                temp = true;
                phase = Phase.PLACE_WAIT;
                timer = 0;
            }
            case PLACE_WAIT -> {
                if (Util.isShulkerBlock(mc.world.getBlockState(box))) {
                    phase = Phase.OPEN;
                    timer = 0;
                } else if (++timer > 30) {
                    temp = false;
                    log.accept("Could not place the shulker box.");
                    useInv = false;
                    return fail(false);
                }
            }
            case GOTO -> {
                if (!Util.isShulkerBlock(mc.world.getBlockState(box))) return fail(true);
                if (Util.inReach(box, 4.0)) {
                    Nav.cancel();
                    phase = Phase.OPEN;
                    timer = 0;
                    return Result.RUNNING;
                }
                timer++;
                if (timer == 1) {
                    BlockPos stand = Util.findStand(box, 3, 4.0, p -> 0);
                    if (stand == null) return fail(true);
                    Nav.goTo(stand);
                } else if (timer > 400) return fail(true);
            }
            case OPEN -> {
                BlockPos up = box.up();
                if (!mc.world.getBlockState(up).getCollisionShape(mc.world, up).isEmpty()) {
                    log.accept("Shulker at " + box.toShortString() + " is blocked on top.");
                    return fail(true);
                }
                Vec3d c = Vec3d.ofCenter(box), eye = mc.player.getEyePos();
                Direction side = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
                Vec3d hit = c.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
                final BlockPos b = box;
                Rotations.rotate(Rotations.getYaw(hit), Rotations.getPitch(hit), 100, () -> {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, side, b, false));
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
                phase = Phase.WAIT_OPEN;
                timer = 0;
            }
            case WAIT_OPEN -> {
                if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler h) {
                    readAndQueue(h);
                    phase = Phase.TAKE;
                    timer = 4;
                } else if (++timer > 50) return fail(true);
            }
            case TAKE -> {
                if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler h)) return fail(true);
                if (--timer > 0) return Result.RUNNING;
                Integer slot = queue.poll();
                if (slot == null) {
                    phase = Phase.CLOSE;
                    timer = 0;
                    return Result.RUNNING;
                }
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                timer = 3;
            }
            case CLOSE -> {
                if (timer == 0) mc.player.closeHandledScreen();
                if (++timer < 6) return Result.RUNNING;
                if (temp) {
                    phase = Phase.MINE;
                    timer = 0;
                } else return Result.DONE;
            }
            case MINE -> {
                BlockState s = mc.world.getBlockState(box);
                if (!Util.isShulkerBlock(s)) {
                    phase = Phase.PICKUP;
                    timer = 0;
                    return Result.RUNNING;
                }
                if (++timer > 200) return Result.DONE;
                Util.equipBestTool(s);
                Vec3d c = Vec3d.ofCenter(box);
                final BlockPos b = box;
                Rotations.rotate(Rotations.getYaw(c), Rotations.getPitch(c), 50, () -> {
                    mc.interactionManager.updateBlockBreakingProgress(b, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
            }
            case PICKUP -> {
                timer++;
                if (timer == 1) Nav.goTo(box);
                boolean near = mc.player.getPos().distanceTo(Vec3d.ofBottomCenter(box)) < 1.5;
                if (near || timer > 100) {
                    Nav.cancel();
                    return Result.DONE;
                }
            }
        }
        return Result.RUNNING;
    }

    private void readAndQueue(ShulkerBoxScreenHandler h) {
        Map<Item, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < 27; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isEmpty()) map.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        if (!temp) catalog.put(box, map);
        StringBuilder sb = new StringBuilder("Shulker " + box.toShortString() + ": ");
        if (map.isEmpty()) sb.append("empty");
        map.forEach((it, n) -> sb.append(n).append("x ").append(it.getName().getString()).append(", "));
        log.accept(sb.toString());

        queue.clear();
        int free = Util.freeSlots() - 1;
        Map<Item, Integer> taken = new HashMap<>();
        for (int i = 0; i < 27 && queue.size() < free; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty() || !wanted.contains(st.getItem())) continue;
            if (taken.getOrDefault(st.getItem(), 0) >= takeStacks) continue;
            queue.add(i);
            taken.merge(st.getItem(), 1, Integer::sum);
        }
    }
}
