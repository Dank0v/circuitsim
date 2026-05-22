package com.circuitsim.block;

/**
 * Pulse voltage source — emits ngspice's {@code PULSE(V1 V2 TD TR TF PW PER)}
 * stimulus during transient analysis. Six user-editable parameters:
 *
 * <ul>
 *   <li>V2 (high voltage)   — stored in BE {@code value}</li>
 *   <li>V1 (low voltage)    — stored in BE {@code pulseVLow}</li>
 *   <li>PER (period)        — stored in BE {@code frequency} (reused)</li>
 *   <li>PW (pulse width / time-high) — BE {@code pulsePw}</li>
 *   <li>TR (rise time)      — BE {@code pulseTr}</li>
 *   <li>TF (fall time)      — BE {@code pulseTf}</li>
 * </ul>
 *
 * <p>{@code TD} (delay) is fixed at 0 and {@code NP} (repeat count) is omitted
 * so the pulse train runs indefinitely. For .op / .ac analyses the source is
 * treated as DC at the initial value V1 (no AC component) — matching the
 * standard SPICE convention.
 */
public class VoltageSourcePulseBlock extends BaseComponentBlock {
    public VoltageSourcePulseBlock(Properties properties) {
        super(properties);
    }
}
