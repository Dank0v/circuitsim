package com.circuitsim.block;

/**
 * Current-controlled current source (Fxxxx).
 * Two pins: front (n+) and back (n-). Output current equals
 * {@code gain × I(vnam)} where {@code vnam} is a separately-placed voltage
 * source (often a 0V current-sense), referenced by the BE's {@code modelName}
 * field.
 */
public class CccsBlock extends BaseComponentBlock {
    public CccsBlock(Properties properties) {
        super(properties);
    }
}
