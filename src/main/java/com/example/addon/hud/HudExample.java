package com.example.addon.hud;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class HudExample extends HudElement {
    public static final HudElementInfo<HudExample> INFO = new HudElementInfo(AddonTemplate.HUD_GROUP, "example", "HUD element example.", HudExample::new);

    public HudExample() {
        super(INFO);
    }

    public void render(HudRenderer renderer) {
        this.setSize(renderer.textWidth("Example element", true), renderer.textHeight(true));
        renderer.quad((double)this.x, (double)this.y, (double)this.getWidth(), (double)this.getHeight(), Color.LIGHT_GRAY);
        renderer.text("Example element", (double)this.x, (double)this.y, Color.WHITE, true);
    }
}
