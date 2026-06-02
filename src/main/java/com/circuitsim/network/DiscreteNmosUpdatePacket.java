package com.circuitsim.network;

import com.circuitsim.block.DiscreteNmosBlock;
import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-bound packet sent by {@link com.circuitsim.screen.DiscreteNmosEditScreen}
 * when the player saves a discrete NMOS's configuration. Carries the model
 * name (must match a {@code .SUBCKT} in an included library) and an optional
 * manual X-index (0 = auto).
 */
public class DiscreteNmosUpdatePacket {

    private final BlockPos pos;
    private final String   modelName;
    private final int      componentNumber;

    public DiscreteNmosUpdatePacket(BlockPos pos, String modelName, int componentNumber) {
        this.pos             = pos;
        this.modelName       = modelName == null ? "" : modelName;
        this.componentNumber = componentNumber;
    }

    public DiscreteNmosUpdatePacket(FriendlyByteBuf buf) {
        this.pos             = buf.readBlockPos();
        this.modelName       = buf.readUtf(64);
        this.componentNumber = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(modelName, 64);
        buf.writeInt(componentNumber);
    }

    public static DiscreteNmosUpdatePacket decode(FriendlyByteBuf buf) {
        return new DiscreteNmosUpdatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DiscreteNmosBlock)) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            cbe.setModelName(modelName);
            cbe.setComponentNumber(componentNumber);
            cbe.setChanged();
            cbe.syncToClient();
        }
    }
}
