package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Receive;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Post;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.IntSetting.Builder;
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
import net.minecraft.world.Heightmap;

public class AmethystLodeFinder extends Module {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private final ConcurrentHashMap<ChunkPos, AmethystLodeFinder.ChunkData> chunkData = new ConcurrentHashMap<>();
    private final SettingGroup sgScanner = this.settings.createGroup("Block Scanner");
    private final SettingGroup sgSound = this.settings.createGroup("Sound Triangulation");
    private final SettingGroup sgLight = this.settings.createGroup("Light Forensics");
    private final SettingGroup sgMob = this.settings.createGroup("Mob Spawn");
    private final SettingGroup sgThreshold = this.settings.createGroup("Threshold");
    private final SettingGroup sgRender = this.settings.createGroup("Render");
    private final SettingGroup sgNotify = this.settings.getDefaultGroup();
    private final Setting<Integer> scanRadius = this.sgScanner
        .add(
            ((Builder)((Builder)((Builder)new Builder().name("scan-radius")).description("Chunk radius to scan for suspicious blocks.")).defaultValue(8))
                .min(1)
                .sliderMax(16)
                .build()
        );
    private final Setting<Integer> scanInterval = this.sgScanner
        .add(
            ((Builder)((Builder)((Builder)new Builder().name("scan-interval-ticks")).description("Ticks between each block scan pass.")).defaultValue(100))
                .min(20)
                .sliderMax(400)
                .build()
        );
    private final Setting<Double> weightSpawner = this.sgScanner
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("weight-spawner"))
                    .description("Score per Spawner block found below Y=0."))
                .defaultValue(50.0)
                .min(1.0)
                .sliderMax(200.0)
                .build()
        );
    private final Setting<Double> weightObsidian = this.sgScanner
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("weight-obsidian"))
                    .description("Score per Obsidian block found below Y=0."))
                .defaultValue(3.0)
                .min(0.5)
                .sliderMax(50.0)
                .build()
        );
    private final Setting<Double> weightEndStone = this.sgScanner
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("weight-end-stone"))
                    .description("Score per End Stone block found below Y=0."))
                .defaultValue(2.0)
                .min(0.5)
                .sliderMax(50.0)
                .build()
        );
    private final Setting<Double> weightAmethystCluster = this.sgScanner
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("weight-amethyst-cluster"))
                    .description("Score per Amethyst Cluster/Bud found below Y=0."))
                .defaultValue(8.0)
                .min(0.5)
                .sliderMax(100.0)
                .build()
        );
    private final Setting<Double> weightAmethystBlock = this.sgScanner
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("weight-amethyst-block"))
                    .description("Score per Amethyst Block found below Y=0."))
                .defaultValue(6.0)
                .min(0.5)
                .sliderMax(100.0)
                .build()
        );
    private final Setting<Integer> maxBlocksPerSection = this.sgScanner
        .add(
            ((Builder)((Builder)((Builder)new Builder().name("max-blocks-per-section"))
                        .description("Cap per block type per section to avoid natural cluster inflation."))
                    .defaultValue(16))
                .min(1)
                .sliderMax(64)
                .build()
        );
    private final Setting<Double> soundBonus = this.sgSound
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("sound-bonus"))
                    .description("Score bonus for a spawner/fire sound detected near a chunk."))
                .defaultValue(40.0)
                .min(1.0)
                .sliderMax(200.0)
                .build()
        );
    private final Setting<Double> soundAttrRadius = this.sgSound
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("sound-attribution-radius"))
                    .description("Block radius around sound origin to spread bonus to adjacent chunks."))
                .defaultValue(8.0)
                .min(2.0)
                .sliderMax(32.0)
                .build()
        );
    private final Setting<Double> lightBonus = this.sgLight
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("light-bonus"))
                    .description("Score bonus per artificially lit enclosed air block found."))
                .defaultValue(5.0)
                .min(0.5)
                .sliderMax(50.0)
                .build()
        );
    private final Setting<Integer> lightScanInterval = this.sgLight
        .add(
            ((Builder)((Builder)((Builder)new Builder().name("light-scan-interval-ticks")).description("Ticks between light forensic passes."))
                    .defaultValue(200))
                .min(40)
                .sliderMax(600)
                .build()
        );
    private final Setting<Integer> lightYMax = this.sgLight
        .add(
            ((Builder)((Builder)((Builder)new Builder().name("light-y-max")).description("Only scan for artificial light below this Y.")).defaultValue(0))
                .min(-64)
                .sliderMin(-64)
                .sliderMax(10)
                .build()
        );
    private final Setting<Integer> lightMinLevel = this.sgLight
        .add(
            ((Builder)((Builder)((Builder)new Builder().name("light-min-level")).description("Minimum block-light level to flag as artificial."))
                    .defaultValue(1))
                .min(1)
                .sliderMax(15)
                .build()
        );
    private final Setting<Double> mobBonus = this.sgMob
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("mob-spawn-bonus"))
                    .description("Score bonus when a hostile mob spawns below Y=0 near a scored chunk."))
                .defaultValue(20.0)
                .min(1.0)
                .sliderMax(100.0)
                .build()
        );
    private final Setting<Double> flagThreshold = this.sgThreshold
        .add(
            ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                        .name("flag-threshold"))
                    .description("Minimum suspicion score before a chunk is rendered as a Base."))
                .defaultValue(80.0)
                .min(10.0)
                .sliderMax(500.0)
                .build()
        );
    private final Setting<Boolean> requireSecondary = this.sgThreshold
        .add(
            ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("require-secondary-signal"))
                        .description("Chunk must have at least one secondary signal (sound/light/mob) to be rendered."))
                    .defaultValue(true))
                .build()
        );
    private final Setting<SettingColor> plateColor = this.sgRender
        .add(
            ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                        .name("plate-fill-color"))
                    .description("Fill color of the base detection plate."))
                .defaultValue(new SettingColor(0, 255, 100, 40))
                .build()
        );
    private final Setting<SettingColor> plateOutlineColor = this.sgRender
        .add(
            ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                        .name("plate-outline-color"))
                    .description("Outline color of the base detection plate."))
                .defaultValue(new SettingColor(0, 255, 120, 200))
                .build()
        );
    private final Setting<Boolean> showTracers = this.sgRender
        .add(
            ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("show-tracers"))
                        .description("Draw tracer lines from camera to flagged chunks."))
                    .defaultValue(true))
                .build()
        );
    private final Setting<SettingColor> tracerColor = this.sgRender
        .add(
            ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                        .name("tracer-color"))
                    .description("Color of tracer lines."))
                .defaultValue(new SettingColor(0, 255, 120, 180))
                .build()
        );
    private final Setting<Boolean> chatNotify = this.sgNotify
        .add(
            ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("chat-notifications"))
                        .description("Print to chat when a chunk is first flagged as a Base."))
                    .defaultValue(true))
                .build()
        );
    private final Setting<Boolean> debugMode = this.sgNotify
        .add(
            ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("debug-mode"))
                        .description("Print score increments to chat for tuning."))
                    .defaultValue(false))
                .build()
        );
    private int blockScanTick = 0;
    private int lightScanTick = 0;
    private static final Set<EntityType<?>> HOSTILE_MOBS = new HashSet<>(
        Arrays.asList(
            EntityType.ZOMBIE,
            EntityType.SKELETON,
            EntityType.SPIDER,
            EntityType.CAVE_SPIDER,
            EntityType.CREEPER,
            EntityType.WITHER_SKELETON,
            EntityType.BLAZE,
            EntityType.ENDERMAN,
            EntityType.HUSK,
            EntityType.STRAY,
            EntityType.DROWNED,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.PILLAGER,
            EntityType.VINDICATOR,
            EntityType.WITCH,
            EntityType.SILVERFISH,
            EntityType.SLIME,
            EntityType.MAGMA_CUBE
        )
    );

    public AmethystLodeFinder() {
        super(
            DonutAddon.CATEGORY,
            "amethyst-lode-finder",
            "DonutSMP multi-vector base detector. Combines block scan, sound, light & mob signals to locate hidden bases below Y=0."
        );
    }

    public void onActivate() {
        this.chunkData.clear();
        this.blockScanTick = 0;
        this.lightScanTick = 0;
        ChatUtils.info("§a[AmethystLodeFinder] §7Activated. Scanning for hidden bases...", new Object[0]);
    }

    public void onDeactivate() {
        this.chunkData.clear();
    }

    @EventHandler
    private void onTick(Post event) {
        if (MC.world != null && MC.player != null) {
            if (++this.blockScanTick >= (Integer)this.scanInterval.get()) {
                this.blockScanTick = 0;
                this.runBlockScan();
            }

            if (++this.lightScanTick >= (Integer)this.lightScanInterval.get()) {
                this.lightScanTick = 0;
                this.runLightForensics();
            }
        }
    }

    private void runBlockScan() {
        int pCx = MC.player.getChunkPos().x;
        int pCz = MC.player.getChunkPos().z;
        int rad = (Integer)this.scanRadius.get();
        int bottomY = MC.world.getBottomY();
        int cap = (Integer)this.maxBlocksPerSection.get();
        int yMax = (Integer)this.lightYMax.get();

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                WorldChunk chunk = MC.world.getChunk(pCx + dx, pCz + dz);
                if (chunk != null) {
                    ChunkPos cp = chunk.getPos();
                    ChunkSection[] sections = chunk.getSectionArray();
                    double chunkScore = 0.0;

                    for (int i = 0; i < sections.length; i++) {
                        if (sections[i] != null && !sections[i].isEmpty()) {
                            int secBaseY = bottomY + i * 16;
                            if (secBaseY + 16 <= yMax) {
                                int spawners = 0;
                                int obsidian = 0;
                                int endstone = 0;
                                int clusters = 0;
                                int ablocks = 0;

                                for (int bx = 0; bx < 16; bx++) {
                                    for (int by = 0; by < 16; by++) {
                                        for (int bz = 0; bz < 16; bz++) {
                                            Block b = sections[i].getBlockState(bx, by, bz).getBlock();
                                            if (b == Blocks.SPAWNER) {
                                                if (spawners < cap) {
                                                    spawners++;
                                                    chunkScore += this.weightSpawner.get();
                                                }
                                            } else if (b != Blocks.OBSIDIAN && b != Blocks.CRYING_OBSIDIAN) {
                                                if (b != Blocks.END_STONE && b != Blocks.END_STONE_BRICKS) {
                                                    if (b != Blocks.AMETHYST_CLUSTER
                                                        && b != Blocks.LARGE_AMETHYST_BUD
                                                        && b != Blocks.MEDIUM_AMETHYST_BUD
                                                        && b != Blocks.SMALL_AMETHYST_BUD
                                                        && b != Blocks.BUDDING_AMETHYST) {
                                                        if (b == Blocks.AMETHYST_BLOCK && ablocks < cap) {
                                                            ablocks++;
                                                            chunkScore += this.weightAmethystBlock.get();
                                                        }
                                                    } else if (clusters < cap) {
                                                        clusters++;
                                                        chunkScore += this.weightAmethystCluster.get();
                                                    }
                                                } else if (endstone < cap) {
                                                    endstone++;
                                                    chunkScore += this.weightEndStone.get();
                                                }
                                            } else if (obsidian < cap) {
                                                obsidian++;
                                                chunkScore += this.weightObsidian.get();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (chunkScore > 0.0) {
                        AmethystLodeFinder.ChunkData data = this.chunkData.computeIfAbsent(cp, k -> new AmethystLodeFinder.ChunkData());
                        data.score += chunkScore;
                        data.secondarySources.add(AmethystLodeFinder.SuspicionSource.BLOCK_SCAN);
                        data.surfaceY = this.computeSurfaceY(chunk);
                        if ((Boolean)this.debugMode.get()) {
                            ChatUtils.info(
                                "[ALF] §eScan§r chunk "
                                    + cp.x
                                    + ","
                                    + cp.z
                                    + " +"
                                    + String.format("%.1f", chunkScore)
                                    + " total="
                                    + String.format("%.1f", data.score),
                                new Object[0]
                            );
                        }

                        this.checkAndFlag(cp, data);
                    }
                }
            }
        }
    }

    private void runLightForensics() {
        int pCx = MC.player.getChunkPos().x;
        int pCz = MC.player.getChunkPos().z;
        int rad = (Integer)this.scanRadius.get();
        int bottomY = MC.world.getBottomY();
        int yMax = (Integer)this.lightYMax.get();
        int minLight = (Integer)this.lightMinLevel.get();

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                WorldChunk chunk = MC.world.getChunk(pCx + dx, pCz + dz);
                if (chunk != null) {
                    ChunkPos cp = chunk.getPos();
                    ChunkSection[] sections = chunk.getSectionArray();
                    int litEnclosed = 0;

                    for (int i = 0; i < sections.length; i++) {
                        if (sections[i] != null && !sections[i].isEmpty()) {
                            int secBaseY = bottomY + i * 16;
                            if (secBaseY + 16 <= yMax) {
                                for (int bx = 1; bx < 15; bx++) {
                                    for (int by = 1; by < 15; by++) {
                                        for (int bz = 1; bz < 15; bz++) {
                                            if (sections[i].getBlockState(bx, by, bz).isAir()) {
                                                int worldX = cp.getStartX() + bx;
                                                int worldY = secBaseY + by;
                                                int worldZ = cp.getStartZ() + bz;
                                                BlockPos bp = new BlockPos(worldX, worldY, worldZ);
                                                int light = MC.world.getLightLevel(LightType.BLOCK, bp);
                                                if (light >= minLight && this.areNeighborsSolid(bp)) {
                                                    litEnclosed++;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (litEnclosed > 0) {
                        double bonus = (double)litEnclosed * (Double)this.lightBonus.get();
                        AmethystLodeFinder.ChunkData data = this.chunkData.computeIfAbsent(cp, k -> new AmethystLodeFinder.ChunkData());
                        data.score += bonus;
                        data.secondarySources.add(AmethystLodeFinder.SuspicionSource.LIGHT);
                        data.surfaceY = this.computeSurfaceY(chunk);
                        if ((Boolean)this.debugMode.get()) {
                            ChatUtils.info(
                                "[ALF] §bLight§r chunk "
                                    + cp.x
                                    + ","
                                    + cp.z
                                    + " litBlocks="
                                    + litEnclosed
                                    + " +"
                                    + String.format("%.1f", bonus),
                                new Object[0]
                            );
                        }

                        this.checkAndFlag(cp, data);
                    }
                }
            }
        }
    }

    private boolean areNeighborsSolid(BlockPos pos) {
        return MC.world == null
            ? false
            : MC.world.getBlockState(pos.north()).isSolidBlock(MC.world, pos.north())
                && MC.world.getBlockState(pos.south()).isSolidBlock(MC.world, pos.south())
                && MC.world.getBlockState(pos.east()).isSolidBlock(MC.world, pos.east())
                && MC.world.getBlockState(pos.west()).isSolidBlock(MC.world, pos.west())
                && MC.world.getBlockState(pos.up()).isSolidBlock(MC.world, pos.up())
                && MC.world.getBlockState(pos.down()).isSolidBlock(MC.world, pos.down());
    }

    @EventHandler
    private void onPacket(Receive event) {
        if (MC.world != null && MC.player != null) {
            if (event.packet instanceof PlaySoundS2CPacket pkt) {
                SoundCategory cat = pkt.getCategory();
                if (cat != SoundCategory.HOSTILE && cat != SoundCategory.NEUTRAL) {
                    return;
                }

                double sx = pkt.getX();
                double sy = pkt.getY();
                double sz = pkt.getZ();
                if (sy >= 0.0) {
                    return;
                }

                ChunkPos cp = new ChunkPos(new BlockPos((int)sx, (int)sy, (int)sz));
                this.addSoundBonus(cp, sx, sz, cat.getName());
            }

            if (event.packet instanceof EntitySpawnS2CPacket pkt) {
                EntityType<?> type = pkt.getEntityType();
                if (!HOSTILE_MOBS.contains(type)) {
                    return;
                }

                double ey = pkt.getY();
                if (ey >= 0.0) {
                    return;
                }

                double ex = pkt.getX();
                double ez = pkt.getZ();
                ChunkPos cp = new ChunkPos(new BlockPos((int)ex, (int)ey, (int)ez));
                AmethystLodeFinder.ChunkData data = this.chunkData.get(cp);
                if (data != null && data.score > 0.0) {
                    double bonus = (Double)this.mobBonus.get();
                    data.score += bonus;
                    data.secondarySources.add(AmethystLodeFinder.SuspicionSource.MOB_SPAWN);
                    if ((Boolean)this.debugMode.get()) {
                        ChatUtils.info(
                            "[ALF] §5Mob§r "
                                + type.getName().getString()
                                + " chunk "
                                + cp.x
                                + ","
                                + cp.z
                                + " +"
                                + String.format("%.1f", bonus),
                            new Object[0]
                        );
                    }

                    this.checkAndFlag(cp, data);
                }
            }
        }
    }

    private void addSoundBonus(ChunkPos origin, double sx, double sz, String soundId) {
        double bonus = (Double)this.soundBonus.get();
        double attrR = (Double)this.soundAttrRadius.get();
        int chunkR = (int)Math.ceil(attrR / 16.0);
        AmethystLodeFinder.ChunkData data = this.chunkData.computeIfAbsent(origin, k -> new AmethystLodeFinder.ChunkData());
        data.score += bonus;
        data.secondarySources.add(AmethystLodeFinder.SuspicionSource.SOUND);
        if ((Boolean)this.debugMode.get()) {
            ChatUtils.info(
                "[ALF] §cSound§r " + soundId + " chunk " + origin.x + "," + origin.z + " +" + String.format("%.1f", bonus), new Object[0]
            );
        }

        this.checkAndFlag(origin, data);

        for (int ddx = -chunkR; ddx <= chunkR; ddx++) {
            for (int ddz = -chunkR; ddz <= chunkR; ddz++) {
                if (ddx != 0 || ddz != 0) {
                    ChunkPos adj = new ChunkPos(origin.x + ddx, origin.z + ddz);
                    double adjCx = (double)adj.getCenterX();
                    double adjCz = (double)adj.getCenterZ();
                    double dist = Math.sqrt((sx - adjCx) * (sx - adjCx) + (sz - adjCz) * (sz - adjCz));
                    if (dist <= attrR) {
                        double adjBonus = bonus * (1.0 - dist / attrR) * 0.5;
                        AmethystLodeFinder.ChunkData adjData = this.chunkData.computeIfAbsent(adj, k -> new AmethystLodeFinder.ChunkData());
                        adjData.score += adjBonus;
                        adjData.secondarySources.add(AmethystLodeFinder.SuspicionSource.SOUND);
                        this.checkAndFlag(adj, adjData);
                    }
                }
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (MC.player != null && MC.world != null) {
            Vec3d eye = MC.player.getCameraPosVec(event.tickDelta);
            Color fill = (Color)this.plateColor.get();
            Color outline = (Color)this.plateOutlineColor.get();
            Color tracer = (Color)this.tracerColor.get();

            for (Entry<ChunkPos, AmethystLodeFinder.ChunkData> entry : this.chunkData.entrySet()) {
                AmethystLodeFinder.ChunkData data = entry.getValue();
                if (data.flagged) {
                    ChunkPos cp = entry.getKey();
                    double plateY = (double)data.surfaceY + 0.05;
                    double x0 = (double)cp.getStartX();
                    double z0 = (double)cp.getStartZ();
                    double x1 = x0 + 16.0;
                    double z1 = z0 + 16.0;
                    event.renderer.box(x0, plateY - 0.05, z0, x1, plateY + 0.15, z1, fill, outline, ShapeMode.Both, 0);
                    if ((Boolean)this.showTracers.get()) {
                        double cx = (double)cp.getCenterX();
                        double cz = (double)cp.getCenterZ();
                        event.renderer.line(eye.x, eye.y, eye.z, cx, plateY, cz, tracer);
                    }
                }
            }
        }
    }

    private void checkAndFlag(ChunkPos cp, AmethystLodeFinder.ChunkData data) {
        if (!data.flagged) {
            boolean scoreOk = data.score >= (Double)this.flagThreshold.get();
            boolean secondaryOk = !(Boolean)this.requireSecondary.get() || !data.secondarySources.isEmpty();
            if (scoreOk && secondaryOk) {
                data.flagged = true;
                if ((Boolean)this.chatNotify.get()) {
                    ChatUtils.info(
                        "[ALF] §a§lBASE DETECTED§r chunk "
                            + cp.x
                            + ","
                            + cp.z
                            + " score="
                            + String.format("%.1f", data.score)
                            + " signals="
                            + data.secondarySources,
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
            int cx = chunk.getPos().getStartX();
            int cz = chunk.getPos().getStartZ();
            int highest = MC.world.getBottomY();

            for (int bx = 0; bx < 16; bx++) {
                for (int bz = 0; bz < 16; bz++) {
                    int colY = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, bx, bz);
                    if (colY > highest) {
                        highest = colY;
                    }
                }
            }

            return highest;
        }
    }

    private static class ChunkData {
        double score = 0.0;
        final Set<AmethystLodeFinder.SuspicionSource> secondarySources = ConcurrentHashMap.newKeySet();
        boolean flagged = false;
        int surfaceY = 64;
    }

    private static enum SuspicionSource {
        BLOCK_SCAN,
        SOUND,
        LIGHT,
        MOB_SPAWN;
    }
}
