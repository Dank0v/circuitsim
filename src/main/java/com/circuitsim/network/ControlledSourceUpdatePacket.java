package com.circuitsim.network;

import com.circuitsim.block.Controlled2x3Block;
import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-bound packet sent by {@link com.circuitsim.screen.ControlledSourceEditScreen}
 * when the player saves a 2×3 controlled source's configuration. Carries the
 * anchor pos, the gain/transconductance value, and the manual netlist index
 * (0 = auto).
 */
public class ControlledSourceUpdatePacket {

    private final BlockPos pos;
    private final double   value;
    private final int      componentNumber;

    public ControlledSourceUpdatePacket(BlockPos pos, double value, int componentNumber) {
        this.pos             = pos;
        this.value           = value;
        this.componentNumber = Math.max(0, componentNumber);
    }

    public ControlledSourceUpdatePacket(FriendlyByteBuf buf) {
        this.pos             = buf.readBlockPos();
        this.value           = buf.readDouble();
        this.componentNumber = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeDouble(value);
        buf.writeVarInt(componentNumber);
    }

    public static ControlledSourceUpdatePacket decode(FriendlyByteBuf buf) {
        return new ControlledSourceUpdatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof Controlled2x3Block)) return;
        if (state.getValue(Controlled2x3Block.CELL_KIND) != Controlled2x3Block.CellKind.ANCHOR) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            cbe.setValue(value);
            cbe.setComponentNumber(componentNumber);
            cbe.setChanged();
            cbe.syncToClient();
        }
    }
}
