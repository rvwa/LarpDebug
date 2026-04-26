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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Receive;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.IntSetting.Builder;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;

public class EntityClusterScanner extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = this.settings.createGroup("Render");
    private final Setting<Integer> clusterRadius = this.sgGeneral
            .add(
                    ((Builder)((Builder)((Builder)new Builder().name("cluster-radius")).description("Chunk radius to group spawns into clusters.")).defaultValue(2))
                            .range(1, 8)
                            .sliderMax(8)
                            .build()
            );
    private final Setting<Integer> minSpawns = this.sgGeneral
            .add(
                    ((Builder)((Builder)((Builder)new Builder().name("min-spawns")).description("Minimum spawns in a cluster to render.")).defaultValue(3))
                            .range(2, 50)
                            .sliderMax(30)
                            .build()
            );
    private final Setting<Boolean> trackPlayers = this.sgGeneral
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("track-players"))
                            .description("Track player entity spawns (login/teleport events)."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Boolean> trackHostile = this.sgGeneral
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("track-hostile"))
                            .description("Track hostile mob spawns."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Boolean> trackPassive = this.sgGeneral
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("track-passive"))
                            .description("Track passive mob spawns (DonutSMP may spawn custom passive mobs from spawners)."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<Boolean> renderEnabled = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                            .name("render"))
                            .description("Render cluster boxes."))
                            .defaultValue(true))
                            .build()
            );
    private final Setting<SettingColor> colorLow = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("color-low"))
                            .description("Color for low-density clusters."))
                            .defaultValue(new SettingColor(255, 50, 50, 80))
                            .visible(this.renderEnabled::get))
                            .build()
            );
    private final Setting<SettingColor> colorHigh = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                            .name("color-high"))
                            .description("Color for high-density clusters."))
                            .defaultValue(new SettingColor(255, 200, 0, 160))
                            .visible(this.renderEnabled::get))
                            .build()
            );
    private final Setting<Double> renderY = this.sgRender
            .add(
                    ((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)((meteordevelopment.meteorclient.settings.DoubleSetting.Builder)new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                            .name("render-y"))
                            .description("Y coordinate to render cluster plates at."))
                            .defaultValue(64.0)
                            .sliderRange(-64.0, 320.0)
                            .visible(this.renderEnabled::get))
                            .build()
            );
    private final Map<ChunkPos, EntityClusterScanner.SpawnRecord> spawnMap = new ConcurrentHashMap<>();
    private final List<EntityClusterScanner.Cluster> activeClusters = Collections.synchronizedList(new ArrayList<>());
    private final Path savePath = Paths.get("meteor-client", "entity_clusters.txt");
    private Instant lastClusterUpdate = Instant.now();

    public EntityClusterScanner() {
        super(DonutAddon.CATEGORY, "entity-cluster-scanner", "Clusters entity spawn locations over time to find spawner farms.");
    }

    public void onActivate() {
        this.load();
        this.info("Loaded " + this.spawnMap.size() + " spawn records.", new Object[0]);
    }

    public void onDeactivate() {
        this.save();
        this.activeClusters.clear();
    }

    @EventHandler
    private void onPacket(Receive event) {
        if (event.packet instanceof EntitySpawnS2CPacket pkt) {
            if (this.mc.world != null && this.mc.player != null) {
                EntityType<?> type = pkt.getEntityType();
                double x = pkt.getX();
                double y = pkt.getY();
                double z = pkt.getZ();
                ChunkPos cp = new ChunkPos(BlockPos.ofFloored(x, y, z));
                boolean record = false;
                String label = type.getUntranslatedName();
                if ((Boolean)this.trackPlayers.get() && type == EntityType.PLAYER) {
                    record = true;
                }

                if ((Boolean)this.trackHostile.get() && type.getSpawnGroup() == SpawnGroup.MONSTER) {
                    record = true;
                }

                if ((Boolean)this.trackPassive.get() && type.getSpawnGroup() == SpawnGroup.CREATURE) {
                    record = true;
                }

                if (record) {
                    this.spawnMap.computeIfAbsent(cp, EntityClusterScanner.SpawnRecord::new).addSpawn(label);
                    if (Duration.between(this.lastClusterUpdate, Instant.now()).getSeconds() >= 5L) {
                        this.updateClusters();
                        this.lastClusterUpdate = Instant.now();
                    }
                }
            }
        }
    }

    private void updateClusters() {
        List<ChunkPos> allChunks = new ArrayList<>(this.spawnMap.keySet());
        Set<ChunkPos> visited = new HashSet<>();
        List<EntityClusterScanner.Cluster> newClusters = new ArrayList<>();

        for (ChunkPos start : allChunks) {
            if (!visited.contains(start)) {
                List<ChunkPos> clusterChunks = new ArrayList<>();
                Deque<ChunkPos> queue = new ArrayDeque<>();
                queue.add(start);
                visited.add(start);

                while (!queue.isEmpty()) {
                    ChunkPos curr = queue.poll();
                    clusterChunks.add(curr);
                    int r = (Integer)this.clusterRadius.get();

                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            ChunkPos nb = new ChunkPos(curr.x + dx, curr.z + dz);
                            if (this.spawnMap.containsKey(nb) && !visited.contains(nb)) {
                                visited.add(nb);
                                queue.add(nb);
                            }
                        }
                    }
                }

                int totalSpawns = 0;
                Set<String> types = new HashSet<>();

                for (ChunkPos cp : clusterChunks) {
                    EntityClusterScanner.SpawnRecord sr = this.spawnMap.get(cp);
                    totalSpawns += sr.spawnCount;
                    types.addAll(sr.entityTypes);
                }

                if (totalSpawns >= (Integer)this.minSpawns.get()) {
                    double sumX = 0.0;
                    double sumZ = 0.0;

                    for (ChunkPos cp : clusterChunks) {
                        sumX += (double)cp.getCenterX();
                        sumZ += (double)cp.getCenterZ();
                    }

                    newClusters.add(
                            new EntityClusterScanner.Cluster(
                                    sumX / (double)clusterChunks.size(), sumZ / (double)clusterChunks.size(), clusterChunks.size(), totalSpawns, types.size()
                            )
                    );
                }
            }
        }

        newClusters.sort((a, b) -> Integer.compare(b.totalSpawns, a.totalSpawns));
        synchronized (this.activeClusters) {
            this.activeClusters.clear();
            this.activeClusters.addAll(newClusters);
        }

        if (!newClusters.isEmpty()) {
            this.info("§e" + newClusters.size() + "§7 clusters — top: §f" + newClusters.get(0).totalSpawns + " spawns", new Object[0]);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if ((Boolean)this.renderEnabled.get()) {
            double rY = (Double)this.renderY.get();
            synchronized (this.activeClusters) {
                for (EntityClusterScanner.Cluster c : this.activeClusters) {
                    float ratio = Math.min(1.0F, (float)c.totalSpawns / 50.0F);
                    SettingColor lo = (SettingColor)this.colorLow.get();
                    SettingColor hi = (SettingColor)this.colorHigh.get();
                    SettingColor col = new SettingColor(
                            (int)((float)lo.r + (float)(hi.r - lo.r) * ratio),
                            (int)((float)lo.g + (float)(hi.g - lo.g) * ratio),
                            (int)((float)lo.b + (float)(hi.b - lo.b) * ratio),
                            (int)((float)lo.a + (float)(hi.a - lo.a) * ratio)
                    );
                    double size = (double)c.chunkCount * 8.0;
                    event.renderer.box(c.x - size, rY, c.z - size, c.x + size, rY + 0.3, c.z + size, col, col, ShapeMode.Both, 0);
                }
            }
        }
    }

    private void load() {
        if (Files.exists(this.savePath)) {
            String line;
            try (BufferedReader r = Files.newBufferedReader(this.savePath)) {
                while ((line = r.readLine()) != null) {
                    EntityClusterScanner.SpawnRecord sr = EntityClusterScanner.SpawnRecord.fromString(line);
                    if (sr != null) {
                        this.spawnMap.put(sr.pos, sr);
                    }
                }
            } catch (IOException var6) {
            }
        }
    }

    private void save() {
        try {
            Files.createDirectories(this.savePath.getParent());

            try (BufferedWriter w = Files.newBufferedWriter(this.savePath)) {
                for (EntityClusterScanner.SpawnRecord sr : this.spawnMap.values()) {
                    w.write(sr.toString());
                    w.newLine();
                }
            }
        } catch (IOException var6) {
        }
    }

    private static class Cluster {
        final double x;
        final double z;
        final int chunkCount;
        final int totalSpawns;
        final int uniqueTypes;

        Cluster(double x, double z, int chunks, int spawns, int types) {
            this.x = x;
            this.z = z;
            this.chunkCount = chunks;
            this.totalSpawns = spawns;
            this.uniqueTypes = types;
        }
    }

    private static class SpawnRecord {
        final ChunkPos pos;
        int spawnCount;
        Instant firstSeen;
        Instant lastSeen;
        final Set<String> entityTypes = new HashSet<>();

        SpawnRecord(ChunkPos pos) {
            this.pos = pos;
            this.firstSeen = Instant.now();
            this.lastSeen = Instant.now();
            this.spawnCount = 1;
        }

        void addSpawn(String type) {
            this.spawnCount++;
            this.lastSeen = Instant.now();
            this.entityTypes.add(type);
        }

        @Override
        public String toString() {
            return this.pos.x
                    + ","
                    + this.pos.z
                    + ","
                    + this.spawnCount
                    + ","
                    + this.firstSeen
                    + ","
                    + this.lastSeen
                    + ","
                    + String.join(";", this.entityTypes);
        }

        static EntityClusterScanner.SpawnRecord fromString(String s) {
            String[] p = s.split(",", 6);
            if (p.length < 6) {
                return null;
            } else {
                try {
                    EntityClusterScanner.SpawnRecord r = new EntityClusterScanner.SpawnRecord(
                            new ChunkPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()))
                    );
                    r.spawnCount = Integer.parseInt(p[2].trim());
                    r.firstSeen = Instant.parse(p[3].trim());
                    r.lastSeen = Instant.parse(p[4].trim());
                    Collections.addAll(r.entityTypes, p[5].split(";"));
                    return r;
                } catch (Exception var3) {
                    return null;
                }
            }
        }
    }
}