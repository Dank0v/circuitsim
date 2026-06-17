package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.CommandsUpdatePacket;
import com.circuitsim.network.ModMessages;
import com.circuitsim.simulation.ParamSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * Editor for the Param block. One large multi-line text box; each non-empty
 * line declares one variable as {@code name = value} (parentheses around the
 * whole declaration are accepted too):
 * <ul>
 *   <li><b>Scalar</b> ({@code Rload = 1k}) — becomes a {@code .param} line and
 *       is substituted into every component slot referencing the name.</li>
 *   <li><b>Range sweep</b> ({@code Rload = 1:5:1}) or <b>list sweep</b>
 *       ({@code Rload = 1,2,3,4,5}) — runs the analysis once per value and
 *       overlays the curves. At most ONE variable may sweep.</li>
 * </ul>
 * The text lives in the BE's {@code commands} slot (reusing
 * {@link CommandsUpdatePacket}); the legacy single-variable {@code label}
 * spec from old saves is migrated into the box on first open.
 */
public class ParametricEditScreen extends Screen {

    private final BlockPos pos;
    private MultiLineEditBox textBox;
    private List<String> errorMessages = List.of();

    private static final int W = 340;
    private static final int H = 300;

    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int HINT   = 0xFFAAAAAA;
    private static final int ERROR  = 0xFFFF6060;

    private static final int Y_BOX   = 30;
    private static final int H_BOX   = 150;
    private static final int Y_TIP   = Y_BOX + H_BOX + 14;

    public ParametricEditScreen(BlockPos pos) {
        super(Component.literal("Param Block"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();

        String saved = "";
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            saved = cbe.getCommands();
            // Old saves stored a single "name=values" spec in the label slot;
            // surface it as the first line so nothing silently disappears.
            if (saved.isBlank()) {
                String[] legacy = parseSpec(cbe.getLabel());
                if (!legacy[0].isEmpty()) saved = legacy[0] + " = " + legacy[1];
            }
        }

        int px = (width - W) / 2;
        int py = (height - H) / 2;

        textBox = new MultiLineEditBox(
                Minecraft.getInstance().font,
                px + 14, py + Y_BOX, W - 28, H_BOX,
                Component.literal("Rload = 1k\nWn = 0.5u"),
                Component.literal("variables"));
        textBox.setCharacterLimit(4000);
        textBox.setValue(saved);
        addRenderableWidget(textBox);
        setInitialFocus(textBox);

        addRenderableWidget(
                Button.builder(Component.literal("Save"), b -> { if (save()) onClose(); })
                        .bounds(px + 20, py + H - 28, 140, 20).build()
        );
        addRenderableWidget(
                Button.builder(Component.literal("Cancel"), b -> onClose())
                        .bounds(px + W - 160, py + H - 28, 140, 20).build()
        );
    }

    private boolean save() {
        String text = textBox.getValue();
        ParamSpec.ParseResult parsed = ParamSpec.parse(text);
        if (!parsed.ok()) {
            errorMessages = parsed.errors.size() > 2
                    ? parsed.errors.subList(0, 2)
                    : parsed.errors;
            return false;
        }
        errorMessages = List.of();
        ModMessages.sendToServer(new CommandsUpdatePacket(pos, text));
        return true;
    }

    /**
     * Legacy spec splitter — returns {@code [name, values]} from the old
     * single-variable {@code "name=values"} label format, empty strings if
     * not set. Still used for old-save migration here and in the extractor.
     */
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
        g.drawCenteredString(f, "Param Block", width / 2, py + 7, TITLE);

        int y = py + Y_TIP;
        g.drawString(f, "One variable per line:  name = value",          px + 14, y,      HINT, false);
        g.drawString(f, "  Scalar:  Rload = 1k",                         px + 14, y + 11, HINT, false);
        g.drawString(f, "  Sweep range (start:stop:step):  Rload = 1:5:1", px + 14, y + 22, HINT, false);
        g.drawString(f, "  Sweep list:  Rload = 1,2,3,4,5",              px + 14, y + 33, HINT, false);
        g.drawString(f, "Only ONE variable may be swept at a time.",     px + 14, y + 44, HINT, false);

        int errY = py + Y_TIP + 58;
        for (String err : errorMessages) {
            g.drawString(f, ellipsize(f, err, W - 28), px + 14, errY, ERROR, false);
            errY += 11;
        }
    }

    private static String ellipsize(net.minecraft.client.gui.Font f, String s, int maxPx) {
        if (f.width(s) <= maxPx) return s;
        while (!s.isEmpty() && f.width(s + "...") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return true; }
}
