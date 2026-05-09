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

    // sky130 resistor fields
    private String modelName  = "";
    private double wParam     = 1.0;
    private double lParam     = 1.0;
    private double multParam  = 1.0;

    // simulate block PDK settings
    private String pdkName    = "none";
    private String pdkLibPath = "";
    private String ngBehavior = "hsa";

    // simulate block dialog state
    private String simAnalysis = "OP";
    private String simParam1   = "10";
    private String simParam2   = "1Meg";
    private String simParam3   = "10";

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
        if (block == ModBlocks.IC_RESISTOR.get())          return "ic_resistor";
        if (block == ModBlocks.IC_CAPACITOR.get())         return "ic_capacitor";
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

    public String getModelName()           { return modelName; }
    public void setModelName(String name)  { this.modelName = name; setChanged(); }
    public double getWParam()              { return wParam; }
    public void setWParam(double w)        { this.wParam = w; setChanged(); }
    public double getLParam()              { return lParam; }
    public void setLParam(double l)        { this.lParam = l; setChanged(); }
    public double getMultParam()           { return multParam; }
    public void setMultParam(double m)     { this.multParam = m; setChanged(); }
    public String getPdkName()               { return pdkName; }
    public void setPdkName(String name)      { this.pdkName = name; setChanged(); }
    public String getPdkLibPath()            { return pdkLibPath; }
    public void setPdkLibPath(String path)   { this.pdkLibPath = path; setChanged(); }
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

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("value", value);
        tag.putString("sourceType", sourceType);
        tag.putDouble("frequency", frequency);
        tag.putString("label", label);
        tag.putString("modelName",  modelName);
        tag.putDouble("wParam",     wParam);
        tag.putDouble("lParam",     lParam);
        tag.putDouble("multParam",  multParam);
        tag.putString("pdkName",    pdkName);
        tag.putString("pdkLibPath", pdkLibPath);
        tag.putString("ngBehavior", ngBehavior);
        tag.putString("simAnalysis", simAnalysis);
        tag.putString("simParam1",   simParam1);
        tag.putString("simParam2",   simParam2);
        tag.putString("simParam3",   simParam3);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("value"))      value      = tag.getDouble("value");
        if (tag.contains("sourceType")) sourceType = tag.getString("sourceType");
        if (tag.contains("frequency"))  frequency  = tag.getDouble("frequency");
        if (tag.contains("label"))      label      = tag.getString("label");
        if (tag.contains("modelName"))  modelName  = tag.getString("modelName");
        if (tag.contains("wParam"))     wParam     = tag.getDouble("wParam");
        if (tag.contains("lParam"))     lParam     = tag.getDouble("lParam");
        if (tag.contains("multParam"))  multParam  = tag.getDouble("multParam");
        if (tag.contains("pdkName"))     pdkName    = tag.getString("pdkName");
        if (tag.contains("pdkLibPath"))  pdkLibPath = tag.getString("pdkLibPath");
        if (tag.contains("ngBehavior"))  ngBehavior = tag.getString("ngBehavior");
        if (tag.contains("simAnalysis")) simAnalysis = tag.getString("simAnalysis");
        if (tag.contains("simParam1"))   simParam1   = tag.getString("simParam1");
        if (tag.contains("simParam2"))   simParam2   = tag.getString("simParam2");
        if (tag.contains("simParam3"))   simParam3   = tag.getString("simParam3");
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.putDouble("value", value);
        tag.putString("sourceType", sourceType);
        tag.putDouble("frequency", frequency);
        tag.putString("label", label);
        tag.putString("modelName",  modelName);
        tag.putDouble("wParam",     wParam);
        tag.putDouble("lParam",     lParam);
        tag.putDouble("multParam",  multParam);
        tag.putString("pdkName",    pdkName);
        tag.putString("pdkLibPath", pdkLibPath);
        tag.putString("ngBehavior", ngBehavior);
        tag.putString("simAnalysis", simAnalysis);
        tag.putString("simParam1",   simParam1);
        tag.putString("simParam2",   simParam2);
        tag.putString("simParam3",   simParam3);
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