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
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

public class SpawnerHistory extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Integer> historyDays = sgGeneral.add(
            new IntSetting.Builder()
                    .name("history-days")
                    .description("How many days of spawner history to keep and display.")
                    .defaultValue(2)
                    .range(1, 30)
                    .sliderMax(30)
                    .build()
    );
    private final Setting<Boolean> autoSave = sgGeneral.add(
            new BoolSetting.Builder()
                    .name("auto-save")
                    .description("Automatically save history to file.")
                    .defaultValue(true)
                    .build()
    );
    private final Setting<Boolean> chatLog = sgGeneral.add(
            new BoolSetting.Builder()
                    .name("chat-log")
                    .description("Print to chat when a new spawner location is logged.")
                    .defaultValue(true)
                    .build()
    );
    private final Setting<Boolean> renderEnabled = sgRender.add(
            new BoolSetting.Builder()
                    .name("render")
                    .description("Render a plate over chunks where spawners have been opened.")
                    .defaultValue(true)
                    .build()
    );
    private final Setting<ShapeMode> shapeMode = sgRender.add(
            new EnumSetting.Builder<ShapeMode>()
                    .name("shape-mode")
                    .description("How the chunk highlight is drawn.")
                    .defaultValue(ShapeMode.Both)
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<SettingColor> sideColor = sgRender.add(
            new ColorSetting.Builder()
                    .name("side-color")
                    .description("Fill color of the chunk highlight.")
                    .defaultValue(new SettingColor(0, 255, 0, 25))
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<SettingColor> lineColor = sgRender.add(
            new ColorSetting.Builder()
                    .name("line-color")
                    .description("Outline color of the chunk highlight.")
                    .defaultValue(new SettingColor(0, 255, 0, 125))
                    .visible(renderEnabled::get)
                    .build()
    );
    private final Setting<Double> renderY = sgRender.add(
            new DoubleSetting.Builder()
                    .name("render-y")
                    .description("Y level to render the plate at.")
                    .defaultValue(64.0)
                    .sliderRange(-64.0, 320.0)
                    .visible(renderEnabled::get)
                    .build()
    );

    private final Map<ChunkPos, Instant> spawnerChunks = new ConcurrentHashMap<>();
    private final Path savePath = Paths.get("meteor-client", "spawner_history.txt");
    private Instant lastCleanup = Instant.now();

    public SpawnerHistory() {
        super(DonutAddon.CATEGORY, "spawner-history", "Logs and displays chunks where spawner screens have been opened.");
    }

    @Override
    public void onActivate() {
        loadHistory();
        cleanupOldEntries();
        info("SpawnerHistory loaded: " + spawnerChunks.size() + " chunk(s).");
    }

    @Override
    public void onDeactivate() {
        if (autoSave.get()) saveHistory();
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof OpenScreenS2CPacket packet)) return;
        if (mc.player == null) return;

        ScreenHandlerType<?> screenHandlerType = packet.getScreenHandlerType();
        if (screenHandlerType == null) return;

        Identifier typeId = Registries.SCREEN_HANDLER.getId(screenHandlerType);
        if (typeId == null || !typeId.getPath().contains("spawner")) return;

        ChunkPos cp = mc.player.getChunkPos();
        Instant existing = spawnerChunks.put(cp, Instant.now());
        if (existing == null) {
            if (chatLog.get()) info("Spawner logged at chunk " + cp.x + ", " + cp.z);
            if (autoSave.get()) saveHistory();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderEnabled.get()) return;

        if (Duration.between(lastCleanup, Instant.now()).toHours() >= 1L) {
            cleanupOldEntries();
            lastCleanup = Instant.now();
        }

        double y = renderY.get();
        for (Entry<ChunkPos, Instant> entry : spawnerChunks.entrySet()) {
            ChunkPos cp = entry.getKey();
            if (Duration.between(entry.getValue(), Instant.now()).toDays() < historyDays.get()) {
                double x0 = cp.getStartX();
                double z0 = cp.getStartZ();
                event.renderer.box(
                        x0, y, z0,
                        x0 + 16.0, y + 0.1, z0 + 16.0,
                        (Color) sideColor.get(),
                        (Color) lineColor.get(),
                        shapeMode.get(),
                        0
                );
            }
        }
    }

    public void sendInfo() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(historyDays.get()));
        int count = 0;
        info("Spawner chunks (last " + historyDays.get() + " days):");
        for (Entry<ChunkPos, Instant> entry : spawnerChunks.entrySet()) {
            if (!entry.getValue().isBefore(cutoff)) {
                ChunkPos cp = entry.getKey();
                info("  Chunk [" + cp.x + ", " + cp.z + "]");
                count++;
            }
        }
        info("Total: " + count);
    }

    private void loadHistory() {
        if (!Files.exists(savePath)) return;
        spawnerChunks.clear();
        try (BufferedReader reader = Files.newBufferedReader(savePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int z = Integer.parseInt(parts[1].trim());
                        Instant ts = Instant.parse(parts[2].trim());
                        spawnerChunks.put(new ChunkPos(x, z), ts);
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException e) {
            error("Failed to load spawner history: " + e.getMessage());
        }
    }

    private void saveHistory() {
        try {
            Files.createDirectories(savePath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(savePath)) {
                for (Entry<ChunkPos, Instant> entry : spawnerChunks.entrySet()) {
                    ChunkPos cp = entry.getKey();
                    writer.write(cp.x + "," + cp.z + "," + entry.getValue());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            error("Failed to save spawner history: " + e.getMessage());
        }
    }

    private void cleanupOldEntries() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(historyDays.get()));
        spawnerChunks.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        if (autoSave.get()) saveHistory();
    }
}