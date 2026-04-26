package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Receive;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Post;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.BoolSetting.Builder;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.client.MinecraftClient;

public class BlockDropFinder extends Module {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final Set<Block> SPAWNER_BLOCKS = ConcurrentHashMap.newKeySet();
    private final SettingGroup sgTickDebug = this.settings.createGroup("Tick Debug");
    private final SettingGroup sgDetector = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = this.settings.createGroup("Render");
    private final SettingGroup sgPrediction = this.settings.createGroup("Prediction");
    private final Setting<Boolean> tickDebugMode = this.sgTickDebug
            .add(
                    ((Builder)((Builder)((Builder)new Builder().name("tick-debug-mode"))
                            .description("Draw a green pillar for every block tick received. Great for finding hopper activity under spawners."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Integer> tickDebugY = this.sgTickDebug
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("tick-y-level"))
                            .description("Only count block ticks below this Y."))
                            .defaultValue(0))
                            .min(-64)
                            .sliderMin(-64)
                            .sliderMax(0)
                            .build()
            );
    private final Setting<Integer> heightPerTick = this.sgTickDebug
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("height-per-tick"))
                            .description("Blocks of height added per recorded tick."))
                            .defaultValue(4))
                            .min(1)
                            .sliderMax(20)
                            .build()
            );
    private final Setting<Integer> tickMinHeight = this.sgTickDebug
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("min-height"))
                            .description("Starting pillar height on first tick."))
                            .defaultValue(40))
                            .min(5)
                            .sliderMax(100)
                            .build()
            );
    private final Setting<Integer> tickMaxHeight = this.sgTickDebug
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("max-height"))
                            .description("Maximum pillar height (caps tick growth)."))
                            .defaultValue(250))
                            .min(50)
                            .sliderMax(400)
                            .build()
            );
    private final Setting<Integer> tickFadeSeconds = this.sgTickDebug
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("tick-fade"))
                            .description("Seconds with no new ticks before pillar disappears. 0 = never."))
                            .defaultValue(60))
                            .min(0)
                            .sliderMax(300)
                            .build()
            );
    private final Setting<Double> tickPillarWidth = this.sgTickDebug
            .add(
                    ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                            .name("tick-width"))
                            .description("Width of tick-debug pillars in blocks."))
                            .defaultValue(2.5)
                            .min(0.5)
                            .sliderMax(6.0)
                            .build()
            );
    private final Setting<Integer> scanRadius = this.sgDetector
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("scan-radius"))
                            .description("Chunk radius for active palette scan and resyncs."))
                            .defaultValue(10))
                            .min(1)
                            .sliderMax(16)
                            .build()
            );
    private final Setting<Double> activityThreshold = this.sgDetector
            .add(
                    ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                            .name("activity-threshold"))
                            .description("Std-dev threshold for statistical block update anomaly (Hopper activity)."))
                            .defaultValue(2.5)
                            .min(1.0)
                            .sliderMax(6.0)
                            .build()
            );
    private final Setting<Boolean> chatDebug = this.sgDetector
            .add(
                    ((Builder)((Builder)((Builder)new Builder().name("chat-debug")).description("Print to chat when a chunk is flagged.")).defaultValue(false)).build()
            );
    private final Setting<Boolean> hideWeak = this.sgRender
            .add(
                    ((Builder)((Builder)((Builder)new Builder().name("hide-weak"))
                            .description("Only show PURPLE confirmed spawner pillars. Hides weak green background activity."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Integer> pillarBaseY = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("pillar-base-y"))
                            .description("Y level all pillars start from."))
                            .defaultValue(0))
                            .min(-64)
                            .sliderMin(-64)
                            .sliderMax(100)
                            .build()
            );
    private final Setting<Double> detectorWidth = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                            .name("detector-width"))
                            .description("Width of precision detector pillars."))
                            .defaultValue(4.0)
                            .min(1.0)
                            .sliderMax(10.0)
                            .build()
            );
    private final Setting<Integer> detectorHeight = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("detector-height"))
                            .description("Fixed height of precision detector pillars."))
                            .defaultValue(200))
                            .min(20)
                            .sliderMax(400)
                            .build()
            );
    private final Setting<Boolean> showTracers = this.sgRender
            .add(((Builder)((Builder)((Builder)new Builder().name("show-tracers")).description("Draw tracer lines to pillars.")).defaultValue(true)).build());
    private final Setting<Boolean> showPrediction = this.sgPrediction
            .add(
                    ((Builder)((Builder)((Builder)new Builder().name("show-prediction"))
                            .description("Gold pillar at weighted centroid of all confirmed spawner chunks."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Integer> minConfirmed = this.sgPrediction
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("min-confirmed"))
                            .description("Minimum purple chunks before gold prediction pillar appears."))
                            .defaultValue(1))
                            .min(1)
                            .sliderMax(10)
                            .build()
            );
    private static final Color C_TICK_FILL = new Color(0, 255, 50, 55);
    private static final Color C_TICK_LINE = new Color(30, 255, 80, 235);
    private static final Color C_TICK_TRACE = new Color(30, 255, 80, 200);
    private static final Color C_WEAK_FILL = new Color(0, 180, 60, 30);
    private static final Color C_WEAK_LINE = new Color(0, 200, 70, 150);
    private static final Color C_WEAK_TRACE = new Color(0, 200, 70, 130);
    private static final Color C_CONF_FILL = new Color(155, 20, 255, 90);
    private static final Color C_CONF_LINE = new Color(175, 45, 255, 255);
    private static final Color C_CONF_TRACE = new Color(175, 45, 255, 220);
    private static final Color C_PRED_FILL = new Color(255, 200, 0, 70);
    private static final Color C_PRED_LINE = new Color(255, 220, 20, 255);
    private static final Color C_PRED_TRACE = new Color(255, 215, 10, 215);
    private final ConcurrentHashMap<ChunkPos, Integer> tickCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Long> tickLastSeen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, ConcurrentLinkedDeque<Long>> updateTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Integer> firstBeCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Set<BlockDropFinder.SpawnerFlag>> detectedChunks = new ConcurrentHashMap<>();
    private static final long WINDOW_MS = 30000L;
    private static final int SCAN_TICKS = 60;
    private int tickCounter = 0;

    public BlockDropFinder() {
        super(DonutAddon.CATEGORY, "block-drop-finder", "DonutSMP custom spawner detector. Finds Spawners & Hoppers beneath Y=0. Purple = Confirmed Spawner.");
    }

    public void onActivate() {
        this.clearAll();
        ChatUtils.info("§a[Block Drop Finder] §7Debug: Made by §bMilo §7And §dFlickz", new Object[0]);
    }

    public void onDeactivate() {
        this.clearAll();
    }

    private void clearAll() {
        this.tickCounts.clear();
        this.tickLastSeen.clear();
        this.updateTimestamps.clear();
        this.firstBeCount.clear();
        this.detectedChunks.clear();
        this.tickCounter = 0;
    }

    @EventHandler
    private void onTick(Post event) {
        if (MC.world != null && MC.player != null) {
            if (++this.tickCounter >= 60) {
                this.tickCounter = 0;
                int pCx = MC.player.getChunkPos().x;
                int pCz = MC.player.getChunkPos().z;
                int rad = (Integer)this.scanRadius.get();
                int bottomY = MC.world.getBottomY();

                for (int dx = -rad; dx <= rad; dx++) {
                    for (int dz = -rad; dz <= rad; dz++) {
                        WorldChunk chunk = MC.world.getChunk(pCx + dx, pCz + dz);
                        if (chunk != null) {
                            ChunkPos cp = chunk.getPos();
                            ChunkSection[] sec = chunk.getSectionArray();

                            for (int i = 0; i < sec.length; i++) {
                                if (sec[i] != null && !sec[i].isEmpty()) {
                                    int secBaseY = bottomY + i * 16;
                                    if (secBaseY + 16 <= 0) {
                                        boolean found = sec[i].getBlockStateContainer().hasAny(s -> SPAWNER_BLOCKS.contains(s.getBlock()));
                                        if (found) {
                                            boolean fresh = this.addFlag(cp, BlockDropFinder.SpawnerFlag.PALETTE_SPAWNER);
                                            if (fresh && (Boolean)this.chatDebug.get()) {
                                                ChatUtils.info(
                                                        "[BDF] §5Spawner/Hopper block§r in palette chunk " + cp.x + "," + cp.z + " Y~" + secBaseY,
                                                        new Object[0]
                                                );
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    private void onPacketReceive(Receive event) {
        if (MC.world != null && MC.player != null) {
            if (event.packet instanceof BlockUpdateS2CPacket pkt) {
                BlockPos pos = pkt.getPos();
                ChunkPos cp = new ChunkPos(pos);
                if ((Boolean)this.tickDebugMode.get() && pos.getY() < (Integer)this.tickDebugY.get()) {
                    this.recordTick(cp);
                }

                this.recordBlockUpdate(cp);
                if (SPAWNER_BLOCKS.contains(pkt.getState().getBlock()) && pos.getY() < 0) {
                    boolean fresh = this.addFlag(cp, BlockDropFinder.SpawnerFlag.DIRECT_STATE);
                    if (fresh) {
                        ChatUtils.info(
                                "[BDF] §c§lSPAWNER/HOPPER STATE§r detected! Chunk " + cp.x + "," + cp.z + " Y=" + pos.getY(),
                                new Object[0]
                        );
                    }
                }
            } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket pkt) {
                boolean[] tdDone = new boolean[]{false};
                ChunkPos[] cpRef = new ChunkPos[]{null};
                pkt.visitUpdates(
                        (bp, bs) -> {
                            if (cpRef[0] == null) {
                                cpRef[0] = new ChunkPos(bp);
                            }

                            if ((Boolean)this.tickDebugMode.get() && !tdDone[0] && bp.getY() < (Integer)this.tickDebugY.get()) {
                                tdDone[0] = true;
                            }

                            if (SPAWNER_BLOCKS.contains(bs.getBlock()) && bp.getY() < 0) {
                                ChunkPos cpx = new ChunkPos(bp);
                                boolean freshx = this.addFlag(cpx, BlockDropFinder.SpawnerFlag.DIRECT_STATE);
                                if (freshx) {
                                    ChatUtils.info(
                                            "[BDF] §c§lSPAWNER/HOPPER DELTA§r detected! Chunk " + cpx.x + "," + cpx.z + " Y=" + bp.getY(),
                                            new Object[0]
                                    );
                                }
                            }
                        }
                );
                if (cpRef[0] != null) {
                    if (tdDone[0]) {
                        this.recordTick(cpRef[0]);
                    }

                    this.recordBlockUpdate(cpRef[0]);
                }
            }
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (MC.world != null && MC.player != null) {
            WorldChunk chunk = event.chunk();
            ChunkPos cp = chunk.getPos();
            if (this.isInScanRadius(cp)) {
                int incoming = chunk.getBlockEntities().size();
                Integer prev = this.firstBeCount.putIfAbsent(cp, incoming);
                if (prev != null) {
                    this.firstBeCount.put(cp, incoming);
                    if (incoming >= prev + 2) {
                        boolean fresh = this.addFlag(cp, BlockDropFinder.SpawnerFlag.RESYNC);
                        if (fresh && (Boolean)this.chatDebug.get()) {
                            ChatUtils.info("[BDF] §9BE Resync§r chunk " + cp.x + "," + cp.z + " BE: " + prev + "→" + incoming, new Object[0]);
                        }
                    }
                }
            }
        }
    }

    private void recordTick(ChunkPos cp) {
        this.tickCounts.merge(cp, 1, Integer::sum);
        this.tickLastSeen.put(cp, System.currentTimeMillis());
    }

    private void recordBlockUpdate(ChunkPos cp) {
        if (this.isInScanRadius(cp)) {
            long now = System.currentTimeMillis();
            ConcurrentLinkedDeque<Long> times = this.updateTimestamps.computeIfAbsent(cp, k -> new ConcurrentLinkedDeque<>());
            times.addLast(now);

            Long h;
            while ((h = times.peekFirst()) != null && now - h > 30000L) {
                times.pollFirst();
            }

            this.evaluateActivityAnomaly(cp);
        }
    }

    private void evaluateActivityAnomaly(ChunkPos target) {
        if (MC.player != null) {
            long now = System.currentTimeMillis();
            int pCx = MC.player.getChunkPos().x;
            int pCz = MC.player.getChunkPos().z;
            int rad = (Integer)this.scanRadius.get();
            List<Double> counts = new ArrayList<>();

            for (Entry<ChunkPos, ConcurrentLinkedDeque<Long>> e : this.updateTimestamps.entrySet()) {
                ChunkPos cp = e.getKey();
                if (Math.abs(cp.x - pCx) <= rad && Math.abs(cp.z - pCz) <= rad) {
                    ConcurrentLinkedDeque<Long> t = e.getValue();

                    Long hh;
                    while ((hh = t.peekFirst()) != null && now - hh > 30000L) {
                        t.pollFirst();
                    }

                    counts.add((double)t.size());
                }
            }

            if (counts.size() >= 5) {
                double sum = 0.0;

                for (double c : counts) {
                    sum += c;
                }

                double mean = sum / (double)counts.size();
                double varSum = 0.0;

                for (double c : counts) {
                    varSum += (c - mean) * (c - mean);
                }

                double stdDev = Math.sqrt(varSum / (double)counts.size());
                if (!(stdDev < 1.0)) {
                    ConcurrentLinkedDeque<Long> tt = this.updateTimestamps.get(target);
                    if (tt != null && (double)tt.size() >= mean + (Double)this.activityThreshold.get() * stdDev) {
                        boolean fresh = this.addFlag(target, BlockDropFinder.SpawnerFlag.ACTIVITY);
                        if (fresh && (Boolean)this.chatDebug.get()) {
                            ChatUtils.info("[BDF] §aHigh Hopper Activity§r chunk " + target.x + "," + target.z, new Object[0]);
                        }
                    }
                }
            }
        }
    }

    private double[] computePrediction() {
        int confirmed = 0;
        double wX = 0.0;
        double wZ = 0.0;
        double totalW = 0.0;

        for (Entry<ChunkPos, Set<BlockDropFinder.SpawnerFlag>> e : this.detectedChunks.entrySet()) {
            if (e.getValue().size() >= 2) {
                confirmed++;
                double w = (double)e.getValue().size();
                wX += ((double)e.getKey().getStartX() + 8.0) * w;
                wZ += ((double)e.getKey().getStartZ() + 8.0) * w;
                totalW += w;
            }
        }

        return confirmed >= this.minConfirmed.get() && totalW != 0.0 ? new double[]{wX / totalW, wZ / totalW} : null;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (MC.player != null && MC.world != null) {
            Vec3d eye = MC.player.getCameraPosVec(event.tickDelta);
            int baseY = (Integer)this.pillarBaseY.get();
            long now = System.currentTimeMillis();
            int fadeS = (Integer)this.tickFadeSeconds.get();
            if ((Boolean)this.tickDebugMode.get()) {
                double hw = (Double)this.tickPillarWidth.get() / 2.0;

                for (Entry<ChunkPos, Integer> e : this.tickCounts.entrySet()) {
                    ChunkPos cp = e.getKey();
                    int ticks = e.getValue();
                    if (fadeS > 0) {
                        Long last = this.tickLastSeen.get(cp);
                        if (last == null || now - last > (long)fadeS * 1000L) {
                            continue;
                        }
                    }

                    int topY = baseY
                            + Math.min((Integer)this.tickMinHeight.get() + ticks * (Integer)this.heightPerTick.get(), (Integer)this.tickMaxHeight.get());
                    double cx = (double)cp.getStartX() + 8.0;
                    double cz = (double)cp.getStartZ() + 8.0;
                    event.renderer.box(cx - hw, (double)baseY, cz - hw, cx + hw, (double)topY, cz + hw, C_TICK_FILL, C_TICK_LINE, ShapeMode.Both, 0);
                    if ((Boolean)this.showTracers.get()) {
                        event.renderer.line(eye.x, eye.y, eye.z, cx, (double)baseY + (double)(topY - baseY) * 0.5, cz, C_TICK_TRACE);
                    }
                }
            }

            if (!this.detectedChunks.isEmpty()) {
                double hw = (Double)this.detectorWidth.get() / 2.0;
                int topY = baseY + (Integer)this.detectorHeight.get();
                boolean skipWeak = (Boolean)this.hideWeak.get();

                for (Entry<ChunkPos, Set<BlockDropFinder.SpawnerFlag>> e : this.detectedChunks.entrySet()) {
                    boolean confirmed = e.getValue().size() >= 2;
                    if (!skipWeak || confirmed) {
                        ChunkPos cpx = e.getKey();
                        Color fill = confirmed ? C_CONF_FILL : C_WEAK_FILL;
                        Color outline = confirmed ? C_CONF_LINE : C_WEAK_LINE;
                        Color tracer = confirmed ? C_CONF_TRACE : C_WEAK_TRACE;
                        double cx = (double)cpx.getStartX() + 8.0;
                        double cz = (double)cpx.getStartZ() + 8.0;
                        event.renderer.box(cx - hw, (double)baseY, cz - hw, cx + hw, (double)topY, cz + hw, fill, outline, ShapeMode.Both, 0);
                        if ((Boolean)this.showTracers.get()) {
                            event.renderer.line(eye.x, eye.y, eye.z, cx, (double)baseY + (double)(topY - baseY) * 0.5, cz, tracer);
                        }
                    }
                }
            }

            if ((Boolean)this.showPrediction.get()) {
                double[] pred = this.computePrediction();
                if (pred != null) {
                    double phw = (Double)this.detectorWidth.get();
                    int ptop = baseY + (Integer)this.detectorHeight.get() + 60;
                    event.renderer.box(
                            pred[0] - phw, (double)baseY, pred[1] - phw,
                            pred[0] + phw, (double)ptop, pred[1] + phw,
                            C_PRED_FILL, C_PRED_LINE, ShapeMode.Both, 0
                    );
                    if ((Boolean)this.showTracers.get()) {
                        event.renderer.line(eye.x, eye.y, eye.z, pred[0], (double)baseY + (double)(ptop - baseY) * 0.5, pred[1], C_PRED_TRACE);
                    }
                }
            }
        }
    }

    private boolean addFlag(ChunkPos cp, BlockDropFinder.SpawnerFlag flag) {
        return this.detectedChunks.computeIfAbsent(cp, k -> ConcurrentHashMap.newKeySet()).add(flag);
    }

    private boolean isInScanRadius(ChunkPos cp) {
        return MC.player == null
                ? false
                : Math.abs(cp.x - MC.player.getChunkPos().x) <= (Integer)this.scanRadius.get()
                && Math.abs(cp.z - MC.player.getChunkPos().z) <= (Integer)this.scanRadius.get();
    }

    static {
        SPAWNER_BLOCKS.addAll(Arrays.asList(Blocks.SPAWNER, Blocks.HOPPER));
    }

    private static enum SpawnerFlag {
        ACTIVITY,
        RESYNC,
        PALETTE_SPAWNER,
        DIRECT_STATE,
        BE_UPDATE;
    }
}