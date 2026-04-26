package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;

public class SpawnerSoundRadar extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgFilter = this.settings.createGroup("Sound Filter");
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Integer> confirmHits = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("confirm-hits")
                    .description("Number of sound packets from the same location to mark as CONFIRMED.")
                    .defaultValue(2)
                    .range(1, 20)
                    .sliderMax(10)
                    .build());

    private final Setting<Double> clusterRadius = this.sgGeneral
            .add(new DoubleSetting.Builder()
                    .name("cluster-radius")
                    .description("Max distance (blocks) between two sounds to be treated as the same spawner.")
                    .defaultValue(8.0)
                    .min(1.0)
                    .sliderMax(20.0)
                    .build());

    private final Setting<Integer> decaySeconds = this.sgGeneral
            .add(new IntSetting.Builder()
                    .name("decay-seconds")
                    .description("Seconds of silence before a detection is discarded.")
                    .defaultValue(600)
                    .range(30, 1800)
                    .sliderMax(600)
                    .build());

    private final Setting<Boolean> chatNotify = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("chat-notifications")
                    .description("Print to chat when a spawner is first confirmed.")
                    .defaultValue(true)
                    .build());

    private final Setting<Boolean> debugAll = this.sgGeneral
            .add(new BoolSetting.Builder()
                    .name("debug-all-hits")
                    .description("Print every matched sound packet to chat (verbose, for tuning).")
                    .defaultValue(false)
                    .build());

    private final Setting<Boolean> broadMatch = this.sgFilter
            .add(new BoolSetting.Builder()
                    .name("broad-match")
                    .description("Match any sound ID containing 'spawner' (recommended). Disable to use exact-ID whitelist only.")
                    .defaultValue(true)
                    .build());

    private final Setting<String> exactId = this.sgFilter
            .add(new StringSetting.Builder()
                    .name("exact-sound-id")
                    .description("Exact sound ID to match when broad-match is off. Example: minecraft:entity.mob_spawner.ambient")
                    .defaultValue("minecraft:entity.mob_spawner.ambient")
                    .visible(() -> !broadMatch.get())
                    .build());

    private final Setting<Double> maxRange = this.sgFilter
            .add(new DoubleSetting.Builder()
                    .name("max-range-blocks")
                    .description("Only record sounds within this many blocks of you (server sends up to 64).")
                    .defaultValue(80.0)
                    .min(8.0)
                    .sliderMax(128.0)
                    .build());

    private final Setting<Boolean> renderEnabled = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("render")
                    .description("Render detected spawner positions.")
                    .defaultValue(true)
                    .build());

    private final Setting<SettingColor> fillUnconfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("fill-unconfirmed")
                    .description("Box fill color for unconfirmed hits.")
                    .defaultValue(new SettingColor(180, 0, 255, 30))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> lineUnconfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("line-unconfirmed")
                    .description("Box outline for unconfirmed hits.")
                    .defaultValue(new SettingColor(180, 0, 255, 160))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> fillConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("fill-confirmed")
                    .description("Box fill color for confirmed spawners.")
                    .defaultValue(new SettingColor(255, 60, 60, 50))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> lineConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("line-confirmed")
                    .description("Box outline for confirmed spawners.")
                    .defaultValue(new SettingColor(255, 60, 60, 220))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<Boolean> showTracers = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-tracers")
                    .description("Draw tracer lines from camera to each detection.")
                    .defaultValue(true)
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> tracerUnconfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("tracer-unconfirmed")
                    .defaultValue(new SettingColor(180, 0, 255, 120))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<SettingColor> tracerConfirmed = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("tracer-confirmed")
                    .defaultValue(new SettingColor(255, 60, 60, 180))
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<Boolean> showPillar = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-pillar")
                    .description("Tall pillar over confirmed spawner locations.")
                    .defaultValue(true)
                    .visible(this.renderEnabled::get)
                    .build());

    private final Setting<Integer> pillarHeight = this.sgRender
            .add(new IntSetting.Builder()
                    .name("pillar-height")
                    .description("Pillar height in blocks.")
                    .defaultValue(160)
                    .min(16)
                    .sliderMax(320)
                    .visible(this.showPillar::get)
                    .build());

    private final List<Detection> detections = Collections.synchronizedList(new ArrayList<>());
    private int tickCounter = 0;

    public SpawnerSoundRadar() {
        super(
                DonutAddon.CATEGORY,
                "spawner-sound-radar",
                "Triangulates spawner positions from PlaySoundS2CPacket. Works at any altitude — bypasses Goliath chunk masking entirely."
        );
    }

    @Override
    public void onActivate() {
        this.detections.clear();
        this.tickCounter = 0;
        ChatUtils.info("§d[SpawnerSoundRadar] §7Active — listening for spawner sounds…");
    }

    @Override
    public void onDeactivate() {
        this.detections.clear();
    }

    @EventHandler
    private void onPacket(Receive event) {
        if (!(event.packet instanceof PlaySoundS2CPacket pkt)) return;
        if (this.mc.player == null) return;

        SoundEvent sound = pkt.getSound().value();
        String key = sound.getId().toString();

        boolean matched = broadMatch.get() ? key.contains("spawner") : key.equals(exactId.get().trim());
        if (!matched) return;

        double sx = pkt.getX();
        double sy = pkt.getY();
        double sz = pkt.getZ();
        Vec3d soundPos = new Vec3d(sx, sy, sz);
        Vec3d playerPos = this.mc.player.getPos();
        double dist = playerPos.distanceTo(soundPos);
        if (dist > maxRange.get()) return;

        if (debugAll.get()) {
            ChatUtils.info("[SSR] §7Sound §f" + key + " §7@ " + fmt(sx) + "," + fmt(sy) + "," + fmt(sz) + " dist=" + fmt(dist));
        }

        double radius = clusterRadius.get();
        synchronized (this.detections) {
            Detection nearest = null;
            double minDist = Double.MAX_VALUE;
            for (Detection d : this.detections) {
                double dd = d.pos.distanceTo(soundPos);
                if (dd < radius && dd < minDist) {
                    minDist = dd;
                    nearest = d;
                }
            }

            if (nearest != null) {
                boolean wasConfirmed = nearest.confirmed;
                nearest.addHit();
                nearest.confirmed = nearest.hits >= confirmHits.get();
                if (!wasConfirmed && nearest.confirmed && chatNotify.get()) {
                    ChatUtils.info("[SSR] §c§lSPAWNER CONFIRMED§r at §f"
                            + fmt(nearest.pos.x) + ", " + fmt(nearest.pos.y) + ", " + fmt(nearest.pos.z)
                            + " §7(hits=" + nearest.hits + ")");
                }
            } else {
                this.detections.add(new Detection(soundPos));
                if (debugAll.get()) {
                    ChatUtils.info("[SSR] §dNew detection§r at §f" + fmt(sx) + "," + fmt(sy) + "," + fmt(sz));
                }
            }
        }
    }

    @EventHandler
    private void onTick(Post event) {
        if (++this.tickCounter % 100 == 0) {
            long cutoff = System.currentTimeMillis() - (long) decaySeconds.get() * 1000L;
            synchronized (this.detections) {
                this.detections.removeIf(d -> d.lastMs < cutoff);
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderEnabled.get() || this.mc.player == null) return;

        Vec3d eye = this.mc.player.getCameraPosVec(event.tickDelta);
        synchronized (this.detections) {
            for (Detection d : this.detections) {
                SettingColor fill = d.confirmed ? fillConfirmed.get() : fillUnconfirmed.get();
                SettingColor line = d.confirmed ? lineConfirmed.get() : lineUnconfirmed.get();
                SettingColor tc   = d.confirmed ? tracerConfirmed.get() : tracerUnconfirmed.get();
                double x0 = Math.floor(d.pos.x);
                double y0 = Math.floor(d.pos.y);
                double z0 = Math.floor(d.pos.z);
                event.renderer.box(x0, y0, z0, x0 + 1.0, y0 + 1.0, z0 + 1.0, fill, line, ShapeMode.Both, 0);
                if (d.confirmed && showPillar.get()) {
                    int h = pillarHeight.get();
                    event.renderer.box(x0, y0 + 1.0, z0, x0 + 1.0, y0 + h, z0 + 1.0, fill, line, ShapeMode.Both, 0);
                }
                if (showTracers.get()) {
                    event.renderer.line(eye.x, eye.y, eye.z, x0 + 0.5, y0 + 0.5, z0 + 0.5, tc);
                }
            }
        }
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    private static class Detection {
        final Vec3d pos;
        int hits;
        long firstMs;
        long lastMs;
        boolean confirmed;

        Detection(Vec3d pos) {
            this.pos = pos;
            this.hits = 1;
            this.firstMs = System.currentTimeMillis();
            this.lastMs = this.firstMs;
            this.confirmed = false;
        }

        void addHit() {
            this.hits++;
            this.lastMs = System.currentTimeMillis();
        }
    }
}