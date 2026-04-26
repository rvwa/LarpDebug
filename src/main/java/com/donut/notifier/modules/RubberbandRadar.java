package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

public class RubberbandRadar extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Integer> undergroundY = sgGeneral.add(
            new IntSetting.Builder()
                    .name("underground-y")
                    .description("Y level below which a position is considered underground.")
                    .defaultValue(0)
                    .range(-64, 32)
                    .sliderRange(-64, 32)
                    .build()
    );
    private final Setting<Integer> cooldownSeconds = sgGeneral.add(
            new IntSetting.Builder()
                    .name("cooldown-seconds")
                    .description("Seconds before the same player UUID can update a detection again.")
                    .defaultValue(10)
                    .range(5, 120)
                    .sliderMax(60)
                    .build()
    );
    private final Setting<Integer> confirmHits = sgGeneral.add(
            new IntSetting.Builder()
                    .name("confirm-hits")
                    .description("Number of separate cooldown-gated observations to mark a chunk CONFIRMED.")
                    .defaultValue(2)
                    .range(1, 20)
                    .sliderMax(10)
                    .build()
    );
    private final Setting<Integer> decayMinutes = sgGeneral.add(
            new IntSetting.Builder()
                    .name("decay-minutes")
                    .description("Minutes before an idle detection is discarded.")
                    .defaultValue(30)
                    .range(1, 60)
                    .sliderMax(30)
                    .build()
    );
    private final Setting<Boolean> chatNotify = sgGeneral.add(
            new BoolSetting.Builder()
                    .name("chat-notifications")
                    .description("Print to chat on new detections and confirmations.")
                    .defaultValue(true)
                    .build()
    );
    private final Setting<Boolean> debugMode = sgGeneral.add(
            new BoolSetting.Builder()
                    .name("debug-mode")
                    .description("Verbose chat output on every detected underground position.")
                    .defaultValue(false)
                    .build()
    );

    // Render settings
    private final Setting<Boolean> renderEnabled = sgRender.add(
            new BoolSetting.Builder()
                    .name("render")
                    .description("Render chunk plates and tracers.")
                    .defaultValue(true)
                    .build()
    );
    private final Setting<SettingColor> fillNew = sgRender.add(
            new ColorSetting.Builder()
                    .name("fill-new")
                    .description("Plate fill — new detection (1 hit).")
                    .defaultValue(new SettingColor(0, 100, 255, 25))
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<SettingColor> lineNew = sgRender.add(
            new ColorSetting.Builder()
                    .name("line-new")
                    .description("Plate outline — new detection.")
                    .defaultValue(new SettingColor(0, 120, 255, 160))
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<SettingColor> fillWarm = sgRender.add(
            new ColorSetting.Builder()
                    .name("fill-warm")
                    .description("Plate fill — warm detection (2+ hits, not confirmed).")
                    .defaultValue(new SettingColor(255, 140, 0, 35))
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<SettingColor> lineWarm = sgRender.add(
            new ColorSetting.Builder()
                    .name("line-warm")
                    .description("Plate outline — warm detection.")
                    .defaultValue(new SettingColor(255, 140, 0, 180))
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<SettingColor> fillConfirmed = sgRender.add(
            new ColorSetting.Builder()
                    .name("fill-confirmed")
                    .description("Plate fill — confirmed base chunk.")
                    .defaultValue(new SettingColor(255, 30, 30, 50))
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<SettingColor> lineConfirmed = sgRender.add(
            new ColorSetting.Builder()
                    .name("line-confirmed")
                    .description("Plate outline — confirmed base chunk.")
                    .defaultValue(new SettingColor(255, 30, 30, 220))
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<Boolean> showTracers = sgRender.add(
            new BoolSetting.Builder()
                    .name("show-tracers")
                    .description("Draw tracer lines to each detected chunk.")
                    .defaultValue(true)
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<SettingColor> tracerColor = sgRender.add(
            new ColorSetting.Builder()
                    .name("tracer-color")
                    .defaultValue(new SettingColor(255, 50, 50, 160))
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<Boolean> showPillar = sgRender.add(
            new BoolSetting.Builder()
                    .name("show-pillar")
                    .description("Tall pillar over CONFIRMED chunks.")
                    .defaultValue(true)
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<Integer> pillarHeight = sgRender.add(
            new IntSetting.Builder()
                    .name("pillar-height")
                    .description("Pillar height in blocks.")
                    .defaultValue(160)
                    .min(16)
                    .sliderMax(256)
                    .visible(showPillar::get)
                    .build()
    );

    private final ConcurrentHashMap<ChunkPos, Detection> detections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private static final UUID SELF_UUID = new UUID(0L, 0L);
    private int tickCounter = 0;

    public RubberbandRadar() {
        super(
                DonutAddon.CATEGORY,
                "rubberband-radar",
                "Detects players at Y<0 via entity scan and our own position corrections. No chunk data required — Goliath-immune."
        );
    }

    @Override
    public void onActivate() {
        detections.clear();
        cooldowns.clear();
        tickCounter = 0;
        ChatUtils.info("[RubberbandRadar] Active. Monitoring for underground players…");
    }

    @Override
    public void onDeactivate() {
        detections.clear();
        cooldowns.clear();
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            if (mc.player == null || mc.world == null) return;
            double corrY = mc.player.getY();
            if (corrY >= undergroundY.get()) return;
            ChunkPos cp = mc.player.getChunkPos();
            if (debugMode.get()) {
                ChatUtils.info("[RBR] SELF corrected to Y=" + String.format("%.1f", corrY) + " at chunk " + cp.x + "," + cp.z);
            }
            record(cp, SELF_UUID, null);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        tickCounter++;
        if (tickCounter % 20 != 0) return;

        for (AbstractClientPlayerEntity entity : mc.world.getPlayers()) {
            if (entity.getUuid().equals(mc.player.getUuid())) continue;
            if (entity.getY() >= undergroundY.get()) continue;
            ChunkPos cp = entity.getChunkPos();
            if (debugMode.get()) {
                ChatUtils.info("[RBR] PLAYER " + entity.getName().getString() + " at Y=" + String.format("%.1f", entity.getY()) + " chunk " + cp.x + "," + cp.z);
            }
            record(cp, entity.getUuid(), entity.getName().getString());
        }

        if (tickCounter % 100 == 0) {
            long cutoff = System.currentTimeMillis() - (long) decayMinutes.get() * 60_000L;
            detections.entrySet().removeIf(e -> e.getValue().lastMs < cutoff);
            long cooldownMs = (long) cooldownSeconds.get() * 1000L;
            cooldowns.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis() - cooldownMs * 2L);
        }
    }

    private void record(ChunkPos cp, UUID uuid, String name) {
        long now = System.currentTimeMillis();
        long cooldownMs = (long) cooldownSeconds.get() * 1000L;
        Long lastHit = cooldowns.get(uuid);
        if (lastHit != null && now - lastHit < cooldownMs) return;

        cooldowns.put(uuid, now);
        Detection existing = detections.get(cp);
        if (existing == null) {
            int surfaceY = getSurfaceY(cp);
            Detection d = new Detection(cp, uuid.equals(SELF_UUID) ? null : uuid, name, surfaceY);
            detections.put(cp, d);
            if (chatNotify.get()) {
                String src = name != null ? "PLAYER " + name : "SELF correction";
                ChatUtils.info("[RBR] New underground detection at chunk " + cp.x + "," + cp.z + " (" + src + ")");
            }
        } else {
            boolean wasConfirmed = existing.confirmed;
            existing.addHit(uuid.equals(SELF_UUID) ? null : uuid, name);
            existing.confirmed = existing.hits >= confirmHits.get();
            if (!wasConfirmed && existing.confirmed && chatNotify.get()) {
                ChatUtils.info("[RBR] BASE CONFIRMED chunk " + cp.x + "," + cp.z + " player=" + existing.label() + " hits=" + existing.hits);
            }
        }
    }

    private int getSurfaceY(ChunkPos cp) {
        if (mc.world == null) return 64;
        WorldChunk chunk = mc.world.getChunk(cp.x, cp.z);
        if (chunk == null) return 64;

        int highest = mc.world.getBottomY();
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, bx, bz);
                if (y > highest) highest = y;
            }
        }
        return highest;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderEnabled.get() || mc.player == null) return;
        Vec3d eye = mc.player.getCameraPosVec(event.tickDelta);

        for (Detection d : detections.values()) {
            double plateY = d.surfaceY + 0.05;
            double x0 = d.cp.getStartX();
            double z0 = d.cp.getStartZ();
            double x1 = x0 + 16.0;
            double z1 = z0 + 16.0;
            double cx = d.cp.getCenterX();
            double cz = d.cp.getCenterZ();

            SettingColor fill, line;
            if (d.confirmed) {
                fill = fillConfirmed.get();
                line = lineConfirmed.get();
            } else if (d.hits >= 2) {
                fill = fillWarm.get();
                line = lineWarm.get();
            } else {
                fill = fillNew.get();
                line = lineNew.get();
            }

            event.renderer.box(x0, plateY, z0, x1, plateY + 0.2, z1, fill, line, ShapeMode.Both, 0);

            if (d.confirmed && showPillar.get()) {
                int h = pillarHeight.get();
                event.renderer.box(x0, plateY + 0.2, z0, x1, plateY + h, z1, fill, line, ShapeMode.Both, 0);
            }

            if (showTracers.get()) {
                event.renderer.line(eye.x, eye.y, eye.z, cx, plateY + 0.1, cz, (Color) tracerColor.get());
            }
        }
    }

    private static class Detection {
        final ChunkPos cp;
        UUID playerUuid;
        String playerName;
        int hits;
        long lastMs;
        int surfaceY;
        boolean confirmed;

        Detection(ChunkPos cp, UUID uuid, String name, int surfaceY) {
            this.cp = cp;
            this.playerUuid = uuid;
            this.playerName = name;
            this.hits = 1;
            this.lastMs = System.currentTimeMillis();
            this.surfaceY = surfaceY;
            this.confirmed = false;
        }

        void addHit(UUID uuid, String name) {
            this.hits++;
            this.lastMs = System.currentTimeMillis();
            if (uuid != null && this.playerUuid == null) {
                this.playerUuid = uuid;
                this.playerName = name;
            }
        }

        String label() {
            return playerName != null ? playerName : "SELF";
        }
    }
}