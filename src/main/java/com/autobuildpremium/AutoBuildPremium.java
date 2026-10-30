package com.autobuildpremium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AutoBuild Premium (Mojang mappings, Minecraft 1.21.11).
 *
 * - Đọc bản vẽ từ Litematica bằng reflection (không cần jar Litematica khi build).
 * - Tự đi bộ tới gần (không dùng Baritone).
 * - Xây chậm: đặt 1 block, chờ, xác minh, lỗi thì để Repair Pass sửa.
 */
public class AutoBuildPremium implements ClientModInitializer {

    public static final String MOD_ID = "autobuildpremium";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Thuộc tính tự cập nhật theo môi trường, bỏ qua khi so sánh. */
    private static final Set<String> IGNORED_PROPS = Set.of(
            "waterlogged", "powered", "lit", "power", "triggered", "distance",
            "persistent", "snowy", "north", "south", "east", "west", "up", "down");

    private static AutoBuildPremium instance;

    public enum State { IDLE, BUILD, REPAIR, WALKING, PAUSED, COMPLETED }

    // ------------------------------------------------------------------
    // Cấu hình
    // ------------------------------------------------------------------
    public static class Config {
        public int placeWaitTicks = 4;
        public int stepDelayTicks = 10;
        public int aimTicks = 2;
        public int maxAttempts = 5;
        public int consecutiveFailureStop = 3;
        public int maxRepairRounds = 3;
        public double maxReachDistance = 4.0;
        public int walkTimeoutTicks = 20 * 60;
        /** true: so cả thuộc tính (hướng, trục, half...). Mặc định chỉ so loại block. */
        public boolean strictProperties = false;
    }

    public static final class BuildTarget {
        public final BlockPos pos;
        public final BlockState desired;
        public int attempts;
        public String lastError = "";

        public BuildTarget(BlockPos pos, BlockState desired) {
            this.pos = pos;
            this.desired = desired;
        }
    }

    private Config config = new Config();
    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");

    private final List<BuildTarget> allTargets = new ArrayList<>();
    private final Deque<BuildTarget> queue = new ArrayDeque<>();

    private State state = State.IDLE;
    /** Chế độ đang chạy: BUILD hoặc REPAIR (dùng để quay lại sau WALKING/PAUSED). */
    private State mode = State.BUILD;
    private BuildTarget current = null;
    private BuildTarget aimedAt = null;

    private int waitTicks = 0;
    private int cooldownTicks = 0;
    private int aimTicks = 0;
    private int consecutiveFailures = 0;
    private int placedOk = 0;
    private int repairRound = 0;
    private String pauseReason = "";

    // Đi bộ
    private Vec3 walkGoal = null;
    private boolean walkAway = false;
    private int walkTicks = 0;
    private int stuckTicks = 0;
    private Vec3 lastWalkPos = null;
    private boolean keysHeld = false;

    public static AutoBuildPremium get() {
        return instance;
    }

    // ------------------------------------------------------------------
    // Khởi tạo
    // ------------------------------------------------------------------
    @Override
    public void onInitializeClient() {
        instance = this;
        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick();
            } catch (Exception e) {
                LOG.error("Lỗi trong tick", e);
                pause("Lỗi nội bộ: " + describe(e));
            }
        });

        // Lệnh: /abp load | start | pause | resume | stop | status | help
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String c = command.trim();
            if (!c.equals("abp") && !c.startsWith("abp ")) return true;
            handleCommand(c);
            return false;
        });

        LOG.info("AutoBuild Premium đã khởi động.");
    }

    // ------------------------------------------------------------------
    // Đọc bản vẽ từ Litematica (reflection)
    // ------------------------------------------------------------------
    public static List<BuildTarget> readSelectedPlacement() throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            throw new IllegalStateException("Chưa cài Litematica.");
        }

        Class<?> dm = Class.forName("fi.dy.masa.litematica.data.DataManager");
        Object manager;
        try {
            manager = callStatic(dm, "getSchematicPlacementManager");
        } catch (NoSuchMethodException e) {
            manager = call(callStatic(dm, "getInstance"), "getSchematicPlacementManager");
        }

        Object placement = call(manager, "getSelectedSchematicPlacement");
        if (placement == null) {
            throw new IllegalStateException("Chưa chọn schematic nào trong Litematica.");
        }
        if (!isNone(call(placement, "getRotation")) || !isNone(call(placement, "getMirror"))) {
            throw new IllegalStateException("Chưa hỗ trợ schematic bị xoay hoặc lật. Hãy đặt lại không xoay/lật.");
        }

        BlockPos origin = (BlockPos) call(placement, "getOrigin");
        Object schem = call(placement, "getSchematic");
        Map<?, ?> positions = (Map<?, ?>) call(schem, "getAreaPositions");
        Map<?, ?> sizes = (Map<?, ?>) call(schem, "getAreaSizes");

        List<BuildTarget> out = new ArrayList<>();
        for (Object nameObj : positions.keySet()) {
            String name = (String) nameObj;

            // Vị trí vùng con: mặc định theo schematic, nếu đã chỉnh trong Litematica thì lấy bản chỉnh.
            Vec3i rel = (Vec3i) positions.get(name);
            Object sub = tryCall(placement, "getRelativeSubRegionPlacement", name);
            if (sub != null) {
                Object enabled = tryCall(sub, "isEnabled");
                if (Boolean.FALSE.equals(enabled)) continue;
                Object r = tryCall(sub, "getRotation");
                Object m = tryCall(sub, "getMirror");
                if ((r != null && !isNone(r)) || (m != null && !isNone(m))) {
                    throw new IllegalStateException("Vùng con '" + name + "' bị xoay/lật, chưa hỗ trợ.");
                }
                Object p = tryCall(sub, "getPos");
                if (p instanceof Vec3i v) rel = v;
            }

            Vec3i size = (Vec3i) sizes.get(name);
            Object container = call(schem, "getSubRegionContainer", name);
            Vec3i cs = (Vec3i) call(container, "getSize");
            Method get = findMethod(container.getClass(), "get", new Class<?>[]{int.class, int.class, int.class});
            if (get == null) throw new NoSuchMethodException(container.getClass().getName() + ".get(int,int,int)");

            // Kích thước âm: góc nhỏ nhất nằm lệch về phía âm.
            int ox = origin.getX() + rel.getX() + (size != null && size.getX() < 0 ? size.getX() + 1 : 0);
            int oy = origin.getY() + rel.getY() + (size != null && size.getY() < 0 ? size.getY() + 1 : 0);
            int oz = origin.getZ() + rel.getZ() + (size != null && size.getZ() < 0 ? size.getZ() + 1 : 0);

            for (int x = 0; x < cs.getX(); x++) {
                for (int y = 0; y < cs.getY(); y++) {
                    for (int z = 0; z < cs.getZ(); z++) {
                        BlockState s = (BlockState) get.invoke(container, x, y, z);
                        if (s == null || s.isAir()) continue;
                        out.add(new BuildTarget(new BlockPos(ox + x, oy + y, oz + z), s));
                    }
                }
            }
        }
        return out;
    }

    private static boolean isNone(Object o) {
        return o != null && "NONE".equals(String.valueOf(o));
    }

    /** Nạp mục tiêu, sắp xếp từ dưới lên theo tầng. */
    public void setTargets(List<BuildTarget> targets) {
        releaseKeys();
        allTargets.clear();
        allTargets.addAll(targets);
        allTargets.sort(Comparator.comparingInt((BuildTarget t) -> t.pos.getY())
                .thenComparingInt(t -> t.pos.getX())
                .thenComparingInt(t -> t.pos.getZ()));
        queue.clear();
        queue.addAll(allTargets);
        state = State.IDLE;
        mode = State.BUILD;
        current = null;
        aimedAt = null;
        waitTicks = cooldownTicks = aimTicks = 0;
        consecutiveFailures = 0;
        placedOk = 0;
        repairRound = 0;
        pauseReason = "";
        report("Đã nạp " + allTargets.size() + " block từ Litematica.");
    }

    // ------------------------------------------------------------------
    // Vòng lặp mỗi tick
    // ------------------------------------------------------------------
    private void tick() {
        if (state == State.IDLE || state == State.PAUSED || state == State.COMPLETED) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            pause("Chưa vào thế giới");
            return;
        }

        if (state == State.WALKING) {
            handleWalking(mc);
            return;
        }

        if (cooldownTicks > 0) { cooldownTicks--; return; }
        if (current == null) { pickNext(); return; }
        if (waitTicks > 0) { waitTicks--; return; }
        if (aimTicks > 0) { aimTicks--; return; }

        // Đã chờ xong: đọc trạng thái thực tế
        if (matches(current)) {
            onSuccess();
            return;
        }
        if (current.attempts >= config.maxAttempts) {
            onFailure("Vượt quá số lần thử (" + current.lastError + ")");
            return;
        }
        tryPlace(mc, current);
    }

    private void pickNext() {
        if (queue.isEmpty()) {
            beginRepairPass();
            return;
        }
        current = queue.poll();
        aimedAt = null;
    }

    // ------------------------------------------------------------------
    // Đặt block
    // ------------------------------------------------------------------
    private void tryPlace(Minecraft mc, BuildTarget t) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null) {
            pause("Chưa vào thế giới");
            return;
        }

        // 1. Vật liệu trong hotbar
        Item needed = t.desired.getBlock().asItem();
        if (needed == Items.AIR) {
            onFailure("Block không có item tương ứng");
            return;
        }
        int slot = findHotbarSlot(player, needed);
        if (slot < 0) {
            pause("Thiếu vật liệu: " + t.desired.getBlock().getName().getString());
            return;
        }

        // 2. Tầm với: nếu xa thì tự đi tới gần
        if (isTooFar(player, t.pos)) {
            startWalk(Vec3.atBottomCenterOf(t.pos), false);
            return;
        }

        // 3. Không phá block khác
        if (!level.getBlockState(t.pos).canBeReplaced()) {
            onFailure("Ô bị block khác chiếm");
            return;
        }

        // 4. Không đứng đè lên ô đích
        if (!t.desired.getCollisionShape(level, t.pos).isEmpty()
                && player.getBoundingBox().intersects(new AABB(t.pos))) {
            Vec3 c = Vec3.atCenterOf(t.pos);
            double dx = player.getX() - c.x;
            double dz = player.getZ() - c.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.01) { dx = 1; dz = 0; len = 1; }
            Vec3 goal = new Vec3(player.getX() + dx / len * 2.5, player.getY(), player.getZ() + dz / len * 2.5);
            startWalk(goal, true);
            return;
        }

        // 5. Mặt hỗ trợ
        BlockHitResult hit = findSupportFace(level, t.pos);
        if (hit == null) {
            t.attempts++;
            t.lastError = "Không có mặt hỗ trợ";
            cooldownTicks = config.stepDelayTicks;
            return;
        }
        double hitMax = config.maxReachDistance + 0.4;
        if (player.getEyePosition().distanceToSqr(hit.getLocation()) > hitMax * hitMax) {
            startWalk(Vec3.atBottomCenterOf(t.pos), false);
            return;
        }

        // 6. Quay mặt về điểm đặt, chờ vài tick cho server nhận hướng nhìn
        if (aimedAt != t) {
            aimAt(player, hit.getLocation());
            aimedAt = t;
            aimTicks = config.aimTicks;
            return;
        }

        // 7. Đặt
        t.attempts++;
        player.getInventory().setSelectedSlot(slot);
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
        } else {
            t.lastError = "Game từ chối: " + result;
        }
        waitTicks = config.placeWaitTicks;
    }

    private boolean matches(BuildTarget t) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        BlockState have = level.getBlockState(t.pos);
        if (have.getBlock() != t.desired.getBlock()) return false;
        if (!config.strictProperties) return true;
        for (Property<?> p : t.desired.getProperties()) {
            if (IGNORED_PROPS.contains(p.getName())) continue;
            if (!have.hasProperty(p)) continue;
            if (!have.getValue(p).equals(t.desired.getValue(p))) return false;
        }
        return true;
    }

    private void onSuccess() {
        placedOk++;
        consecutiveFailures = 0;
        current = null;
        aimedAt = null;
        cooldownTicks = config.stepDelayTicks;
    }

    private void onFailure(String reason) {
        if (current == null) return;
        consecutiveFailures++;
        current.lastError = reason;
        LOG.warn("Lỗi tại {}: {}", current.pos.toShortString(), reason);
        // Không dừng cả công trình: block lỗi sẽ được quét lại ở Repair Pass.
        current.attempts = 0;
        current = null;
        aimedAt = null;
        cooldownTicks = config.stepDelayTicks;

        if (consecutiveFailures >= config.consecutiveFailureStop) {
            pause("Có " + consecutiveFailures + " lỗi liên tiếp, kiểm tra lại công trình");
        }
    }

    // ------------------------------------------------------------------
    // Đi bộ tới gần (không dùng Baritone)
    // ------------------------------------------------------------------
    private void startWalk(Vec3 goal, boolean away) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        walkGoal = goal;
        walkAway = away;
        walkTicks = 0;
        stuckTicks = 0;
        lastWalkPos = p.position();
        state = State.WALKING;
        report(away ? "Lùi ra khỏi ô cần đặt." : "Đi tới gần " + BlockPos.containing(goal).toShortString() + ".");
    }

    private void handleWalking(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || current == null || walkGoal == null) {
            pause("Mất trạng thái khi di chuyển");
            return;
        }
        walkTicks++;

        // Đã tới nơi: quay lại xây
        boolean done = walkAway
                ? !p.getBoundingBox().intersects(new AABB(current.pos))
                : !isTooFar(p, current.pos);
        if (done) {
            releaseKeys();
            state = mode;
            return;
        }

        if (walkTicks > config.walkTimeoutTicks) {
            pause("Hết thời gian di chuyển tới " + current.pos.toShortString());
            return;
        }

        double dx = walkGoal.x - p.getX();
        double dz = walkGoal.z - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        // Đã đứng sát mà vẫn không với tới: lệch quá nhiều theo chiều cao
        if (!walkAway && horiz < 1.5) {
            pause("Không với tới " + current.pos.toShortString() + " theo chiều cao. Hãy tự kê/đứng gần hơn rồi /abp resume");
            return;
        }

        // Phát hiện kẹt: mỗi 20 tick phải di chuyển được ít nhất 0.3 block
        if (walkTicks % 20 == 0) {
            if (lastWalkPos != null && p.position().distanceToSqr(lastWalkPos) < 0.09) {
                stuckTicks += 20;
            } else {
                stuckTicks = 0;
            }
            lastWalkPos = p.position();
            if (stuckTicks >= 60) {
                pause("Bị kẹt khi đi tới " + current.pos.toShortString() + ". Hãy đi tay gần hơn rồi /abp resume");
                return;
            }
        }

        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
        keysHeld = true;
    }

    private void releaseKeys() {
        if (!keysHeld) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
        }
        keysHeld = false;
    }

    private boolean isTooFar(LocalPlayer player, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double max = config.maxReachDistance;
        return player.getEyePosition().distanceToSqr(center) > max * max;
    }

    // ------------------------------------------------------------------
    // Repair Pass: quét lại toàn bộ, sửa block sai từ dưới lên
    // ------------------------------------------------------------------
    private void beginRepairPass() {
        List<BuildTarget> broken = allTargets.stream()
                .filter(t -> !matches(t))
                .sorted(Comparator.comparingInt((BuildTarget t) -> t.pos.getY())
                        .thenComparingInt(t -> t.pos.getX())
                        .thenComparingInt(t -> t.pos.getZ()))
                .collect(Collectors.toList());

        if (broken.isEmpty()) {
            state = State.COMPLETED;
            report("Hoàn thành, không có block lỗi.");
            return;
        }
        if (repairRound >= config.maxRepairRounds) {
            state = State.COMPLETED;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(5, broken.size()); i++) {
                BuildTarget b = broken.get(i);
                sb.append("\n - ").append(b.pos.toShortString()).append(": ")
                        .append(b.desired.getBlock().getName().getString())
                        .append(b.lastError.isEmpty() ? "" : " (" + b.lastError + ")");
            }
            report("Dừng sau " + repairRound + " lượt sửa. Còn " + broken.size() + " block sai/thiếu:" + sb);
            return;
        }

        repairRound++;
        mode = State.REPAIR;
        state = State.REPAIR;
        for (BuildTarget t : broken) {
            t.attempts = 0;
            queue.add(t);
        }
        report("Repair Pass " + repairRound + "/" + config.maxRepairRounds + ": " + broken.size() + " block cần sửa.");
    }

    // ------------------------------------------------------------------
    // Điều khiển
    // ------------------------------------------------------------------
    public void start() {
        if (allTargets.isEmpty()) {
            report("Chưa có mục tiêu. Dùng /abp load trước.");
            return;
        }
        if (state == State.COMPLETED) {
            report("Công trình đã hoàn thành. Dùng /abp load để nạp lại.");
            return;
        }
        if (state == State.PAUSED) {
            report("Đang tạm dừng. Dùng /abp resume.");
            return;
        }
        if (state != State.IDLE) {
            report("Đang chạy rồi.");
            return;
        }
        state = mode;
        report("Bắt đầu xây chậm an toàn.");
    }

    public void pause(String reason) {
        if (state == State.BUILD || state == State.REPAIR || state == State.WALKING) {
            releaseKeys();
            state = State.PAUSED;
            pauseReason = reason;
            report("Tạm dừng: " + reason);
        }
    }

    public void resume() {
        if (state != State.PAUSED) {
            report("Không ở trạng thái tạm dừng.");
            return;
        }
        consecutiveFailures = 0;
        pauseReason = "";
        if (current != null) current.attempts = 0;
        aimedAt = null;
        state = mode;
        report("Tiếp tục.");
    }

    /** Dừng khẩn cấp: nhả phím, giữ nguyên tiến độ đã xác minh. */
    public void emergencyStop() {
        releaseKeys();
        if (current != null) queue.addFirst(current);
        current = null;
        aimedAt = null;
        waitTicks = 0;
        cooldownTicks = 0;
        aimTicks = 0;
        if (state != State.COMPLETED) state = State.IDLE;
        report("Đã dừng khẩn cấp. Dùng /abp start để chạy tiếp.");
    }

    // ------------------------------------------------------------------
    // Lệnh chat
    // ------------------------------------------------------------------
    private void handleCommand(String cmd) {
        String[] parts = cmd.split("\\s+");
        String sub = parts.length > 1 ? parts[1].toLowerCase() : "status";
        switch (sub) {
            case "load" -> {
                try {
                    setTargets(readSelectedPlacement());
                } catch (Exception e) {
                    LOG.error("Không đọc được schematic", e);
                    report("Không đọc được schematic: " + describe(e));
                }
            }
            case "start" -> start();
            case "pause" -> pause("người chơi yêu cầu");
            case "resume" -> resume();
            case "stop" -> emergencyStop();
            case "status" -> {
                long ok = allTargets.stream().filter(this::matches).count();
                report(String.format(
                        "Trạng thái: %s | Đúng: %d/%d | Hàng đợi: %d | Lỗi liên tiếp: %d%s",
                        state, ok, allTargets.size(), queue.size(), consecutiveFailures,
                        pauseReason.isEmpty() ? "" : " | Lý do: " + pauseReason));
            }
            default -> report("Lệnh: /abp load | start | pause | resume | stop | status");
        }
    }

    // ------------------------------------------------------------------
    // Tiện ích
    // ------------------------------------------------------------------
    private static int findHotbarSlot(LocalPlayer player, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) return i;
        }
        return -1;
    }

    /** Block khi click sẽ mở GUI hoặc bật/tắt thay vì đặt block. */
    private static boolean isInteractive(Block b) {
        return b instanceof EntityBlock
                || b instanceof CraftingTableBlock
                || b instanceof DoorBlock
                || b instanceof TrapDoorBlock
                || b instanceof FenceGateBlock
                || b instanceof ButtonBlock
                || b instanceof LeverBlock
                || b instanceof AnvilBlock
                || b instanceof BedBlock
                || b instanceof NoteBlock
                || b instanceof DiodeBlock;
    }

    /** Chọn một block đặc đang tiếp giáp ô đích, trả về mặt tiếp xúc. */
    private static BlockHitResult findSupportFace(ClientLevel level, BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos support = target.relative(d);
            BlockState s = level.getBlockState(support);
            if (s.isAir() || isInteractive(s.getBlock())) continue;
            Direction face = d.getOpposite();
            if (!s.isFaceSturdy(level, support, face)) continue;
            Vec3 hitPos = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            return new BlockHitResult(hitPos, face, support, false);
        }
        return null;
    }

    private static void aimAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, h))));
    }

    private void report(String msg) {
        LOG.info(msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[AutoBuild] " + msg), false);
        }
    }

    private static String describe(Throwable e) {
        Throwable t = e;
        if (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    // ------------------------------------------------------------------
    // Reflection helpers (gọi Litematica không cần jar lúc build)
    // ------------------------------------------------------------------
    private static Class<?> boxed(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == boolean.class) return Boolean.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return Void.class;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>[] argTypes) {
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != argTypes.length) continue;
            Class<?>[] pt = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < pt.length; i++) {
                if (!boxed(pt[i]).isAssignableFrom(boxed(argTypes[i]))) { ok = false; break; }
            }
            if (ok) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = args[i].getClass();
        Method m = findMethod(target.getClass(), name, types);
        if (m == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        return m.invoke(target, args);
    }

    private static Object callStatic(Class<?> c, String name) throws Exception {
        Method m = findMethod(c, name, new Class<?>[0]);
        if (m == null) throw new NoSuchMethodException(c.getName() + "." + name);
        return m.invoke(null);
    }

    private static Object tryCall(Object target, String name, Object... args) {
        try {
            return call(target, name, args);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Cấu hình
    // ------------------------------------------------------------------
    private void loadConfig() {
        try {
            if (Files.exists(configPath)) {
                try (Reader r = Files.newBufferedReader(configPath)) {
                    Config loaded = GSON.fromJson(r, Config.class);
                    if (loaded != null) config = loaded;
                }
            }
            saveConfig(); // ghi lại để bổ sung các thông số mới
        } catch (Exception e) {
            LOG.error("Không đọc được cấu hình, dùng mặc định.", e);
        }
    }

    public void saveConfig() {
        try (Writer w = Files.newBufferedWriter(configPath)) {
            GSON.toJson(config, w);
        } catch (Exception e) {
            LOG.error("Không lưu được cấu hình.", e);
        }
    }
}
