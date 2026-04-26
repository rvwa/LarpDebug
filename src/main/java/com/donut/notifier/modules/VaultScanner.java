package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public class VaultScanner extends Module {
    private static final MinecraftClient MC = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgTargets = this.settings.createGroup("Target Blocks");
    private final SettingGroup sgRender  = this.settings.createGroup("Render");

    private final Setting<Integer> playerProximity = sgGeneral.add(new IntSetting.Builder()
            .name("player-proximity")
            .description("Max chunks from a real player to trigger the unmasking trust.")
            .defaultValue(8).min(1).sliderMax(16)
            .build());
    private final Setting<Boolean> useThreading = sgGeneral.add(new BoolSetting.Builder()
            .name("async-scanning")
            .description("Prevents lag spikes by scanning chunks on a background thread.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> findAmethyst = sgTargets.add(new BoolSetting.Builder()
            .name("amethyst").description("Log Budding Amethyst.").defaultValue(true).build());
    private final Setting<Boolean> findSpawners = sgTargets.add(new BoolSetting.Builder()
            .name("spawners").description("Log Monster Spawners.").defaultValue(true).build());
    private final Setting<Boolean> findChests = sgTargets.add(new BoolSetting.Builder()
            .name("chests").description("Log Chests, Trapped Chests, and Barrels.").defaultValue(true).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
            .name("render-esp").description("Render boxes around recorded blocks.").defaultValue(true).build());
    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
            .name("show-tracers").description("Draw tracer lines to the blocks.")
            .defaultValue(false).visible(render::get).build());
    private final Setting<SettingColor> amethystColor = sgRender.add(new ColorSetting.Builder()
            .name("amethyst-color").description("Color for Amethyst.")
            .defaultValue(new SettingColor(160, 32, 240, 100)).visible(findAmethyst::get).build());
    private final Setting<SettingColor> spawnerColor = sgRender.add(new ColorSetting.Builder()
            .name("spawner-color").description("Color for Spawners.")
            .defaultValue(new SettingColor(255, 69, 0, 100)).visible(findSpawners::get).build());
    private final Setting<SettingColor> chestColor = sgRender.add(new ColorSetting.Builder()
            .name("chest-color").description("Color for Chests.")
            .defaultValue(new SettingColor(255, 215, 0, 100)).visible(findChests::get).build());

    private final ConcurrentHashMap<ChunkPos, Set<SavedBlock>> database = new ConcurrentHashMap<>();
    private final Path savePath = Paths.get("meteor-client", "vault_scanner_data.csv");
    private ExecutorService threadPool;
    private final Set<Block> targetBlocks = new HashSet<>();

    public VaultScanner() {
        super(DonutAddon.CATEGORY, "vault-scanner",
                "Passively logs and permanently maps hidden blocks using optimized palette scanning.");
    }

    @Override
    public void onActivate() {
        threadPool = Executors.newFixedThreadPool(2);
        updateTargetBlocks();
        loadDatabase();
        info("Loaded " + countTotalBlocks() + " saved blocks across " + database.size() + " chunks.");
    }

    @Override
    public void onDeactivate() {
        saveDatabase();
        if (threadPool != null && !threadPool.isShutdown()) threadPool.shutdown();
        info("Saved database to disk.");
    }

    private void updateTargetBlocks() {
        targetBlocks.clear();
        if (findAmethyst.get()) targetBlocks.add(Blocks.BUDDING_AMETHYST);
        if (findSpawners.get()) targetBlocks.add(Blocks.SPAWNER);
        if (findChests.get()) {
            targetBlocks.add(Blocks.CHEST);
            targetBlocks.add(Blocks.TRAPPED_CHEST);
            targetBlocks.add(Blocks.BARREL);
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (MC.world == null || MC.player == null) return;
        WorldChunk chunk = event.chunk();
        ChunkPos chunkPos = chunk.getPos();
        updateTargetBlocks();
        if (targetBlocks.isEmpty() || !isPlayerNearby(chunkPos)) return;
        if (useThreading.get()) threadPool.execute(() -> scanChunk(chunk, chunkPos));
        else scanChunk(chunk, chunkPos);
    }

    private boolean isPlayerNearby(ChunkPos chunkPos) {
        boolean nearby = MC.world.getPlayers().stream()
                .filter(p -> p != MC.player)
                .anyMatch(p -> p.getChunkPos().getChebyshevDistance(chunkPos) <= playerProximity.get());
        if (!nearby && !MC.player.isSpectator())
            nearby = MC.player.getChunkPos().getChebyshevDistance(chunkPos) <= playerProximity.get();
        return nearby;
    }

    private void scanChunk(WorldChunk chunk, ChunkPos chunkPos) {
        Set<SavedBlock> foundBlocks = new HashSet<>();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = MC.world.getBottomY();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            if (!section.getBlockStateContainer().hasAny(s -> targetBlocks.contains(s.getBlock()))) continue;

            int sectionBaseY = bottomY + i * 16;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();
                        if (targetBlocks.contains(block)) {
                            BlockPos pos = new BlockPos(startX + x, sectionBaseY + y, startZ + z);
                            foundBlocks.add(new SavedBlock(pos, getBlockIdentifier(block)));
                        }
                    }
                }
            }
        }

        if (!foundBlocks.isEmpty()) {
            database.merge(chunkPos, foundBlocks, (oldSet, newSet) -> {
                Set<SavedBlock> merged = ConcurrentHashMap.newKeySet();
                if (oldSet != null) merged.addAll(oldSet);
                merged.addAll(newSet);
                return merged;
            });
            saveDatabase();
        }
    }

    private String getBlockIdentifier(Block block) {
        if (block == Blocks.BUDDING_AMETHYST) return "AMETHYST";
        if (block == Blocks.SPAWNER)          return "SPAWNER";
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL) return "CHEST";
        return "UNKNOWN";
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || database.isEmpty() || MC.player == null) return;
        for (Set<SavedBlock> blocksInChunk : database.values()) {
            for (SavedBlock block : blocksInChunk) {
                Color c = getColorForType(block.type);
                if (c == null) continue;
                double x = block.pos.getX(), y = block.pos.getY(), z = block.pos.getZ();
                event.renderer.box(x, y, z, x + 1, y + 1, z + 1, c, c, ShapeMode.Lines, 0);
                if (showTracers.get())
                    event.renderer.line(event.offsetX, event.offsetY, event.offsetZ, x + 0.5, y + 0.5, z + 0.5, c);
            }
        }
    }

    private Color getColorForType(String type) {
        return switch (type) {
            case "AMETHYST" -> findAmethyst.get() ? (Color) amethystColor.get() : null;
            case "SPAWNER"  -> findSpawners.get() ? (Color) spawnerColor.get()  : null;
            case "CHEST"    -> findChests.get()   ? (Color) chestColor.get()    : null;
            default -> null;
        };
    }

    private void loadDatabase() {
        if (!Files.exists(savePath)) return;
        try (BufferedReader r = Files.newBufferedReader(savePath)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length != 4) continue;
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    String type = parts[3].trim();
                    BlockPos pos = new BlockPos(x, y, z);
                    ChunkPos cPos = new ChunkPos(pos);
                    database.computeIfAbsent(cPos, k -> ConcurrentHashMap.newKeySet()).add(new SavedBlock(pos, type));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            error("Failed to load vault database.");
        }
    }

    private synchronized void saveDatabase() {
        try {
            Files.createDirectories(savePath.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(savePath)) {
                for (Set<SavedBlock> blocks : database.values()) {
                    for (SavedBlock b : blocks) {
                        w.write(b.pos.getX() + "," + b.pos.getY() + "," + b.pos.getZ() + "," + b.type);
                        w.newLine();
                    }
                }
            }
        } catch (IOException e) {
            error("Failed to save vault database.");
        }
    }

    private int countTotalBlocks() {
        return database.values().stream().mapToInt(Set::size).sum();
    }

    private static class SavedBlock {
        public final BlockPos pos;
        public final String type;

        SavedBlock(BlockPos pos, String type) {
            this.pos = pos;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SavedBlock that)) return false;
            return pos.equals(that.pos) && type.equals(that.type);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }
}