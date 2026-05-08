package com.circuitsim.client.renderer;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.init.ModBlocks;
import com.circuitsim.screen.ComponentEditScreen;
import com.circuitsim.simulation.NetlistBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Block;

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
        String line1 = getPrimaryText(be);
        if (line1 == null) return;
        String line2 = getSecondaryText(be); // may be null

        var font = Minecraft.getInstance().font;
        var camera = Minecraft.getInstance().getEntityRenderDispatcher();

        poseStack.pushPose();

        // Centre above the block, floating ~0.15 blocks above the top face
        poseStack.translate(0.5, 1.5, 0.5);

        // Rotate to always face the camera
        poseStack.mulPose(camera.cameraOrientation());

        // Minecraft text is 8px tall; scale so it fits neatly above a 1-block space
        float scale = 0.022f;
        poseStack.scale(-scale, -scale, scale);

        var pose = poseStack.last().pose();

        if (line2 != null) {
            // Two-line layout: line1 on top, line2 below
            float y1 = -font.lineHeight - 1f;
            float y2 = 1f;
            drawLabel(font, line1, y1, pose, buffer, packedLight);
            drawLabel(font, line2, y2, pose, buffer, packedLight);
        } else {
            // Single line, centred vertically
            drawLabel(
                font,
                line1,
                -(font.lineHeight / 2f),
                pose,
                buffer,
                packedLight
            );
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

        // Semi-transparent dark background pill for readability
        font.drawInBatch(
            text,
            x,
            y,
            0xFFFFFFFF,
            false,
            pose,
            buffer,
            Font.DisplayMode.NORMAL,
            0x55000000, // background colour (ARGB)
            0xF000F0
        );
    }

    // -------------------------------------------------------------------------
    // Text content — what to show per block type
    // -------------------------------------------------------------------------

    /** First (or only) line of text. Returns null to suppress rendering entirely. */
    private static String getPrimaryText(ComponentBlockEntity be) {
        Block block = be.getBlockState().getBlock();
        double val = be.getValue();

        if (block == ModBlocks.RESISTOR.get()) {
            return val == 0.0
                ? "?\u03A9"
                : ComponentEditScreen.formatValue(val) + "\u03A9";
        }
        if (block == ModBlocks.CAPACITOR.get()) {
            return val == 0.0
                ? "?F"
                : ComponentEditScreen.formatValue(val) + "F";
        }
        if (block == ModBlocks.INDUCTOR.get()) {
            return val == 0.0
                ? "?H"
                : ComponentEditScreen.formatValue(val) + "H";
        }
        if (block == ModBlocks.VOLTAGE_SOURCE.get()) {
            return val == 0.0
                ? "?V"
                : ComponentEditScreen.formatValue(val) + "V";
        }
        if (block == ModBlocks.CURRENT_SOURCE.get()) {
            return val == 0.0
                ? "?A"
                : ComponentEditScreen.formatValue(val) + "A";
        }
        if (block == ModBlocks.PROBE.get()) {
            String lbl = be.getProbeLabel();
            return (lbl == null || lbl.isEmpty()) ? "V Probe" : lbl;
        }
        if (block == ModBlocks.CURRENT_PROBE.get()) {
            String lbl = be.getProbeLabel();
            return (lbl == null || lbl.isEmpty()) ? "I Probe" : lbl;
        }
        if (block == ModBlocks.DIODE.get()) {
            return "Diode";
        }
        if (block == ModBlocks.PARAMETRIC.get()) {
            String sweep = be.getLabel();
            return (sweep == null || sweep.isEmpty())
                ? "Param: ?"
                : "Param: " + sweep;
        }
        if (block == ModBlocks.VOLTAGE_SOURCE_SIN.get()) {
            return val == 0.0
                ? "?V"
                : ComponentEditScreen.formatValue(val) + "V";
        }
        if (block == ModBlocks.IC_RESISTOR.get()) {
            double r = NetlistBuilder.computeSky130Resistance(
                    be.getWParam(), be.getLParam(), be.getMultParam());
            return ComponentEditScreen.formatValue(r) + "\u03A9";
        }
        // Wire, Ground, Simulate — no label needed
        return null;
    }

    /** Optional second line (e.g. source type for voltage sources). May return null. */
    private static String getSecondaryText(ComponentBlockEntity be) {
        Block block = be.getBlockState().getBlock();
        if (
            block == ModBlocks.VOLTAGE_SOURCE.get() ||
            block == ModBlocks.CURRENT_SOURCE.get()
        ) {
            String st = be.getSourceType();
            return (st == null || st.isEmpty()) ? "DC" : st;
        }
        if (block == ModBlocks.VOLTAGE_SOURCE_SIN.get()) {
            double freq = be.getFrequency();
            return ComponentEditScreen.formatValue(freq) + "Hz";
        }
        if (block == ModBlocks.IC_RESISTOR.get()) {
            String model = be.getModelName();
            return (model == null || model.isEmpty()) ? "res_high_po" : model;
        }
        return null;
    }
}
