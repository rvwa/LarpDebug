package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Vector3d;

public class SpawnerNotifier extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender  = this.settings.createGroup("Render");
    private final SettingGroup sgWebhook = this.settings.createGroup("Webhook");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-feedback").description("Sends a chat message when a spawner is found.").defaultValue(true).build());
    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
            .name("show-distance").description("Shows distance in chat messages.").defaultValue(true).build());
    private final Setting<Boolean> toastNotification = sgGeneral.add(new BoolSetting.Builder()
            .name("toast-notification").description("Shows a toast notification when a spawner is found.").defaultValue(true).build());
    private final Setting<Boolean> disconnectOnFind = sgGeneral.add(new BoolSetting.Builder()
            .name("disconnect-on-find").description("Disconnects from the server when a spawner is found.").defaultValue(false).build());

    private final Setting<Boolean> showEsp = sgRender.add(new BoolSetting.Builder()
            .name("show-esp").description("Highlights spawners with a box.").defaultValue(true).build());
    private final Setting<SettingColor> espColor = sgRender.add(new ColorSetting.Builder()
            .name("esp-color").description("The color of the ESP box.").defaultValue(new SettingColor(255, 0, 0, 150)).build());
    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
            .name("tracers").description("Draws tracers to spawners.").defaultValue(true).build());
    private final Setting<Boolean> tracersClosest = sgRender.add(new BoolSetting.Builder()
            .name("tracers-closest-only").description("Only draws tracers to the closest spawner.").defaultValue(false).build());
    private final Setting<Double> maxTracerDistance = sgRender.add(new DoubleSetting.Builder()
            .name("max-tracer-distance").description("Maximum distance to draw tracers.")
            .defaultValue(256.0).range(1.0, 1024.0).sliderMax(512.0).build());
    private final Setting<TracerMode> tracerMode = sgRender.add(new EnumSetting.Builder<TracerMode>()
            .name("tracer-mode").description("The style of tracers to use.").defaultValue(TracerMode.TwoD).build());
    private final Setting<Double> tracerWidth = sgRender.add(new DoubleSetting.Builder()
            .name("tracer-width").description("The width of the tracer lines.")
            .defaultValue(1.0).range(0.1, 5.0).sliderMax(3.0).build());

    private final Setting<Boolean> enableWebhook = sgWebhook.add(new BoolSetting.Builder()
            .name("enable-webhook").description("Sends a Discord webhook when a spawner is found.").defaultValue(false).build());
    private final Setting<Boolean> selfPing = sgWebhook.add(new BoolSetting.Builder()
            .name("self-ping").description("Pings you in the Discord webhook.").defaultValue(false).build());

    private final Set<BlockPos> spawnerPositions = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> processedChunks  = ConcurrentHashMap.newKeySet();
    private String webhookUrl = "";
    private String discordId  = "";
    private HttpClient httpClient;

    public SpawnerNotifier() {
        super(AddonTemplate.CATEGORY, "spawner", "Notifies and highlights spawners with ESP and webhooks");
        AddonTemplate.LOG.info("SpawnerNotifier module initialized!");
    }

    private HttpClient getHttpClient() {
        if (httpClient == null) httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return httpClient;
    }

    @Override
    public void onActivate() {
        spawnerPositions.clear();
        processedChunks.clear();
        loadWebhookConfig();
        if (mc.world != null && mc.player != null) {
            int rd = mc.options.getClampedViewDistance();
            int pX = mc.player.getChunkPos().x, pZ = mc.player.getChunkPos().z;
            for (int x = pX - rd; x <= pX + rd; x++)
                for (int z = pZ - rd; z <= pZ + rd; z++) {
                    WorldChunk chunk = mc.world.getChunk(x, z);
                    if (chunk != null) checkChunk(chunk);
                }
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        checkChunk(event.chunk());
    }

    private void checkChunk(WorldChunk chunk) {
        if (chunk == null || mc.world == null) return;
        ChunkPos cpos = chunk.getPos();
        if (processedChunks.contains(cpos)) return;

        MeteorExecutor.execute(() -> {
            int found = 0;
            ChunkSection[] sections = chunk.getSectionArray();
            for (int i = 0; i < sections.length; i++) {
                ChunkSection section = sections[i];
                if (section == null || section.isEmpty()) continue;
                if (!section.getBlockStateContainer().hasAny(s -> s.isOf(Blocks.SPAWNER))) continue;
                int sectionY = mc.world.getBottomY() + i * 16;
                for (int x = 0; x < 16; x++)
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            if (section.getBlockState(x, y, z).isOf(Blocks.SPAWNER))
                                if (spawnerPositions.add(new BlockPos(cpos.getStartX() + x, sectionY + y, cpos.getStartZ() + z)))
                                    found++;
            }
            if (found > 0) {
                processedChunks.add(cpos);
                int finalFound = found;
                mc.execute(() -> handleDetection(cpos, finalFound));
            }
        });
    }

    private void handleDetection(ChunkPos cpos, int count) {
        int cx = cpos.x * 16 + 8, cz = cpos.z * 16 + 8;
        if (chatFeedback.get()) {
            String msg = "Found " + count + " spawner" + (count > 1 ? "s" : "") + " at " + cx + ", " + cz;
            if (showDistance.get() && mc.player != null)
                msg += String.format(" (%.0fm)", Math.sqrt(mc.player.squaredDistanceTo(cx, mc.player.getY(), cz)));
            ChatUtils.info(msg);
        }
        if (toastNotification.get()) mc.getToastManager().add(new SpawnerToast());
        if (enableWebhook.get()) sendWebhook(cpos, count);
        if (disconnectOnFind.get()) {
            toggle();
            if (mc.getNetworkHandler() != null)
                mc.getNetworkHandler().getConnection().disconnect(Text.literal("Spawner Found"));
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || spawnerPositions.isEmpty()) return;
        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Vec3d eyePos = playerPos.add(0, mc.player.getStandingEyeHeight(), 0);
        double maxDistSq = maxTracerDistance.get() * maxTracerDistance.get();

        BlockPos closest = null;
        if (showTracers.get() && tracerMode.get() == TracerMode.ThreeD && tracersClosest.get()) {
            double minD = Double.MAX_VALUE;
            for (BlockPos pos : spawnerPositions) {
                double d = playerPos.squaredDistanceTo(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
                if (d < minD) { minD = d; closest = pos; }
            }
        }

        for (BlockPos pos : spawnerPositions) {
            double x = pos.getX(), y = pos.getY(), z = pos.getZ();
            if (playerPos.squaredDistanceTo(x + .5, y + .5, z + .5) > maxDistSq) continue;
            if (showEsp.get())
                event.renderer.box(x, y, z, x + 1, y + 1, z + 1, (Color) espColor.get(), (Color) espColor.get(), ShapeMode.Both, 0);
            if (showTracers.get() && tracerMode.get() == TracerMode.ThreeD && (!tracersClosest.get() || pos.equals(closest)))
                event.renderer.line(eyePos.x, eyePos.y, eyePos.z, x + .5, y + .5, z + .5, (Color) espColor.get());
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || spawnerPositions.isEmpty()) return;
        if (!showTracers.get() || tracerMode.get() != TracerMode.TwoD) return;

        NametagUtils.onRender(event.drawContext.getMatrices().peek().getPositionMatrix());
        double cx = event.screenWidth / 2.0, cy = event.screenHeight / 2.0;
        double maxDistSq = maxTracerDistance.get() * maxTracerDistance.get();
        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);

        BlockPos closest = null;
        if (tracersClosest.get()) {
            double minD = Double.MAX_VALUE;
            for (BlockPos pos : spawnerPositions) {
                double d = playerPos.squaredDistanceTo(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
                if (d < minD) { minD = d; closest = pos; }
            }
        }

        for (BlockPos pos : spawnerPositions) {
            if (tracersClosest.get() && !pos.equals(closest)) continue;
            if (playerPos.squaredDistanceTo(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > maxDistSq) continue;
            Vector3d screenPos = new Vector3d(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
            if (NametagUtils.to2D(screenPos, 1.0))
                Renderer2D.COLOR.line(cx, cy, screenPos.x, screenPos.y, (Color) espColor.get());
        }
    }

    private void sendWebhook(ChunkPos cpos, int count) {
        if (webhookUrl.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            try {
                String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "Singleplayer";
                int cx = cpos.x * 16 + 8, cz = cpos.z * 16 + 8;
                String ping = selfPing.get() && !discordId.isEmpty() ? "<@" + discordId + ">" : "";
                String desc = count + " spawner(s) found at " + cx + ", " + cz;
                String body = String.format(
                        "{\"content\":\"%s\",\"username\":\"Meteor SpawnerNotifier\",\"embeds\":[{\"title\":\"Spawner Alert\",\"description\":\"%s\",\"color\":15158332,\"fields\":[{\"name\":\"Coords\",\"value\":\"%d, %d\",\"inline\":true},{\"name\":\"Server\",\"value\":\"%s\",\"inline\":true}]}]}",
                        escape(ping), escape(desc), cx, cz, escape(server));
                getHttpClient().sendAsync(
                        HttpRequest.newBuilder().uri(URI.create(webhookUrl))
                                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)).build(),
                        BodyHandlers.ofString());
            } catch (Exception ignored) {}
        });
    }

    private void loadWebhookConfig() {
        try {
            File folder = new File(mc.runDirectory, "meteor-addon");
            folder.mkdirs();
            File file = new File(folder, "spawner_webhook.txt");
            if (!file.exists()) return;
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                webhookUrl = br.readLine(); if (webhookUrl == null) webhookUrl = "";
                discordId  = br.readLine(); if (discordId  == null) discordId  = "";
            }
        } catch (Exception ignored) {}
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static class SpawnerToast implements Toast {
        private static final Identifier TEXTURE = Identifier.of("minecraft", "toast/advancement");
        private static final ItemStack ICON = new ItemStack(Items.SPAWNER);

        @Override
        public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
            context.fill(0, 0, getWidth(), getHeight(), -1879048192);
            context.drawItem(ICON, 8, 8);
            context.drawText(manager.getClient().textRenderer, Text.literal("Spawner Detected!"), 30, 7, -1, false);
            context.drawText(manager.getClient().textRenderer, Text.literal("Mob spawner found nearby"), 30, 18, -1, false);
            return startTime >= 5000L ? Visibility.HIDE : Visibility.SHOW;
        }

        @Override public int getWidth()  { return 160; }
        @Override public int getHeight() { return 32;  }
    }

    public enum TracerMode {
        ThreeD("3D"), TwoD("2D");
        private final String title;
        TracerMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }
}