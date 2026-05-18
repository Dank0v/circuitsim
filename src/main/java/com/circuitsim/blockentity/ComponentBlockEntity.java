package com.circuitsim.blockentity;

import com.circuitsim.init.ModBlockEntities;
import com.circuitsim.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class ComponentBlockEntity extends BlockEntity {

    private double value = 0.0;
    private String sourceType = "DC";
    private double frequency = 60.0;
    private String label = "";
    private int    componentNumber = 0;   // 0 = auto, otherwise R<N>/C<N>/etc. in netlist

    // sky130 resistor/mosfet fields
    private String modelName  = "";
    private double wParam     = 1.0;
    private double lParam     = 1.0;
    private double multParam  = 1.0;
    private double nfParam    = 1.0;

    // simulate block PDK settings
    private String pdkName    = "none";
    private String pdkLibPath = "";
    // PSpice (psa) compatibility mode uses .INCLUDE for libraries, and supports
    // multiple library files. Each line is one library path. Stored separately
    // from pdkLibPath so switching between psa and hsa preserves both.
    private String pdkLibPaths = "";
    private String ngBehavior = "hsa";

    // simulate block dialog state
    private String simAnalysis = "OP";
    private String simParam1   = "10";
    private String simParam2   = "1Meg";
    private String simParam3   = "10";

    // commands block: free-form ngspice control commands, one per line
    private String commands    = "";

    // amplifier block: when true, the 7-pin variant (with offset pins) is active.
    // Drives both the netlist subcircuit pin count and the visual model of the
    // OFF1/OFF2 cells.
    private boolean offsetEnabled = false;

    public ComponentBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COMPONENT_BE.get(), pos, state);
    }

    public String getComponentType() {
        Block block = getBlockState().getBlock();
        if (block == ModBlocks.RESISTOR.get())           return "resistor";
        if (block == ModBlocks.CAPACITOR.get())          return "capacitor";
        if (block == ModBlocks.INDUCTOR.get())           return "inductor";
        if (block == ModBlocks.VOLTAGE_SOURCE.get())     return "voltage_source";
        if (block == ModBlocks.VOLTAGE_SOURCE_SIN.get()) return "voltage_source_sin";
        if (block == ModBlocks.CURRENT_SOURCE.get())     return "current_source";
        if (block == ModBlocks.DIODE.get())              return "diode";
        if (block == ModBlocks.PROBE.get())              return "probe";
        if (block == ModBlocks.CURRENT_PROBE.get())      return "current_probe";
        if (block == ModBlocks.SIMULATE.get())           return "simulate";
        if (block == ModBlocks.IC_RESISTOR.get())          return "ic_resistor3";
        if (block == ModBlocks.IC_CAPACITOR.get())         return "ic_capacitor2";
        if (block == ModBlocks.IC_NMOS4.get())             return "ic_nmos4";
        if (block == ModBlocks.IC_PMOS4.get())             return "ic_pmos4";
        if (block == ModBlocks.COMMANDS.get())             return "commands";
        if (block == ModBlocks.AMPLIFIER.get())            return "amplifier";
        if (block == ModBlocks.CCVS.get())                 return "ccvs";
        if (block == ModBlocks.CCCS.get())                 return "cccs";
        if (block == ModBlocks.VCVS.get())                 return "vcvs";
        if (block == ModBlocks.VCCS.get())                 return "vccs";
        return "unknown";
    }

    public double getValue()             { return value; }
    public void setValue(double value)   { this.value = value; setChanged(); }
    public String getSourceType()        { return sourceType; }
    public void setSourceType(String st) { this.sourceType = st; setChanged(); }
    public double getFrequency()         { return frequency; }
    public void setFrequency(double f)   { this.frequency = f; setChanged(); }
    public String getLabel()             { return label; }
    public void setLabel(String label)   { this.label = label; setChanged(); }
    public String getProbeLabel()        { return label; }
    public void setProbeLabel(String l)  { this.label = l; setChanged(); }
    public int getComponentNumber()        { return componentNumber; }
    public void setComponentNumber(int n)  { this.componentNumber = Math.max(0, n); setChanged(); }

    public String getModelName()           { return modelName; }
    public void setModelName(String name)  { this.modelName = name; setChanged(); }
    public double getWParam()              { return wParam; }
    public void setWParam(double w)        { this.wParam = w; setChanged(); }
    public double getLParam()              { return lParam; }
    public void setLParam(double l)        { this.lParam = l; setChanged(); }
    public double getMultParam()           { return multParam; }
    public void setMultParam(double m)     { this.multParam = m; setChanged(); }
    public double getNfParam()             { return nfParam; }
    public void setNfParam(double nf)      { this.nfParam = nf; setChanged(); }
    public String getPdkName()               { return pdkName; }
    public void setPdkName(String name)      { this.pdkName = name; setChanged(); }
    public String getPdkLibPath()            { return pdkLibPath; }
    public void setPdkLibPath(String path)   { this.pdkLibPath = path; setChanged(); }
    public String getPdkLibPaths()           { return pdkLibPaths; }
    public void setPdkLibPaths(String paths) { this.pdkLibPaths = paths == null ? "" : paths; setChanged(); }
    public String getNgBehavior()            { return ngBehavior; }
    public void setNgBehavior(String mode)   { this.ngBehavior = mode; setChanged(); }
    public String getSimAnalysis()           { return simAnalysis; }
    public void setSimAnalysis(String a)     { this.simAnalysis = a; setChanged(); }
    public String getSimParam1()             { return simParam1; }
    public void setSimParam1(String v)       { this.simParam1 = v; setChanged(); }
    public String getSimParam2()             { return simParam2; }
    public void setSimParam2(String v)       { this.simParam2 = v; setChanged(); }
    public String getSimParam3()             { return simParam3; }
    public void setSimParam3(String v)       { this.simParam3 = v; setChanged(); }
    public String getCommands()              { return commands; }
    public void setCommands(String c)        { this.commands = c == null ? "" : c; setChanged(); }
    public boolean isOffsetEnabled()         { return offsetEnabled; }
    public void setOffsetEnabled(boolean e)  { this.offsetEnabled = e; setChanged(); }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("value", value);
        tag.putString("sourceType", sourceType);
        tag.putDouble("frequency", frequency);
        tag.putString("label", label);
        tag.putInt("componentNumber", componentNumber);
        tag.putString("modelName",  modelName);
        tag.putDouble("wParam",     wParam);
        tag.putDouble("lParam",     lParam);
        tag.putDouble("multParam",  multParam);
        tag.putDouble("nfParam",    nfParam);
        tag.putString("pdkName",    pdkName);
        tag.putString("pdkLibPath", pdkLibPath);
        tag.putString("pdkLibPaths", pdkLibPaths);
        tag.putString("ngBehavior", ngBehavior);
        tag.putString("simAnalysis", simAnalysis);
        tag.putString("simParam1",   simParam1);
        tag.putString("simParam2",   simParam2);
        tag.putString("simParam3",   simParam3);
        tag.putString("commands",    commands);
        tag.putBoolean("offsetEnabled", offsetEnabled);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("value"))      value      = tag.getDouble("value");
        if (tag.contains("sourceType")) sourceType = tag.getString("sourceType");
        if (tag.contains("frequency"))  frequency  = tag.getDouble("frequency");
        if (tag.contains("label"))      label      = tag.getString("label");
        if (tag.contains("componentNumber")) componentNumber = tag.getInt("componentNumber");
        if (tag.contains("modelName"))  modelName  = tag.getString("modelName");
        if (tag.contains("wParam"))     wParam     = tag.getDouble("wParam");
        if (tag.contains("lParam"))     lParam     = tag.getDouble("lParam");
        if (tag.contains("multParam"))  multParam  = tag.getDouble("multParam");
        if (tag.contains("nfParam"))    nfParam    = tag.getDouble("nfParam");
        if (tag.contains("pdkName"))     pdkName    = tag.getString("pdkName");
        if (tag.contains("pdkLibPath"))  pdkLibPath = tag.getString("pdkLibPath");
        if (tag.contains("pdkLibPaths")) pdkLibPaths = tag.getString("pdkLibPaths");
        if (tag.contains("ngBehavior"))  ngBehavior = tag.getString("ngBehavior");
        if (tag.contains("simAnalysis")) simAnalysis = tag.getString("simAnalysis");
        if (tag.contains("simParam1"))   simParam1   = tag.getString("simParam1");
        if (tag.contains("simParam2"))   simParam2   = tag.getString("simParam2");
        if (tag.contains("simParam3"))   simParam3   = tag.getString("simParam3");
        if (tag.contains("commands"))    commands    = tag.getString("commands");
        if (tag.contains("offsetEnabled")) offsetEnabled = tag.getBoolean("offsetEnabled");
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.putDouble("value", value);
        tag.putString("sourceType", sourceType);
        tag.putDouble("frequency", frequency);
        tag.putString("label", label);
        tag.putInt("componentNumber", componentNumber);
        tag.putString("modelName",  modelName);
        tag.putDouble("wParam",     wParam);
        tag.putDouble("lParam",     lParam);
        tag.putDouble("multParam",  multParam);
        tag.putDouble("nfParam",    nfParam);
        tag.putString("pdkName",    pdkName);
        tag.putString("pdkLibPath", pdkLibPath);
        tag.putString("pdkLibPaths", pdkLibPaths);
        tag.putString("ngBehavior", ngBehavior);
        tag.putString("simAnalysis", simAnalysis);
        tag.putString("simParam1",   simParam1);
        tag.putString("simParam2",   simParam2);
        tag.putString("simParam3",   simParam3);
        tag.putString("commands",    commands);
        tag.putBoolean("offsetEnabled", offsetEnabled);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    public void syncToClient() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}