package com.circuitsim.network;

import com.circuitsim.block.VSwitchBlock;
import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-bound packet sent by {@link com.circuitsim.screen.VSwitchEditScreen}
 * when the player saves a voltage-controlled switch's configuration. Carries
 * the four SW model parameters, the optional initial state ("", "on", "off")
 * and an optional manual S-index (0 = auto).
 */
public class VSwitchUpdatePacket {

    private final BlockPos pos;
    private final double   vt;
    private final double   vh;
    private final double   ron;
    private final double   roff;
    private final String   initState;
    private final int      componentNumber;

    public VSwitchUpdatePacket(BlockPos pos, double vt, double vh, double ron, double roff,
                               String initState, int componentNumber) {
        this.pos             = pos;
        this.vt              = vt;
        this.vh              = vh;
        this.ron             = ron;
        this.roff            = roff;
        this.initState       = initState == null ? "" : initState;
        this.componentNumber = componentNumber;
    }

    public VSwitchUpdatePacket(FriendlyByteBuf buf) {
        this.pos             = buf.readBlockPos();
        this.vt              = buf.readDouble();
        this.vh              = buf.readDouble();
        this.ron             = buf.readDouble();
        this.roff            = buf.readDouble();
        this.initState       = buf.readUtf(8);
        this.componentNumber = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeDouble(vt);
        buf.writeDouble(vh);
        buf.writeDouble(ron);
        buf.writeDouble(roff);
        buf.writeUtf(initState, 8);
        buf.writeInt(componentNumber);
    }

    public static VSwitchUpdatePacket decode(FriendlyByteBuf buf) {
        return new VSwitchUpdatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof VSwitchBlock)) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            cbe.setSwVt(vt);
            cbe.setSwVh(vh);
            cbe.setSwRon(ron);
            cbe.setSwRoff(roff);
            cbe.setSwInit("on".equals(initState) || "off".equals(initState) ? initState : "");
            cbe.setComponentNumber(componentNumber);
            cbe.setChanged();
            cbe.syncToClient();
        }
    }
}
