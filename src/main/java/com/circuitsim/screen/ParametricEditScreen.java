package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.ComponentUpdatePacket;
import com.circuitsim.network.ModMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Editor for the Parametric block. Two fields:
 * <ul>
 *   <li><b>Variable name</b> — e.g. {@code Rs}, {@code Ws}. Any component whose
 *       value field is set to this name receives the swept value.</li>
 *   <li><b>Values</b> — a single value (constant define) or a sweep spec
 *       (comma list like {@code 1k,2k,5k} or range {@code 100:1k:100}).</li>
 * </ul>
 * Stored together in the BE's {@code label} slot as {@code name=values}.
 */
public class ParametricEditScreen extends Screen {

    private final BlockPos pos;
    private EditBox nameField;
    private EditBox valuesField;
    private String  errorMessage = "";

    private static final int W = 280;
    private static final int H = 180;

    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int LABEL  = 0xFFFFFFFF;
    private static final int HINT   = 0xFFAAAAAA;
    private static final int ERROR  = 0xFFFF6060;

    public ParametricEditScreen(BlockPos pos) {
        super(Component.literal("Parametric Variable"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();

        String storedName = "";
        String storedValues = "";
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            String[] parsed = parseSpec(cbe.getLabel());
            storedName   = parsed[0];
            storedValues = parsed[1];
        }

        int px = (width - W) / 2;
        int py = (height - H) / 2;

        nameField = new EditBox(Minecraft.getInstance().font,
                px + 14, py + 50, W - 28, 18, Component.literal("name"));
        nameField.setMaxLength(64);
        nameField.setValue(storedName);
        addRenderableWidget(nameField);

        valuesField = new EditBox(Minecraft.getInstance().font,
                px + 14, py + 100, W - 28, 18, Component.literal("values"));
        valuesField.setMaxLength(256);
        valuesField.setValue(storedValues);
        addRenderableWidget(valuesField);

        setInitialFocus(nameField);

        addRenderableWidget(
                Button.builder(Component.literal("Save"), b -> { if (save()) onClose(); })
                        .bounds(px + 20, py + H - 28, 110, 20).build()
        );
        addRenderableWidget(
                Button.builder(Component.literal("Cancel"), b -> onClose())
                        .bounds(px + W - 130, py + H - 28, 110, 20).build()
        );
    }

    private boolean save() {
        errorMessage = "";
        String name   = nameField.getValue().trim();
        String values = valuesField.getValue().trim();

        if (!name.isEmpty() && !ComponentEditScreen.isIdentifier(name)) {
            errorMessage = "Variable name must be a plain identifier";
            return false;
        }

        String spec = name.isEmpty() ? "" : (name + "=" + values);

        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        double curValue = 0.0;
        String curSrc   = "DC";
        double curFreq  = 0.0;
        if (be instanceof ComponentBlockEntity cbe) {
            curValue = cbe.getValue();
            curSrc   = cbe.getSourceType();
            curFreq  = cbe.getFrequency();
        }
        ModMessages.sendToServer(new ComponentUpdatePacket(
                pos, curValue, curSrc, curFreq, spec));
        return true;
    }

    /** Returns [name, values]. Empty strings if not set. */
    public static String[] parseSpec(String raw) {
        if (raw == null) return new String[]{"", ""};
        int idx = raw.indexOf('=');
        if (idx < 0) return new String[]{"", ""};
        String name   = raw.substring(0, idx).trim();
        String values = raw.substring(idx + 1).trim();
        return new String[]{name, values};
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);

        int px = (width - W) / 2;
        int py = (height - H) / 2;

        g.fill(px, py, px + W, py + H, BG);
        // border
        g.fill(px,             py,             px + W,         py + 2,     BORDER);
        g.fill(px,             py + H - 2,     px + W,         py + H,     BORDER);
        g.fill(px,             py,             px + 2,         py + H,     BORDER);
        g.fill(px + W - 2,     py,             px + W,         py + H,     BORDER);
        g.fill(px + 2,         py + 22,        px + W - 2,     py + 23,    0xFF444444);

        super.render(g, mx, my, pt);

        var f = Minecraft.getInstance().font;
        g.drawCenteredString(f, "Parametric Variable", width / 2, py + 7, TITLE);

        g.drawString(f, "Variable name:",         px + 14, py + 38,  LABEL, false);
        g.drawString(f, "Values (single or sweep):", px + 14, py + 88,  LABEL, false);
        g.drawString(f, "Examples: 1k    1k,2k,5k    100:1k:100", px + 14, py + 124, HINT, false);

        if (!errorMessage.isEmpty()) {
            g.drawCenteredString(f, errorMessage, width / 2, py + H - 42, ERROR);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return true; }
}
