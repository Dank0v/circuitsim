package com.circuitsim.network;

import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server-bound packet sent by {@link com.circuitsim.screen.CommandsEditScreen}
 * when the player saves the contents of a Commands block.
 */
public class CommandsUpdatePacket {

    private static final int MAX_LEN = 8192;

    private final BlockPos pos;
    private final String   commands;

    public CommandsUpdatePacket(BlockPos pos, String commands) {
        this.pos      = pos;
        this.commands = commands == null ? "" : commands;
    }

    public CommandsUpdatePacket(FriendlyByteBuf buf) {
        this.pos      = buf.readBlockPos();
        this.commands = buf.readUtf(MAX_LEN);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(commands, MAX_LEN);
    }

    public static CommandsUpdatePacket decode(FriendlyByteBuf buf) {
        return new CommandsUpdatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            cbe.setCommands(commands);
            cbe.setChanged();
            cbe.syncToClient();
        }
    }
}
