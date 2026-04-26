package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.IntSetting.Builder;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.client.MinecraftClient;
import org.joml.Vector3d;

public class GeodeRecorder extends Module {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private final SettingGroup sgScanning = this.settings.createGroup("Scanning");
    private final SettingGroup sgWaypoints = this.settings.createGroup("Waypoints");
    private final SettingGroup sgRender = this.settings.createGroup("Render - ESP");
    private final SettingGroup sgText = this.settings.createGroup("Render - Text");
    private final SettingGroup sgTracers = this.settings.createGroup("Render - Tracers");
    private final Setting<Integer> playerProximity = this.sgScanning
            .add(
                    ((Builder)((Builder)((Builder)new Builder().name("player-proximity")).description("Max chunks from a real player to trigger the unmasking trust."))
                            .defaultValue(6))
                            .min(1)
                            .sliderMax(16)
                            .build()
            );
    private final Setting<Boolean> useThreading = this.sgScanning
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("async-scanning"))
                            .description("Prevents lag spikes by scanning chunks on a background thread."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Integer> minBuddingForGeode = this.sgScanning
            .add(
                    ((Builder)((Builder)((Builder)new Builder().name("min-budding-blocks"))
                            .description("Minimum number of budding amethyst blocks to be considered a real geode."))
                            .defaultValue(10))
                            .min(1)
                            .sliderMax(50)
                            .build()
            );
    private final Setting<Boolean> autoWaypoint = this.sgWaypoints
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("auto-waypoint"))
                            .description("Automatically create Meteor waypoints for newly discovered geodes."))
                            .defaultValue(false))
                            .build()
            );
    private final Setting<Boolean> renderClusters = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("render-clusters"))
                            .description("Draw a large bounding box around the entire geode."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Boolean> renderBlocks = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("render-budding-blocks"))
                            .description("Draw boxes around individual budding amethyst blocks."))
                            .defaultValue(false))
                            .build()
            );
    private final Setting<SettingColor> clusterColor = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("cluster-color"))
                            .description("Color for the geode bounding box."))
                            .defaultValue(new SettingColor(180, 50, 255, 60))
                            .build()
            );
    private final Setting<SettingColor> blockColor = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("block-color"))
                            .description("Color for individual budding blocks."))
                            .defaultValue(new SettingColor(200, 100, 255, 120))
                            .build()
            );
    private final Setting<Boolean> renderText = this.sgText
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("render-nametags"))
                            .description("Show floating text with geode stats."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Double> textScale = this.sgText
            .add(
                    ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                            .name("text-scale"))
                            .description("Scale of the floating text."))
                            .defaultValue(1.5)
                            .min(0.5)
                            .sliderMax(3.0)
                            .visible(this.renderText::get))
                            .build()
            );
    private final Setting<SettingColor> textColor = this.sgText
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("text-color"))
                            .description("Color of the text."))
                            .defaultValue(new SettingColor(255, 255, 255, 255))
                            .visible(this.renderText::get))
                            .build()
            );
    private final Setting<Boolean> showTracers = this.sgTracers
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("show-tracers"))
                            .description("Draw tracer lines to geode centers."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<SettingColor> tracerColor = this.sgTracers
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("tracer-color"))
                            .description("Color for tracers."))
                            .defaultValue(new SettingColor(180, 50, 255, 200))
                            .visible(this.showTracers::get))
                            .build()
            );
    private final ConcurrentHashMap<ChunkPos, Set<BlockPos>> rawBuddingBlocks = new ConcurrentHashMap<>();
    private final List<GeodeRecorder.GeodeCluster> geodeClusters = new ArrayList<>();
    private boolean clustersNeedRebuild = false;
    private final Path savePath = Paths.get("meteor-client", "geode_database.csv");
    private ExecutorService threadPool;
    private final Set<Integer> notifiedClusterHashes = new HashSet<>();

    public GeodeRecorder() {
        super(DonutAddon.CATEGORY, "geode-recorder", "Advanced Geode logger. Maps, clusters, and saves Budding Amethyst.");
    }

    public void onActivate() {
        this.threadPool = Executors.newFixedThreadPool(2);
        this.rawBuddingBlocks.clear();
        this.geodeClusters.clear();
        this.clustersNeedRebuild = false;
        this.loadDatabase();
        this.rebuildClusters();
        int totalBlocks = this.rawBuddingBlocks.values().stream().mapToInt(Set::size).sum();
        this.info("Loaded " + this.geodeClusters.size() + " Geodes containing " + totalBlocks + " budding blocks.", new Object[0]);
    }

    public void onDeactivate() {
        this.saveDatabase();
        if (this.threadPool != null && !this.threadPool.isShutdown()) {
            this.threadPool.shutdown();
        }

        this.info("Saved Geode database.", new Object[0]);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (MC.world != null && MC.player != null) {
            WorldChunk chunk = event.chunk();
            ChunkPos chunkPos = chunk.getPos();
            if (this.isPlayerNearby(chunkPos)) {
                if ((Boolean)this.useThreading.get()) {
                    this.threadPool.execute(() -> this.scanChunk(chunk, chunkPos));
                } else {
                    this.scanChunk(chunk, chunkPos);
                }
            }
        }
    }

    private boolean isPlayerNearby(ChunkPos chunkPos) {
        boolean nearby = MC.world
                .getPlayers()
                .stream()
                .filter(p -> p != MC.player)
                .anyMatch(p -> p.getChunkPos().getChebyshevDistance(chunkPos) <= (Integer)this.playerProximity.get());
        if (!nearby && !MC.player.isSpectator()) {
            nearby = MC.player.getChunkPos().getChebyshevDistance(chunkPos) <= (Integer)this.playerProximity.get();
        }

        return nearby;
    }

    private void scanChunk(WorldChunk chunk, ChunkPos chunkPos) {
        Set<BlockPos> newlyFound = new HashSet<>();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = MC.world.getBottomY();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section != null && !section.isEmpty()) {
                boolean hasTarget = section.getBlockStateContainer().hasAny(statex -> statex.isOf(Blocks.BUDDING_AMETHYST));
                if (hasTarget) {
                    int sectionBaseY = bottomY + i * 16;

                    for (int y = 0; y < 16; y++) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                Block block = state.getBlock();
                                if (block == Blocks.BUDDING_AMETHYST) {
                                    BlockPos pos = new BlockPos(startX + x, sectionBaseY + y, startZ + z);
                                    newlyFound.add(pos);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!newlyFound.isEmpty()) {
            boolean isNewData = false;
            Set<BlockPos> existing = this.rawBuddingBlocks.computeIfAbsent(chunkPos, k -> ConcurrentHashMap.newKeySet());

            for (BlockPos pos : newlyFound) {
                if (existing.add(pos)) {
                    isNewData = true;
                }
            }

            if (isNewData) {
                this.clustersNeedRebuild = true;
                this.saveDatabase();
                MC.execute(this::rebuildClusters);
            }
        }
    }

    private synchronized void rebuildClusters() {
        if (this.clustersNeedRebuild) {
            this.clustersNeedRebuild = false;
            List<BlockPos> allBlocks = new ArrayList<>();

            for (Set<BlockPos> chunkBlocks : this.rawBuddingBlocks.values()) {
                allBlocks.addAll(chunkBlocks);
            }

            List<GeodeRecorder.GeodeCluster> newClusters = new ArrayList<>();
            Set<BlockPos> visited = new HashSet<>();

            for (BlockPos startBlock : allBlocks) {
                if (!visited.contains(startBlock)) {
                    List<BlockPos> currentClusterBlocks = new ArrayList<>();
                    Queue<BlockPos> queue = new LinkedList<>();
                    queue.add(startBlock);
                    visited.add(startBlock);

                    while (!queue.isEmpty()) {
                        BlockPos current = queue.poll();
                        currentClusterBlocks.add(current);

                        for (BlockPos other : allBlocks) {
                            if (!visited.contains(other) && current.getSquaredDistance(other) <= 576.0) {
                                visited.add(other);
                                queue.add(other);
                            }
                        }
                    }

                    if (currentClusterBlocks.size() >= (Integer)this.minBuddingForGeode.get()) {
                        GeodeRecorder.GeodeCluster cluster = new GeodeRecorder.GeodeCluster(currentClusterBlocks);
                        newClusters.add(cluster);
                        int hash = cluster.hashCode();
                        if (!this.notifiedClusterHashes.contains(hash)) {
                            this.notifiedClusterHashes.add(hash);
                            this.onNewGeodeFound(cluster);
                        }
                    }
                }
            }

            this.geodeClusters.clear();
            this.geodeClusters.addAll(newClusters);
        }
    }

    private void onNewGeodeFound(GeodeRecorder.GeodeCluster cluster) {
        String msg = String.format(
                "Found Geode with %d Budding Amethyst at [%d, %d, %d]",
                cluster.blocks.size(),
                (int)cluster.center.x,
                (int)cluster.center.y,
                (int)cluster.center.z
        );
        this.info(msg, new Object[0]);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (MC.player != null) {
            if ((Boolean)this.renderBlocks.get()) {
                for (Set<BlockPos> chunkBlocks : this.rawBuddingBlocks.values()) {
                    for (BlockPos pos : chunkBlocks) {
                        event.renderer.box(
                                (double)pos.getX(), (double)pos.getY(), (double)pos.getZ(),
                                (double)(pos.getX() + 1), (double)(pos.getY() + 1), (double)(pos.getZ() + 1),
                                (Color)this.blockColor.get(), (Color)this.blockColor.get(), ShapeMode.Lines, 0
                        );
                    }
                }
            }

            for (GeodeRecorder.GeodeCluster cluster : this.geodeClusters) {
                if ((Boolean)this.renderClusters.get()) {
                    event.renderer.box(
                            (double)cluster.minX, (double)cluster.minY, (double)cluster.minZ,
                            (double)(cluster.maxX + 1), (double)(cluster.maxY + 1), (double)(cluster.maxZ + 1),
                            (Color)this.clusterColor.get(), (Color)this.clusterColor.get(), ShapeMode.Lines, 0
                    );
                }

                if ((Boolean)this.showTracers.get()) {
                    event.renderer.line(
                            event.offsetX, event.offsetY, event.offsetZ,
                            cluster.center.x, cluster.center.y, cluster.center.z,
                            (Color)this.tracerColor.get()
                    );
                }
            }
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if ((Boolean)this.renderText.get() && MC.player != null) {
            for (GeodeRecorder.GeodeCluster cluster : this.geodeClusters) {
                Vec3d pos = cluster.center.add(0.0, (double)cluster.maxY - cluster.center.y + 1.5, 0.0);
                Vector3d v3d = new Vector3d(pos.x, pos.y, pos.z);
                if (NametagUtils.to2D(v3d, (Double)this.textScale.get())) {
                    NametagUtils.begin(v3d);
                    TextRenderer.get().begin(1.0, false, true);
                    String text = "Geode";
                    String yield = cluster.blocks.size() + " Budding Blocks";
                    double w1 = TextRenderer.get().getWidth(text);
                    double w2 = TextRenderer.get().getWidth(yield);
                    TextRenderer.get().render(text, -w1 / 2.0, -10.0, (Color)this.textColor.get(), true);
                    TextRenderer.get().render(yield, -w2 / 2.0, 0.0, new Color(200, 150, 255), true);
                    TextRenderer.get().end();
                    NametagUtils.end();
                }
            }
        }
    }

    private void loadDatabase() {
        if (Files.exists(this.savePath)) {
            String line;
            try (BufferedReader r = Files.newBufferedReader(this.savePath)) {
                while ((line = r.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length == 3) {
                        try {
                            int x = Integer.parseInt(parts[0].trim());
                            int y = Integer.parseInt(parts[1].trim());
                            int z = Integer.parseInt(parts[2].trim());
                            BlockPos pos = new BlockPos(x, y, z);
                            ChunkPos cPos = new ChunkPos(pos);
                            this.rawBuddingBlocks.computeIfAbsent(cPos, k -> ConcurrentHashMap.newKeySet()).add(pos);
                        } catch (NumberFormatException var10) {
                        }
                    }
                }
            } catch (IOException var12) {
                this.error("Failed to load Geode database.", new Object[0]);
            }
        }
    }

    private synchronized void saveDatabase() {
        try {
            Files.createDirectories(this.savePath.getParent());

            try (BufferedWriter w = Files.newBufferedWriter(this.savePath)) {
                for (Set<BlockPos> blocks : this.rawBuddingBlocks.values()) {
                    for (BlockPos pos : blocks) {
                        w.write(pos.getX() + "," + pos.getY() + "," + pos.getZ());
                        w.newLine();
                    }
                }
            }
        } catch (IOException var8) {
            this.error("Failed to save Geode database.", new Object[0]);
        }
    }

    private static class GeodeCluster {
        public final List<BlockPos> blocks;
        public final Vec3d center;
        public final int minX;
        public final int minY;
        public final int minZ;
        public final int maxX;
        public final int maxY;
        public final int maxZ;

        public GeodeCluster(List<BlockPos> blocks) {
            this.blocks = new ArrayList<>(blocks);
            long sumX = 0L;
            long sumY = 0L;
            long sumZ = 0L;
            int miX = Integer.MAX_VALUE;
            int miY = Integer.MAX_VALUE;
            int miZ = Integer.MAX_VALUE;
            int maX = Integer.MIN_VALUE;
            int maY = Integer.MIN_VALUE;
            int maZ = Integer.MIN_VALUE;

            for (BlockPos p : blocks) {
                sumX += (long)p.getX();
                sumY += (long)p.getY();
                sumZ += (long)p.getZ();
                if (p.getX() < miX) miX = p.getX();
                if (p.getY() < miY) miY = p.getY();
                if (p.getZ() < miZ) miZ = p.getZ();
                if (p.getX() > maX) maX = p.getX();
                if (p.getY() > maY) maY = p.getY();
                if (p.getZ() > maZ) maZ = p.getZ();
            }

            this.minX = miX;
            this.minY = miY;
            this.minZ = miZ;
            this.maxX = maX;
            this.maxY = maY;
            this.maxZ = maZ;
            this.center = new Vec3d(
                    (double)sumX / (double)blocks.size() + 0.5,
                    (double)sumY / (double)blocks.size() + 0.5,
                    (double)sumZ / (double)blocks.size() + 0.5
            );
        }

        @Override
        public int hashCode() {
            return Objects.hash((int)this.center.x, (int)this.center.y, (int)this.center.z, this.blocks.size());
        }
    }
}