package com.circuitsim.network;

import com.circuitsim.block.AmplifierBlock;
import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-bound packet sent by {@link com.circuitsim.screen.AmplifierEditScreen}
 * when the player saves an amplifier's configuration. Carries:
 * <ul>
 *   <li>{@code pos} — anchor cell of the 5×5 amplifier
 *   <li>{@code modelName} — subcircuit name (must match a {@code .SUBCKT} in the
 *       included .lib / .INCLUDE file)
 *   <li>{@code componentNumber} — manual X-index, or 0 for auto
 *   <li>{@code offsetEnabled} — true for the 7-pin variant
 * </ul>
 */
public class AmplifierUpdatePacket {

    private final BlockPos pos;
    private final String   modelName;
    private final int      componentNumber;
    private final boolean  offsetEnabled;
    /** Vertical mirror — input and supply pins swap rails. */
    private final boolean  mirrored;

    public AmplifierUpdatePacket(BlockPos pos, String modelName, int componentNumber, boolean offsetEnabled) {
        this(pos, modelName, componentNumber, offsetEnabled, false);
    }

    public AmplifierUpdatePacket(BlockPos pos, String modelName, int componentNumber,
                                  boolean offsetEnabled, boolean mirrored) {
        this.pos             = pos;
        this.modelName       = modelName == null ? "" : modelName;
        this.componentNumber = componentNumber;
        this.offsetEnabled   = offsetEnabled;
        this.mirrored        = mirrored;
    }

    public AmplifierUpdatePacket(FriendlyByteBuf buf) {
        this.pos             = buf.readBlockPos();
        this.modelName       = buf.readUtf(64);
        this.componentNumber = buf.readInt();
        this.offsetEnabled   = buf.readBoolean();
        this.mirrored        = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(modelName, 64);
        buf.writeInt(componentNumber);
        buf.writeBoolean(offsetEnabled);
        buf.writeBoolean(mirrored);
    }

    public static AmplifierUpdatePacket decode(FriendlyByteBuf buf) {
        return new AmplifierUpdatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AmplifierBlock)) return;
        // Only the anchor carries the BE; reject updates aimed at non-anchor cells.
        if (state.getValue(AmplifierBlock.CELL_KIND) != AmplifierBlock.CellKind.ANCHOR) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            cbe.setLabel(modelName);                  // label field stores subcircuit model name
            cbe.setComponentNumber(componentNumber);
            cbe.setOffsetEnabled(offsetEnabled);
            cbe.setChanged();
            cbe.syncToClient();
        }

        Direction facing = state.getValue(AmplifierBlock.FACING);
        // Apply mirror first so the kindFor lookup inside applyOffsetToggle
        // sees the post-mirror layout when deciding OFF1/OFF2 placement.
        AmplifierBlock.applyMirrorToggle(level, pos, facing, mirrored);
        AmplifierBlock.applyOffsetToggle(level, pos, facing, offsetEnabled);
    }
}
