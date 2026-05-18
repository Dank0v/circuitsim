package com.circuitsim.block;

/**
 * Voltage-controlled current source (Gxxxx). 2×3 multi-block: control pins on
 * the west side, output pins on the east side. The "value" stored on the
 * anchor BE is the transconductance in siemens (mhos).
 */
public class VccsBlock extends Controlled2x3Block {
    public VccsBlock(Properties properties) {
        super(properties);
    }

    @Override public String displayName() { return "VCCS (G)"; }
    @Override public String valueLabel()  { return "Transconductance (S)"; }
}
