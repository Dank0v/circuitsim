package com.circuitsim.network;

import com.circuitsim.block.Controlled2x3Block;
import com.circuitsim.block.TransformerBlock;
import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-bound packet sent by {@link com.circuitsim.screen.TransformerEditScreen}
 * when the player saves a transformer's configuration. Carries the anchor pos,
 * the primary/secondary inductances (wParam/lParam slots), the per-winding
 * series resistances (multParam/nfParam slots, 0 = ideal winding), the
 * coupling coefficient k (value slot), each with an optional Parametric
 * variable name, and the manual K-line netlist index (0 = auto).
 */
public class TransformerUpdatePacket {

    private final BlockPos pos;
    private final double   lp;
    private final String   lpExpr;
    private final double   ls;
    private final String   lsExpr;
    private final double   rp;
    private final String   rpExpr;
    private final double   rs;
    private final String   rsExpr;
    private final double   k;
    private final String   kExpr;
    private final int      componentNumber;

    public TransformerUpdatePacket(BlockPos pos, double lp, String lpExpr,
                                   double ls, String lsExpr,
                                   double rp, String rpExpr,
                                   double rs, String rsExpr,
                                   double k, String kExpr, int componentNumber) {
        this.pos             = pos;
        this.lp              = lp;
        this.lpExpr          = lpExpr == null ? "" : lpExpr;
        this.ls              = ls;
        this.lsExpr          = lsExpr == null ? "" : lsExpr;
        this.rp              = rp;
        this.rpExpr          = rpExpr == null ? "" : rpExpr;
        this.rs              = rs;
        this.rsExpr          = rsExpr == null ? "" : rsExpr;
        this.k               = k;
        this.kExpr           = kExpr == null ? "" : kExpr;
        this.componentNumber = Math.max(0, componentNumber);
    }

    public TransformerUpdatePacket(FriendlyByteBuf buf) {
        this.pos             = buf.readBlockPos();
        this.lp              = buf.readDouble();
        this.lpExpr          = buf.readUtf(64);
        this.ls              = buf.readDouble();
        this.lsExpr          = buf.readUtf(64);
        this.rp              = buf.readDouble();
        this.rpExpr          = buf.readUtf(64);
        this.rs              = buf.readDouble();
        this.rsExpr          = buf.readUtf(64);
        this.k               = buf.readDouble();
        this.kExpr           = buf.readUtf(64);
        this.componentNumber = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeDouble(lp);
        buf.writeUtf(lpExpr == null ? "" : lpExpr, 64);
        buf.writeDouble(ls);
        buf.writeUtf(lsExpr == null ? "" : lsExpr, 64);
        buf.writeDouble(rp);
        buf.writeUtf(rpExpr == null ? "" : rpExpr, 64);
        buf.writeDouble(rs);
        buf.writeUtf(rsExpr == null ? "" : rsExpr, 64);
        buf.writeDouble(k);
        buf.writeUtf(kExpr == null ? "" : kExpr, 64);
        buf.writeVarInt(componentNumber);
    }

    public static TransformerUpdatePacket decode(FriendlyByteBuf buf) {
        return new TransformerUpdatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TransformerBlock)) return;
        if (state.getValue(Controlled2x3Block.CELL_KIND) != Controlled2x3Block.CellKind.ANCHOR) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            cbe.setWParam(lp);
            cbe.setWExpr(lpExpr);
            cbe.setLParam(ls);
            cbe.setLExpr(lsExpr);
            cbe.setMultParam(rp);
            cbe.setMultExpr(rpExpr);
            cbe.setNfParam(rs);
            cbe.setNfExpr(rsExpr);
            cbe.setValue(k);
            cbe.setValueExpr(kExpr);
            cbe.setComponentNumber(componentNumber);
            cbe.setChanged();
            cbe.syncToClient();
        }
    }
}
