package com.circuitsim.block;

/**
 * Voltage-controlled voltage source (Exxxx). 2×3 multi-block: control pins on
 * the west side, output pins on the east side. The "value" stored on the
 * anchor BE is the dimensionless voltage gain.
 */
public class VcvsBlock extends Controlled2x3Block {
    public VcvsBlock(Properties properties) {
        super(properties);
    }

    @Override public String displayName() { return "VCVS (E)"; }
    @Override public String valueLabel()  { return "Voltage gain"; }
}
