package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import net.minecraft.entity.SpawnGroup;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;

public class NovaIntelligenceRadar extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Integer> confirmThreshold = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("confirm-threshold")
                    .description("Score required (plus ≥2 different vectors) to mark a chunk CONFIRMED.")
                    .defaultValue(5)
                    .range(2, 40)
                    .sliderMax(20)
                    .build());

    private final Setting<Integer> soundBelowY = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("sound-below-y")
                    .description("Only score sounds below this Y level.")
                    .defaultValue(0)
                    .range(-64, 64)
                    .sliderRange(-64, 64)
                    .build());

    private final Setting<Double> soundClusterRadius = this.sgGeneral
            .add(new DoubleSetting.Builder()
                    .name("sound-cluster-radius")
                    .description("Blocks radius to cluster repeated sounds.")
                    .defaultValue(8.0)
                    .min(1.0)
                    .sliderMax(16.0)
                    .build());

    private final Setting<Integer> soundConfirmSecs = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("sound-confirm-window-secs")
                    .description("Window (seconds) in which 3 sound hits from same cluster earn a bonus.")
                    .defaultValue(30)
                    .range(5, 60)
                    .sliderMax(30)
                    .build());

    private final Setting<Double> entitySoundLinkRadius = this.sgGeneral
            .add(new DoubleSetting.Builder()
                    .name("entity-sound-link-radius")
                    .description("Blocks: entity spawn within this of a sound hit earns bonus score.")
                    .defaultValue(16.0)
                    .min(2.0)
                    .sliderMax(24.0)
                    .build());

    private final Setting<Integer> decaySeconds = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("decay-seconds")
                    .description("Idle seconds before a chunk record is dropped.")
                    .defaultValue(600)
                    .range(60, 1800)
                    .sliderMax(600)
                    .build());

    private final Setting<Boolean> chatNotify = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("chat-notifications")
                    .description("Print promotions to chat.")
                    .defaultValue(true)
                    .build());

    private final Setting<Boolean> renderEnabled = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("render")
                    .description("Render chunk plates.")
                    .defaultValue(true)
                    .build());

    private final Setting<SettingColor> fillCandidate = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("fill-candidate")
                    .defaultValue(new SettingColor(255, 140, 0, 30))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> lineCandidate = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("line-candidate")
                    .defaultValue(new SettingColor(255, 140, 0, 160))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> fillConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("fill-confirmed")
                    .defaultValue(new SettingColor(50, 255, 80, 50))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> lineConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("line-confirmed")
                    .defaultValue(new SettingColor(50, 255, 80, 220))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<Boolean> showTracers = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-tracers")
                    .defaultValue(true)
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> tracerColor = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("tracer-color")
                    .defaultValue(new SettingColor(80, 255, 80, 160))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<Boolean> showPillar = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-pillar")
                    .defaultValue(true)
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<Integer> pillarHeight = this.sgRender
            .add(new IntSetting.Builder()
                    .name("pillar-height")
                    .defaultValue(160)
                    .min(16)
                    .sliderMax(256)
                    .visible(this.showPillar::get)
                    .build());

    private final ConcurrentHashMap<ChunkPos, ChunkRecord> records = new ConcurrentHashMap<>();
    private final List<long[]> globalSoundHits = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<ChunkPos, Long> chunkSeen = new ConcurrentHashMap<>();
    private int tickCounter = 0;

    public NovaIntelligenceRadar() {
        super(DonutAddon.CATEGORY, "nova-intelligence-radar", "Multi-vector confidence scoring: sound + entity + rubberband + chunk anomaly.");
    }

    @Override
    public void onActivate() {
        this.records.clear();
        this.globalSoundHits.clear();
        this.chunkSeen.clear();
        this.tickCounter = 0;
    }

    @Override
    public void onDeactivate() {
        this.records.clear();
        this.globalSoundHits.clear();
    }

    @EventHandler
    private void onTick(Post event) {
        if (++this.tickCounter % 100 == 0) {
            long cutoff = System.currentTimeMillis() - (long) decaySeconds.get() * 1000L;
            this.records.entrySet().removeIf(e -> e.getValue().lastMs < cutoff);
            this.globalSoundHits.removeIf(h -> h[2] < cutoff);
        }
    }

    @EventHandler
    private void onPacket(Receive event) {
        if (this.mc.world == null || this.mc.player == null) return;

        if (event.packet instanceof PlaySoundS2CPacket pkt) {
            if (pkt.getY() < (double) soundBelowY.get()) {
                Vec3d soundPos = new Vec3d(pkt.getX(), pkt.getY(), pkt.getZ());
                ChunkPos cp = new ChunkPos(BlockPos.ofFloored(soundPos));
                ChunkRecord r = getOrCreate(cp);
                r.addScore(1, "SOUND");
                long now = System.currentTimeMillis();
                this.globalSoundHits.add(new long[]{
                        Double.doubleToLongBits(pkt.getX()),
                        Double.doubleToLongBits(pkt.getZ()),
                        now
                });
                int clusterHits = countRecentSoundCluster(soundPos, now);
                if (clusterHits >= 3) {
                    r.addScore(3, "SOUND_CLUSTER");
                    if (chatNotify.get() && !r.isConfirmed(confirmThreshold.get())) {
                        ChatUtils.info("[Nova] §eSound cluster§r chunk §f" + cp.x + "," + cp.z + " §7hits=" + clusterHits);
                    }
                }
                maybeConfirm(r);
            }
        } else if (event.packet instanceof EntitySpawnS2CPacket pkt) {
            if (pkt.getY() < 0.0 && pkt.getEntityType().getSpawnGroup() == SpawnGroup.MONSTER) {
                Vec3d pos = new Vec3d(pkt.getX(), pkt.getY(), pkt.getZ());
                ChunkPos cp = new ChunkPos(BlockPos.ofFloored(pos));
                ChunkRecord r = getOrCreate(cp);
                r.addScore(2, "ENTITY");
                if (isNearRecentSound(pos)) {
                    r.addScore(3, "ENTITY_SOUND_LINK");
                    if (chatNotify.get()) {
                        ChatUtils.info("[Nova] §dEntity+Sound link§r chunk §f" + cp.x + "," + cp.z);
                    }
                }
                maybeConfirm(r);
            }
        } else if (event.packet instanceof PlayerPositionLookS2CPacket) {
            if (this.mc.player.getY() < 0.0) {
                ChunkPos cp = this.mc.player.getChunkPos();
                ChunkRecord r = getOrCreate(cp);
                r.addScore(3, "RUBBERBAND");
                maybeConfirm(r);
            }
        } else if (event.packet instanceof ChunkDataS2CPacket pkt) {
            ChunkPos cp = new ChunkPos(pkt.getChunkX(), pkt.getChunkZ());
            Long prev = this.chunkSeen.put(cp, System.currentTimeMillis());
            if (prev != null && System.currentTimeMillis() - prev < 30000L) {
                ChunkRecord r = getOrCreate(cp);
                r.addScore(2, "CHUNK_ANOMALY");
                maybeConfirm(r);
            }
        }
    }

    private ChunkRecord getOrCreate(ChunkPos cp) {
        return this.records.computeIfAbsent(cp, ChunkRecord::new);
    }

    private void maybeConfirm(ChunkRecord r) {
        if (r.isConfirmed(confirmThreshold.get())
                && chatNotify.get()
                && r.score - r.vectors.size() < confirmThreshold.get()) {
            ChatUtils.info("[Nova] §a§lCONFIRMED§r chunk §f" + r.cp.x + "," + r.cp.z + " §7score=" + r.score + " vectors=" + r.vectors);
        }
    }

    private int countRecentSoundCluster(Vec3d pos, long now) {
        long windowMs = (long) soundConfirmSecs.get() * 1000L;
        double radius = soundClusterRadius.get();
        int count = 0;
        synchronized (this.globalSoundHits) {
            for (long[] h : this.globalSoundHits) {
                if (now - h[2] <= windowMs) {
                    double hx = Double.longBitsToDouble(h[0]);
                    double hz = Double.longBitsToDouble(h[1]);
                    double dist = Math.sqrt((hx - pos.x) * (hx - pos.x) + (hz - pos.z) * (hz - pos.z));
                    if (dist <= radius) count++;
                }
            }
        }
        return count;
    }

    private boolean isNearRecentSound(Vec3d pos) {
        long cutoff = System.currentTimeMillis() - 60000L;
        double radius = entitySoundLinkRadius.get();
        synchronized (this.globalSoundHits) {
            for (long[] h : this.globalSoundHits) {
                if (h[2] >= cutoff) {
                    double hx = Double.longBitsToDouble(h[0]);
                    double hz = Double.longBitsToDouble(h[1]);
                    double dist = Math.sqrt((hx - pos.x) * (hx - pos.x) + (hz - pos.z) * (hz - pos.z));
                    if (dist <= radius) return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderEnabled.get() || this.mc.player == null) return;

        Vec3d eye = this.mc.player.getCameraPosVec(event.tickDelta);
        int thresh = confirmThreshold.get();

        for (ChunkRecord r : this.records.values()) {
            boolean confirmed = r.isConfirmed(thresh);
            SettingColor fill = confirmed ? fillConfirmed.get() : fillCandidate.get();
            SettingColor line = confirmed ? lineConfirmed.get() : lineCandidate.get();
            double x0 = r.cp.getStartX();
            double z0 = r.cp.getStartZ();
            double x1 = x0 + 16.0;
            double z1 = z0 + 16.0;
            double cx = r.cp.getCenterX();
            double cz = r.cp.getCenterZ();
            double plateY = r.surfaceY + 0.05;
            event.renderer.box(x0, plateY, z0, x1, plateY + 0.2, z1, fill, line, ShapeMode.Both, 0);
            if (confirmed && showPillar.get()) {
                int h = pillarHeight.get();
                event.renderer.box(x0, plateY + 0.2, z0, x1, plateY + h, z1, fill, line, ShapeMode.Both, 0);
            }
            if (showTracers.get()) {
                event.renderer.line(eye.x, eye.y, eye.z, cx, plateY, cz, (Color) tracerColor.get());
            }
        }
    }

    private static class ChunkRecord {
        final ChunkPos cp;
        int score;
        final Set<String> vectors = new HashSet<>();
        long firstMs;
        long lastMs;
        int surfaceY = 64;
        final List<long[]> soundHits = new ArrayList<>();

        ChunkRecord(ChunkPos cp) {
            this.cp = cp;
            this.firstMs = System.currentTimeMillis();
            this.lastMs = this.firstMs;
        }

        void addScore(int delta, String vector) {
            this.score += delta;
            this.lastMs = System.currentTimeMillis();
            this.vectors.add(vector);
        }

        boolean isConfirmed(int threshold) {
            return this.score >= threshold && this.vectors.size() >= 2;
        }
    }
}