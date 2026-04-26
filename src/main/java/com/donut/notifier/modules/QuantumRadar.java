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
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.block.entity.EnchantingTableBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public class QuantumRadar extends Module {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private final ConcurrentHashMap<ChunkPos, QData> data = new ConcurrentHashMap<>();

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgQ1 = this.settings.createGroup("Q1 Block-Entity Scan");
    private final SettingGroup sgQ2 = this.settings.createGroup("Q2 Sound Triangulation");
    private final SettingGroup sgQ3 = this.settings.createGroup("Q3 Light Forensics");
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Double> confirmThreshold = this.sgGeneral
            .add(new DoubleSetting.Builder()
                    .name("confirm-threshold")
                    .description("Minimum suspicion score to flag a chunk as confirmed.")
                    .defaultValue(60.0)
                    .min(10.0)
                    .sliderMax(400.0)
                    .build());

    private final Setting<Boolean> requireMultiFlag = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("require-multi-signal")
                    .description("Chunk must have signals from 2+ independent vectors to confirm.")
                    .defaultValue(true)
                    .build());

    private final Setting<Double> playerBonus = this.sgGeneral
            .add(new DoubleSetting.Builder()
                    .name("player-bonus")
                    .description("Score added when a live player is found at Y<0 (Q5).")
                    .defaultValue(55.0)
                    .min(1.0)
                    .sliderMax(200.0)
                    .build());

    private final Setting<Double> itemDropBonus = this.sgGeneral
            .add(new DoubleSetting.Builder()
                    .name("item-drop-bonus")
                    .description("Score added when an item entity spawns below Y=0 (Q4).")
                    .defaultValue(18.0)
                    .min(1.0)
                    .sliderMax(100.0)
                    .build());

    private final Setting<Boolean> chatNotify = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("chat-notifications")
                    .description("Print to chat when a chunk is first confirmed.")
                    .defaultValue(true)
                    .build());

    private final Setting<Boolean> debugMode = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("debug-mode")
                    .description("Print every score increment to chat for calibration.")
                    .defaultValue(false)
                    .build());

    private final Setting<Boolean> q1Enabled = this.sgQ1
            .add(new BoolSetting.Builder()
                    .name("enabled")
                    .description("Enable client-side block-entity graph scanning (Q1).")
                    .defaultValue(true)
                    .build());

    private final Setting<Integer> scanRadius = this.sgQ1
            .add(new IntSetting.Builder()
                    .name("scan-radius")
                    .description("Chunk radius to scan for block entities below Y=0.")
                    .defaultValue(8)
                    .min(1)
                    .sliderMax(16)
                    .build());

    private final Setting<Integer> scanInterval = this.sgQ1
            .add(new IntSetting.Builder()
                    .name("scan-interval-ticks")
                    .description("Ticks between Q1 scan passes.")
                    .defaultValue(60)
                    .min(10)
                    .sliderMax(400)
                    .build());

    private final Setting<Integer> scanYCeiling = this.sgQ1
            .add(new IntSetting.Builder()
                    .name("scan-y-ceiling")
                    .description("Only count block entities strictly below this Y level.")
                    .defaultValue(0)
                    .min(-64)
                    .sliderMin(-64)
                    .sliderMax(16)
                    .build());

    private final Setting<Double> weightSpawner = this.sgQ1
            .add(new DoubleSetting.Builder()
                    .name("weight-spawner-be")
                    .description("Score per MobSpawnerBlockEntity found below Y ceiling.")
                    .defaultValue(80.0)
                    .min(1.0)
                    .sliderMax(300.0)
                    .build());

    private final Setting<Double> weightOtherBE = this.sgQ1
            .add(new DoubleSetting.Builder()
                    .name("weight-other-be")
                    .description("Score per other notable block entity (Chest, Hopper, Beacon …).")
                    .defaultValue(5.0)
                    .min(0.5)
                    .sliderMax(50.0)
                    .build());

    private final Setting<Boolean> q2Enabled = this.sgQ2
            .add(new BoolSetting.Builder()
                    .name("enabled")
                    .description("Enable sound triangulation (Q2).")
                    .defaultValue(true)
                    .build());

    private final Setting<Double> soundBonus = this.sgQ2
            .add(new DoubleSetting.Builder()
                    .name("sound-bonus")
                    .description("Score per HOSTILE/NEUTRAL sound packet below the Y threshold.")
                    .defaultValue(38.0)
                    .min(1.0)
                    .sliderMax(200.0)
                    .build());

    private final Setting<Integer> soundYThreshold = this.sgQ2
            .add(new IntSetting.Builder()
                    .name("sound-y-threshold")
                    .description("Only register sounds originating below this Y level.")
                    .defaultValue(0)
                    .min(-64)
                    .sliderMin(-64)
                    .sliderMax(0)
                    .build());

    private final Setting<Boolean> q3Enabled = this.sgQ3
            .add(new BoolSetting.Builder()
                    .name("enabled")
                    .description("Enable light propagation forensics (Q3).")
                    .defaultValue(true)
                    .build());

    private final Setting<Double> lightBonus = this.sgQ3
            .add(new DoubleSetting.Builder()
                    .name("light-bonus-per-block")
                    .description("Score per artificially lit enclosed air block found below Y=0.")
                    .defaultValue(6.0)
                    .min(0.5)
                    .sliderMax(50.0)
                    .build());

    private final Setting<Integer> lightScanInterval = this.sgQ3
            .add(new IntSetting.Builder()
                    .name("light-scan-interval-ticks")
                    .description("Ticks between Q3 light scan passes.")
                    .defaultValue(220)
                    .min(40)
                    .sliderMax(600)
                    .build());

    private final Setting<Integer> lightMinLevel = this.sgQ3
            .add(new IntSetting.Builder()
                    .name("light-min-level")
                    .description("Minimum block-light to count as artificial (1–15).")
                    .defaultValue(1)
                    .min(1)
                    .sliderMax(15)
                    .build());

    private final Setting<SettingColor> plateColorBase = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("plate-base-color")
                    .description("Plate fill for a multi-signal base detection.")
                    .defaultValue(new SettingColor(0, 255, 100, 40))
                    .build());

    private final Setting<SettingColor> plateColorSpawner = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("plate-spawner-color")
                    .description("Plate fill when a Spawner block entity is directly confirmed.")
                    .defaultValue(new SettingColor(180, 0, 255, 55))
                    .build());

    private final Setting<SettingColor> plateColorPlayer = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("plate-player-color")
                    .description("Plate fill when a live underground player is detected.")
                    .defaultValue(new SettingColor(0, 150, 255, 55))
                    .build());

    private final Setting<SettingColor> plateOutline = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("plate-outline-color")
                    .description("Outline color for all detection plates.")
                    .defaultValue(new SettingColor(0, 255, 120, 220))
                    .build());

    private final Setting<Boolean> showPillar = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-pillar")
                    .description("Render a tall alert pillar over each confirmed chunk.")
                    .defaultValue(true)
                    .build());

    private final Setting<Integer> pillarHeight = this.sgRender
            .add(new IntSetting.Builder()
                    .name("pillar-height")
                    .description("Height of the alert pillar in blocks.")
                    .defaultValue(120)
                    .min(16)
                    .sliderMax(320)
                    .build());

    private final Setting<SettingColor> pillarFill = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("pillar-fill-color")
                    .description("Fill color of the alert pillar.")
                    .defaultValue(new SettingColor(255, 0, 0, 20))
                    .build());

    private final Setting<SettingColor> pillarLine = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("pillar-line-color")
                    .description("Outline color of the alert pillar.")
                    .defaultValue(new SettingColor(255, 0, 0, 200))
                    .build());

    private final Setting<SettingColor> spawnerEspFill = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("spawner-esp-fill")
                    .description("Fill color for per-spawner ESP boxes.")
                    .defaultValue(new SettingColor(255, 215, 0, 40))
                    .build());

    private final Setting<SettingColor> spawnerEspLine = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("spawner-esp-line")
                    .description("Outline color for per-spawner ESP boxes.")
                    .defaultValue(new SettingColor(255, 215, 0, 255))
                    .build());

    private final Setting<Boolean> showTracers = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-tracers")
                    .description("Draw tracer lines from camera to confirmed chunks.")
                    .defaultValue(true)
                    .build());

    private final Setting<SettingColor> tracerColor = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("tracer-color")
                    .description("Color for tracer lines.")
                    .defaultValue(new SettingColor(255, 50, 50, 160))
                    .build());

    private int q1Tick = 0;
    private int q3Tick = 0;

    public QuantumRadar() {
        super(
                DonutAddon.CATEGORY,
                "quantum-radar",
                "Client-rendering-forensics base detector. Reads block entities from the client's own WorldChunk object graph — data the server cannot un-send after loading."
        );
    }

    @Override
    public void onActivate() {
        this.data.clear();
        this.q1Tick = 0;
        this.q3Tick = 0;
        ChatUtils.info("§b[QuantumRadar] §7Online. Reading client chunk graph…");
    }

    @Override
    public void onDeactivate() {
        this.data.clear();
    }

    @EventHandler
    private void onTick(Post event) {
        if (MC.world == null || MC.player == null) return;

        if (q1Enabled.get() && ++this.q1Tick >= scanInterval.get()) {
            this.q1Tick = 0;
            this.runQ1BlockEntityScan();
            this.runQ5PlayerScan();
        }

        if (q3Enabled.get() && ++this.q3Tick >= lightScanInterval.get()) {
            this.q3Tick = 0;
            this.runQ3LightForensics();
        }
    }

    private void runQ1BlockEntityScan() {
        int pCx = MC.player.getChunkPos().x;
        int pCz = MC.player.getChunkPos().z;
        int rad = scanRadius.get();
        int yCeil = scanYCeiling.get();

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                WorldChunk chunk = MC.world.getChunk(pCx + dx, pCz + dz);
                if (chunk == null) continue;

                ChunkPos cp = chunk.getPos();
                double chunkPts = 0.0;
                List<BlockPos> spawnerPositions = new ArrayList<>();

                for (Entry<BlockPos, BlockEntity> be : chunk.getBlockEntities().entrySet()) {
                    BlockPos bp = be.getKey();
                    if (bp.getY() >= yCeil) continue;
                    BlockEntity entity = be.getValue();
                    if (entity instanceof MobSpawnerBlockEntity) {
                        chunkPts += weightSpawner.get();
                        spawnerPositions.add(bp);
                    } else if (entity instanceof ChestBlockEntity
                            || entity instanceof TrappedChestBlockEntity
                            || entity instanceof BarrelBlockEntity
                            || entity instanceof HopperBlockEntity
                            || entity instanceof DispenserBlockEntity
                            || entity instanceof DropperBlockEntity
                            || entity instanceof BeaconBlockEntity
                            || entity instanceof EnchantingTableBlockEntity) {
                        chunkPts += weightOtherBE.get();
                    }
                }

                if (chunkPts > 0.0) {
                    QData d = this.data.computeIfAbsent(cp, QData::new);
                    d.add(QFlag.BLOCK_ENTITY, chunkPts);
                    d.surfaceY = this.computeSurfaceY(chunk);
                    if (!spawnerPositions.isEmpty()) {
                        d.confirmedSpawners.addAll(spawnerPositions);
                        if (d.labelType < 1) d.labelType = 1;
                    }
                    if (debugMode.get()) {
                        ChatUtils.info("[QR] §eQ1§r chunk " + cp.x + "," + cp.z
                                + " BE+" + String.format("%.0f", chunkPts)
                                + " spawners=" + spawnerPositions.size());
                    }
                    this.tryConfirm(cp, d);
                }
            }
        }
    }

    private void runQ3LightForensics() {
        int pCx = MC.player.getChunkPos().x;
        int pCz = MC.player.getChunkPos().z;
        int rad = scanRadius.get();
        int yCeil = scanYCeiling.get();
        int minLvl = lightMinLevel.get();
        int bottomY = MC.world.getBottomY();

        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                WorldChunk chunk = MC.world.getChunk(pCx + dx, pCz + dz);
                if (chunk == null) continue;

                ChunkPos cp = chunk.getPos();
                ChunkSection[] sections = chunk.getSectionArray();
                int lit = 0;

                for (int i = 0; i < sections.length; i++) {
                    if (sections[i] == null || sections[i].isEmpty()) continue;
                    int secBase = bottomY + i * 16;
                    if (secBase + 16 > yCeil) continue;

                    for (int bx = 1; bx < 15; bx++) {
                        for (int by = 1; by < 15; by++) {
                            for (int bz = 1; bz < 15; bz++) {
                                if (sections[i].getBlockState(bx, by, bz).isAir()) {
                                    BlockPos bp = new BlockPos(cp.getStartX() + bx, secBase + by, cp.getStartZ() + bz);
                                    if (MC.world.getLightLevel(LightType.BLOCK, bp) >= minLvl && this.allNeighborsSolid(bp)) {
                                        lit++;
                                    }
                                }
                            }
                        }
                    }
                }

                if (lit > 0) {
                    double pts = lit * lightBonus.get();
                    QData d = this.data.computeIfAbsent(cp, QData::new);
                    d.add(QFlag.LIGHT, pts);
                    d.surfaceY = this.computeSurfaceY(chunk);
                    if (debugMode.get()) {
                        ChatUtils.info("[QR] §bQ3§r chunk " + cp.x + "," + cp.z
                                + " lit=" + lit + " +" + String.format("%.1f", pts));
                    }
                    this.tryConfirm(cp, d);
                }
            }
        }
    }

    private void runQ5PlayerScan() {
        if (MC.world == null || MC.player == null) return;
        for (AbstractClientPlayerEntity e : MC.world.getPlayers()) {
            if (!e.getUuid().equals(MC.player.getUuid()) && e.getY() < 0.0) {
                this.recordPlayer(e);
            }
        }
    }

    private void recordPlayer(AbstractClientPlayerEntity p) {
        ChunkPos cp = p.getChunkPos();
        QData d = this.data.computeIfAbsent(cp, QData::new);
        d.add(QFlag.PLAYER, playerBonus.get());
        if (d.labelType < 2) d.labelType = 2;
        if (debugMode.get()) {
            ChatUtils.info("[QR] §9Q5§r player " + p.getName().getString() + " Y=" + String.format("%.1f", p.getY()));
        }
        this.tryConfirm(cp, d);
    }

    @EventHandler
    private void onPacket(Receive event) {
        if (MC.world == null || MC.player == null) return;

        if (q2Enabled.get() && event.packet instanceof PlaySoundS2CPacket pkt) {
            SoundCategory cat = pkt.getCategory();
            if (cat != SoundCategory.HOSTILE && cat != SoundCategory.NEUTRAL) return;

            double sy = pkt.getY();
            if (sy >= soundYThreshold.get()) return;

            ChunkPos cp = new ChunkPos(BlockPos.ofFloored(pkt.getX(), sy, pkt.getZ()));
            QData d = this.data.computeIfAbsent(cp, QData::new);
            d.add(QFlag.SOUND, soundBonus.get());
            WorldChunk chunk = MC.world.getChunk(cp.x, cp.z);
            if (chunk != null) d.surfaceY = this.computeSurfaceY(chunk);
            if (debugMode.get()) {
                ChatUtils.info("[QR] §cQ2§r sound " + cat.getName() + " Y=" + String.format("%.1f", sy)
                        + " +" + String.format("%.0f", soundBonus.get()));
            }
            this.tryConfirm(cp, d);
        }

        if (event.packet instanceof EntitySpawnS2CPacket pkt) {
            if (pkt.getEntityType() != EntityType.ITEM) return;
            double ey = pkt.getY();
            if (ey >= 0.0) return;

            ChunkPos cp = new ChunkPos(BlockPos.ofFloored(pkt.getX(), ey, pkt.getZ()));
            QData d = this.data.computeIfAbsent(cp, QData::new);
            d.add(QFlag.ITEM_DROP, itemDropBonus.get());
            WorldChunk chunk = MC.world.getChunk(cp.x, cp.z);
            if (chunk != null) d.surfaceY = this.computeSurfaceY(chunk);
            if (debugMode.get()) {
                ChatUtils.info("[QR] §6Q4§r item drop Y=" + String.format("%.1f", ey)
                        + " +" + String.format("%.0f", itemDropBonus.get()));
            }
            this.tryConfirm(cp, d);
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (MC.world == null || MC.player == null) return;
        if (event.entity instanceof AbstractClientPlayerEntity p) {
            if (!p.getUuid().equals(MC.player.getUuid()) && p.getY() < 0.0) {
                this.recordPlayer(p);
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (MC.player == null || MC.world == null) return;

        Vec3d eye = MC.player.getCameraPosVec(event.tickDelta);

        for (Entry<ChunkPos, QData> entry : this.data.entrySet()) {
            QData d = entry.getValue();
            if (!d.confirmed) continue;

            ChunkPos cp = entry.getKey();
            double plateY = d.surfaceY + 0.05;
            double x0 = cp.getStartX();
            double z0 = cp.getStartZ();
            double x1 = x0 + 16.0;
            double z1 = z0 + 16.0;

            SettingColor fill = switch (d.labelType) {
                case 1 -> plateColorSpawner.get();
                case 2 -> plateColorPlayer.get();
                default -> plateColorBase.get();
            };
            event.renderer.box(x0, plateY - 0.05, z0, x1, plateY + 0.15, z1, fill, (Color) plateOutline.get(), ShapeMode.Both, 0);

            if (showPillar.get()) {
                int h = pillarHeight.get();
                event.renderer.box(x0, plateY, z0, x1, plateY + h, z1,
                        (Color) pillarFill.get(), (Color) pillarLine.get(), ShapeMode.Both, 0);
            }

            if (showTracers.get()) {
                event.renderer.line(eye.x, eye.y, eye.z, cp.getCenterX(), plateY, cp.getCenterZ(), (Color) tracerColor.get());
            }

            if (!d.confirmedSpawners.isEmpty()) {
                SettingColor ef = spawnerEspFill.get();
                SettingColor el = spawnerEspLine.get();
                for (BlockPos sp : d.confirmedSpawners) {
                    event.renderer.box(
                            sp.getX(), sp.getY(), sp.getZ(),
                            sp.getX() + 1, sp.getY() + 1, sp.getZ() + 1,
                            ef, el, ShapeMode.Both, 0
                    );
                }
            }
        }
    }

    private void tryConfirm(ChunkPos cp, QData d) {
        if (d.confirmed) return;
        boolean scoreOk = d.score >= confirmThreshold.get();
        boolean flagsOk = !requireMultiFlag.get() || d.flags.size() >= 2;
        if (scoreOk && flagsOk) {
            d.confirmed = true;
            if (chatNotify.get()) {
                String type = switch (d.labelType) {
                    case 1 -> "§5SPAWNER";
                    case 2 -> "§9PLAYER";
                    default -> "§aBASE";
                };
                ChatUtils.info("[QuantumRadar] " + type + "§r confirmed chunk "
                        + cp.x + "," + cp.z
                        + " §7score=§f" + String.format("%.0f", d.score)
                        + " §7signals=§f" + d.flags.size());
            }
        }
    }

    private boolean allNeighborsSolid(BlockPos pos) {
        if (MC.world == null) return false;
        BlockPos n = pos.north(), s = pos.south(), e = pos.east(), w = pos.west(), u = pos.up(), dn = pos.down();
        return MC.world.getBlockState(n).isSolidBlock(MC.world, n)
                && MC.world.getBlockState(s).isSolidBlock(MC.world, s)
                && MC.world.getBlockState(e).isSolidBlock(MC.world, e)
                && MC.world.getBlockState(w).isSolidBlock(MC.world, w)
                && MC.world.getBlockState(u).isSolidBlock(MC.world, u)
                && MC.world.getBlockState(dn).isSolidBlock(MC.world, dn);
    }

    private int computeSurfaceY(WorldChunk chunk) {
        if (MC.world == null) return 64;
        int high = MC.world.getBottomY();
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, bx, bz);
                if (y > high) high = y;
            }
        }
        return high;
    }

    private static class QData {
        final ChunkPos cp;
        final Set<QFlag> flags = ConcurrentHashMap.newKeySet();
        final Set<BlockPos> confirmedSpawners = ConcurrentHashMap.newKeySet();
        double score = 0.0;
        boolean confirmed = false;
        int surfaceY = 64;
        int labelType = 0;

        QData(ChunkPos cp) {
            this.cp = cp;
        }

        void add(QFlag flag, double pts) {
            this.flags.add(flag);
            this.score += pts;
        }
    }

    private enum QFlag {
        BLOCK_ENTITY,
        SOUND,
        LIGHT,
        ITEM_DROP,
        PLAYER
    }
}