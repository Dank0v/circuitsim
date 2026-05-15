package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.CommandsUpdatePacket;
import com.circuitsim.network.ModMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Editor for the Commands block. Shows a multi-line text box where each line
 * is inserted verbatim into the {@code .control} block of the simulation
 * netlist. Example contents:
 * <pre>
 *   print @m.xm1.msky130_fd_pr__nfet_01v8[gm]
 *   print @m.xm1.msky130_fd_pr__nfet_01v8[id]
 * </pre>
 */
public class CommandsEditScreen extends Screen {

    private final BlockPos pos;
    private MultiLineEditBox editBox;

    private static final int W = 360;
    private static final int H = 290;

    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int HINT   = 0xFFAAAAAA;

    public CommandsEditScreen(BlockPos pos) {
        super(Component.literal("ngspice Commands"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();

        int px = (width - W) / 2;
        int py = (height - H) / 2;

        String saved = "";
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            saved = cbe.getCommands();
        }

        int boxX = px + 14;
        int boxY = py + 60;
        int boxW = W - 28;
        int boxH = H - 110;

        editBox = new MultiLineEditBox(
                Minecraft.getInstance().font,
                boxX, boxY, boxW, boxH,
                Component.literal("e.g.\nprint @m.xm1.msky130_fd_pr__nfet_01v8[gm]\nplot vdiff = v(out) - v(in)\nplot gain = db(v(out)/v(in))"),
                Component.literal("commands")
        );
        editBox.setCharacterLimit(8000);
        editBox.setValue(saved);
        addRenderableWidget(editBox);
        setInitialFocus(editBox);

        addRenderableWidget(
                Button.builder(Component.literal("Save"), b -> { sendPacket(); onClose(); })
                        .bounds(px + 20, py + H - 28, 110, 20).build()
        );
        addRenderableWidget(
                Button.builder(Component.literal("Cancel"), b -> onClose())
                        .bounds(px + W - 130, py + H - 28, 110, 20).build()
        );
    }

    private void sendPacket() {
        ModMessages.sendToServer(new CommandsUpdatePacket(pos, editBox.getValue()));
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
        g.drawCenteredString(f, "ngspice Commands", width / 2, py + 7, TITLE);
        int hintW = W - 28;
        var hint = net.minecraft.network.chat.Component.literal(
                "One command per line in .control. Use 'plot NAME = EXPR' "
                        + "(or 'plot NAME[unit] = EXPR') to graph an expression.");
        var lines = f.split(hint, hintW);
        int hintY = py + 30;
        for (var line : lines) {
            g.drawString(f, line, px + 14, hintY, HINT, false);
            hintY += f.lineHeight;
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return true; }
}
