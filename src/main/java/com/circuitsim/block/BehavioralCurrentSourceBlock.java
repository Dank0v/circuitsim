package com.circuitsim.block;

/**
 * Behavioral (arbitrary) current source — ngspice {@code B} device with an
 * {@code I=expr} assignment. Two pins: front (n+) and back (n-). The current
 * pushed from n+ to n- is whatever the user's expression evaluates to, e.g.
 * {@code v(in)/1k} or {@code 1m*v(ctrl)}.
 *
 * <p>The expression text is stored in the block entity's {@code modelName}
 * slot (the same free-text carrier CCVS/CCCS use for their controlling source
 * name); {@link com.circuitsim.simulation.NetlistBuilder} emits it verbatim
 * after the {@code I=} keyword.
 */
public class BehavioralCurrentSourceBlock extends BaseComponentBlock {
    public BehavioralCurrentSourceBlock(Properties properties) {
        super(properties);
    }
}
