package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Receive;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Post;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.DoubleSetting.Builder;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.world.Heightmap;

public class ArchonRadar extends Module {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private final ConcurrentHashMap<ChunkPos, ArchonRadar.RadarData> radar = new ConcurrentHashMap<>();
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgScanner = this.settings.createGroup("Block Scanner");
    private final SettingGroup sgSignals = this.settings.createGroup("Signals");
    private final SettingGroup sgLight = this.settings.createGroup("Light Forensics");
    private final SettingGroup sgRender = this.settings.createGroup("Render");
    private final Setting<Double> confirmThreshold = this.sgGeneral
            .add(
                    ((Builder)((Builder)new Builder().name("confirm-threshold")).description("Minimum suspicion score to flag a chunk as a confirmed base."))
                            .defaultValue(60.0)
                            .min(10.0)
                            .sliderMax(300.0)
                            .build()
            );
    private final Setting<Boolean> requireMultiFlag = this.sgGeneral
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("require-multi-flag"))
                            .description("Require at least 2 different signal types before confirming a chunk."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Boolean> chatNotify = this.sgGeneral
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("chat-notifications"))
                            .description("Print to chat when a chunk is first confirmed."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Boolean> debugScores = this.sgGeneral
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("debug-scores"))
                            .description("Print every score increment to chat for tuning."))
                            .defaultValue(false))
                            .build()
            );
    private final Setting<Integer> scanRadius = this.sgScanner
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("scan-radius"))
                            .description("Chunk radius to palette-scan for suspicious blocks."))
                            .defaultValue(8))
                            .min(1)
                            .sliderMax(16)
                            .build()
            );
    private final Setting<Integer> scanInterval = this.sgScanner
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("scan-interval-ticks"))
                            .description("Ticks between block-scan passes."))
                            .defaultValue(80))
                            .min(20)
                            .sliderMax(400)
                            .build()
            );
    private final Setting<Integer> scanYCeiling = this.sgScanner
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("scan-y-ceiling"))
                            .description("Only scan chunk sections fully below this Y level."))
                            .defaultValue(0))
                            .min(-64)
                            .sliderMin(-64)
                            .sliderMax(16)
                            .build()
            );
    private final Setting<Double> weightSpawner = this.sgScanner
            .add(
                    ((Builder)((Builder)new Builder().name("weight-spawner")).description("Score per Spawner block found below the Y ceiling."))
                            .defaultValue(60.0)
                            .min(1.0)
                            .sliderMax(200.0)
                            .build()
            );
    private final Setting<Double> weightSuspicious = this.sgScanner
            .add(
                    ((Builder)((Builder)new Builder().name("weight-suspicious-block"))
                            .description("Score per other suspicious block (Obsidian, Chest, Hopper, Amethyst ...)."))
                            .defaultValue(4.0)
                            .min(0.5)
                            .sliderMax(50.0)
                            .build()
            );
    private final Setting<Integer> maxBlocksPerSection = this.sgScanner
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("max-blocks-per-section"))
                            .description("Cap per block type per section to avoid natural cluster inflation."))
                            .defaultValue(16))
                            .min(1)
                            .sliderMax(64)
                            .build()
            );
    private final Setting<Double> soundBonus = this.sgSignals
            .add(
                    ((Builder)((Builder)new Builder().name("sound-bonus")).description("Score bonus when a HOSTILE/NEUTRAL sound is heard below Y=0 (V2)."))
                            .defaultValue(35.0)
                            .min(1.0)
                            .sliderMax(200.0)
                            .build()
            );
    private final Setting<Integer> soundYThreshold = this.sgSignals
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("sound-y-threshold"))
                            .description("Only record sounds originating below this Y level."))
                            .defaultValue(0))
                            .min(-64)
                            .sliderMin(-64)
                            .sliderMax(0)
                            .build()
            );
    private final Setting<Double> itemDropBonus = this.sgSignals
            .add(
                    ((Builder)((Builder)new Builder().name("item-drop-bonus"))
                            .description("Score bonus when an item entity spawns below Y=0 (block-breaking activity, V3)."))
                            .defaultValue(15.0)
                            .min(1.0)
                            .sliderMax(100.0)
                            .build()
            );
    private final Setting<Double> playerBonus = this.sgSignals
            .add(
                    ((Builder)((Builder)new Builder().name("player-y-bonus")).description("Score bonus when a player entity is detected at Y<0 (V5)."))
                            .defaultValue(50.0)
                            .min(1.0)
                            .sliderMax(200.0)
                            .build()
            );
    private final Setting<Double> lightBonusPerBlock = this.sgLight
            .add(
                    ((Builder)((Builder)new Builder().name("light-bonus-per-block"))
                            .description("Score bonus per artificially lit enclosed air block found below Y=0 (V4)."))
                            .defaultValue(6.0)
                            .min(0.5)
                            .sliderMax(50.0)
                            .build()
            );
    private final Setting<Integer> lightScanInterval = this.sgLight
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("light-scan-interval-ticks"))
                            .description("Ticks between light forensic passes."))
                            .defaultValue(200))
                            .min(40)
                            .sliderMax(600)
                            .build()
            );
    private final Setting<Integer> lightMinLevel = this.sgLight
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("light-min-level"))
                            .description("Minimum block-light level to count as artificial (1-15)."))
                            .defaultValue(1))
                            .min(1)
                            .sliderMax(15)
                            .build()
            );
    private final Setting<SettingColor> plateColorBase = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("plate-color-base"))
                            .description("Plate fill for a general multi-signal base detection."))
                            .defaultValue(new SettingColor(0, 255, 100, 40))
                            .build()
            );
    private final Setting<SettingColor> plateColorSpawner = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("plate-color-spawner"))
                            .description("Plate fill when a Spawner block is directly confirmed."))
                            .defaultValue(new SettingColor(180, 0, 255, 60))
                            .build()
            );
    private final Setting<SettingColor> plateColorPlayer = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("plate-color-player"))
                            .description("Plate fill when an underground player is detected."))
                            .defaultValue(new SettingColor(0, 150, 255, 55))
                            .build()
            );
    private final Setting<SettingColor> outlineColor = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("outline-color"))
                            .description("Outline color for all detection plates."))
                            .defaultValue(new SettingColor(0, 255, 120, 220))
                            .build()
            );
    private final Setting<SettingColor> spawnerEspFill = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("spawner-esp-fill"))
                            .description("Fill color for individual Spawner ESP boxes."))
                            .defaultValue(new SettingColor(255, 215, 0, 50))
                            .build()
            );
    private final Setting<SettingColor> spawnerEspLine = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("spawner-esp-line"))
                            .description("Outline color for individual Spawner ESP boxes."))
                            .defaultValue(new SettingColor(255, 215, 0, 255))
                            .build()
            );
    private final Setting<Boolean> showTracers = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("show-tracers"))
                            .description("Draw tracer lines from camera to confirmed chunk centres."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<SettingColor> tracerColor = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("tracer-color"))
                            .description("Color for tracer lines."))
                            .defaultValue(new SettingColor(0, 255, 120, 150))
                            .build()
            );
    private final Setting<Boolean> showPillar = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("show-pillar"))
                            .description("Render a tall red pillar over each confirmed chunk, visible from far away."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Integer> pillarHeight = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                            .name("pillar-height"))
                            .description("Height of the red alert pillar in blocks."))
                            .defaultValue(100))
                            .min(16)
                            .sliderMax(320)
                            .build()
            );
    private final Setting<SettingColor> pillarFillColor = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("pillar-fill-color"))
                            .description("Fill color of the alert pillar."))
                            .defaultValue(new SettingColor(255, 30, 30, 25))
                            .build()
            );
    private final Setting<SettingColor> pillarLineColor = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("pillar-line-color"))
                            .description("Outline color of the alert pillar."))
                            .defaultValue(new SettingColor(255, 30, 30, 200))
                            .build()
            );
    private int blockScanTick = 0;
    private int lightScanTick = 0;

    public ArchonRadar() {
        super(
                DonutAddon.CATEGORY,
                "archon-radar",
                "DonutSMP multi-vector passive intelligence radar. Detects Spawners and player bases below Y=0 from high altitude (Y=150+) with 5 independent bypass signals."
        );
    }

    public void onActivate() {
        this.radar.clear();
        this.blockScanTick = 0;
        this.lightScanTick = 0;
        ChatUtils.info("§a[ArchonRadar] §7Activated. All 5 detection vectors online.", new Object[0]);
    }

    public void onDeactivate() {
        this.radar.clear();
    }

    @EventHandler
    private void onTick(Post event) {
        if (MC.world != null && MC.player != null) {
            if (++this.blockScanTick >= (Integer)this.scanInterval.get()) {
                this.blockScanTick = 0;
                this.runV1BlockForensics();
                this.runV5PlayerDesync();
            }

            if (++this.lightScanTick >= (Integer)this.lightScanInterval.get()) {
                this.lightScanTick = 0;
                this.runV4LightForensics();
            }
        }
    }

    private void runV1BlockForensics() {
        int pCx = MC.player.getChunkPos().x;
        int pCz = MC.player.getChunkPos().z;
        int rad = (Integer)this.scanRadius.get();
        int bottomY = MC.world.getBottomY();
        int yCeil = (Integer)this.scanYCeiling.get();
        int cap = (Integer)this.maxBlocksPerSection.get();

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                WorldChunk chunk = MC.world.getChunk(pCx + dx, pCz + dz);
                if (chunk != null) {
                    ChunkPos cp = chunk.getPos();
                    ChunkSection[] sections = chunk.getSectionArray();
                    double chunkScore = 0.0;
                    List<BlockPos> newSpawners = new ArrayList<>();

                    for (int i = 0; i < sections.length; i++) {
                        if (sections[i] != null && !sections[i].isEmpty()) {
                            int secBaseY = bottomY + i * 16;
                            if (secBaseY + 16 <= yCeil) {
                                boolean sectionHasTarget = sections[i]
                                        .getBlockStateContainer()
                                        .hasAny(s -> s.getBlock() == Blocks.SPAWNER || this.isSuspiciousBlock(s.getBlock()));
                                if (sectionHasTarget) {
                                    int spawners = 0;
                                    int other = 0;

                                    for (int bx = 0; bx < 16; bx++) {
                                        for (int by = 0; by < 16; by++) {
                                            for (int bz = 0; bz < 16; bz++) {
                                                Block b = sections[i].getBlockState(bx, by, bz).getBlock();
                                                if (b == Blocks.SPAWNER) {
                                                    if (spawners < cap) {
                                                        spawners++;
                                                        chunkScore += this.weightSpawner.get();
                                                        newSpawners.add(new BlockPos(cp.getStartX() + bx, secBaseY + by, cp.getStartZ() + bz));
                                                    }
                                                } else if (this.isSuspiciousBlock(b) && other < cap) {
                                                    other++;
                                                    chunkScore += this.weightSuspicious.get();
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (chunkScore > 0.0) {
                        ArchonRadar.RadarData data = this.radar.computeIfAbsent(cp, ArchonRadar.RadarData::new);
                        data.addFlag(ArchonRadar.RadarFlag.BLOCK_SCAN, chunkScore);
                        data.surfaceY = this.computeSurfaceY(chunk);
                        if (!newSpawners.isEmpty()) {
                            data.confirmedSpawners.addAll(newSpawners);
                            if (data.labelType < 1) {
                                data.labelType = 1;
                            }
                        }

                        if ((Boolean)this.debugScores.get()) {
                            ChatUtils.info(
                                    "[AR] §eV1-Block§r chunk " + cp.x + "," + cp.z + " +" + String.format("%.1f", chunkScore), new Object[0]
                            );
                        }

                        this.tryConfirm(cp, data);
                    }
                }
            }
        }
    }

    private boolean isSuspiciousBlock(Block b) {
        return b == Blocks.OBSIDIAN
                || b == Blocks.CRYING_OBSIDIAN
                || b == Blocks.END_STONE
                || b == Blocks.END_STONE_BRICKS
                || b == Blocks.CHEST
                || b == Blocks.TRAPPED_CHEST
                || b == Blocks.BARREL
                || b == Blocks.AMETHYST_BLOCK
                || b == Blocks.AMETHYST_CLUSTER
                || b == Blocks.BUDDING_AMETHYST
                || b == Blocks.LARGE_AMETHYST_BUD
                || b == Blocks.MEDIUM_AMETHYST_BUD
                || b == Blocks.HOPPER
                || b == Blocks.DROPPER
                || b == Blocks.DISPENSER
                || b == Blocks.BEACON
                || b == Blocks.ENCHANTING_TABLE
                || b == Blocks.ANVIL;
    }

    private void runV4LightForensics() {
        int pCx = MC.player.getChunkPos().x;
        int pCz = MC.player.getChunkPos().z;
        int rad = (Integer)this.scanRadius.get();
        int bottomY = MC.world.getBottomY();
        int yCeil = (Integer)this.scanYCeiling.get();
        int minLvl = (Integer)this.lightMinLevel.get();

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                WorldChunk chunk = MC.world.getChunk(pCx + dx, pCz + dz);
                if (chunk != null) {
                    ChunkPos cp = chunk.getPos();
                    ChunkSection[] sections = chunk.getSectionArray();
                    int litCount = 0;

                    for (int i = 0; i < sections.length; i++) {
                        if (sections[i] != null && !sections[i].isEmpty()) {
                            int secBaseY = bottomY + i * 16;
                            if (secBaseY + 16 <= yCeil) {
                                for (int bx = 1; bx < 15; bx++) {
                                    for (int by = 1; by < 15; by++) {
                                        for (int bz = 1; bz < 15; bz++) {
                                            if (sections[i].getBlockState(bx, by, bz).isAir()) {
                                                BlockPos bp = new BlockPos(cp.getStartX() + bx, secBaseY + by, cp.getStartZ() + bz);
                                                int light = MC.world.getLightLevel(LightType.BLOCK, bp);
                                                if (light >= minLvl && this.areNeighborsSolid(bp)) {
                                                    litCount++;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (litCount > 0) {
                        double bonus = (double)litCount * (Double)this.lightBonusPerBlock.get();
                        ArchonRadar.RadarData data = this.radar.computeIfAbsent(cp, ArchonRadar.RadarData::new);
                        data.addFlag(ArchonRadar.RadarFlag.LIGHT, bonus);
                        data.surfaceY = this.computeSurfaceY(chunk);
                        if ((Boolean)this.debugScores.get()) {
                            ChatUtils.info(
                                    "[AR] §bV4-Light§r chunk " + cp.x + "," + cp.z + " lit=" + litCount + " +" + String.format("%.1f", bonus),
                                    new Object[0]
                            );
                        }

                        this.tryConfirm(cp, data);
                    }
                }
            }
        }
    }

    private boolean areNeighborsSolid(BlockPos pos) {
        if (MC.world == null) {
            return false;
        } else {
            BlockPos n = pos.north();
            BlockPos s = pos.south();
            BlockPos e = pos.east();
            BlockPos w = pos.west();
            BlockPos u = pos.up();
            BlockPos d = pos.down();
            return MC.world.getBlockState(n).isSolidBlock(MC.world, n)
                    && MC.world.getBlockState(s).isSolidBlock(MC.world, s)
                    && MC.world.getBlockState(e).isSolidBlock(MC.world, e)
                    && MC.world.getBlockState(w).isSolidBlock(MC.world, w)
                    && MC.world.getBlockState(u).isSolidBlock(MC.world, u)
                    && MC.world.getBlockState(d).isSolidBlock(MC.world, d);
        }
    }

    private void runV5PlayerDesync() {
        if (MC.world != null && MC.player != null) {
            for (AbstractClientPlayerEntity entity : MC.world.getPlayers()) {
                if (!entity.getUuid().equals(MC.player.getUuid()) && !(entity.getY() >= 0.0)) {
                    ChunkPos cp = entity.getChunkPos();
                    ArchonRadar.RadarData data = this.radar.computeIfAbsent(cp, ArchonRadar.RadarData::new);
                    data.addFlag(ArchonRadar.RadarFlag.PLAYER_SEEN, (Double)this.playerBonus.get());
                    if (data.labelType < 2) {
                        data.labelType = 2;
                    }

                    if ((Boolean)this.debugScores.get()) {
                        ChatUtils.info(
                                "[AR] §9V5-Player§r "
                                        + entity.getName().getString()
                                        + " Y="
                                        + String.format("%.1f", entity.getY())
                                        + " chunk "
                                        + cp.x
                                        + ","
                                        + cp.z,
                                new Object[0]
                        );
                    }

                    this.tryConfirm(cp, data);
                }
            }
        }
    }

    @EventHandler
    private void onPacket(Receive event) {
        if (MC.world != null && MC.player != null) {
            if (event.packet instanceof PlaySoundS2CPacket pkt) {
                SoundCategory cat = pkt.getCategory();
                if (cat != SoundCategory.HOSTILE && cat != SoundCategory.NEUTRAL) {
                    return;
                }

                double sy = pkt.getY();
                if (sy >= (double)((Integer)this.soundYThreshold.get()).intValue()) {
                    return;
                }

                double sx = pkt.getX();
                double sz = pkt.getZ();
                ChunkPos cp = new ChunkPos(BlockPos.ofFloored(sx, sy, sz));
                ArchonRadar.RadarData data = this.radar.computeIfAbsent(cp, ArchonRadar.RadarData::new);
                data.addFlag(ArchonRadar.RadarFlag.SOUND, (Double)this.soundBonus.get());
                WorldChunk chunk = MC.world.getChunk(cp.x, cp.z);
                if (chunk != null) {
                    data.surfaceY = this.computeSurfaceY(chunk);
                }

                if ((Boolean)this.debugScores.get()) {
                    ChatUtils.info(
                            "[AR] §cV2-Sound§r "
                                    + cat.getName()
                                    + " chunk "
                                    + cp.x
                                    + ","
                                    + cp.z
                                    + " Y="
                                    + String.format("%.1f", sy)
                                    + " +"
                                    + String.format("%.0f", this.soundBonus.get()),
                            new Object[0]
                    );
                }

                this.tryConfirm(cp, data);
            }

            if (event.packet instanceof EntitySpawnS2CPacket pkt) {
                if (pkt.getEntityType() != EntityType.ITEM) {
                    return;
                }

                double ey = pkt.getY();
                if (ey >= 0.0) {
                    return;
                }

                double ex = pkt.getX();
                double ez = pkt.getZ();
                ChunkPos cpx = new ChunkPos(BlockPos.ofFloored(ex, ey, ez));
                ArchonRadar.RadarData datax = this.radar.computeIfAbsent(cpx, ArchonRadar.RadarData::new);
                datax.addFlag(ArchonRadar.RadarFlag.ITEM_DROP, (Double)this.itemDropBonus.get());
                WorldChunk chunkx = MC.world.getChunk(cpx.x, cpx.z);
                if (chunkx != null) {
                    datax.surfaceY = this.computeSurfaceY(chunkx);
                }

                if ((Boolean)this.debugScores.get()) {
                    ChatUtils.info(
                            "[AR] §6V3-Item§r drop Y="
                                    + String.format("%.1f", ey)
                                    + " chunk "
                                    + cpx.x
                                    + ","
                                    + cpx.z
                                    + " +"
                                    + String.format("%.0f", this.itemDropBonus.get()),
                            new Object[0]
                    );
                }

                this.tryConfirm(cpx, datax);
            }
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (MC.world != null && MC.player != null) {
            if (event.entity instanceof AbstractClientPlayerEntity player) {
                if (!player.getUuid().equals(MC.player.getUuid()) && !(player.getY() >= 0.0)) {
                    ChunkPos cp = player.getChunkPos();
                    ArchonRadar.RadarData data = this.radar.computeIfAbsent(cp, ArchonRadar.RadarData::new);
                    data.addFlag(ArchonRadar.RadarFlag.PLAYER_SEEN, (Double)this.playerBonus.get());
                    if (data.labelType < 2) {
                        data.labelType = 2;
                    }

                    if ((Boolean)this.debugScores.get()) {
                        ChatUtils.info(
                                "[AR] §9V5-EntityAdded§r player "
                                        + player.getName().getString()
                                        + " Y="
                                        + String.format("%.1f", player.getY())
                                        + " chunk "
                                        + cp.x
                                        + ","
                                        + cp.z,
                                new Object[0]
                        );
                    }

                    this.tryConfirm(cp, data);
                }
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (MC.player != null && MC.world != null) {
            Vec3d eye = MC.player.getCameraPosVec(event.tickDelta);

            for (Entry<ChunkPos, ArchonRadar.RadarData> entry : this.radar.entrySet()) {
                ArchonRadar.RadarData data = entry.getValue();
                if (data.confirmed) {
                    ChunkPos cp = entry.getKey();
                    double plateY = (double)data.surfaceY + 0.05;
                    double x0 = (double)cp.getStartX();
                    double z0 = (double)cp.getStartZ();
                    double x1 = x0 + 16.0;
                    double z1 = z0 + 16.0;

                    SettingColor fill = switch (data.labelType) {
                        case 1 -> (SettingColor)this.plateColorSpawner.get();
                        case 2 -> (SettingColor)this.plateColorPlayer.get();
                        default -> (SettingColor)this.plateColorBase.get();
                    };
                    SettingColor ol = (SettingColor)this.outlineColor.get();
                    event.renderer.box(x0, plateY - 0.05, z0, x1, plateY + 0.15, z1, fill, ol, ShapeMode.Both, 0);
                    if ((Boolean)this.showTracers.get()) {
                        double cx = (double)cp.getCenterX();
                        double cz = (double)cp.getCenterZ();
                        event.renderer.line(eye.x, eye.y, eye.z, cx, plateY, cz, (Color)this.tracerColor.get());
                    }

                    if ((Boolean)this.showPillar.get()) {
                        int h = (Integer)this.pillarHeight.get();
                        event.renderer.box(
                                x0, plateY, z0, x1, plateY + (double)h, z1,
                                (Color)this.pillarFillColor.get(),
                                (Color)this.pillarLineColor.get(),
                                ShapeMode.Both, 0
                        );
                    }

                    if (!data.confirmedSpawners.isEmpty()) {
                        SettingColor espFill = (SettingColor)this.spawnerEspFill.get();
                        SettingColor espLine = (SettingColor)this.spawnerEspLine.get();

                        for (BlockPos sp : data.confirmedSpawners) {
                            event.renderer.box(
                                    (double)sp.getX(), (double)sp.getY(), (double)sp.getZ(),
                                    (double)(sp.getX() + 1), (double)(sp.getY() + 1), (double)(sp.getZ() + 1),
                                    espFill, espLine, ShapeMode.Both, 0
                            );
                        }
                    }
                }
            }
        }
    }

    private void tryConfirm(ChunkPos cp, ArchonRadar.RadarData data) {
        if (!data.confirmed) {
            boolean scoreOk = data.score >= (Double)this.confirmThreshold.get();
            boolean flagsOk = !(Boolean)this.requireMultiFlag.get() || data.flags.size() >= 2;
            if (scoreOk && flagsOk) {
                data.confirmed = true;
                if ((Boolean)this.chatNotify.get()) {
                    String type = switch (data.labelType) {
                        case 1 -> "§5SPAWNER";
                        case 2 -> "§9PLAYER";
                        default -> "§aBASE";
                    };
                    ChatUtils.info(
                            "[ArchonRadar] "
                                    + type
                                    + "§r confirmed chunk "
                                    + cp.x
                                    + ","
                                    + cp.z
                                    + " §7score=§f"
                                    + String.format("%.0f", data.score)
                                    + " §7flags=§f"
                                    + data.flags.size(),
                            new Object[0]
                    );
                }
            }
        }
    }

    private int computeSurfaceY(WorldChunk chunk) {
        if (MC.world == null) {
            return 64;
        } else {
            int highest = MC.world.getBottomY();

            for (int bx = 0; bx < 16; bx++) {
                for (int bz = 0; bz < 16; bz++) {
                    int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, bx, bz);
                    if (y > highest) {
                        highest = y;
                    }
                }
            }

            return highest;
        }
    }

    private static class RadarData {
        final ChunkPos cp;
        final Set<ArchonRadar.RadarFlag> flags = ConcurrentHashMap.newKeySet();
        final Set<BlockPos> confirmedSpawners = ConcurrentHashMap.newKeySet();
        double score = 0.0;
        boolean confirmed = false;
        int surfaceY = 64;
        int labelType = 0;

        RadarData(ChunkPos cp) {
            this.cp = cp;
        }

        void addFlag(ArchonRadar.RadarFlag flag, double bonus) {
            this.flags.add(flag);
            this.score += bonus;
        }
    }

    private static enum RadarFlag {
        BLOCK_SCAN,
        SOUND,
        ITEM_DROP,
        LIGHT,
        PLAYER_SEEN;
    }
}