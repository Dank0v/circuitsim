package com.circuitsim.network;

import com.circuitsim.block.Controlled2x3Block;
import com.circuitsim.block.TransmissionLineBlock;
import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-bound packet sent by
 * {@link com.circuitsim.screen.TransmissionLineEditScreen} when the player
 * saves a transmission line's configuration. Carries the anchor pos, the mode
 * ({@code "lossless"} or {@code "ltra"}, stored in the modelName slot) and
 * the five mode-interpreted parameters (lossless | lossy): p1 = Z0 | R,
 * p2 = TD | L, p3 = F | G, p4 = NL | C, p5 = — | LEN, each with an optional
 * Parametric variable name, plus the manual netlist index (0 = auto).
 */
public class TransmissionLineUpdatePacket {

    private final BlockPos pos;
    private final String   mode;
    private final double   p1;
    private final String   e1;
    private final double   p2;
    private final String   e2;
    private final double   p3;
    private final String   e3;
    private final double   p4;
    private final String   e4;
    private final double   p5;
    private final String   e5;
    private final int      componentNumber;

    public TransmissionLineUpdatePacket(BlockPos pos, String mode,
                                        double p1, String e1, double p2, String e2,
                                        double p3, String e3, double p4, String e4,
                                        double p5, String e5, int componentNumber) {
        this.pos             = pos;
        this.mode            = mode == null ? "" : mode;
        this.p1              = p1;
        this.e1              = e1 == null ? "" : e1;
        this.p2              = p2;
        this.e2              = e2 == null ? "" : e2;
        this.p3              = p3;
        this.e3              = e3 == null ? "" : e3;
        this.p4              = p4;
        this.e4              = e4 == null ? "" : e4;
        this.p5              = p5;
        this.e5              = e5 == null ? "" : e5;
        this.componentNumber = Math.max(0, componentNumber);
    }

    public TransmissionLineUpdatePacket(FriendlyByteBuf buf) {
        this.pos             = buf.readBlockPos();
        this.mode            = buf.readUtf(16);
        this.p1              = buf.readDouble();
        this.e1              = buf.readUtf(64);
        this.p2              = buf.readDouble();
        this.e2              = buf.readUtf(64);
        this.p3              = buf.readDouble();
        this.e3              = buf.readUtf(64);
        this.p4              = buf.readDouble();
        this.e4              = buf.readUtf(64);
        this.p5              = buf.readDouble();
        this.e5              = buf.readUtf(64);
        this.componentNumber = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(mode == null ? "" : mode, 16);
        buf.writeDouble(p1);
        buf.writeUtf(e1 == null ? "" : e1, 64);
        buf.writeDouble(p2);
        buf.writeUtf(e2 == null ? "" : e2, 64);
        buf.writeDouble(p3);
        buf.writeUtf(e3 == null ? "" : e3, 64);
        buf.writeDouble(p4);
        buf.writeUtf(e4 == null ? "" : e4, 64);
        buf.writeDouble(p5);
        buf.writeUtf(e5 == null ? "" : e5, 64);
        buf.writeVarInt(componentNumber);
    }

    public static TransmissionLineUpdatePacket decode(FriendlyByteBuf buf) {
        return new TransmissionLineUpdatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TransmissionLineBlock)) return;
        if (state.getValue(Controlled2x3Block.CELL_KIND) != Controlled2x3Block.CellKind.ANCHOR) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            cbe.setModelName(mode);
            cbe.setValue(p1);
            cbe.setValueExpr(e1);
            cbe.setWParam(p2);
            cbe.setWExpr(e2);
            cbe.setLParam(p3);
            cbe.setLExpr(e3);
            cbe.setMultParam(p4);
            cbe.setMultExpr(e4);
            cbe.setNfParam(p5);
            cbe.setNfExpr(e5);
            cbe.setComponentNumber(componentNumber);
            cbe.setChanged();
            cbe.syncToClient();
        }
    }
}
