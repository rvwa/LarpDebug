package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public class ModuleExample extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender  = this.settings.createGroup("Render");

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
            .name("scale")
            .description("The size of the marker.")
            .defaultValue(2.0).range(0.5, 10.0).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
            .name("color")
            .description("The color of the marker.")
            .defaultValue(Color.MAGENTA).build());

    public ModuleExample() {
        super(AddonTemplate.CATEGORY, "world-origin", "An example module that highlights the center of the world.");
    }

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        Box marker = new Box(BlockPos.ORIGIN);
        marker = marker.stretch(
                scale.get() * marker.getLengthX(),
                scale.get() * marker.getLengthY(),
                scale.get() * marker.getLengthZ()
        );
        event.renderer.box(marker, (Color) color.get(), (Color) color.get(), ShapeMode.Both, 0);
    }
}