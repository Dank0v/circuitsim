package com.circuitsim.client.renderer;

import com.circuitsim.block.SubcircuitBlock;
import com.circuitsim.blockentity.SubcircuitBlockEntity;
import com.circuitsim.client.ClientOpData;
import com.circuitsim.client.KeyBindings;
import com.circuitsim.network.OperatingPointPacket;
import com.circuitsim.screen.ComponentEditScreen;
import com.circuitsim.subcircuit.SubcircuitBlueprint;
import com.circuitsim.subcircuit.SubcircuitChip;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders two things over a {@link SubcircuitBlock}:
 * <ul>
 *   <li>the loaded subcircuit's name (billboarded, honouring the label keybind), and</li>
 *   <li>when the subcircuit-OP-projection key (J) is toggled on after an .OP run,
 *       a shrunk, faithful 3D copy of the inner circuit floating above the block,
 *       with each internal device's chosen operating-point params over it.</li>
 * </ul>
 * The block entity lives on the anchor cell (local 0,0); both overlays are
 * centred over the 5×5 footprint.
 */
public class SubcircuitBlockEntityRenderer
    implements BlockEntityRenderer<SubcircuitBlockEntity> {

    /** Scale of one blueprint block in the floating mini-circuit. */
    private static final float MINI_SCALE = 0.18f;
    /** Height (blocks) of the mini-circuit's centre above the block's base. */
    private static final float MINI_HEIGHT = 2.2f;
    /** Don't build the (heavier) projection past this distance, in blocks. */
    private static final double PROJECT_MAX_DIST = 32.0;
    /** Billboard text scale for the per-device OP labels in the projection. */
    private static final float LABEL_SCALE = 0.009f;
    /** Extra gap (blocks) between a device's top and its OP label. */
    private static final float LABEL_GAP = 0.07f;

    public SubcircuitBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(SubcircuitBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        // The floating mini-circuit OP projection is independent of the name-label
        // toggle: it shows only while the J projection mode is active.
        if (ClientOpData.isProjectionActive()) {
            renderProjection(be, poseStack, buffer);
        }

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
        EntityRenderDispatcher camera = Minecraft.getInstance().getEntityRenderDispatcher();

        // Centre the label over the 5×5: the BE is on the anchor at local (0,0).
        double[] c = footprintCentre(be);
        billboard(poseStack, buffer, font, camera, (float) c[0], 1.5f, (float) c[1],
                lines, 0.022f, 0xFFFFFFFF, false);
    }

    // -------------------------------------------------------------------------
    // Floating mini-circuit OP projection
    // -------------------------------------------------------------------------

    private void renderProjection(SubcircuitBlockEntity be, PoseStack poseStack,
                                  MultiBufferSource buffer) {
        BlockPos pos = be.getBlockPos();
        List<OperatingPointPacket.SubDevice> devs = ClientOpData.subDevicesFor(pos);
        if (devs.isEmpty()) return;

        // Skip the heavier block-model render when the player is far away.
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (cam.distanceToSqr(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5)
                > PROJECT_MAX_DIST * PROJECT_MAX_DIST) {
            return;
        }

        Mini mini = miniFor(be);
        if (mini == null) return;

        double[] c = footprintCentre(be);
        float ox = (float) c[0], oz = (float) c[1];

        // 1. The shrunk circuit, centred on (ox, MINI_HEIGHT, oz), axis-aligned so
        //    it's a faithful copy of how the schematic was built.
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        poseStack.pushPose();
        poseStack.translate(ox, MINI_HEIGHT, oz);
        poseStack.scale(MINI_SCALE, MINI_SCALE, MINI_SCALE);
        poseStack.translate(-mini.cx, -mini.cy, -mini.cz);
        for (SubcircuitBlueprint.PreviewBlock b : mini.blocks) {
            poseStack.pushPose();
            poseStack.translate(b.dx(), b.dy(), b.dz());
            dispatcher.renderSingleBlock(b.state(), poseStack, buffer,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        poseStack.popPose();

        // 2. The operating-point labels over each internal device. Drawn in a
        //    separate, unscaled pass so the text size is independent of the model
        //    scale; positions are computed in the same frame as the model above.
        Font font = Minecraft.getInstance().font;
        EntityRenderDispatcher camera = Minecraft.getInstance().getEntityRenderDispatcher();
        for (OperatingPointPacket.SubDevice d : devs) {
            List<String> lines = projectionLines(d);
            if (lines.isEmpty()) continue;
            float lx = ox + MINI_SCALE * (d.dx + 0.5f - mini.cx);
            // Anchor at the device's top face (+1.0) plus a small gap; the label
            // is bottom-anchored so the whole stack grows upward, clear of the block.
            float ly = MINI_HEIGHT + MINI_SCALE * (d.dy + 1.0f - mini.cy) + LABEL_GAP;
            float lz = oz + MINI_SCALE * (d.dz + 0.5f - mini.cz);
            billboard(poseStack, buffer, font, camera, lx, ly, lz, lines,
                    LABEL_SCALE, 0xFF8FE3FF, true);
        }
    }

    /** The chosen-param lines for one mini-device (mirrors the K annotation style). */
    private static List<String> projectionLines(OperatingPointPacket.SubDevice d) {
        List<String> lines = new ArrayList<>();
        for (String param : ClientOpData.chosenParams(d.typeKey)) {
            Double v = d.params.get(param);
            if (v == null) continue;
            lines.add(param + "=" + ComponentEditScreen.formatValue(v));
        }
        return lines;
    }

    // -------------------------------------------------------------------------
    // Cached blueprint preview (parsed once per chip, not per frame)
    // -------------------------------------------------------------------------

    /** Parsed blueprint + its centre, keyed by the chip's identity. */
    private record Mini(String key, List<SubcircuitBlueprint.PreviewBlock> blocks,
                        float cx, float cy, float cz) {}

    private static final Map<BlockPos, Mini> CACHE = new HashMap<>();

    private static Mini miniFor(SubcircuitBlockEntity be) {
        ItemStack chip = be.getChip();
        if (!SubcircuitChip.isPresent(chip)) return null;
        String key = SubcircuitChip.getName(chip) + "#" + SubcircuitChip.getDef(chip).length();
        BlockPos pos = be.getBlockPos();
        Mini cached = CACHE.get(pos);
        if (cached != null && cached.key.equals(key)) return cached;

        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        CompoundTag bp = SubcircuitChip.getBlueprint(chip);
        List<SubcircuitBlueprint.PreviewBlock> blocks =
                SubcircuitBlueprint.previewBlocks(bp, level.holderLookup(Registries.BLOCK));
        if (blocks.isEmpty()) { CACHE.remove(pos); return null; }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (SubcircuitBlueprint.PreviewBlock b : blocks) {
            minX = Math.min(minX, b.dx()); maxX = Math.max(maxX, b.dx());
            minY = Math.min(minY, b.dy()); maxY = Math.max(maxY, b.dy());
            minZ = Math.min(minZ, b.dz()); maxZ = Math.max(maxZ, b.dz());
        }
        Mini m = new Mini(key, blocks,
                (minX + maxX + 1) / 2f, (minY + maxY + 1) / 2f, (minZ + maxZ + 1) / 2f);
        CACHE.put(pos, m);
        return m;
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /** Footprint centre (x, z) relative to the anchor BE's block origin. */
    private static double[] footprintCentre(SubcircuitBlockEntity be) {
        double ox = 0.5, oz = 0.5;
        BlockState state = be.getBlockState();
        if (state.getBlock() instanceof SubcircuitBlock && state.hasProperty(SubcircuitBlock.FACING)) {
            Direction facing = state.getValue(SubcircuitBlock.FACING);
            int[] d = SubcircuitBlock.worldDelta(2, 2, facing);
            ox += d[0];
            oz += d[1];
        }
        return new double[]{ox, oz};
    }

    /**
     * Billboards a stack of text lines toward the camera at a local offset.
     * {@code anchorBottom} grows the stack upward from the anchor (so it sits
     * fully above the point); otherwise it's vertically centred on the anchor.
     */
    private static void billboard(PoseStack poseStack, MultiBufferSource buffer, Font font,
                                  EntityRenderDispatcher camera, float x, float y, float z,
                                  List<String> lines, float scale, int color, boolean anchorBottom) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(camera.cameraOrientation());
        poseStack.scale(-scale, -scale, scale);

        var pose = poseStack.last().pose();
        int n = lines.size();
        int lineSpacing = font.lineHeight + 1;
        float totalHeight = n * font.lineHeight + (n - 1);
        float startY = anchorBottom ? -totalHeight : -(totalHeight / 2f);
        for (int i = 0; i < n; i++) {
            String text = lines.get(i);
            float tx = -(font.width(text) / 2f);
            font.drawInBatch(text, tx, startY + i * lineSpacing, color, false, pose,
                    buffer, Font.DisplayMode.NORMAL, 0x66000000, 0xF000F0);
        }
        poseStack.popPose();
    }

    /** The overlays sit above a 5×5 footprint, so allow them to render from far off. */
    @Override
    public int getViewDistance() {
        return 96;
    }
}
