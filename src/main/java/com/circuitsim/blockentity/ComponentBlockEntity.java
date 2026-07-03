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
    // Probe "name only" mode: when true the probe names (and can merge) its net
    // but is excluded from simulation print/plot. Only meaningful for voltage
    // probes; ignored by every other component type.
    private boolean probeNoPlot = false;
    // Probe "subcircuit pin" mode: when true, the net this probe labels is
    // exported as an external terminal when the connected circuit is converted
    // to a subcircuit. subcktPinOrder gives the terminal's position in the
    // .subckt pin list (1-based; 0 = unordered, sorted after numbered pins).
    // Only meaningful for voltage probes.
    private boolean subcktPin      = false;
    private int     subcktPinOrder = 0;
    // Resistor "noiseless" mode: when true the resistor line carries the
    // ngspice instance flag `noisy=0`, excluding its thermal noise from
    // .noise analysis. Only meaningful for plain resistors.
    private boolean rNoiseless = false;
    private int    componentNumber = 0;   // 0 = auto, otherwise R<N>/C<N>/etc. in netlist
    // When non-empty, the component's "value" is sourced at simulation time
    // from a Parametric block defining this variable name. Empty means use
    // the numeric `value` field directly.
    private String valueExpr = "";

    // Voltage source can carry both a DC bias (in `value`) and an AC magnitude
    // (here). The editor exposes both as separate fields; the legacy DC/AC
    // toggle is gone. acValueExpr lets the AC slot reference a Parametric var.
    private double acValue     = 0.0;
    private String acValueExpr = "";
    // Same idea, one per sky130 / IC slot. Only one of (numeric, expr) is
    // meaningful at a time per slot; the editor decides which based on the
    // user input.
    private String wExpr    = "";
    private String lExpr    = "";
    private String multExpr = "";
    private String nfExpr   = "";

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
    private String ngBehavior = "none";

    // simulate block dialog state
    private String simAnalysis = "OP";
    private String simParam1   = "10";
    private String simParam2   = "1Meg";
    private String simParam3   = "10";
    // Per-analysis param sets for the simulate dialog. simParam1/2/3 above is
    // the legacy single set (whatever analysis was active last); these remember
    // AC and TRAN independently so switching tabs or reopening the dialog keeps
    // each analysis's own values. OP/DC/NOISE don't use the param1/2/3 boxes.
    private String simAcParam1   = "10";
    private String simAcParam2   = "1Meg";
    private String simAcParam3   = "10";
    private String simTranParam1 = "1u";
    private String simTranParam2 = "10m";
    private String simTranParam3 = "";
    // .DC analysis config. dcSource1 is the SPICE source name to sweep
    // (e.g. "V1"); start/stop/step are parsed via parseSI at sim time.
    // dc2D enables an outer sweep on dcSource2.
    private String  dcSource1 = "V1";
    private String  dcStart1  = "0";
    private String  dcStop1   = "5";
    private String  dcStep1   = "0.1";
    private boolean dc2D      = false;
    private String  dcSource2 = "";
    private String  dcStart2  = "0";
    private String  dcStop2   = "1";
    private String  dcStep2   = "0.25";
    // Temperature override for the simulate block. Single value ("27") sets
    // the circuit temperature; a sweep spec ("20:40:5" or "20,30,40")
    // triggers a multi-run pass — OP gives a 1D probe-vs-temperature plot,
    // AC/TRAN overlay one curve per temperature on top of their natural axis.
    private String simTemp     = "27";

    // commands block: free-form ngspice control commands, one per line
    private String commands    = "";

    // amplifier block: when true, the 7-pin variant (with offset pins) is active.
    // Drives both the netlist subcircuit pin count and the visual model of the
    // OFF1/OFF2 cells.
    private boolean offsetEnabled = false;

    // PULSE voltage source parameters. value reuses the existing field for V2
    // (high voltage) and frequency reuses for PER (period); the rest live
    // here. Defaults model a typical 5 V / 500 kHz digital pulse train.
    private double pulseVLow = 0.0;     // V1 (initial / low voltage)
    private double pulseTr   = 1e-9;    // rise time   (1 ns)
    private double pulseTf   = 1e-9;    // fall time   (1 ns)
    private double pulsePw   = 1e-6;    // pulse width / time-high (1 us)

    // Voltage-controlled switch (.model SW) parameters. Defaults give a usable
    // logic-level switch out of the box: threshold mid-way through a 0..5 V
    // control swing, no hysteresis, 1 ohm on / 1 TOhm off.
    private double swVt   = 2.5;        // threshold voltage  (V)
    private double swVh   = 0.0;        // hysteresis voltage (V)
    private double swRon  = 1.0;        // on resistance      (Ohm)
    private double swRoff = 1e12;       // off resistance     (Ohm)
    private String swInit = "";         // initial state: "", "on", "off"

    // Simulate block: .noise analysis configuration. All raw UI strings so the
    // dialog round-trips exactly what the player typed.
    private String noiseOut   = "";     // output node (probe label or node id)
    private String noiseRef   = "";     // optional reference node (differential)
    private String noiseSrc   = "";     // input source name (e.g. V1)
    private String noiseSweep = "dec";  // sweep type: dec / lin / oct
    private String noisePts   = "20";   // points (per dec/oct, or total for lin)
    private String noiseFstart = "1";   // sweep start frequency (Hz)
    private String noiseFstop  = "1Meg";// sweep stop frequency (Hz)
    private String noisePtsSum = "";    // optional pts-per-summary (device breakdown)

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
        if (block == ModBlocks.VOLTAGE_SOURCE_PULSE.get()) return "voltage_source_pulse";
        if (block == ModBlocks.CURRENT_SOURCE.get())     return "current_source";
        if (block == ModBlocks.BEHAVIORAL_VOLTAGE_SOURCE.get()) return "behavioral_voltage_source";
        if (block == ModBlocks.BEHAVIORAL_CURRENT_SOURCE.get()) return "behavioral_current_source";
        if (block == ModBlocks.DIODE.get())              return "diode";
        if (block == ModBlocks.PROBE.get())              return "probe";
        if (block == ModBlocks.CURRENT_PROBE.get())      return "current_probe";
        if (block == ModBlocks.LOOP_PROBE.get())         return "loop_probe";
        if (block == ModBlocks.SIMULATE.get())           return "simulate";
        if (block == ModBlocks.IC_RESISTOR.get())          return "ic_resistor3";
        if (block == ModBlocks.IC_CAPACITOR.get())         return "ic_capacitor2";
        if (block == ModBlocks.IC_NMOS4.get())             return "ic_nmos4";
        if (block == ModBlocks.IC_PMOS4.get())             return "ic_pmos4";
        if (block == ModBlocks.COMMANDS.get())             return "commands";
        if (block == ModBlocks.AMPLIFIER.get())            return "amplifier";
        if (block == ModBlocks.DISCRETE_NMOS.get())        return "discrete_nmos";
        if (block == ModBlocks.DISCRETE_PMOS.get())        return "discrete_pmos";
        if (block == ModBlocks.DISCRETE_NPN.get())         return "discrete_npn";
        if (block == ModBlocks.DISCRETE_PNP.get())         return "discrete_pnp";
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
    public boolean isProbeNoPlot()       { return probeNoPlot; }
    public void setProbeNoPlot(boolean b){ this.probeNoPlot = b; setChanged(); }
    public boolean isSubcktPin()         { return subcktPin; }
    public void setSubcktPin(boolean b)  { this.subcktPin = b; setChanged(); }
    public int getSubcktPinOrder()       { return subcktPinOrder; }
    public void setSubcktPinOrder(int n) { this.subcktPinOrder = Math.max(0, n); setChanged(); }
    public boolean isRNoiseless()        { return rNoiseless; }
    public void setRNoiseless(boolean b) { this.rNoiseless = b; setChanged(); }
    public int getComponentNumber()        { return componentNumber; }
    public void setComponentNumber(int n)  { this.componentNumber = Math.max(0, n); setChanged(); }
    public String getValueExpr()           { return valueExpr == null ? "" : valueExpr; }
    public void setValueExpr(String expr)  { this.valueExpr = expr == null ? "" : expr; setChanged(); }
    public double getAcValue()             { return acValue; }
    public void setAcValue(double v)       { this.acValue = v; setChanged(); }
    public String getAcValueExpr()         { return acValueExpr == null ? "" : acValueExpr; }
    public void setAcValueExpr(String e)   { this.acValueExpr = e == null ? "" : e; setChanged(); }
    public String getWExpr()               { return wExpr == null ? "" : wExpr; }
    public void setWExpr(String e)         { this.wExpr = e == null ? "" : e; setChanged(); }
    public String getLExpr()               { return lExpr == null ? "" : lExpr; }
    public void setLExpr(String e)         { this.lExpr = e == null ? "" : e; setChanged(); }
    public String getMultExpr()            { return multExpr == null ? "" : multExpr; }
    public void setMultExpr(String e)      { this.multExpr = e == null ? "" : e; setChanged(); }
    public String getNfExpr()              { return nfExpr == null ? "" : nfExpr; }
    public void setNfExpr(String e)        { this.nfExpr = e == null ? "" : e; setChanged(); }

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
    public String getSimAcParam1()           { return simAcParam1   == null ? "10"   : simAcParam1; }
    public void setSimAcParam1(String v)     { this.simAcParam1   = v == null ? "10"   : v; setChanged(); }
    public String getSimAcParam2()           { return simAcParam2   == null ? "1Meg" : simAcParam2; }
    public void setSimAcParam2(String v)     { this.simAcParam2   = v == null ? "1Meg" : v; setChanged(); }
    public String getSimAcParam3()           { return simAcParam3   == null ? "10"   : simAcParam3; }
    public void setSimAcParam3(String v)     { this.simAcParam3   = v == null ? "10"   : v; setChanged(); }
    public String getSimTranParam1()         { return simTranParam1 == null ? "1u"   : simTranParam1; }
    public void setSimTranParam1(String v)   { this.simTranParam1 = v == null ? "1u"   : v; setChanged(); }
    public String getSimTranParam2()         { return simTranParam2 == null ? "10m"  : simTranParam2; }
    public void setSimTranParam2(String v)   { this.simTranParam2 = v == null ? "10m"  : v; setChanged(); }
    public String getSimTranParam3()         { return simTranParam3 == null ? ""     : simTranParam3; }
    public void setSimTranParam3(String v)   { this.simTranParam3 = v == null ? ""     : v; setChanged(); }
    public String  getDcSource1()             { return dcSource1 == null ? "" : dcSource1; }
    public void    setDcSource1(String v)     { this.dcSource1 = v == null ? "" : v; setChanged(); }
    public String  getDcStart1()              { return dcStart1 == null ? "0" : dcStart1; }
    public void    setDcStart1(String v)      { this.dcStart1 = v == null ? "0" : v; setChanged(); }
    public String  getDcStop1()               { return dcStop1 == null ? "0" : dcStop1; }
    public void    setDcStop1(String v)       { this.dcStop1 = v == null ? "0" : v; setChanged(); }
    public String  getDcStep1()               { return dcStep1 == null ? "0" : dcStep1; }
    public void    setDcStep1(String v)       { this.dcStep1 = v == null ? "0" : v; setChanged(); }
    public boolean getDc2D()                  { return dc2D; }
    public void    setDc2D(boolean v)         { this.dc2D = v; setChanged(); }
    public String  getDcSource2()             { return dcSource2 == null ? "" : dcSource2; }
    public void    setDcSource2(String v)     { this.dcSource2 = v == null ? "" : v; setChanged(); }
    public String  getDcStart2()              { return dcStart2 == null ? "0" : dcStart2; }
    public void    setDcStart2(String v)      { this.dcStart2 = v == null ? "0" : v; setChanged(); }
    public String  getDcStop2()               { return dcStop2 == null ? "0" : dcStop2; }
    public void    setDcStop2(String v)       { this.dcStop2 = v == null ? "0" : v; setChanged(); }
    public String  getDcStep2()               { return dcStep2 == null ? "0" : dcStep2; }
    public void    setDcStep2(String v)       { this.dcStep2 = v == null ? "0" : v; setChanged(); }
    public String getSimTemp()               { return simTemp; }
    public void setSimTemp(String t)         { this.simTemp = (t == null || t.isEmpty()) ? "27" : t; setChanged(); }
    public String getCommands()              { return commands; }
    public void setCommands(String c)        { this.commands = c == null ? "" : c; setChanged(); }
    public boolean isOffsetEnabled()         { return offsetEnabled; }
    public void setOffsetEnabled(boolean e)  { this.offsetEnabled = e; setChanged(); }

    public double getPulseVLow()             { return pulseVLow; }
    public void setPulseVLow(double v)       { this.pulseVLow = v; setChanged(); }
    public double getPulseTr()               { return pulseTr; }
    public void setPulseTr(double v)         { this.pulseTr = v; setChanged(); }
    public double getPulseTf()               { return pulseTf; }
    public void setPulseTf(double v)         { this.pulseTf = v; setChanged(); }
    public double getPulsePw()               { return pulsePw; }
    public void setPulsePw(double v)         { this.pulsePw = v; setChanged(); }

    public double getSwVt()                  { return swVt; }
    public void setSwVt(double v)            { this.swVt = v; setChanged(); }
    public double getSwVh()                  { return swVh; }
    public void setSwVh(double v)            { this.swVh = v; setChanged(); }
    public double getSwRon()                 { return swRon; }
    public void setSwRon(double v)           { this.swRon = v; setChanged(); }
    public double getSwRoff()                { return swRoff; }
    public void setSwRoff(double v)          { this.swRoff = v; setChanged(); }
    public String getSwInit()                { return swInit == null ? "" : swInit; }
    public void setSwInit(String s)          { this.swInit = s == null ? "" : s; setChanged(); }

    public String getNoiseOut()              { return noiseOut == null ? "" : noiseOut; }
    public void setNoiseOut(String v)        { this.noiseOut = v == null ? "" : v; setChanged(); }
    public String getNoiseRef()              { return noiseRef == null ? "" : noiseRef; }
    public void setNoiseRef(String v)        { this.noiseRef = v == null ? "" : v; setChanged(); }
    public String getNoiseSrc()              { return noiseSrc == null ? "" : noiseSrc; }
    public void setNoiseSrc(String v)        { this.noiseSrc = v == null ? "" : v; setChanged(); }
    public String getNoiseSweep()            { return (noiseSweep == null || noiseSweep.isEmpty()) ? "dec" : noiseSweep; }
    public void setNoiseSweep(String v)      { this.noiseSweep = v == null ? "dec" : v; setChanged(); }
    public String getNoisePts()              { return noisePts == null ? "20" : noisePts; }
    public void setNoisePts(String v)        { this.noisePts = v == null ? "20" : v; setChanged(); }
    public String getNoiseFstart()           { return noiseFstart == null ? "1" : noiseFstart; }
    public void setNoiseFstart(String v)     { this.noiseFstart = v == null ? "1" : v; setChanged(); }
    public String getNoiseFstop()            { return noiseFstop == null ? "1Meg" : noiseFstop; }
    public void setNoiseFstop(String v)      { this.noiseFstop = v == null ? "1Meg" : v; setChanged(); }
    public String getNoisePtsSum()           { return noisePtsSum == null ? "" : noisePtsSum; }
    public void setNoisePtsSum(String v)     { this.noisePtsSum = v == null ? "" : v; setChanged(); }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("value", value);
        tag.putString("sourceType", sourceType);
        tag.putDouble("frequency", frequency);
        tag.putString("label", label);
        tag.putBoolean("probeNoPlot", probeNoPlot);
        tag.putBoolean("subcktPin", subcktPin);
        tag.putInt("subcktPinOrder", subcktPinOrder);
        tag.putBoolean("rNoiseless", rNoiseless);
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
        tag.putString("simAcParam1",   simAcParam1);
        tag.putString("simAcParam2",   simAcParam2);
        tag.putString("simAcParam3",   simAcParam3);
        tag.putString("simTranParam1", simTranParam1);
        tag.putString("simTranParam2", simTranParam2);
        tag.putString("simTranParam3", simTranParam3);
        tag.putString("simTemp",     simTemp);
        tag.putString("commands",    commands);
        tag.putBoolean("offsetEnabled", offsetEnabled);
        tag.putDouble("pulseVLow", pulseVLow);
        tag.putDouble("pulseTr",   pulseTr);
        tag.putDouble("pulseTf",   pulseTf);
        tag.putDouble("pulsePw",   pulsePw);
        tag.putDouble("swVt",   swVt);
        tag.putDouble("swVh",   swVh);
        tag.putDouble("swRon",  swRon);
        tag.putDouble("swRoff", swRoff);
        tag.putString("swInit", swInit == null ? "" : swInit);
        tag.putString("noiseOut",    noiseOut    == null ? "" : noiseOut);
        tag.putString("noiseRef",    noiseRef    == null ? "" : noiseRef);
        tag.putString("noiseSrc",    noiseSrc    == null ? "" : noiseSrc);
        tag.putString("noiseSweep",  noiseSweep  == null ? "dec" : noiseSweep);
        tag.putString("noisePts",    noisePts    == null ? "20" : noisePts);
        tag.putString("noiseFstart", noiseFstart == null ? "1" : noiseFstart);
        tag.putString("noiseFstop",  noiseFstop  == null ? "1Meg" : noiseFstop);
        tag.putString("noisePtsSum", noisePtsSum == null ? "" : noisePtsSum);
        tag.putString("valueExpr", valueExpr == null ? "" : valueExpr);
        tag.putString("wExpr",     wExpr     == null ? "" : wExpr);
        tag.putString("lExpr",     lExpr     == null ? "" : lExpr);
        tag.putString("multExpr",  multExpr  == null ? "" : multExpr);
        tag.putString("nfExpr",    nfExpr    == null ? "" : nfExpr);
        tag.putDouble("acValue",   acValue);
        tag.putString("acValueExpr", acValueExpr == null ? "" : acValueExpr);
        tag.putString("dcSource1", dcSource1 == null ? ""  : dcSource1);
        tag.putString("dcStart1",  dcStart1  == null ? "0" : dcStart1);
        tag.putString("dcStop1",   dcStop1   == null ? "0" : dcStop1);
        tag.putString("dcStep1",   dcStep1   == null ? "0" : dcStep1);
        tag.putBoolean("dc2D", dc2D);
        tag.putString("dcSource2", dcSource2 == null ? ""  : dcSource2);
        tag.putString("dcStart2",  dcStart2  == null ? "0" : dcStart2);
        tag.putString("dcStop2",   dcStop2   == null ? "0" : dcStop2);
        tag.putString("dcStep2",   dcStep2   == null ? "0" : dcStep2);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("value"))      value      = tag.getDouble("value");
        if (tag.contains("sourceType")) sourceType = tag.getString("sourceType");
        if (tag.contains("frequency"))  frequency  = tag.getDouble("frequency");
        if (tag.contains("label"))      label      = tag.getString("label");
        if (tag.contains("probeNoPlot")) probeNoPlot = tag.getBoolean("probeNoPlot");
        if (tag.contains("subcktPin"))      subcktPin      = tag.getBoolean("subcktPin");
        if (tag.contains("subcktPinOrder")) subcktPinOrder = tag.getInt("subcktPinOrder");
        if (tag.contains("rNoiseless"))  rNoiseless  = tag.getBoolean("rNoiseless");
        if (tag.contains("componentNumber")) componentNumber = tag.getInt("componentNumber");
        if (tag.contains("modelName"))  modelName  = tag.getString("modelName");
        if (tag.contains("wParam"))     wParam     = tag.getDouble("wParam");
        if (tag.contains("lParam"))     lParam     = tag.getDouble("lParam");
        if (tag.contains("multParam"))  multParam  = tag.getDouble("multParam");
        if (tag.contains("nfParam"))    nfParam    = tag.getDouble("nfParam");
        if (tag.contains("pdkName"))     pdkName    = tag.getString("pdkName");
        if (tag.contains("pdkLibPath"))  pdkLibPath = tag.getString("pdkLibPath");
        if (tag.contains("pdkLibPaths")) pdkLibPaths = tag.getString("pdkLibPaths");
        if (tag.contains("ngBehavior")) {
            ngBehavior = tag.getString("ngBehavior");
            // Old saves may carry compat modes that are no longer offered
            // ("lt" for LTspice, "ki" for Keysight). Quietly downgrade them
            // to "none" so those blocks fall back to strict ngspice instead
            // of leaving a now-invisible mode selected.
            if ("lt".equals(ngBehavior) || "ki".equals(ngBehavior)) ngBehavior = "none";
        }
        if (tag.contains("simAnalysis")) simAnalysis = tag.getString("simAnalysis");
        if (tag.contains("simParam1"))   simParam1   = tag.getString("simParam1");
        if (tag.contains("simParam2"))   simParam2   = tag.getString("simParam2");
        if (tag.contains("simParam3"))   simParam3   = tag.getString("simParam3");
        if (tag.contains("simAcParam1"))   simAcParam1   = tag.getString("simAcParam1");
        if (tag.contains("simAcParam2"))   simAcParam2   = tag.getString("simAcParam2");
        if (tag.contains("simAcParam3"))   simAcParam3   = tag.getString("simAcParam3");
        if (tag.contains("simTranParam1")) simTranParam1 = tag.getString("simTranParam1");
        if (tag.contains("simTranParam2")) simTranParam2 = tag.getString("simTranParam2");
        if (tag.contains("simTranParam3")) simTranParam3 = tag.getString("simTranParam3");
        // Legacy migration: pre-split saves stored one simParam set for whatever
        // analysis was active. Seed the matching per-analysis bucket once (only
        // when the new key is absent) so those values survive the upgrade.
        if (!tag.contains("simAcParam1") && "AC".equals(simAnalysis)) {
            simAcParam1 = simParam1; simAcParam2 = simParam2; simAcParam3 = simParam3;
        }
        if (!tag.contains("simTranParam1") && "TRAN".equals(simAnalysis)) {
            simTranParam1 = simParam1; simTranParam2 = simParam2; simTranParam3 = simParam3;
        }
        if (tag.contains("simTemp"))     simTemp     = tag.getString("simTemp");
        if (tag.contains("commands"))    commands    = tag.getString("commands");
        if (tag.contains("offsetEnabled")) offsetEnabled = tag.getBoolean("offsetEnabled");
        if (tag.contains("pulseVLow"))   pulseVLow   = tag.getDouble("pulseVLow");
        if (tag.contains("pulseTr"))     pulseTr     = tag.getDouble("pulseTr");
        if (tag.contains("pulseTf"))     pulseTf     = tag.getDouble("pulseTf");
        if (tag.contains("pulsePw"))     pulsePw     = tag.getDouble("pulsePw");
        if (tag.contains("swVt"))        swVt        = tag.getDouble("swVt");
        if (tag.contains("swVh"))        swVh        = tag.getDouble("swVh");
        if (tag.contains("swRon"))       swRon       = tag.getDouble("swRon");
        if (tag.contains("swRoff"))      swRoff      = tag.getDouble("swRoff");
        if (tag.contains("swInit"))      swInit      = tag.getString("swInit");
        if (tag.contains("noiseOut"))    noiseOut    = tag.getString("noiseOut");
        if (tag.contains("noiseRef"))    noiseRef    = tag.getString("noiseRef");
        if (tag.contains("noiseSrc"))    noiseSrc    = tag.getString("noiseSrc");
        if (tag.contains("noiseSweep"))  noiseSweep  = tag.getString("noiseSweep");
        if (tag.contains("noisePts"))    noisePts    = tag.getString("noisePts");
        if (tag.contains("noiseFstart")) noiseFstart = tag.getString("noiseFstart");
        if (tag.contains("noiseFstop"))  noiseFstop  = tag.getString("noiseFstop");
        if (tag.contains("noisePtsSum")) noisePtsSum = tag.getString("noisePtsSum");
        if (tag.contains("valueExpr"))   valueExpr   = tag.getString("valueExpr");
        if (tag.contains("wExpr"))       wExpr       = tag.getString("wExpr");
        if (tag.contains("lExpr"))       lExpr       = tag.getString("lExpr");
        if (tag.contains("multExpr"))    multExpr    = tag.getString("multExpr");
        if (tag.contains("nfExpr"))      nfExpr      = tag.getString("nfExpr");
        if (tag.contains("acValue"))     acValue     = tag.getDouble("acValue");
        if (tag.contains("acValueExpr")) acValueExpr = tag.getString("acValueExpr");
        // Legacy migration: pre-rework voltage sources stored a single value
        // plus a sourceType "AC"/"DC" toggle. Move that value into the new
        // dedicated AC slot when the saved data still uses the old shape.
        if (!tag.contains("acValue")
                && "voltage_source".equals(getComponentType())
                && "AC".equalsIgnoreCase(sourceType)) {
            acValue     = value;
            acValueExpr = valueExpr == null ? "" : valueExpr;
            value       = 0.0;
            valueExpr   = "";
        }
        if (tag.contains("dcSource1"))   dcSource1   = tag.getString("dcSource1");
        if (tag.contains("dcStart1"))    dcStart1    = tag.getString("dcStart1");
        if (tag.contains("dcStop1"))     dcStop1     = tag.getString("dcStop1");
        if (tag.contains("dcStep1"))     dcStep1     = tag.getString("dcStep1");
        if (tag.contains("dc2D"))        dc2D        = tag.getBoolean("dc2D");
        if (tag.contains("dcSource2"))   dcSource2   = tag.getString("dcSource2");
        if (tag.contains("dcStart2"))    dcStart2    = tag.getString("dcStart2");
        if (tag.contains("dcStop2"))     dcStop2     = tag.getString("dcStop2");
        if (tag.contains("dcStep2"))     dcStep2     = tag.getString("dcStep2");
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.putDouble("value", value);
        tag.putString("sourceType", sourceType);
        tag.putDouble("frequency", frequency);
        tag.putString("label", label);
        tag.putBoolean("probeNoPlot", probeNoPlot);
        tag.putBoolean("subcktPin", subcktPin);
        tag.putInt("subcktPinOrder", subcktPinOrder);
        tag.putBoolean("rNoiseless", rNoiseless);
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
        tag.putString("simAcParam1",   simAcParam1);
        tag.putString("simAcParam2",   simAcParam2);
        tag.putString("simAcParam3",   simAcParam3);
        tag.putString("simTranParam1", simTranParam1);
        tag.putString("simTranParam2", simTranParam2);
        tag.putString("simTranParam3", simTranParam3);
        tag.putString("simTemp",     simTemp);
        tag.putString("commands",    commands);
        tag.putBoolean("offsetEnabled", offsetEnabled);
        tag.putDouble("pulseVLow", pulseVLow);
        tag.putDouble("pulseTr",   pulseTr);
        tag.putDouble("pulseTf",   pulseTf);
        tag.putDouble("pulsePw",   pulsePw);
        tag.putDouble("swVt",   swVt);
        tag.putDouble("swVh",   swVh);
        tag.putDouble("swRon",  swRon);
        tag.putDouble("swRoff", swRoff);
        tag.putString("swInit", swInit == null ? "" : swInit);
        tag.putString("noiseOut",    noiseOut    == null ? "" : noiseOut);
        tag.putString("noiseRef",    noiseRef    == null ? "" : noiseRef);
        tag.putString("noiseSrc",    noiseSrc    == null ? "" : noiseSrc);
        tag.putString("noiseSweep",  noiseSweep  == null ? "dec" : noiseSweep);
        tag.putString("noisePts",    noisePts    == null ? "20" : noisePts);
        tag.putString("noiseFstart", noiseFstart == null ? "1" : noiseFstart);
        tag.putString("noiseFstop",  noiseFstop  == null ? "1Meg" : noiseFstop);
        tag.putString("noisePtsSum", noisePtsSum == null ? "" : noisePtsSum);
        tag.putString("valueExpr", valueExpr == null ? "" : valueExpr);
        tag.putString("wExpr",     wExpr     == null ? "" : wExpr);
        tag.putString("lExpr",     lExpr     == null ? "" : lExpr);
        tag.putString("multExpr",  multExpr  == null ? "" : multExpr);
        tag.putString("nfExpr",    nfExpr    == null ? "" : nfExpr);
        tag.putDouble("acValue",   acValue);
        tag.putString("acValueExpr", acValueExpr == null ? "" : acValueExpr);
        tag.putString("dcSource1", dcSource1 == null ? ""  : dcSource1);
        tag.putString("dcStart1",  dcStart1  == null ? "0" : dcStart1);
        tag.putString("dcStop1",   dcStop1   == null ? "0" : dcStop1);
        tag.putString("dcStep1",   dcStep1   == null ? "0" : dcStep1);
        tag.putBoolean("dc2D", dc2D);
        tag.putString("dcSource2", dcSource2 == null ? ""  : dcSource2);
        tag.putString("dcStart2",  dcStart2  == null ? "0" : dcStart2);
        tag.putString("dcStop2",   dcStop2   == null ? "0" : dcStop2);
        tag.putString("dcStep2",   dcStep2   == null ? "0" : dcStep2);
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