package com.circuitsim.network;

import com.circuitsim.block.BaseComponentBlock;
import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

public class ComponentUpdatePacket {

    private final BlockPos pos;
    private final double   value;
    private final String   sourceType;
    private final double   frequency;
    private final String   label;
    private final int      componentNumber;
    // sky130 resistor/mosfet fields
    private final String   modelName;
    private final double   wParam;
    private final double   lParam;
    private final double   multParam;
    private final double   nfParam;
    private final String   pdkName;
    private final boolean  mirrored;
    // Pulse-source parameters. Other component types simply pass through the
    // BE's existing values unchanged — the editor only sends meaningful pulse
    // numbers when the open block is a VoltageSourcePulseBlock.
    private final double   pulseVLow;
    private final double   pulseTr;
    private final double   pulseTf;
    private final double   pulsePw;
    /** When non-empty, the value field is sourced from a Parametric block
     *  defining this variable at sim time. Empty -> use the numeric value. */
    private final String   valueExpr;
    // Per-slot variable names for IC components (W/L/mult/nf). Empty means
    // use the numeric field directly.
    private final String   wExpr;
    private final String   lExpr;
    private final String   multExpr;
    private final String   nfExpr;
    // Voltage source AC magnitude (and its optional Parametric variable name).
    // Other component types ignore these — the editor sends 0 / "" for them.
    private final double   acValue;
    private final String   acValueExpr;
    // Probe "name only" mode. Only meaningful for voltage probes; every other
    // component type sends false.
    private final boolean  probeNoPlot;
    // Resistor "noiseless" mode (emits `noisy=0` on the R line). Only
    // meaningful for plain resistors; every other component type sends false.
    private final boolean  rNoiseless;
    // Probe "subcircuit pin" mode + its ordering. Only meaningful for voltage
    // probes; every other component type sends false / 0.
    private final boolean  subcktPin;
    private final int      subcktPinOrder;

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label) {
        this(pos, value, sourceType, frequency, label, "", 1.0, 1.0, 1.0, 1.0, "none", 0, false);
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  String pdkName) {
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam, 1.0, pdkName, 0, false);
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName) {
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam, nfParam, pdkName, 0, false);
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName, int componentNumber) {
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam, nfParam, pdkName, componentNumber, false);
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName, int componentNumber, boolean mirrored) {
        // Forwards to the full-pulse constructor with neutral pulse defaults
        // (V1=0, TR=TF=1 ns, PW=1 us). Pulse-source edits use the full ctor
        // directly so these are only seen when the BE is something else.
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam,
                nfParam, pdkName, componentNumber, mirrored, 0.0, 1e-9, 1e-9, 1e-6, "");
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName, int componentNumber, boolean mirrored,
                                  double pulseVLow, double pulseTr, double pulseTf, double pulsePw) {
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam,
                nfParam, pdkName, componentNumber, mirrored, pulseVLow, pulseTr, pulseTf, pulsePw, "");
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName, int componentNumber, boolean mirrored,
                                  double pulseVLow, double pulseTr, double pulseTf, double pulsePw,
                                  String valueExpr) {
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam,
                nfParam, pdkName, componentNumber, mirrored, pulseVLow, pulseTr, pulseTf, pulsePw,
                valueExpr, "", "", "", "");
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName, int componentNumber, boolean mirrored,
                                  double pulseVLow, double pulseTr, double pulseTf, double pulsePw,
                                  String valueExpr,
                                  String wExpr, String lExpr, String multExpr, String nfExpr) {
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam,
                nfParam, pdkName, componentNumber, mirrored, pulseVLow, pulseTr, pulseTf, pulsePw,
                valueExpr, wExpr, lExpr, multExpr, nfExpr, 0.0, "");
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName, int componentNumber, boolean mirrored,
                                  double pulseVLow, double pulseTr, double pulseTf, double pulsePw,
                                  String valueExpr,
                                  String wExpr, String lExpr, String multExpr, String nfExpr,
                                  double acValue, String acValueExpr) {
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam,
                nfParam, pdkName, componentNumber, mirrored, pulseVLow, pulseTr, pulseTf, pulsePw,
                valueExpr, wExpr, lExpr, multExpr, nfExpr, acValue, acValueExpr, false);
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName, int componentNumber, boolean mirrored,
                                  double pulseVLow, double pulseTr, double pulseTf, double pulsePw,
                                  String valueExpr,
                                  String wExpr, String lExpr, String multExpr, String nfExpr,
                                  double acValue, String acValueExpr, boolean probeNoPlot) {
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam,
                nfParam, pdkName, componentNumber, mirrored, pulseVLow, pulseTr, pulseTf, pulsePw,
                valueExpr, wExpr, lExpr, multExpr, nfExpr, acValue, acValueExpr, probeNoPlot, false);
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName, int componentNumber, boolean mirrored,
                                  double pulseVLow, double pulseTr, double pulseTf, double pulsePw,
                                  String valueExpr,
                                  String wExpr, String lExpr, String multExpr, String nfExpr,
                                  double acValue, String acValueExpr, boolean probeNoPlot,
                                  boolean rNoiseless) {
        this(pos, value, sourceType, frequency, label, modelName, wParam, lParam, multParam,
                nfParam, pdkName, componentNumber, mirrored, pulseVLow, pulseTr, pulseTf, pulsePw,
                valueExpr, wExpr, lExpr, multExpr, nfExpr, acValue, acValueExpr, probeNoPlot,
                rNoiseless, false, 0);
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam,
                                  double nfParam, String pdkName, int componentNumber, boolean mirrored,
                                  double pulseVLow, double pulseTr, double pulseTf, double pulsePw,
                                  String valueExpr,
                                  String wExpr, String lExpr, String multExpr, String nfExpr,
                                  double acValue, String acValueExpr, boolean probeNoPlot,
                                  boolean rNoiseless, boolean subcktPin, int subcktPinOrder) {
        this.pos             = pos;
        this.value           = value;
        this.sourceType      = sourceType;
        this.frequency       = frequency;
        this.label           = label;
        this.modelName       = modelName;
        this.wParam          = wParam;
        this.lParam          = lParam;
        this.multParam       = multParam;
        this.nfParam         = nfParam;
        this.pdkName         = pdkName;
        this.componentNumber = Math.max(0, componentNumber);
        this.mirrored        = mirrored;
        this.pulseVLow       = pulseVLow;
        this.pulseTr         = pulseTr;
        this.pulseTf         = pulseTf;
        this.pulsePw         = pulsePw;
        this.valueExpr       = valueExpr == null ? "" : valueExpr;
        this.wExpr           = wExpr     == null ? "" : wExpr;
        this.lExpr           = lExpr     == null ? "" : lExpr;
        this.multExpr        = multExpr  == null ? "" : multExpr;
        this.nfExpr          = nfExpr    == null ? "" : nfExpr;
        this.acValue         = acValue;
        this.acValueExpr     = acValueExpr == null ? "" : acValueExpr;
        this.probeNoPlot     = probeNoPlot;
        this.rNoiseless      = rNoiseless;
        this.subcktPin       = subcktPin;
        this.subcktPinOrder  = Math.max(0, subcktPinOrder);
    }

    public ComponentUpdatePacket(FriendlyByteBuf buf) {
        this.pos             = buf.readBlockPos();
        this.value           = buf.readDouble();
        this.sourceType      = buf.readUtf(64);
        this.frequency       = buf.readDouble();
        this.label           = buf.readUtf(256);
        this.modelName       = buf.readUtf(128);
        this.wParam          = buf.readDouble();
        this.lParam          = buf.readDouble();
        this.multParam       = buf.readDouble();
        this.nfParam         = buf.readDouble();
        this.pdkName         = buf.readUtf(32);
        this.componentNumber = buf.readVarInt();
        this.mirrored        = buf.readBoolean();
        this.pulseVLow       = buf.readDouble();
        this.pulseTr         = buf.readDouble();
        this.pulseTf         = buf.readDouble();
        this.pulsePw         = buf.readDouble();
        this.valueExpr       = buf.readUtf(64);
        this.wExpr           = buf.readUtf(64);
        this.lExpr           = buf.readUtf(64);
        this.multExpr        = buf.readUtf(64);
        this.nfExpr          = buf.readUtf(64);
        this.acValue         = buf.readDouble();
        this.acValueExpr     = buf.readUtf(64);
        this.probeNoPlot     = buf.readBoolean();
        this.rNoiseless      = buf.readBoolean();
        this.subcktPin       = buf.readBoolean();
        this.subcktPinOrder  = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeDouble(value);
        buf.writeUtf(sourceType, 64);
        buf.writeDouble(frequency);
        buf.writeUtf(label, 256);
        buf.writeUtf(modelName, 128);
        buf.writeDouble(wParam);
        buf.writeDouble(lParam);
        buf.writeDouble(multParam);
        buf.writeDouble(nfParam);
        buf.writeUtf(pdkName, 32);
        buf.writeVarInt(componentNumber);
        buf.writeBoolean(mirrored);
        buf.writeDouble(pulseVLow);
        buf.writeDouble(pulseTr);
        buf.writeDouble(pulseTf);
        buf.writeDouble(pulsePw);
        buf.writeUtf(valueExpr == null ? "" : valueExpr, 64);
        buf.writeUtf(wExpr     == null ? "" : wExpr,     64);
        buf.writeUtf(lExpr     == null ? "" : lExpr,     64);
        buf.writeUtf(multExpr  == null ? "" : multExpr,  64);
        buf.writeUtf(nfExpr    == null ? "" : nfExpr,    64);
        buf.writeDouble(acValue);
        buf.writeUtf(acValueExpr == null ? "" : acValueExpr, 64);
        buf.writeBoolean(probeNoPlot);
        buf.writeBoolean(rNoiseless);
        buf.writeBoolean(subcktPin);
        buf.writeVarInt(subcktPinOrder);
    }

    public static ComponentUpdatePacket decode(FriendlyByteBuf buf) {
        return new ComponentUpdatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            cbe.setValue(value);
            cbe.setSourceType(sourceType);
            cbe.setFrequency(frequency);
            cbe.setLabel(label);
            cbe.setModelName(modelName);
            cbe.setWParam(wParam);
            cbe.setLParam(lParam);
            cbe.setMultParam(multParam);
            cbe.setNfParam(nfParam);
            cbe.setPdkName(pdkName);
            cbe.setComponentNumber(componentNumber);
            cbe.setPulseVLow(pulseVLow);
            cbe.setPulseTr(pulseTr);
            cbe.setPulseTf(pulseTf);
            cbe.setPulsePw(pulsePw);
            cbe.setValueExpr(valueExpr);
            cbe.setWExpr(wExpr);
            cbe.setLExpr(lExpr);
            cbe.setMultExpr(multExpr);
            cbe.setNfExpr(nfExpr);
            cbe.setAcValue(acValue);
            cbe.setAcValueExpr(acValueExpr);
            cbe.setProbeNoPlot(probeNoPlot);
            cbe.setRNoiseless(rNoiseless);
            cbe.setSubcktPin(subcktPin);
            cbe.setSubcktPinOrder(subcktPinOrder);
            cbe.setChanged();

            BlockState curState = level.getBlockState(pos);
            if (curState.hasProperty(BaseComponentBlock.MIRRORED)
                    && curState.getValue(BaseComponentBlock.MIRRORED) != mirrored) {
                level.setBlock(pos, curState.setValue(BaseComponentBlock.MIRRORED, mirrored), 3);
            }

            cbe.syncToClient();
        }
    }
}
