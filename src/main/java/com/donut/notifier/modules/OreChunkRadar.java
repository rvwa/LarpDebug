package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Receive;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Post;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public class OreChunkRadar extends Module {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private final ConcurrentHashMap<ChunkPos, Detection> detections = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ChunkPos> pendingScan = new ConcurrentLinkedQueue<>();
    private int tickCounter = 0;

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgScan = this.settings.createGroup("Scoring");
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Integer> marginChunks = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("margin-chunks")
                    .description("Chunks to add beyond your render distance before flagging as 'other player'.")
                    .defaultValue(2)
                    .range(0, 8)
                    .sliderMax(8)
                    .build());

    private final Setting<Integer> confirmThreshold = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("confirm-threshold")
                    .description("Total score required to mark a detection as confirmed (green plate).")
                    .defaultValue(10)
                    .range(1, 200)
                    .sliderMax(100)
                    .build());

    private final Setting<Integer> clusterRadius = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("cluster-radius-chunks")
                    .description("Chunks within this distance share their confidence with each other.")
                    .defaultValue(5)
                    .range(1, 16)
                    .sliderMax(16)
                    .build());

    private final Setting<Integer> decaySeconds = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("decay-seconds")
                    .description("Seconds before an un-refreshed detection is discarded.")
                    .defaultValue(120)
                    .range(10, 600)
                    .sliderMax(300)
                    .build());

    private final Setting<Boolean> chatNotify = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("chat-notifications")
                    .description("Print to chat on new detections and confirmations.")
                    .defaultValue(true)
                    .build());

    private final Setting<Boolean> debugMode = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("debug-mode")
                    .description("Print full detection details to chat.")
                    .defaultValue(false)
                    .build());

    private final Setting<Integer> scorePerOre = this.sgScan
            .add(new IntSetting.Builder()
                    .name("score-per-ore")
                    .description("Confidence points per ore block found in a flagged chunk.")
                    .defaultValue(1)
                    .range(1, 10)
                    .sliderMax(10)
                    .build());

    private final Setting<Integer> scorePerSpawner = this.sgScan
            .add(new IntSetting.Builder()
                    .name("score-per-spawner")
                    .description("Confidence points per Spawner block found (strong signal).")
                    .defaultValue(8)
                    .range(1, 30)
                    .sliderMax(30)
                    .build());

    private final Setting<Integer> adjacentBonus = this.sgScan
            .add(new IntSetting.Builder()
                    .name("cluster-neighbor-bonus")
                    .description("Bonus score applied per other detection within cluster-radius.")
                    .defaultValue(3)
                    .range(0, 20)
                    .sliderMax(20)
                    .build());

    private final Setting<SettingColor> fillUnconfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("fill-unconfirmed")
                    .description("Plate fill for unconfirmed detections.")
                    .defaultValue(new SettingColor(255, 50, 50, 35))
                    .build());

    private final Setting<SettingColor> lineUnconfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("line-unconfirmed")
                    .description("Plate outline for unconfirmed detections.")
                    .defaultValue(new SettingColor(255, 50, 50, 180))
                    .build());

    private final Setting<SettingColor> fillConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("fill-confirmed")
                    .description("Plate fill for confirmed detections.")
                    .defaultValue(new SettingColor(50, 255, 80, 50))
                    .build());

    private final Setting<SettingColor> lineConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("line-confirmed")
                    .description("Plate outline for confirmed detections.")
                    .defaultValue(new SettingColor(50, 255, 80, 220))
                    .build());

    private final Setting<Boolean> showTracers = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-tracers")
                    .description("Draw tracer lines to each detected chunk.")
                    .defaultValue(true)
                    .build());

    private final Setting<SettingColor> tracerUnconfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("tracer-unconfirmed")
                    .description("Tracer color for unconfirmed detections.")
                    .defaultValue(new SettingColor(255, 80, 80, 130))
                    .build());

    private final Setting<SettingColor> tracerConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("tracer-confirmed")
                    .description("Tracer color for confirmed detections.")
                    .defaultValue(new SettingColor(80, 255, 80, 180))
                    .build());

    private final Setting<Boolean> showPillar = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-pillar")
                    .description("Render a tall alert pillar over confirmed detections.")
                    .defaultValue(true)
                    .build());

    private final Setting<Integer> pillarHeight = this.sgRender
            .add(new IntSetting.Builder()
                    .name("pillar-height")
                    .description("Height of the confirmation pillar in blocks.")
                    .defaultValue(80)
                    .min(16)
                    .sliderMax(256)
                    .build());

    public OreChunkRadar() {
        super(
                DonutAddon.CATEGORY,
                "ore-chunk-radar",
                "Detects other players by identifying chunks loaded outside your render distance containing ores or spawners. Server cannot patch without breaking chunk loading."
        );
    }

    @Override
    public void onActivate() {
        this.detections.clear();
        this.pendingScan.clear();
        this.tickCounter = 0;
        ChatUtils.info("§a[OreChunkRadar] §7Active. Monitoring chunk loading overspill…");
    }

    @Override
    public void onDeactivate() {
        this.detections.clear();
        this.pendingScan.clear();
    }

    @EventHandler
    private void onPacket(Receive event) {
        if (MC.world == null || MC.player == null) return;

        if (event.packet instanceof ChunkDataS2CPacket pkt) {
            int cx = pkt.getChunkX();
            int cz = pkt.getChunkZ();
            int playerCx = MC.player.getChunkPos().x;
            int playerCz = MC.player.getChunkPos().z;
            int viewDist = (int) MC.options.getViewDistance().getValue() + marginChunks.get();
            if (Math.abs(cx - playerCx) > viewDist || Math.abs(cz - playerCz) > viewDist) {
                ChunkPos cp = new ChunkPos(cx, cz);
                this.pendingScan.add(cp);
                if (debugMode.get()) {
                    ChatUtils.info("[OCR] §7Overspill chunk §f" + cx + "," + cz
                            + " §7dist=§f" + Math.max(Math.abs(cx - playerCx), Math.abs(cz - playerCz)));
                }
            }
        }
    }

    @EventHandler
    private void onTick(Post event) {
        if (MC.world == null || MC.player == null) return;

        this.tickCounter++;

        if (this.tickCounter % 3 == 0) {
            int limit = 8;
            while (!this.pendingScan.isEmpty() && limit-- > 0) {
                ChunkPos cp = this.pendingScan.poll();
                if (cp != null) {
                    WorldChunk chunk = MC.world.getChunk(cp.x, cp.z);
                    if (chunk != null) {
                        this.runOreScan(cp, chunk);
                    }
                }
            }
        }

        if (this.tickCounter % 100 == 0) {
            long cutoff = System.currentTimeMillis() - (long) decaySeconds.get() * 1000L;
            this.detections.entrySet().removeIf(e -> e.getValue().lastUpdated < cutoff);
        }

        if (this.tickCounter % 40 == 0) {
            this.recalculateClusterScores();
        }
    }

    private void runOreScan(ChunkPos cp, WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int oreScore = 0;
        int spawnerScore = 0;

        for (ChunkSection section : sections) {
            if (section != null && !section.isEmpty()) {
                boolean hasTarget = section.getBlockStateContainer().hasAny(s -> this.isTargetBlock(s.getBlock()));
                if (hasTarget) {
                    for (int bx = 0; bx < 16; bx++) {
                        for (int by = 0; by < 16; by++) {
                            for (int bz = 0; bz < 16; bz++) {
                                Block b = section.getBlockState(bx, by, bz).getBlock();
                                if (b == Blocks.SPAWNER) {
                                    spawnerScore += scorePerSpawner.get();
                                } else if (this.isOreBlock(b)) {
                                    oreScore += scorePerOre.get();
                                }
                            }
                        }
                    }
                }
            }
        }

        int totalScore = oreScore + spawnerScore;
        if (totalScore > 0) {
            int surfaceY = this.computeSurfaceY(chunk);
            Detection existing = this.detections.get(cp);
            if (existing != null) {
                existing.baseScore += totalScore;
                existing.lastUpdated = System.currentTimeMillis();
            } else {
                this.detections.put(cp, new Detection(cp, totalScore, surfaceY));
                if (chatNotify.get()) {
                    ChatUtils.info("[OCR] §eDetection§r chunk §f" + cp.x + "," + cp.z
                            + " §7ore=" + oreScore + " §5spawner=" + spawnerScore);
                }
            }
        }
    }

    private void recalculateClusterScores() {
        int radius = clusterRadius.get();
        int bonus = adjacentBonus.get();
        int threshold = confirmThreshold.get();

        for (Detection d : this.detections.values()) {
            int neighborBonus = 0;
            for (Detection other : this.detections.values()) {
                if (other != d
                        && Math.abs(other.cp.x - d.cp.x) <= radius
                        && Math.abs(other.cp.z - d.cp.z) <= radius) {
                    neighborBonus += bonus;
                }
            }
            d.totalScore = d.baseScore + neighborBonus;
            boolean wasConfirmed = d.confirmed;
            d.confirmed = d.totalScore >= threshold;
            if (!wasConfirmed && d.confirmed && chatNotify.get()) {
                ChatUtils.info("[OCR] §a§lPLAYER CONFIRMED§r near chunk §f"
                        + d.cp.x + "," + d.cp.z + " §7confidence=§f" + d.totalScore);
            }
        }
    }

    private boolean isTargetBlock(Block b) {
        return this.isOreBlock(b) || b == Blocks.SPAWNER;
    }

    private boolean isOreBlock(Block b) {
        return b == Blocks.DIAMOND_ORE
                || b == Blocks.DEEPSLATE_DIAMOND_ORE
                || b == Blocks.EMERALD_ORE
                || b == Blocks.DEEPSLATE_EMERALD_ORE
                || b == Blocks.ANCIENT_DEBRIS
                || b == Blocks.GOLD_ORE
                || b == Blocks.DEEPSLATE_GOLD_ORE
                || b == Blocks.IRON_ORE
                || b == Blocks.DEEPSLATE_IRON_ORE
                || b == Blocks.NETHER_GOLD_ORE
                || b == Blocks.NETHER_QUARTZ_ORE
                || b == Blocks.LAPIS_ORE
                || b == Blocks.DEEPSLATE_LAPIS_ORE
                || b == Blocks.COAL_ORE
                || b == Blocks.DEEPSLATE_COAL_ORE
                || b == Blocks.COPPER_ORE
                || b == Blocks.DEEPSLATE_COPPER_ORE;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (MC.player == null || MC.world == null) return;

        Vec3d eye = MC.player.getCameraPosVec(event.tickDelta);

        for (Detection d : this.detections.values()) {
            double plateY = d.surfaceY + 0.05;
            double x0 = d.cp.getStartX();
            double z0 = d.cp.getStartZ();
            double x1 = x0 + 16.0;
            double z1 = z0 + 16.0;
            double cx = d.cp.getCenterX();
            double cz = d.cp.getCenterZ();
            SettingColor fill = d.confirmed ? fillConfirmed.get() : fillUnconfirmed.get();
            SettingColor line = d.confirmed ? lineConfirmed.get() : lineUnconfirmed.get();
            event.renderer.box(x0, plateY - 0.05, z0, x1, plateY + 0.15, z1, fill, line, ShapeMode.Both, 0);
            if (d.confirmed && showPillar.get()) {
                int h = pillarHeight.get();
                event.renderer.box(x0, plateY, z0, x1, plateY + h, z1, fill, line, ShapeMode.Both, 0);
            }
            if (showTracers.get()) {
                SettingColor tc = d.confirmed ? tracerConfirmed.get() : tracerUnconfirmed.get();
                event.renderer.line(eye.x, eye.y, eye.z, cx, plateY, cz, tc);
            }
        }
    }

    private int computeSurfaceY(WorldChunk chunk) {
        if (MC.world == null) return 64;

        int highest = MC.world.getBottomY();
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, bx, bz);
                if (y > highest) highest = y;
            }
        }
        return highest;
    }

    private static class Detection {
        final ChunkPos cp;
        int baseScore;
        int totalScore;
        boolean confirmed;
        int surfaceY;
        long lastUpdated;

        Detection(ChunkPos cp, int baseScore, int surfaceY) {
            this.cp = cp;
            this.baseScore = baseScore;
            this.totalScore = baseScore;
            this.confirmed = false;
            this.surfaceY = surfaceY;
            this.lastUpdated = System.currentTimeMillis();
        }
    }
}