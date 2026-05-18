package com.circuitsim.client.renderer;

import com.circuitsim.block.AmplifierBlock;
import com.circuitsim.block.Controlled2x3Block;
import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.client.KeyBindings;
import com.circuitsim.init.ModBlocks;
import com.circuitsim.screen.ComponentEditScreen;
import com.circuitsim.simulation.NetlistBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

public class ComponentBlockEntityRenderer
    implements BlockEntityRenderer<ComponentBlockEntity>
{

    public ComponentBlockEntityRenderer(
        BlockEntityRendererProvider.Context ctx
    ) {}

    @Override
    public void render(
        ComponentBlockEntity be,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        int packedOverlay
    ) {
        if (!KeyBindings.labelsVisible) return;
        List<String> lines = getLines(be);
        if (lines.isEmpty()) return;

        var font = Minecraft.getInstance().font;
        var camera = Minecraft.getInstance().getEntityRenderDispatcher();

        poseStack.pushPose();

        // For the amplifier, the BE sits on the anchor cell at local (0,0). To
        // float the label over the *centre* of the 5×5 footprint, translate by
        // worldDelta(2,2,facing). Other blocks stay at (0.5, 1.5, 0.5).
        double ox = 0.5, oz = 0.5;
        var state = be.getBlockState();
        if (state.getBlock() == ModBlocks.AMPLIFIER.get()
                && state.hasProperty(AmplifierBlock.FACING)) {
            Direction facing = state.getValue(AmplifierBlock.FACING);
            int[] d = AmplifierBlock.worldDelta(2, 2, facing);
            ox += d[0];
            oz += d[1];
        } else if (state.getBlock() instanceof Controlled2x3Block
                && state.hasProperty(Controlled2x3Block.FACING)) {
            // Anchor is at local (0,1); centre of the 2×3 footprint is (0.5, 1.0).
            // Delta from anchor to centre is (col=0.5, row=0); apply the same
            // facing rotation that worldDelta uses.
            Direction facing = state.getValue(Controlled2x3Block.FACING);
            double dx, dz;
            switch (facing) {
                case EAST  -> { dx =  0.0; dz =  0.5; }
                case SOUTH -> { dx = -0.5; dz =  0.0; }
                case WEST  -> { dx =  0.0; dz = -0.5; }
                default    -> { dx =  0.5; dz =  0.0; } // NORTH
            }
            ox += dx;
            oz += dz;
        }
        poseStack.translate(ox, 1.5, oz);

        // Rotate to always face the camera
        poseStack.mulPose(camera.cameraOrientation());

        // Minecraft text is 8px tall; scale so it fits neatly above a 1-block space
        float scale = 0.022f;
        poseStack.scale(-scale, -scale, scale);

        var pose = poseStack.last().pose();

        int n = lines.size();
        int lineSpacing = font.lineHeight + 2;
        float totalHeight = n * font.lineHeight + (n - 1) * 2f;
        float startY = -(totalHeight / 2f);

        for (int i = 0; i < n; i++) {
            drawLabel(font, lines.get(i), startY + i * lineSpacing, pose, buffer, packedLight);
        }

        poseStack.popPose();
    }

    // -------------------------------------------------------------------------
    // Rendering helper
    // -------------------------------------------------------------------------

    private static void drawLabel(
        Font font,
        String text,
        float y,
        org.joml.Matrix4f pose,
        MultiBufferSource buffer,
        int packedLight
    ) {
        float x = -(font.width(text) / 2f);

        font.drawInBatch(
            text,
            x,
            y,
            0xFFFFFFFF,
            false,
            pose,
            buffer,
            Font.DisplayMode.NORMAL,
            0x55000000,
            0xF000F0
        );
    }

    // -------------------------------------------------------------------------
    // Text content — what to show per block type
    // -------------------------------------------------------------------------

    private static List<String> getLines(ComponentBlockEntity be) {
        Block block = be.getBlockState().getBlock();
        List<String> lines = new ArrayList<>();
        double val = be.getValue();

        if (block == ModBlocks.RESISTOR.get()) {
            lines.add(val == 0.0
                ? "?\u03A9"
                : ComponentEditScreen.formatValue(val) + "\u03A9");
        } else if (block == ModBlocks.CAPACITOR.get()) {
            lines.add(val == 0.0 ? "?F" : ComponentEditScreen.formatValue(val) + "F");
        } else if (block == ModBlocks.INDUCTOR.get()) {
            lines.add(val == 0.0 ? "?H" : ComponentEditScreen.formatValue(val) + "H");
        } else if (block == ModBlocks.VOLTAGE_SOURCE.get()) {
            lines.add(val == 0.0 ? "?V" : ComponentEditScreen.formatValue(val) + "V");
            String st = be.getSourceType();
            lines.add((st == null || st.isEmpty()) ? "DC" : st);
        } else if (block == ModBlocks.VOLTAGE_SOURCE_SIN.get()) {
            lines.add(val == 0.0 ? "?V" : ComponentEditScreen.formatValue(val) + "V");
            lines.add(ComponentEditScreen.formatValue(be.getFrequency()) + "Hz");
        } else if (block == ModBlocks.CURRENT_SOURCE.get()) {
            lines.add(val == 0.0 ? "?A" : ComponentEditScreen.formatValue(val) + "A");
            String st = be.getSourceType();
            lines.add((st == null || st.isEmpty()) ? "DC" : st);
        } else if (block == ModBlocks.PROBE.get()) {
            String lbl = be.getProbeLabel();
            lines.add((lbl == null || lbl.isEmpty()) ? "V Probe" : lbl);
        } else if (block == ModBlocks.CURRENT_PROBE.get()) {
            String lbl = be.getProbeLabel();
            lines.add((lbl == null || lbl.isEmpty()) ? "I Probe" : lbl);
        } else if (block == ModBlocks.DIODE.get()) {
            lines.add("Diode");
        } else if (block == ModBlocks.PARAMETRIC.get()) {
            String sweep = be.getLabel();
            lines.add((sweep == null || sweep.isEmpty()) ? "Param: ?" : "Param: " + sweep);
        } else if (block == ModBlocks.IC_RESISTOR.get()) {
            Double r = NetlistBuilder.computeResistance(
                    be.getPdkName(), be.getModelName(),
                    be.getWParam(), be.getLParam(), be.getMultParam());
            lines.add(r != null
                ? ComponentEditScreen.formatValue(r) + "\u03A9"
                : "?");
            String model = be.getModelName();
            lines.add((model == null || model.isEmpty()) ? "res_high_po" : model);
        } else if (block == ModBlocks.IC_CAPACITOR.get()) {
            Double c = NetlistBuilder.computeCapacitance(
                    be.getPdkName(), be.getModelName(),
                    be.getWParam(), be.getLParam(), be.getMultParam());
            lines.add(c != null
                ? ComponentEditScreen.formatValue(c) + "F"
                : "?");
            String model = be.getModelName();
            lines.add((model == null || model.isEmpty()) ? "cap_mim_m3_1" : model);
        } else if (block == ModBlocks.VCVS.get()) {
            lines.add("VCVS");
            lines.add(val == 0.0 ? "?" : ComponentEditScreen.formatValue(val) + " V/V");
        } else if (block == ModBlocks.VCCS.get()) {
            lines.add("VCCS");
            lines.add(val == 0.0 ? "?" : ComponentEditScreen.formatValue(val) + "S");
        } else if (block == ModBlocks.CCVS.get()) {
            lines.add("CCVS");
            lines.add(val == 0.0
                ? "?\u03A9"
                : ComponentEditScreen.formatValue(val) + "\u03A9");
            String vnam = be.getModelName();
            if (vnam != null && !vnam.isEmpty()) lines.add("ctl: " + vnam);
        } else if (block == ModBlocks.CCCS.get()) {
            lines.add("CCCS");
            lines.add(val == 0.0 ? "?" : ComponentEditScreen.formatValue(val) + " A/A");
            String vnam = be.getModelName();
            if (vnam != null && !vnam.isEmpty()) lines.add("ctl: " + vnam);
        } else if (block == ModBlocks.AMPLIFIER.get()) {
            // Amp BE only exists on the anchor cell; show the subcircuit model name.
            String model = be.getLabel();
            lines.add((model == null || model.isEmpty()) ? "Amplifier" : model);
        } else if (block == ModBlocks.IC_NMOS4.get() || block == ModBlocks.IC_PMOS4.get()) {
            boolean isNmos     = block == ModBlocks.IC_NMOS4.get();
            String defaultModel = isNmos ? "nfet_01v8" : "pfet_01v8";
            String model        = be.getModelName();
            double w    = be.getWParam();
            double l    = be.getLParam();
            double mult = be.getMultParam();
            int    nf   = (int) Math.max(1, Math.round(be.getNfParam()));
            lines.add((model == null || model.isEmpty()) ? defaultModel : model);
            lines.add("W: " + ComponentEditScreen.trimTrailingZeros(String.format("%.6f", w)) + "u");
            lines.add("L: " + ComponentEditScreen.trimTrailingZeros(String.format("%.6f", l)) + "u");
            lines.add("mult: " + ComponentEditScreen.trimTrailingZeros(String.format("%.6f", mult)));
            lines.add("NF: " + nf);
        }

        return lines;
    }
}
