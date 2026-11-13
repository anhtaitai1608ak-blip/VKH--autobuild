package com.vkh.autobuild;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * VKH AutoBuild: builds the schematic loaded in Litematica.
 * - analyses shulker boxes and fetches the materials it needs
 * - plans a smart route (Baritone) and places blocks with the right orientation
 * - removes blocks that were placed wrong (wrong block or wrong direction) and re-places them
 */
public final class AutoBuildModule extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgSupply = settings.createGroup("Supply");

    private final Setting<Integer> radius = sg.add(new IntSetting.Builder()
        .name("scan-radius").description("Horizontal radius around you that is scanned for work.")
        .defaultValue(20).min(4).sliderMax(48).build());
    private final Setting<Integer> height = sg.add(new IntSetting.Builder()
        .name("scan-height").description("Vertical radius around you that is scanned for work.")
        .defaultValue(10).min(2).sliderMax(30).build());
    private final Setting<Double> reach = sg.add(new DoubleSetting.Builder()
        .name("reach").description("Max distance to place/break from.")
        .defaultValue(4.2).min(2).sliderMax(5).build());
    private final Setting<Integer> blocksPerTick = sg.add(new IntSetting.Builder()
        .name("blocks-per-action").description("Blocks placed per action.")
        .defaultValue(1).min(1).sliderMax(4).build());
    private final Setting<Integer> placeDelay = sg.add(new IntSetting.Builder()
        .name("delay").description("Ticks between actions.")
        .defaultValue(2).min(0).sliderMax(20).build());
    private final Setting<Boolean> fixWrong = sg.add(new BoolSetting.Builder()
        .name("fix-wrong-blocks").description("Break blocks that are the wrong block or face the wrong way, then place them again.")
        .defaultValue(true).build());
    private final Setting<Integer> hotbarSlot = sg.add(new IntSetting.Builder()
        .name("hotbar-slot").description("Hotbar slot (1-9) used to hold blocks that come from inventory.")
        .defaultValue(9).min(1).max(9).sliderMax(9).build());
    private final Setting<Boolean> useBaritone = sg.add(new BoolSetting.Builder()
        .name("use-baritone").description("Walk with Baritone. Needs Baritone installed.")
        .defaultValue(true).build());

    private final Setting<Boolean> restock = sgSupply.add(new BoolSetting.Builder()
        .name("auto-restock").description("Fetch missing materials from shulker boxes.")
        .defaultValue(true).build());
    private final Setting<Boolean> invShulkers = sgSupply.add(new BoolSetting.Builder()
        .name("inventory-shulkers").description("Use shulker boxes carried in the inventory (placed, emptied of what we need, mined again).")
        .defaultValue(true).build());
    private final Setting<Boolean> worldShulkers = sgSupply.add(new BoolSetting.Builder()
        .name("world-shulkers").description("Use shulker boxes placed in the world near you.")
        .defaultValue(true).build());
    private final Setting<Integer> takeStacks = sgSupply.add(new IntSetting.Builder()
        .name("stacks-per-item").description("Stacks taken per item per trip.")
        .defaultValue(3).min(1).sliderMax(27).build());

    private static final class Task {
        final BlockPos pos;
        final BlockState want;
        final boolean brk;

        Task(BlockPos pos, BlockState want, boolean brk) {
            this.pos = pos;
            this.want = want;
            this.brk = brk;
        }
    }

    private enum State { BUILD, RESTOCK }

    private State state = State.BUILD;
    private final List<Task> tasks = new ArrayList<>();
    private final Map<BlockPos, Integer> recent = new HashMap<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Map<BlockPos, Integer> blacklist = new HashMap<>();
    private final Map<Item, Integer> unavailable = new HashMap<>();
    private final Restocker restocker = new Restocker(s -> info(s));

    private int tick, delay, lastScan = -999, navTicks, breakTicks, lastIdleMsg = -999, lastWarn = -999;
    private BlockPos navStand, navTask, breakingPos;
    private boolean complete;

    public AutoBuildModule() {
        super(VKHAutoBuild.CATEGORY, "vkh-auto-build",
            "Auto builder: reads your Litematica schematic, fetches materials from shulkers, paths, places with correct orientation and fixes mistakes.");
    }

    @Override
    public void onActivate() {
        tick = 0;
        delay = 0;
        lastScan = -999;
        state = State.BUILD;
        tasks.clear();
        recent.clear();
        attempts.clear();
        blacklist.clear();
        unavailable.clear();
        restocker.reset();
        navStand = null;
        breakingPos = null;
        complete = false;
        if (!SchematicSource.litematicaPresent()) {
            error("Litematica not found. Install Litematica + MaLiLib for 1.21.11.");
            toggle();
            return;
        }
        if (useBaritone.get()) {
            if (Nav.baritonePresent()) {
                Nav.lockBlocks(true);
                info("Baritone place/break disabled while building (restored when you turn this off).");
            } else warning("Baritone not found - the module can build in reach but will not walk.");
        }
        info("VKH AutoBuild on. Load a schematic in Litematica and stand near it.");
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;
        if (Nav.baritonePresent() && useBaritone.get()) {
            Nav.cancel();
            Nav.lockBlocks(false);
        }
        if (mc.currentScreen != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        tick++;
        World sw = SchematicSource.world();
        if (sw == null) {
            if (tick - lastWarn > 200) { lastWarn = tick; warning("No schematic loaded in Litematica."); }
            return;
        }
        if (state == State.RESTOCK) {
            Restocker.Result r = restocker.tick();
            if (r != Restocker.Result.RUNNING) {
                if (r == Restocker.Result.FAILED) {
                    for (Item it : missingNow) unavailable.put(it, tick + 1200);
                    warning("No shulker with the needed materials found.");
                }
                state = State.BUILD;
                lastScan = -999;
                delay = 5;
            }
            return;
        }
        if (mc.currentScreen != null) return;
        if (navStand != null) { handleNav(); return; }
        if (delay > 0) { delay--; return; }

        if (tick - lastScan >= 10 || tasks.isEmpty()) rescan(sw);
        if (tick % 2000 == 0) attempts.clear();
        act();
    }

    private Set<Item> missingNow = new HashSet<>();

    // ---------------------------------------------------------------- scanning

    private boolean blacklisted(BlockPos p) {
        Integer until = blacklist.get(p);
        if (until == null) return false;
        if (until < tick) { blacklist.remove(p); return false; }
        return true;
    }

    private void rescan(World sw) {
        lastScan = tick;
        tasks.clear();
        BlockPos pp = mc.player.getBlockPos();
        int r = radius.get(), h = height.get();
        BlockPos.Mutable m = new BlockPos.Mutable();
        List<Task> places = new ArrayList<>(), breaks = new ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (!mc.world.getChunkManager().isChunkLoaded((pp.getX() + x) >> 4, (pp.getZ() + z) >> 4)) continue;
                for (int y = -h; y <= h; y++) {
                    m.set(pp.getX() + x, pp.getY() + y, pp.getZ() + z);
                    if (mc.world.isOutOfHeightLimit(m)) continue;
                    BlockState want = sw.getBlockState(m);
                    if (want.isAir() || Util.skipTarget(want)) continue;
                    BlockState cur = mc.world.getBlockState(m);
                    if (Util.replaceable(cur)) {
                        if (!blacklisted(m)) places.add(new Task(m.toImmutable(), want, false));
                    } else if (fixWrong.get() && !PlacementSolver.orientMatches(want, cur)
                        && mc.world.getBlockEntity(m) == null && cur.getHardness(mc.world, m) >= 0
                        && !blacklisted(m)) {
                        breaks.add(new Task(m.toImmutable(), want, true));
                    }
                }
            }
        }
        breaks.sort(Comparator.comparingDouble(t -> t.pos.getSquaredDistance(pp)));
        places.sort(Comparator.comparingInt((Task t) -> t.pos.getY()).thenComparingDouble(t -> t.pos.getSquaredDistance(pp)));
        tasks.addAll(breaks);
        tasks.addAll(places);
        if (tasks.size() > 800) tasks.subList(800, tasks.size()).clear();
    }

    // ---------------------------------------------------------------- acting

    private boolean has(Map<Item, Boolean> cache, Item item) {
        return cache.computeIfAbsent(item, it -> mc.player.isCreative() || InvUtils.find(it).found());
    }

    private void act() {
        if (breakingPos != null && continueBreak()) return;

        Map<Item, Boolean> haveCache = new HashMap<>();
        List<Task> actionable = new ArrayList<>();
        Set<Item> miss = new HashSet<>();
        int done = 0;

        for (Task t : tasks) {
            if (blacklisted(t.pos)) continue;
            BlockState cur = mc.world.getBlockState(t.pos);
            if (t.brk) {
                if (Util.replaceable(cur) || PlacementSolver.orientMatches(t.want, cur)) continue;
                actionable.add(t);
                if (Util.inReach(t.pos, reach.get())) {
                    startBreak(t.pos);
                    return;
                }
            } else {
                if (!Util.replaceable(cur)) continue;
                if (recent.getOrDefault(t.pos, 0) > tick) continue;
                Item item = t.want.getBlock().asItem();
                if (!has(haveCache, item)) {
                    if (unavailable.getOrDefault(item, 0) < tick) miss.add(item);
                    continue;
                }
                if (!PlacementSolver.hasSupport(t.pos)) continue;
                actionable.add(t);
                if (done < blocksPerTick.get() && Util.inReach(t.pos, reach.get()) && place(t)) done++;
            }
        }

        if (done > 0) {
            delay = placeDelay.get();
            return;
        }
        if (!actionable.isEmpty()) {
            complete = false;
            navigate(actionable);
            return;
        }
        if (!miss.isEmpty() && restock.get()) {
            missingNow = miss;
            if (restocker.start(miss, takeStacks.get(), invShulkers.get(), worldShulkers.get(), hotbarSlot.get() - 1)) {
                info("Missing " + miss.size() + " material type(s) - fetching from shulkers.");
                state = State.RESTOCK;
            } else {
                for (Item it : miss) unavailable.put(it, tick + 1200);
                warning("Out of materials and no shulker with them was found.");
            }
            return;
        }
        if (!complete || tick - lastIdleMsg > 600) {
            complete = true;
            lastIdleMsg = tick;
            info(miss.isEmpty() ? "Nothing left to build in range. Walk to the next part of the schematic."
                : "Waiting for materials: " + miss.size() + " type(s) missing.");
        }
        delay = 10;
    }

    private boolean selectItem(Item item) {
        int slot = hotbarSlot.get() - 1;
        if (mc.player.isCreative()) {
            mc.interactionManager.clickCreativeStack(new ItemStack(item, item.getMaxCount()), 36 + slot);
            InvUtils.swap(slot, false);
            return true;
        }
        FindItemResult r = InvUtils.find(item);
        if (!r.found()) return false;
        if (r.isHotbar()) {
            InvUtils.swap(r.slot(), false);
        } else {
            InvUtils.move().from(r.slot()).toHotbar(slot);
            InvUtils.swap(slot, false);
        }
        return true;
    }

    private boolean place(Task t) {
        int n = attempts.merge(t.pos, 1, Integer::sum);
        if (n > 4) {
            blacklist.put(t.pos, tick + 600);
            attempts.remove(t.pos);
            return false;
        }
        PlacementSolver.Plan plan = PlacementSolver.solve(t.pos, t.want);
        if (plan == null) return false;
        if (!selectItem(t.want.getBlock().asItem())) return false;
        Rotations.rotate(plan.yaw(), plan.pitch(), 100, () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(plan.hit(), plan.side(), plan.neighbor(), false));
            mc.player.swingHand(Hand.MAIN_HAND);
        });
        recent.put(t.pos, tick + 6);
        return true;
    }

    private void startBreak(BlockPos p) {
        breakingPos = p;
        breakTicks = 0;
        continueBreak();
    }

    private boolean continueBreak() {
        BlockPos p = breakingPos;
        BlockState cur = mc.world.getBlockState(p);
        if (Util.replaceable(cur) || ++breakTicks > 120 || !Util.inReach(p, reach.get() + 0.5)) {
            if (breakTicks > 120) blacklist.put(p, tick + 600);
            breakingPos = null;
            delay = placeDelay.get();
            return false;
        }
        Util.equipBestTool(cur);
        Vec3d c = Vec3d.ofCenter(p), eye = mc.player.getEyePos();
        Direction side = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        Rotations.rotate(Rotations.getYaw(c), Rotations.getPitch(c), 50, () -> {
            mc.interactionManager.updateBlockBreakingProgress(p, side);
            mc.player.swingHand(Hand.MAIN_HAND);
        });
        return true;
    }

    // ---------------------------------------------------------------- smart routing

    private void navigate(List<Task> actionable) {
        if (!useBaritone.get() || !Nav.baritonePresent()) {
            if (tick - lastWarn > 200) { lastWarn = tick; warning("Work is out of reach and Baritone is unavailable - walk closer."); }
            delay = 20;
            return;
        }
        Task first = actionable.get(0);
        int sample = Math.min(actionable.size(), 60);
        double rch = reach.get();
        BlockPos stand = Util.findStand(first.pos, 4, rch, p -> {
            Vec3d eye = new Vec3d(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
            int n = 0;
            for (int i = 0; i < sample; i++)
                if (eye.squaredDistanceTo(Vec3d.ofCenter(actionable.get(i).pos)) <= (rch - 0.4) * (rch - 0.4)) n++;
            return n * 10.0;
        });
        if (stand == null) {
            blacklist.put(first.pos, tick + 400);
            return;
        }
        navStand = stand;
        navTask = first.pos;
        navTicks = 0;
        Nav.goTo(stand);
    }

    private void handleNav() {
        navTicks++;
        double d = mc.player.getPos().distanceTo(Vec3d.ofBottomCenter(navStand));
        if (d < 1.3) {
            Nav.cancel();
            navStand = null;
            delay = 3;
            lastScan = -999;
        } else if (navTicks > 400) {
            Nav.cancel();
            blacklist.put(navTask, tick + 600);
            navStand = null;
            delay = 5;
        }
    }
}
