package com.circuitsim.block;

/**
 * Behavioral (arbitrary) voltage source — ngspice {@code B} device with a
 * {@code V=expr} assignment. Two pins: front (n+) and back (n-). The output
 * voltage is whatever the user's expression evaluates to, e.g.
 * {@code v(in)*v(in)} or {@code 5*sin(6.28*1k*time)}.
 *
 * <p>The expression text is stored in the block entity's {@code modelName}
 * slot (the same free-text carrier CCVS/CCCS use for their controlling source
 * name); {@link com.circuitsim.simulation.NetlistBuilder} emits it verbatim
 * after the {@code V=} keyword.
 */
public class BehavioralVoltageSourceBlock extends BaseComponentBlock {
    public BehavioralVoltageSourceBlock(Properties properties) {
        super(properties);
    }
}
