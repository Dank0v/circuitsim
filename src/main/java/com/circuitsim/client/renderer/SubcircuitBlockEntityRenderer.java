package com.circuitsim.client.renderer;

import com.circuitsim.block.SubcircuitBlock;
import com.circuitsim.blockentity.SubcircuitBlockEntity;
import com.circuitsim.client.KeyBindings;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Floats the loaded subcircuit's name above the {@link SubcircuitBlock}. The
 * block entity lives on the anchor cell (local 0,0); the label is translated to
 * the centre of the 5×5 footprint and billboarded toward the camera, matching
 * the {@link ComponentBlockEntityRenderer} style and honouring the label-toggle
 * keybind.
 */
public class SubcircuitBlockEntityRenderer
    implements BlockEntityRenderer<SubcircuitBlockEntity> {

    public SubcircuitBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(SubcircuitBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!KeyBindings.labelsVisible) return;

        List<String> lines = new ArrayList<>();
        if (be.hasChip()) {
            lines.add(be.getSubcktName());
            int pins = be.getActivePinCount();
            lines.add("(" + pins + (pins == 1 ? " pin)" : " pins)"));
        } else {
            lines.add("Subcircuit");
            lines.add("(empty)");
        }

        Font font = Minecraft.getInstance().font;
        var camera = Minecraft.getInstance().getEntityRenderDispatcher();

        poseStack.pushPose();

        // Centre the label over the 5×5: the BE is on the anchor at local (0,0).
        double ox = 0.5, oz = 0.5;
        BlockState state = be.getBlockState();
        if (state.getBlock() instanceof SubcircuitBlock && state.hasProperty(SubcircuitBlock.FACING)) {
            Direction facing = state.getValue(SubcircuitBlock.FACING);
            int[] d = SubcircuitBlock.worldDelta(2, 2, facing);
            ox += d[0];
            oz += d[1];
        }
        poseStack.translate(ox, 1.5, oz);

        poseStack.mulPose(camera.cameraOrientation());
        float scale = 0.022f;
        poseStack.scale(-scale, -scale, scale);

        var pose = poseStack.last().pose();
        int n = lines.size();
        int lineSpacing = font.lineHeight + 2;
        float totalHeight = n * font.lineHeight + (n - 1) * 2f;
        float startY = -(totalHeight / 2f);

        for (int i = 0; i < n; i++) {
            String text = lines.get(i);
            float x = -(font.width(text) / 2f);
            font.drawInBatch(text, x, startY + i * lineSpacing, 0xFFFFFFFF, false, pose,
                    buffer, Font.DisplayMode.NORMAL, 0x55000000, 0xF000F0);
        }

        poseStack.popPose();
    }

    /** The label sits above a 5×5 footprint, so allow it to render from far off. */
    @Override
    public int getViewDistance() {
        return 96;
    }
}
