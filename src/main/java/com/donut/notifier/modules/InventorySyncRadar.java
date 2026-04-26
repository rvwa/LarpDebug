package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Receive;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Send;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Post;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;

public class InventorySyncRadar extends Module {
    private static final Set<Item> SPAWNER_LOOT = Set.of(
            Items.BONE,
            Items.ARROW,
            Items.STRING,
            Items.ROTTEN_FLESH,
            Items.GUNPOWDER,
            Items.BLAZE_POWDER,
            Items.IRON_INGOT,
            Items.GOLD_NUGGET,
            Items.SPIDER_EYE,
            Items.ENDER_PEARL,
            Items.BLAZE_ROD,
            Items.NETHER_BRICK
    );
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgFilter = this.settings.createGroup("Screen Filter");
    private final SettingGroup sgRender = this.settings.createGroup("Render");
    private final Setting<Integer> confirmThreshold = this.sgGeneral
            .add(
                    new IntSetting.Builder()
                            .name("confirm-threshold")
                            .description("Score required to confirm a chunk as a spawner location.")
                            .defaultValue(3)
                            .range(2, 30)
                            .sliderMax(15)
                            .build()
            );
    private final Setting<Integer> decaySeconds = this.sgGeneral
            .add(
                    new IntSetting.Builder()
                            .name("decay-seconds")
                            .description("Idle seconds before a detection is dropped.")
                            .defaultValue(600)
                            .range(30, 1800)
                            .sliderMax(600)
                            .build()
            );
    private final Setting<Boolean> chatNotify = this.sgGeneral
            .add(
                    new BoolSetting.Builder()
                            .name("chat-notifications")
                            .defaultValue(true)
                            .build()
            );
    private final Setting<Integer> minLootMatches = this.sgFilter
            .add(
                    new IntSetting.Builder()
                            .name("min-loot-matches")
                            .description("Minimum number of spawner-loot item types needed to flag an inventory.")
                            .defaultValue(2)
                            .range(1, SPAWNER_LOOT.size())
                            .sliderMax(8)
                            .build()
            );
    private final Setting<String> screenKeyword = this.sgFilter
            .add(
                    new StringSetting.Builder()
                            .name("screen-keyword")
                            .description("Screen title substring to treat as a spawner screen (case-insensitive).")
                            .defaultValue("spawner")
                            .build()
            );
    private final Setting<Boolean> renderEnabled = this.sgRender
            .add(
                    new BoolSetting.Builder()
                            .name("render")
                            .defaultValue(true)
                            .build()
            );
    private final Setting<SettingColor> fillColor = this.sgRender
            .add(
                    new ColorSetting.Builder()
                            .name("fill")
                            .defaultValue(new SettingColor(0, 220, 80, 40))
                            .visible(this.renderEnabled::get)
                            .build()
            );
    private final Setting<SettingColor> lineColor = this.sgRender
            .add(
                    new ColorSetting.Builder()
                            .name("line")
                            .defaultValue(new SettingColor(0, 220, 80, 200))
                            .visible(this.renderEnabled::get)
                            .build()
            );
    private final Setting<Boolean> showTracers = this.sgRender
            .add(
                    new BoolSetting.Builder()
                            .name("show-tracers")
                            .defaultValue(true)
                            .visible(this.renderEnabled::get)
                            .build()
            );
    private final Setting<SettingColor> tracerColor = this.sgRender
            .add(
                    new ColorSetting.Builder()
                            .name("tracer-color")
                            .defaultValue(new SettingColor(0, 220, 80, 150))
                            .visible(this.renderEnabled::get)
                            .build()
            );
    private final ConcurrentHashMap<ChunkPos, Detection> detections = new ConcurrentHashMap<>();
    private volatile BlockPos lastClickPos = null;
    private volatile boolean spawnerScreenOpen = false;
    private volatile long screenOpenMs = 0L;
    private int tickCounter = 0;

    public InventorySyncRadar() {
        super(
                DonutAddon.CATEGORY,
                "inventory-sync-radar",
                "Detects underground spawners by correlating block interactions, screen opens, and inventory loot patterns."
        );
    }

    @Override
    public void onActivate() {
        this.detections.clear();
        this.lastClickPos = null;
        this.spawnerScreenOpen = false;
    }

    @Override
    public void onDeactivate() {
        this.detections.clear();
    }

    @EventHandler
    private void onTick(Post event) {
        if (++this.tickCounter % 100 == 0) {
            long cutoff = System.currentTimeMillis() - (long) decaySeconds.get() * 1000L;
            this.detections.entrySet().removeIf(e -> e.getValue().lastMs < cutoff);
        }
    }

    @EventHandler
    private void onSend(Send event) {
        if (event.packet instanceof PlayerInteractBlockC2SPacket pkt) {
            this.lastClickPos = pkt.getBlockHitResult().getBlockPos();
        }
    }

    @EventHandler
    private void onReceive(Receive event) {
        if (this.mc.world == null || this.mc.player == null) return;

        if (event.packet instanceof OpenScreenS2CPacket pkt) {
            String title = pkt.getName().getString().toLowerCase(Locale.ROOT);
            if (title.contains(screenKeyword.get().toLowerCase(Locale.ROOT))) {
                this.spawnerScreenOpen = true;
                this.screenOpenMs = System.currentTimeMillis();
                BlockPos lcp = this.lastClickPos;
                if (lcp != null && lcp.getY() < 0) {
                    ChunkPos cp = new ChunkPos(lcp);
                    this.addScore(cp, 2, "SCREEN_OPEN_Y<0");
                    if (chatNotify.get()) {
                        ChatUtils.info("[ISR] §eSpawner screen§r opened at Y<0 chunk §f" + cp.x + "," + cp.z);
                    }
                }
            } else {
                this.spawnerScreenOpen = false;
            }
        } else if (event.packet instanceof InventoryS2CPacket pkt) {
            if (!this.spawnerScreenOpen) return;

            if (System.currentTimeMillis() - this.screenOpenMs > 5000L) {
                this.spawnerScreenOpen = false;
                return;
            }

            int lootMatches = 0;
            Set<Item> seenItems = new HashSet<>();
            List<ItemStack> contents = pkt.getContents();

            for (ItemStack stack : contents) {
                if (!stack.isEmpty()) {
                    Item item = stack.getItem();
                    if (SPAWNER_LOOT.contains(item) && seenItems.add(item)) {
                        lootMatches++;
                    }
                }
            }

            if (lootMatches >= minLootMatches.get()) {
                BlockPos lcp = this.lastClickPos;
                if (lcp != null && lcp.getY() < 0) {
                    ChunkPos cp = new ChunkPos(lcp);
                    this.addScore(cp, 5, "SCREEN+LOOT_Y<0");
                    if (chatNotify.get()) {
                        ChatUtils.info("[ISR] §a§lSPAWNER LOOT MATCH§r chunk §f" + cp.x + "," + cp.z + " §7loot=" + lootMatches + " matches");
                    }
                }
            }

            this.spawnerScreenOpen = false;
        } else if (event.packet instanceof PlayerPositionLookS2CPacket && this.mc.player.getY() < 0.0) {
            ChunkPos cp = this.mc.player.getChunkPos();
            this.addScore(cp, 3, "RUBBERBAND");
        }
    }

    private void addScore(ChunkPos cp, int delta, String reason) {
        Detection d = this.detections.computeIfAbsent(cp, Detection::new);
        boolean wasConfirmed = d.score >= confirmThreshold.get();
        d.addScore(delta, reason);
        if (!wasConfirmed && d.score >= confirmThreshold.get()) {
            d.confirmed = true;
            if (chatNotify.get()) {
                ChatUtils.info("[ISR] §a§lCONFIRMED SPAWNER CHUNK§r §f" + cp.x + "," + cp.z + " §7via " + reason);
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderEnabled.get() || this.mc.player == null) return;

        Vec3d eye = this.mc.player.getCameraPosVec(event.tickDelta);

        for (Detection d : this.detections.values()) {
            double x0 = d.cp.getStartX();
            double z0 = d.cp.getStartZ();
            double x1 = x0 + 16.0;
            double z1 = z0 + 16.0;
            double cx = d.cp.getCenterX();
            double cz = d.cp.getCenterZ();
            float ratio = Math.min(1.0F, (float) d.score / (float) confirmThreshold.get());
            SettingColor fill = fillColor.get();
            SettingColor line = lineColor.get();
            SettingColor scaledFill = new SettingColor(fill.r, fill.g, fill.b, (int) (fill.a * ratio));
            event.renderer.box(x0, 64.0, z0, x1, 64.2, z1, scaledFill, line, ShapeMode.Both, 0);
            if (showTracers.get()) {
                event.renderer.line(eye.x, eye.y, eye.z, cx, 64.1, cz, (Color) tracerColor.get());
            }
        }
    }

    private static class Detection {
        final ChunkPos cp;
        int score;
        long lastMs;
        boolean confirmed;
        String reason = "";

        Detection(ChunkPos cp) {
            this.cp = cp;
            this.lastMs = System.currentTimeMillis();
        }

        void addScore(int delta, String reason) {
            this.score += delta;
            this.lastMs = System.currentTimeMillis();
            this.reason = reason;
        }
    }
}