package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

public class BlockDropFinder extends Module {
    private static final MinecraftClient MC = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender  = this.settings.createGroup("Render");

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
            .name("scan-radius")
            .description("Chunk radius around the player considered for anomaly statistics.")
            .defaultValue(8).min(1).sliderMax(16).build());
    private final Setting<Double> activityThreshold = sgGeneral.add(new DoubleSetting.Builder()
            .name("activity-threshold")
            .description("Standard deviations above mean update rate to flag a chunk (Method 1).")
            .defaultValue(2.0).min(0.5).sliderMax(5.0).build());
    private final Setting<Integer> deepYThreshold = sgGeneral.add(new IntSetting.Builder()
            .name("deep-y")
            .description("Any block update below this Y is immediately flagged. Default -3.")
            .defaultValue(-3).min(-64).sliderMin(-64).sliderMax(0).build());
    private final Setting<Integer> soundYThreshold = sgGeneral.add(new IntSetting.Builder()
            .name("sound-y-threshold")
            .description("Hostile/neutral sounds below this Y are counted as spawner ticks. Default 0.")
            .defaultValue(0).min(-64).sliderMin(-64).sliderMax(32).build());
    private final Setting<Integer> soundCountFlag = sgGeneral.add(new IntSetting.Builder()
            .name("sound-count-flag")
            .description("Number of underground hostile/neutral sounds in a chunk before it is flagged.")
            .defaultValue(5).min(1).sliderMax(20).build());
    private final Setting<Boolean> chatDebug = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-debug")
            .description("Print debug info to chat whenever a chunk is newly flagged.")
            .defaultValue(false).build());

    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
            .name("show-tracers")
            .description("Draw tracer lines from your eye to flagged chunk centers.")
            .defaultValue(true).build());
    private final Setting<Integer> plateY = sgRender.add(new IntSetting.Builder()
            .name("plate-y")
            .description("Y level at which the flat detection plate is rendered.")
            .defaultValue(60).min(-64).sliderMin(-64).sliderMax(320).build());

    private static final Color C_ACT_FILL  = new Color(0,   210, 60,  60);
    private static final Color C_ACT_LINE  = new Color(0,   230, 70,  210);
    private static final Color C_ACT_TRACE = new Color(0,   230, 70,  220);
    private static final Color C_RES_FILL  = new Color(50,  120, 255, 60);
    private static final Color C_RES_LINE  = new Color(80,  150, 255, 210);
    private static final Color C_RES_TRACE = new Color(80,  150, 255, 220);
    private static final Color C_SND_FILL  = new Color(200, 40,  220, 60);
    private static final Color C_SND_LINE  = new Color(210, 60,  240, 210);
    private static final Color C_SND_TRACE = new Color(210, 60,  240, 220);
    private static final Color C_BOTH_FILL  = new Color(255, 155, 20, 80);
    private static final Color C_BOTH_LINE  = new Color(255, 175, 30, 220);
    private static final Color C_BOTH_TRACE = new Color(255, 175, 30, 230);

    private final ConcurrentHashMap<ChunkPos, ConcurrentLinkedDeque<Long>> updateTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Integer> firstBeCount  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Integer> soundCounts   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Set<DetectionFlag>> detectedChunks = new ConcurrentHashMap<>();
    private static final long WINDOW_MS = 30_000L;

    public BlockDropFinder() {
        super(AddonTemplate.CATEGORY, "block-drop-finder",
                "Detects hidden underground bases via update anomalies, resync BE-count changes and spawner-tick sounds.");
    }

    @Override
    public void onActivate() {
        updateTimestamps.clear();
        firstBeCount.clear();
        soundCounts.clear();
        detectedChunks.clear();
    }

    @Override
    public void onDeactivate() {
        updateTimestamps.clear();
        firstBeCount.clear();
        soundCounts.clear();
        detectedChunks.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (MC.world == null || MC.player == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket pkt) {
            BlockPos pos = pkt.getPos();
            ChunkPos cp = new ChunkPos(pos);
            recordBlockUpdate(cp);
            if (pos.getY() < deepYThreshold.get()) {
                boolean isNew = addFlag(cp, DetectionFlag.ACTIVITY);
                if (isNew && chatDebug.get())
                    ChatUtils.info("[BDF] Deep update at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " chunk " + cp.x + "," + cp.z);
            }

        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket pkt) {
            boolean[] done = {false};
            int[] minY = {Integer.MAX_VALUE};
            ChunkPos[] cpRef = {null};
            pkt.visitUpdates((blockPos, blockState) -> {
                if (!done[0]) { cpRef[0] = new ChunkPos(blockPos); done[0] = true; }
                if (blockPos.getY() < minY[0]) minY[0] = blockPos.getY();
            });
            if (cpRef[0] != null) {
                recordBlockUpdate(cpRef[0]);
                if (minY[0] < deepYThreshold.get()) {
                    boolean isNew = addFlag(cpRef[0], DetectionFlag.ACTIVITY);
                    if (isNew && chatDebug.get())
                        ChatUtils.info("[BDF] Deep delta update at Y=" + minY[0] + " chunk " + cpRef[0].x + "," + cpRef[0].z);
                }
            }

        } else if (event.packet instanceof PlaySoundS2CPacket pkt) {
            SoundCategory cat = pkt.getCategory();
            if (cat != SoundCategory.HOSTILE && cat != SoundCategory.NEUTRAL) return;
            double soundY = pkt.getY();
            if (soundY >= soundYThreshold.get()) return;
            double soundX = pkt.getX(), soundZ = pkt.getZ();
            ChunkPos cp = new ChunkPos((int) Math.floor(soundX) >> 4, (int) Math.floor(soundZ) >> 4);
            if (!isInScanRadius(cp)) return;
            int newCount = soundCounts.merge(cp, 1, Integer::sum);
            if (newCount >= soundCountFlag.get()) {
                boolean isNew = addFlag(cp, DetectionFlag.SOUND_TICK);
                if (isNew && chatDebug.get())
                    ChatUtils.info("[BDF] Spawner-tick sound chunk " + cp.x + "," + cp.z + " Y=" + String.format("%.1f", soundY) + " count=" + newCount);
            }
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (MC.world == null || MC.player == null) return;
        WorldChunk chunk = event.chunk();
        ChunkPos cp = chunk.getPos();
        if (!isInScanRadius(cp)) return;
        int incomingCount = chunk.getBlockEntities().size();
        Integer previous = firstBeCount.putIfAbsent(cp, incomingCount);
        if (previous != null) {
            firstBeCount.put(cp, incomingCount);
            if (incomingCount > previous) {
                boolean isNew = addFlag(cp, DetectionFlag.RESYNC);
                if (isNew && chatDebug.get())
                    ChatUtils.info("[BDF] Resync anomaly chunk " + cp.x + "," + cp.z + " before=" + previous + " after=" + incomingCount);
            }
        }
    }

    private void recordBlockUpdate(ChunkPos cp) {
        if (!isInScanRadius(cp)) return;
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> times = updateTimestamps.computeIfAbsent(cp, k -> new ConcurrentLinkedDeque<>());
        times.addLast(now);
        Long oldest;
        while ((oldest = times.peekFirst()) != null && now - oldest > WINDOW_MS) times.pollFirst();
        evaluateActivityAnomaly(cp);
    }

    private void evaluateActivityAnomaly(ChunkPos target) {
        if (MC.player == null) return;
        long now = System.currentTimeMillis();
        int pCx = MC.player.getChunkPos().x, pCz = MC.player.getChunkPos().z;
        int rad = scanRadius.get();
        List<Double> counts = new ArrayList<>();

        for (Entry<ChunkPos, ConcurrentLinkedDeque<Long>> e : updateTimestamps.entrySet()) {
            ChunkPos cp = e.getKey();
            if (Math.abs(cp.x - pCx) > rad || Math.abs(cp.z - pCz) > rad) continue;
            ConcurrentLinkedDeque<Long> times = e.getValue();
            Long h;
            while ((h = times.peekFirst()) != null && now - h > WINDOW_MS) times.pollFirst();
            counts.add((double) times.size());
        }

        if (counts.size() < 3) return;
        double sum = counts.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / counts.size();
        double varSum = counts.stream().mapToDouble(c -> (c - mean) * (c - mean)).sum();
        double stdDev = Math.sqrt(varSum / counts.size());
        if (stdDev < 0.5) return;

        ConcurrentLinkedDeque<Long> targetTimes = updateTimestamps.get(target);
        if (targetTimes == null) return;
        double rate = targetTimes.size();
        double cutoff = mean + activityThreshold.get() * stdDev;
        if (rate >= cutoff) {
            boolean isNew = addFlag(target, DetectionFlag.ACTIVITY);
            if (isNew && chatDebug.get())
                ChatUtils.info("[BDF] Activity anomaly chunk " + target.x + "," + target.z + " rate=" + (int) rate + " cutoff=" + String.format("%.1f", cutoff));
        }
    }

    private boolean addFlag(ChunkPos cp, DetectionFlag flag) {
        return detectedChunks.computeIfAbsent(cp, k -> ConcurrentHashMap.newKeySet()).add(flag);
    }

    private boolean isInScanRadius(ChunkPos cp) {
        if (MC.player == null) return false;
        int dx = Math.abs(cp.x - MC.player.getChunkPos().x);
        int dz = Math.abs(cp.z - MC.player.getChunkPos().z);
        return dx <= scanRadius.get() && dz <= scanRadius.get();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (MC.player == null || MC.world == null || detectedChunks.isEmpty()) return;
        Vec3d eye = MC.player.getCameraPosVec(event.tickDelta);
        int py = plateY.get();

        for (Entry<ChunkPos, Set<DetectionFlag>> entry : detectedChunks.entrySet()) {
            ChunkPos cp = entry.getKey();
            Set<DetectionFlag> flags = entry.getValue();
            if (flags.isEmpty()) continue;
            boolean multi = flags.size() > 1;
            DetectionFlag primary = flags.iterator().next();
            Color fill    = multi ? C_BOTH_FILL  : fillColor(primary);
            Color outline = multi ? C_BOTH_LINE  : lineColor(primary);
            Color tracer  = multi ? C_BOTH_TRACE : traceColor(primary);
            double x0 = cp.getStartX(), z0 = cp.getStartZ();
            double x1 = x0 + 16, z1 = z0 + 16;
            event.renderer.box(x0, py, z0, x1, py + 0.15, z1, fill, outline, ShapeMode.Both, 0);
            if (showTracers.get())
                event.renderer.line(eye.x, eye.y, eye.z, x0 + 8, py + 0.075, z0 + 8, tracer);
        }
    }

    private static Color fillColor(DetectionFlag f) {
        return switch (f) { case ACTIVITY -> C_ACT_FILL; case RESYNC -> C_RES_FILL; case SOUND_TICK -> C_SND_FILL; };
    }
    private static Color lineColor(DetectionFlag f) {
        return switch (f) { case ACTIVITY -> C_ACT_LINE; case RESYNC -> C_RES_LINE; case SOUND_TICK -> C_SND_LINE; };
    }
    private static Color traceColor(DetectionFlag f) {
        return switch (f) { case ACTIVITY -> C_ACT_TRACE; case RESYNC -> C_RES_TRACE; case SOUND_TICK -> C_SND_TRACE; };
    }

    private enum DetectionFlag { ACTIVITY, RESYNC, SOUND_TICK }
}