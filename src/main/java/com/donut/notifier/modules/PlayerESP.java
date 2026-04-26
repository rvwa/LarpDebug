package com.donut.notifier.modules;

import com.donut.notifier.DonutAddon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

public class PlayerESP extends Module {
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Boolean> showEsp = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("show-box")
                    .description("Highlights players with a box.")
                    .defaultValue(true)
                    .build());

    private final Setting<SettingColor> espColor = this.sgRender
            .add(new ColorSetting.Builder()
                    .name("box-color")
                    .description("The color of the ESP box.")
                    .defaultValue(new SettingColor(0, 255, 200, 150))
                    .build());

    private final Setting<Boolean> showTracers = this.sgRender
            .add(new BoolSetting.Builder()
                    .name("tracers")
                    .description("Draws tracers to players.")
                    .defaultValue(true)
                    .build());

    private final Setting<TracerMode> tracerMode = this.sgRender
            .add(new EnumSetting.Builder<TracerMode>()
                    .name("tracer-mode")
                    .description("The style of tracers to use.")
                    .defaultValue(TracerMode.TwoD)
                    .build());

    private final Setting<Double> maxDistance = this.sgRender
            .add(new DoubleSetting.Builder()
                    .name("max-distance")
                    .description("Maximum distance to render players.")
                    .defaultValue(512.0)
                    .range(1.0, 2048.0)
                    .sliderMax(1024.0)
                    .build());

    private final Setting<Double> tracerWidth = this.sgRender
            .add(new DoubleSetting.Builder()
                    .name("tracer-width")
                    .description("The width of the tracer lines.")
                    .defaultValue(1.0)
                    .range(0.1, 5.0)
                    .sliderMax(3.0)
                    .build());

    public PlayerESP() {
        super(DonutAddon.CATEGORY, "player-esp", "Highlights nearby players with smooth rendering");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (this.mc.player == null || this.mc.world == null) return;

        Vec3d eyePos = this.mc.player.getLerpedPos(event.tickDelta)
                .add(0.0, this.mc.player.getStandingEyeHeight(), 0.0);
        double maxDistSq = maxDistance.get() * maxDistance.get();

        for (AbstractClientPlayerEntity player : this.mc.world.getPlayers()) {
            if (player == this.mc.player || !player.isAlive()) continue;
            if (this.mc.player.squaredDistanceTo(player) > maxDistSq) continue;

            if (showEsp.get()) {
                Vec3d pos = player.getLerpedPos(event.tickDelta);
                double width = player.getWidth() / 2.0;
                double height = player.getHeight();
                Color c = espColor.get();
                event.renderer.box(
                        pos.x - width, pos.y, pos.z - width,
                        pos.x + width, pos.y + height, pos.z + width,
                        c, c, ShapeMode.Both, 0
                );
            }

            if (showTracers.get() && tracerMode.get() == TracerMode.ThreeD) {
                Vec3d target = player.getLerpedPos(event.tickDelta)
                        .add(0.0, player.getHeight() / 2.0, 0.0);
                event.renderer.line(
                        eyePos.x, eyePos.y, eyePos.z,
                        target.x, target.y, target.z,
                        espColor.get()
                );
            }
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (this.mc.player == null || this.mc.world == null) return;
        if (!showTracers.get() || tracerMode.get() != TracerMode.TwoD) return;

        double centerX = event.screenWidth / 2.0;
        double centerY = event.screenHeight / 2.0;
        double maxDistSq = maxDistance.get() * maxDistance.get();

        for (AbstractClientPlayerEntity player : this.mc.world.getPlayers()) {
            if (player == this.mc.player || !player.isAlive()) continue;
            if (this.mc.player.squaredDistanceTo(player) > maxDistSq) continue;

            Vec3d target = player.getLerpedPos(event.tickDelta)
                    .add(0.0, player.getHeight() / 2.0, 0.0);
            Vector3d screenPos = new Vector3d(target.x, target.y, target.z);
            if (NametagUtils.to2D(screenPos, 1.0)) {
                Renderer2D.COLOR.line(centerX, centerY, screenPos.x, screenPos.y, espColor.get());
            }
        }
    }

    public enum TracerMode {
        ThreeD("3D"),
        TwoD("2D");

        private final String title;

        TracerMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return this.title;
        }
    }
}