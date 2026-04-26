package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public class PlayerSightingHeatmap extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgScan = this.settings.createGroup("Underground Scan");
    private final SettingGroup sgTime = this.settings.createGroup("Time Window");
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Boolean> autoSave = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("auto-save")
                    .description("Automatically save heatmap to file.")
                    .defaultValue(true)
                    .build());

    private final Setting<Boolean> chatNotify = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("chat-notifications")
                    .description("Print promotion events (SUSPECTED / CONFIRMED) to chat.")
                    .defaultValue(true)
                    .build());

    private final Setting<Integer> confirmThreshold = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("confirm-threshold")
                    .description("Sighting count needed to promote SUSPECTED → CONFIRMED.")
                    .defaultValue(2)
                    .range(1, 50)
                    .sliderMax(20)
                    .build());

    private final Setting<Integer> scanRadius = this.sgScan
            .add(new IntSetting.Builder()
                    .name("scan-radius-chunks")
                    .description("Chunks around the spotted player to scan for ores/spawners.")
                    .defaultValue(5)
                    .range(0, 8)
                    .sliderMax(8)
                    .build());

    private final Setting<Integer> scanBelowY = this.sgScan
            .add(new IntSetting.Builder()
                    .name("scan-below-y")
                    .description("Only count ore/spawner hits below this Y level (underground filter).")
                    .defaultValue(0)
                    .range(-64, 128)
                    .sliderRange(-64, 128)
                    .build());

    private final Setting<Integer> scorePerOre = this.sgScan
            .add(new IntSetting.Builder()
                    .name("score-per-ore")
                    .description("Points per deepslate/rare ore found during scan.")
                    .defaultValue(1)
                    .range(1, 10)
                    .sliderMax(10)
                    .build());

    private final Setting<Integer> scorePerSpawner = this.sgScan
            .add(new IntSetting.Builder()
                    .name("score-per-spawner")
                    .description("Points per Spawner found during scan (strong base signal).")
                    .defaultValue(8)
                    .range(1, 30)
                    .sliderMax(30)
                    .build());

    private final Setting<Integer> minOreScore = this.sgScan
            .add(new IntSetting.Builder()
                    .name("min-ore-score")
                    .description("Minimum ore score to promote a sighting chunk to SUSPECTED.")
                    .defaultValue(1)
                    .range(1, 50)
                    .sliderMax(30)
                    .build());

    private final Setting<Integer> days = this.sgTime
            .add(new IntSetting.Builder()
                    .name("days")
                    .description("Days to keep sighting data.")
                    .defaultValue(30)
                    .range(0, 30)
                    .build());

    private final Setting<Integer> hours = this.sgTime
            .add(new IntSetting.Builder()
                    .name("hours")
                    .description("Hours to keep sighting data.")
                    .defaultValue(0)
                    .range(0, 23)
                    .build());

    private final Setting<Integer> minutes = this.sgTime
            .add(new IntSetting.Builder()
                    .name("minutes")
                    .description("Minutes to keep sighting data.")
                    .defaultValue(0)
                    .range(0, 59)
                    .build());

    private final Setting<Boolean> renderEnabled = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("render")
                    .description("Render the heatmap overlay.")
                    .defaultValue(true)
                    .build());

    private final Setting<SettingColor> lowColor = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("sighting-low-color")
                    .description("Color for low-activity SIGHTING chunks.")
                    .defaultValue(new SettingColor(0, 255, 0, 30))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> highColor = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("sighting-high-color")
                    .description("Color for high-activity SIGHTING chunks.")
                    .defaultValue(new SettingColor(255, 255, 0, 120))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> fillSuspected = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("fill-suspected")
                    .description("Plate fill for SUSPECTED base chunks.")
                    .defaultValue(new SettingColor(255, 140, 0, 45))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> lineSuspected = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("line-suspected")
                    .description("Plate outline for SUSPECTED base chunks.")
                    .defaultValue(new SettingColor(255, 140, 0, 200))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> fillConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("fill-confirmed")
                    .description("Plate fill for CONFIRMED bases.")
                    .defaultValue(new SettingColor(50, 255, 80, 55))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> lineConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("line-confirmed")
                    .description("Plate outline for CONFIRMED bases.")
                    .defaultValue(new SettingColor(50, 255, 80, 220))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<Boolean> showTracers = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-tracers")
                    .description("Draw tracer lines to SUSPECTED / CONFIRMED chunks.")
                    .defaultValue(true)
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> tracerSuspected = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("tracer-suspected")
                    .description("Tracer color for SUSPECTED chunks.")
                    .defaultValue(new SettingColor(255, 165, 0, 140))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> tracerConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("tracer-confirmed")
                    .description("Tracer color for CONFIRMED chunks.")
                    .defaultValue(new SettingColor(80, 255, 80, 190))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<Boolean> showPillar = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-pillar")
                    .description("Render a tall pillar over CONFIRMED base chunks.")
                    .defaultValue(true)
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<Integer> pillarHeight = this.sgRender
            .add(new IntSetting.Builder()
                    .name("pillar-height")
                    .description("Height of the confirmation pillar in blocks.")
                    .defaultValue(80)
                    .min(16)
                    .sliderMax(256)
                    .visible(this.showPillar::get)
                    .build());

    private final Map<ChunkPos, Sighting> heatmap = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> antiSpam = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ChunkPos> oreScanQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<ChunkPos, ChunkPos> scanOrigin = new ConcurrentHashMap<>();
    private final Path savePath = Paths.get("meteor-client", "player_heatmap.txt");
    private int tickCounter = 0;

    public PlayerSightingHeatmap() {
        super(
                DonutAddon.CATEGORY,
                "player-heatmap",
                "Heatmap base finder: records player sightings and scans surrounding chunks for underground ores/spawners to locate hidden bases."
        );
    }

    @Override
    public void onActivate() {
        this.load();
        this.cleanup();
        this.tickCounter = 0;
        this.antiSpam.clear();
        this.oreScanQueue.clear();
        this.scanOrigin.clear();
    }

    @Override
    public void onDeactivate() {
        if (autoSave.get()) this.save();
        this.antiSpam.clear();
        this.oreScanQueue.clear();
        this.scanOrigin.clear();
    }

    @EventHandler
    private void onTick(Post event) {
        if (this.mc.world == null || this.mc.player == null) return;

        this.tickCounter++;

        if (this.tickCounter % 20 == 0) {
            Instant now = Instant.now();
            for (AbstractClientPlayerEntity entity : this.mc.world.getPlayers()) {
                if (!entity.getUuid().equals(this.mc.player.getUuid())) {
                    Instant lastSeen = this.antiSpam.get(entity.getUuid());
                    boolean spamOk = lastSeen == null || Duration.between(lastSeen, now).getSeconds() >= 30L;
                    ChunkPos cp = entity.getChunkPos();
                    this.recordSighting(cp);
                    if (spamOk) {
                        this.antiSpam.put(entity.getUuid(), now);
                        this.enqueueSurroundingScan(cp);
                    }
                }
            }
        }

        if (this.tickCounter % 3 == 0) {
            int limit = 6;
            while (!this.oreScanQueue.isEmpty() && limit-- > 0) {
                ChunkPos toScan = this.oreScanQueue.poll();
                if (toScan == null) break;
                WorldChunk chunk = this.mc.world.getChunk(toScan.x, toScan.z);
                if (chunk == null) {
                    this.oreScanQueue.add(toScan);
                    break;
                }
                this.runOreScan(toScan, chunk, this.scanOrigin.remove(toScan));
            }
        }
    }

    private void recordSighting(ChunkPos cp) {
        Sighting s = this.heatmap.computeIfAbsent(cp, Sighting::new);
        s.touch();
        this.maybePromoteConfirmed(s);
        if (autoSave.get()) this.save();
    }

    private void enqueueSurroundingScan(ChunkPos origin) {
        int r = scanRadius.get();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos target = new ChunkPos(origin.x + dx, origin.z + dz);
                if (!this.scanOrigin.containsKey(target)) {
                    this.scanOrigin.put(target, origin);
                    this.oreScanQueue.add(target);
                }
            }
        }
    }

    private void runOreScan(ChunkPos cp, WorldChunk chunk, ChunkPos originCp) {
        if (this.mc.world == null) return;

        int belowY = scanBelowY.get();
        int bottomY = this.mc.world.getBottomY();
        int oreScore = 0;
        int lowestOreY = belowY;
        ChunkSection[] sections = chunk.getSectionArray();

        for (int si = 0; si < sections.length; si++) {
            if (sections[si] == null || sections[si].isEmpty()) continue;
            int sectionBottomY = bottomY + si * 16;
            if (sectionBottomY >= belowY) break;

            boolean hasTarget = sections[si].getBlockStateContainer().hasAny(sx -> this.isTargetBlock(sx.getBlock()));
            if (!hasTarget) continue;

            for (int bx = 0; bx < 16; bx++) {
                for (int by = 0; by < 16; by++) {
                    int absoluteY = sectionBottomY + by;
                    if (absoluteY >= belowY) continue;
                    for (int bz = 0; bz < 16; bz++) {
                        Block b = sections[si].getBlockState(bx, by, bz).getBlock();
                        if (b == Blocks.SPAWNER) {
                            oreScore += scorePerSpawner.get();
                            if (absoluteY < lowestOreY) lowestOreY = absoluteY;
                        } else if (this.isOreBlock(b)) {
                            oreScore += scorePerOre.get();
                            if (absoluteY < lowestOreY) lowestOreY = absoluteY;
                        }
                    }
                }
            }
        }

        if (oreScore >= minOreScore.get()) {
            ChunkPos promoteCp = originCp != null ? originCp : cp;
            Sighting s = this.heatmap.computeIfAbsent(promoteCp, Sighting::new);
            s.oreScore += oreScore;
            s.oreFloorY = Math.min(s.oreFloorY, lowestOreY);
            if (s.tier == Tier.SIGHTING) {
                s.tier = Tier.SUSPECTED;
                if (chatNotify.get()) {
                    ChatUtils.info("[Heatmap] §eSUSPECTED BASE§r at chunk §f"
                            + promoteCp.x + "," + promoteCp.z
                            + " §7oreScore=" + s.oreScore + " oreFloorY=" + s.oreFloorY);
                }
            }
            this.maybePromoteConfirmed(s);
            if (autoSave.get()) this.save();
        }
    }

    private void maybePromoteConfirmed(Sighting s) {
        if (s.tier == Tier.SUSPECTED && s.count >= confirmThreshold.get()) {
            s.tier = Tier.CONFIRMED;
            if (chatNotify.get()) {
                ChatUtils.info("[Heatmap] §a§lBASE CONFIRMED§r chunk §f"
                        + s.cp.x + "," + s.cp.z
                        + " §7sightings=" + s.count + " oreScore=" + s.oreScore);
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
                || b == Blocks.LAPIS_ORE
                || b == Blocks.DEEPSLATE_LAPIS_ORE
                || b == Blocks.COAL_ORE
                || b == Blocks.DEEPSLATE_COAL_ORE
                || b == Blocks.COPPER_ORE
                || b == Blocks.DEEPSLATE_COPPER_ORE;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderEnabled.get() || this.mc.player == null || this.mc.world == null) return;

        Duration keep = this.window();
        Instant cutoff = Instant.now().minus(keep);
        Vec3d eye = this.mc.player.getCameraPosVec(event.tickDelta);

        for (Sighting s : this.heatmap.values()) {
            if (s.last.isBefore(cutoff)) continue;

            double x0 = s.cp.getStartX();
            double z0 = s.cp.getStartZ();
            double x1 = x0 + 16.0;
            double z1 = z0 + 16.0;
            double cx = s.cp.getCenterX();
            double cz = s.cp.getCenterZ();

            switch (s.tier) {
                case SIGHTING -> {
                    int score = s.densityScore(keep);
                    float ratio = Math.min(1.0F, Math.max(0.0F, score / 20.0F));
                    SettingColor l = lowColor.get();
                    SettingColor h = highColor.get();
                    int r = (int)(l.r + (h.r - l.r) * ratio);
                    int g = (int)(l.g + (h.g - l.g) * ratio);
                    int b = (int)(l.b + (h.b - l.b) * ratio);
                    int a = (int)(l.a + (h.a - l.a) * ratio);
                    SettingColor c = new SettingColor(r, g, b, a);
                    event.renderer.box(x0, 64.0, z0, x1, 64.1, z1, c, c, ShapeMode.Both, 0);
                }
                case SUSPECTED -> {
                    double plateY = s.oreFloorY + 0.05;
                    event.renderer.box(x0, plateY - 0.05, z0, x1, plateY + 0.25, z1,
                            fillSuspected.get(), lineSuspected.get(), ShapeMode.Both, 0);
                    if (showTracers.get()) {
                        event.renderer.line(eye.x, eye.y, eye.z, cx, plateY, cz, (Color) tracerSuspected.get());
                    }
                }
                case CONFIRMED -> {
                    double plateY = s.oreFloorY + 0.05;
                    SettingColor fill = fillConfirmed.get();
                    SettingColor line = lineConfirmed.get();
                    event.renderer.box(x0, plateY - 0.05, z0, x1, plateY + 0.25, z1, fill, line, ShapeMode.Both, 0);
                    if (showPillar.get()) {
                        event.renderer.box(x0, plateY, z0, x1, plateY + pillarHeight.get(), z1, fill, line, ShapeMode.Both, 0);
                    }
                    if (showTracers.get()) {
                        event.renderer.line(eye.x, eye.y, eye.z, cx, plateY, cz, (Color) tracerConfirmed.get());
                    }
                }
            }
        }
    }

    private void load() {
        if (!Files.exists(this.savePath)) return;
        this.heatmap.clear();
        try (BufferedReader r = Files.newBufferedReader(this.savePath)) {
            String line;
            while ((line = r.readLine()) != null) {
                Sighting s = Sighting.from(line);
                if (s != null) this.heatmap.put(s.cp, s);
            }
        } catch (IOException ignored) {}
    }

    private void save() {
        try {
            Files.createDirectories(this.savePath.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(this.savePath)) {
                for (Sighting s : this.heatmap.values()) {
                    w.write(s.toString());
                    w.newLine();
                }
            }
        } catch (IOException ignored) {}
    }

    private void cleanup() {
        Instant cutoff = Instant.now().minus(this.window());
        this.heatmap.entrySet().removeIf(e -> e.getValue().last.isBefore(cutoff));
    }

    private Duration window() {
        return Duration.ofDays(days.get())
                .plusHours(hours.get())
                .plusMinutes(minutes.get());
    }

    private static class Sighting {
        final ChunkPos cp;
        Instant first;
        Instant last;
        int count;
        Tier tier;
        int oreScore;
        int oreFloorY;

        Sighting(ChunkPos cp) {
            this.cp = cp;
            this.first = this.last = Instant.now();
            this.count = 1;
            this.tier = Tier.SIGHTING;
            this.oreScore = 0;
            this.oreFloorY = 64;
        }

        void touch() {
            this.last = Instant.now();
            this.count++;
        }

        int densityScore(Duration window) {
            long age = Duration.between(this.last, Instant.now()).getSeconds();
            return age >= window.getSeconds() ? 0
                    : (int)((double) this.count * (1.0 - (double) age / (double) window.getSeconds())) + 1;
        }

        @Override
        public String toString() {
            return cp.x + "," + cp.z + "," + first + "," + last + ","
                    + count + "," + tier.name() + "," + oreScore + "," + oreFloorY;
        }

        static Sighting from(String line) {
            String[] p = line.split(",");
            if (p.length < 5) return null;
            try {
                Sighting s = new Sighting(new ChunkPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())));
                s.first = Instant.parse(p[2].trim());
                s.last = Instant.parse(p[3].trim());
                s.count = Integer.parseInt(p[4].trim());
                s.tier = p.length > 5 ? Tier.valueOf(p[5].trim()) : Tier.SIGHTING;
                s.oreScore = p.length > 6 ? Integer.parseInt(p[6].trim()) : 0;
                s.oreFloorY = p.length > 7 ? Integer.parseInt(p[7].trim()) : 64;
                return s;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private enum Tier {
        SIGHTING,
        SUSPECTED,
        CONFIRMED
    }
}