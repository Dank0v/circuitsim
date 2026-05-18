package com.circuitsim.block;

/**
 * Current-controlled voltage source (Hxxxx).
 * Two pins: front (n+) and back (n-). Output voltage equals
 * {@code transresistance × I(vnam)} where {@code vnam} is a separately-placed
 * voltage source (often a 0V current-sense), referenced by the BE's
 * {@code modelName} field.
 */
public class CcvsBlock extends BaseComponentBlock {
    public CcvsBlock(Properties properties) {
        super(properties);
    }
}
