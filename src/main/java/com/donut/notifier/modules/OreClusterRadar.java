package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Receive;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Post;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public class OreClusterRadar extends Module {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private final Set<ChunkPos> redChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> flaggedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> greenChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> scannedKeys = ConcurrentHashMap.newKeySet();
    private final Deque<ChunkPos> scanQueue = new ConcurrentLinkedDeque<>();
    private final List<Vec3d> clusterCenters = new ArrayList<>();

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgScan = this.settings.createGroup("Scanning");
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Boolean> chatNotify = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("chat-notifications")
                    .description("Print to chat when a new ore cluster is flagged.")
                    .defaultValue(true)
                    .build());

    private final Setting<Boolean> debugMode = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("debug-mode")
                    .description("Extra scan detail in chat.")
                    .defaultValue(false)
                    .build());

    private final Setting<Integer> redZoneRadius = this.sgScan
            .add(new IntSetting.Builder()
                    .name("red-zone-radius")
                    .description("Chunk radius around local player marked as exclusion zone (red).")
                    .defaultValue(9)
                    .range(1, 20)
                    .sliderMax(16)
                    .build());

    private final Setting<Integer> haloRadius = this.sgScan
            .add(new IntSetting.Builder()
                    .name("halo-radius")
                    .description("Chunk radius of green halo spread from each flagged chunk.")
                    .defaultValue(9)
                    .range(1, 20)
                    .sliderMax(16)
                    .build());

    private final Setting<Integer> oreYCeiling = this.sgScan
            .add(new IntSetting.Builder()
                    .name("ore-y-ceiling")
                    .description("Only count ore blocks strictly BELOW this Y level (reduces surface noise).")
                    .defaultValue(100)
                    .range(-64, 320)
                    .sliderMax(160)
                    .build());

    private final Setting<Integer> chunksPerTick = this.sgScan
            .add(new IntSetting.Builder()
                    .name("chunks-per-tick")
                    .description("Maximum chunks dequeued and scanned per tick.")
                    .defaultValue(50)
                    .range(1, 200)
                    .sliderMax(100)
                    .build());

    private final Setting<Integer> viewDistMargin = this.sgScan
            .add(new IntSetting.Builder()
                    .name("view-dist-margin")
                    .description("Extra chunks beyond render distance to enqueue for scanning.")
                    .defaultValue(2)
                    .range(0, 8)
                    .sliderMax(8)
                    .build());

    private final Setting<Double> boxHalfSize = this.sgRender
            .add(new DoubleSetting.Builder()
                    .name("box-half-size")
                    .description("Half-width of the rendered cluster pillar in blocks.")
                    .defaultValue(8.0)
                    .min(1.0)
                    .sliderMax(16.0)
                    .build());

    private final Setting<Integer> nearPlayerDist = this.sgRender
            .add(new IntSetting.Builder()
                    .name("near-player-dist")
                    .description("Distance (blocks) at which another player causes the pillar to turn green.")
                    .defaultValue(144)
                    .range(16, 512)
                    .sliderMax(300)
                    .build());

    private final Setting<SettingColor> redFillColor = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("red-fill-color")
                    .description("Pillar fill when no nearby player is detected.")
                    .defaultValue(new SettingColor(255, 0, 0, 50))
                    .build());

    private final Setting<SettingColor> redLineColor = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("red-line-color")
                    .description("Pillar outline when no nearby player is detected.")
                    .defaultValue(new SettingColor(255, 0, 0, 220))
                    .build());

    private final Setting<SettingColor> greenFillColor = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("green-fill-color")
                    .description("Pillar fill when another player is nearby (known player cluster).")
                    .defaultValue(new SettingColor(0, 255, 0, 50))
                    .build());

    private final Setting<SettingColor> greenLineColor = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("green-line-color")
                    .description("Pillar outline when another player is nearby.")
                    .defaultValue(new SettingColor(0, 255, 0, 220))
                    .build());

    public OreClusterRadar() {
        super(
                DonutAddon.CATEGORY,
                "ore-cluster-radar",
                "Finds chunks loaded outside your render distance containing valuable ores below Y=100, clusters nearby detections via BFS, and renders predicted player locations."
        );
    }

    @Override
    public void onActivate() {
        this.redChunks.clear();
        this.flaggedChunks.clear();
        this.greenChunks.clear();
        this.scannedKeys.clear();
        this.scanQueue.clear();
        synchronized (this.clusterCenters) {
            this.clusterCenters.clear();
        }
        ChatUtils.info("§b[OreClusterRadar] §7Active. Scanning chunks for ore clusters…");
    }

    @Override
    public void onDeactivate() {
        this.redChunks.clear();
        this.flaggedChunks.clear();
        this.greenChunks.clear();
        this.scannedKeys.clear();
        this.scanQueue.clear();
        synchronized (this.clusterCenters) {
            this.clusterCenters.clear();
        }
    }

    @EventHandler
    private void onPacket(Receive event) {
        if (MC.world == null || MC.player == null) return;
        if (event.packet instanceof ChunkDataS2CPacket pkt) {
            this.enqueue(new ChunkPos(pkt.getChunkX(), pkt.getChunkZ()), true);
        }
    }

    @EventHandler
    private void onTick(Post event) {
        if (MC.world == null || MC.player == null) return;

        ChunkPos playerPos = MC.player.getChunkPos();
        int redRad = redZoneRadius.get();
        int greenRad = haloRadius.get();

        for (int x = -redRad; x <= redRad; x++) {
            for (int z = -redRad; z <= redRad; z++) {
                this.redChunks.add(new ChunkPos(playerPos.x + x, playerPos.z + z));
            }
        }

        for (AbstractClientPlayerEntity player : MC.world.getPlayers()) {
            if (!player.getUuid().equals(MC.player.getUuid())) {
                ChunkPos gp = player.getChunkPos();
                if (!this.redChunks.contains(gp) && this.flaggedChunks.add(gp)) {
                    this.spreadGreenHalo(gp, greenRad);
                    if (chatNotify.get()) {
                        ChatUtils.info("[OCR] §aPlayer halo§r @ chunk " + gp.x + "," + gp.z);
                    }
                }
            }
        }

        int viewDist = (int) MC.options.getViewDistance().getValue() + viewDistMargin.get();
        for (int x = -viewDist; x <= viewDist; x++) {
            for (int z = -viewDist; z <= viewDist; z++) {
                ChunkPos cp = new ChunkPos(playerPos.x + x, playerPos.z + z);
                if (!this.redChunks.contains(cp)) {
                    this.enqueue(cp, false);
                }
            }
        }

        int limit = chunksPerTick.get();
        while (!this.scanQueue.isEmpty() && limit-- > 0) {
            ChunkPos cp = this.scanQueue.pollFirst();
            if (cp == null) break;

            if (!this.redChunks.contains(cp)) {
                WorldChunk chunk = MC.world.getChunk(cp.x, cp.z);
                if (chunk == null) {
                    this.scannedKeys.remove(cp.toLong());
                } else if (this.containsTargetOre(chunk) && this.flaggedChunks.add(cp)) {
                    this.spreadGreenHalo(cp, greenRad);
                    if (chatNotify.get()) {
                        ChatUtils.info("[OCR] §eOre cluster§r flagged @ chunk " + cp.x + "," + cp.z);
                    }
                }
            }
        }

        this.updateClusters();
    }

    private boolean containsTargetOre(WorldChunk chunk) {
        if (MC.world == null) return false;

        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = MC.world.getBottomY();
        int yCeil = oreYCeiling.get();

        for (int s = 0; s < sections.length; s++) {
            if (sections[s] == null || sections[s].isEmpty()) continue;
            int sectionBaseY = bottomY + s * 16;
            if (sectionBaseY >= yCeil) break;

            boolean hasTarget = sections[s].getBlockStateContainer().hasAny(st -> this.isTargetBlock(st.getBlock()));
            if (hasTarget) {
                int byMax = Math.min(16, yCeil - sectionBaseY);
                for (int by = 0; by < byMax; by++) {
                    for (int bx = 0; bx < 16; bx++) {
                        for (int bz = 0; bz < 16; bz++) {
                            if (this.isTargetBlock(sections[s].getBlockState(bx, by, bz).getBlock())) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isTargetBlock(Block b) {
        return b == Blocks.DIAMOND_ORE
                || b == Blocks.DEEPSLATE_DIAMOND_ORE
                || b == Blocks.EMERALD_ORE
                || b == Blocks.DEEPSLATE_EMERALD_ORE
                || b == Blocks.ANCIENT_DEBRIS
                || b == Blocks.GOLD_ORE
                || b == Blocks.DEEPSLATE_GOLD_ORE
                || b == Blocks.NETHER_GOLD_ORE
                || b == Blocks.IRON_ORE
                || b == Blocks.DEEPSLATE_IRON_ORE
                || b == Blocks.COPPER_ORE
                || b == Blocks.DEEPSLATE_COPPER_ORE
                || b == Blocks.SPAWNER;
    }

    private void spreadGreenHalo(ChunkPos center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                ChunkPos neighbor = new ChunkPos(center.x + x, center.z + z);
                if (!this.redChunks.contains(neighbor)) {
                    this.greenChunks.add(neighbor);
                }
            }
        }
    }

    private void enqueue(ChunkPos cp, boolean priority) {
        if (cp != null && this.scannedKeys.add(cp.toLong())) {
            if (priority) {
                this.scanQueue.addFirst(cp);
            } else {
                this.scanQueue.addLast(cp);
            }
        }
    }

    private void updateClusters() {
        if (this.greenChunks.isEmpty()) {
            synchronized (this.clusterCenters) {
                this.clusterCenters.clear();
            }
            return;
        }

        List<List<ChunkPos>> clusters = new ArrayList<>();
        Set<ChunkPos> visited = new HashSet<>();

        for (ChunkPos start : new ArrayList<>(this.greenChunks)) {
            if (!visited.contains(start)) {
                List<ChunkPos> cluster = new ArrayList<>();
                Deque<ChunkPos> bfsQueue = new ArrayDeque<>();
                bfsQueue.add(start);
                visited.add(start);

                while (!bfsQueue.isEmpty()) {
                    ChunkPos curr = bfsQueue.poll();
                    cluster.add(curr);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx != 0 || dz != 0) {
                                ChunkPos nb = new ChunkPos(curr.x + dx, curr.z + dz);
                                if (this.greenChunks.contains(nb) && visited.add(nb)) {
                                    bfsQueue.add(nb);
                                }
                            }
                        }
                    }
                }
                clusters.add(cluster);
            }
        }

        synchronized (this.clusterCenters) {
            this.clusterCenters.clear();
            for (List<ChunkPos> cluster : clusters) {
                double sumX = 0.0, sumZ = 0.0;
                for (ChunkPos cp : cluster) {
                    sumX += cp.getCenterX();
                    sumZ += cp.getCenterZ();
                }
                this.clusterCenters.add(new Vec3d(sumX / cluster.size(), 0.0, sumZ / cluster.size()));
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (MC.world == null || MC.player == null) return;

        double nearDist = nearPlayerDist.get();
        double halfSize = boxHalfSize.get();

        synchronized (this.clusterCenters) {
            for (Vec3d center : this.clusterCenters) {
                int cx = (int) Math.floor(center.x / 16.0);
                int cz = (int) Math.floor(center.z / 16.0);
                WorldChunk chunk = MC.world.getChunk(cx, cz);
                if (chunk == null) continue;

                int localX = (int) Math.floor(center.x) & 15;
                int localZ = (int) Math.floor(center.z) & 15;
                int surfaceY = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, localX, localZ);

                boolean nearPlayer = false;
                for (AbstractClientPlayerEntity player : MC.world.getPlayers()) {
                    if (!player.getUuid().equals(MC.player.getUuid())) {
                        double distX = Math.abs(player.getX() - center.x);
                        double distZ = Math.abs(player.getZ() - center.z);
                        if (distX <= nearDist && distZ <= nearDist) {
                            nearPlayer = true;
                            break;
                        }
                    }
                }

                SettingColor fill = nearPlayer ? greenFillColor.get() : redFillColor.get();
                SettingColor line = nearPlayer ? greenLineColor.get() : redLineColor.get();
                Color fillC = new Color(fill.r, fill.g, fill.b, fill.a);
                Color lineC = new Color(line.r, line.g, line.b, line.a);
                event.renderer.box(
                        center.x - halfSize, -63.0, center.z - halfSize,
                        center.x + halfSize, surfaceY, center.z + halfSize,
                        fillC, lineC, ShapeMode.Both, 0
                );
            }
        }
    }
}