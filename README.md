# CircuitSim — A Minecraft Circuit Simulation Mod

> **This mod was built entirely with AI assistance.**

CircuitSim is a Minecraft Forge mod for version **1.20.1** that lets you build and simulate real electronic circuits inside the game. Place component blocks in the world, connect them with wires, and right-click the **Simulate Block** to run a full SPICE simulation powered by [ngspice](https://ngspice.sourceforge.io/).

![Non-inverting amplifier](gifs/non_inverting.gif)

![Miller amplifier](gifs/miller_amp.gif)

![MOSFET output characteristics](gifs/mosfet_output_char.gif)

---
 
## Features

### Passive & basic components
- **Resistor, Capacitor, Inductor**
- **Voltage sources:** DC, sinusoidal (SIN), pulse (PULSE)
- **Current source**
- **Diode**
- **VC Switch** — voltage-controlled switch (ngspice `S` device, `SW` model)
- **Wire** and **Ground**

### Controlled (dependent) sources
- **VCVS** — voltage-controlled voltage source (E)
- **VCCS** — voltage-controlled current source (G)
- **CCVS** — current-controlled voltage source (H)
- **CCCS** — current-controlled current source (F)

### IC / PDK components
- **IC Resistor**, **IC Capacitor**
- **IC NMOS4**, **IC PMOS4** (4-terminal MOSFETs)
- **SKY130A** PDK support with `.lib` path configuration, plus a generic placeholder PDK and a free-form `.include` mode for custom libraries

### Multi-cell blocks
- **Amplifier** — 5×5 op-amp subcircuit with selectable 5-pin or 7-pin (offset-null) variants and a vertical-mirror toggle for swapping inverting/non-inverting inputs and supply rails

### Measurement
- **Voltage Probe** — labelled, measures node voltage
- **Current Probe** — placed in series to measure branch current

### Simulation control
- **Simulate Block** — runs the analysis. Supported types:
  - `.OP` — DC operating point
  - `.AC` — frequency sweep (start/stop/points-per-decade, log-frequency axis)
  - `.TRAN` — transient analysis (time step, stop time)
  - `.NOISE` — small-signal noise analysis (output node, input source, dec/lin/oct sweep)
- **Temperature** — single value (e.g. `27`) or sweep spec (e.g. `20:40:5` or `20,30,40`) for one run per temperature
- **ngspice compat modes:** `hsa`, `psa`, `lt`, `ki`, `va`
- **Param Block** — declares variables, one per line: scalars become `.param` lines, a single `start:stop:step` range or comma list sweeps that variable across runs
- **Commands Block** — multi-line block of raw ngspice commands injected verbatim into the netlist `.control` section
- **Sim Link Block** — bridges two physically-disconnected sub-circuits into the same netlist without unioning their nodes (useful for routing a CCVS/CCCS controlling source from a remote region)

### Output
- **Graph screen** — plot up to two probes simultaneously (stacked), with hover tooltips showing exact values; log10 X axis for AC sweeps
- **FFT button** — on any transient plot, computes and shows the magnitude spectrum of the displayed traces (selectable window function)
- **SPICE simulation** via ngspice as a subprocess — real node voltages and branch currents
- `.OP` results printed to the in-game chat
- **In-game GUI** for every component to configure values with SI-suffix parsing (k, M, u, n, p, …)
---
 
## Requirements
 
- Minecraft **1.20.1**
- Minecraft Forge **47.3.0**
- Java **17**
- [ngspice](https://ngspice.sourceforge.io/) installed and available on your system `PATH`
---
 
## Installation
### Method 1: Just take it
1. Install **Java 17** and **Minecraft Forge 47.3.0** for Minecraft 1.20.1.
2. Install [ngspice](https://ngspice.sourceforge.io/) and make sure `ngspice_con` (Windows) or `ngspice` (Linux/Mac) is accessible from your terminal.
3. Grab the [Latest Release](../../releases/latest)
4. Put the mod into your Minecraft `mods/` folder 


### Method 2: Build it yourself
1. Install **Java 17** and **Minecraft Forge 47.3.0** for Minecraft 1.20.1.
2. Install [ngspice](https://ngspice.sourceforge.io/) and make sure `ngspice_con` (Windows) or `ngspice` (Linux/Mac) is accessible from your terminal.
3. Clone this repository:
   ```bash
   git clone https://github.com/Dank0v/circuitsim.git
   cd circuitsim
   ```
4. Build the mod using the Gradle wrapper:
   ```bash
   # Windows
   gradlew.bat build
 
   # Linux / Mac
   ./gradlew build
   ```
5. Find the compiled `.jar` in `build/libs/` — it will be named something like `circuitsim-x.x.x.jar`.
6. Drop that `.jar` into your Minecraft `mods/` folder.
7. Launch the game.
---
 
## How to Use
 
1. Open the **Circuit Simulator** creative tab to find all blocks.
2. Place component blocks and connect them with **Wire** blocks. Every circuit needs at least one **Ground** block.
3. Right-click any component to open its configuration GUI and set its value.
4. Place a **Simulate Block** anywhere connected to the circuit.
5. Right-click the **Simulate Block** to run the simulation. Results appear in chat.
### Probes
 
- **Voltage Probe** — place adjacent to a wire node. Right-click to give it a label. The simulation will report the voltage at that node.
- **Current Probe** — place in series (between two wires) to measure current through that branch.
---

## Feature Guides

### Noise analysis (`.NOISE`)

Noise analysis answers the question *"how much random noise does my circuit itself add?"*. ngspice freezes the circuit at its DC operating point, asks every noisy device (resistor thermal noise, transistor shot/flicker noise, …) how much it contributes at each frequency, and sums it all up at the **output node** you choose. You also pick an **input source** — any independent V or I source already in the circuit (use its netlist name, e.g. `V1`) — and a frequency sweep (`dec`/`lin`/`oct`, points, Fstart, Fstop). The output node accepts a probe label (e.g. `vout`) or a raw node number, and an optional **Ref** node measures the difference `v(out, ref)` instead.

The result is two curves on a log-log plot. **`onoise_spectrum`** is the noise actually present at your output, in V/√Hz — a flat stretch is white (thermal) noise, a rising slope toward low frequencies is 1/f flicker noise. **`inoise_spectrum`** is the same noise *divided back through the circuit's gain*: "how big a noise source at the input would explain this output noise". That's the number that lets you compare amplifiers fairly, regardless of how much gain each one has. Chat also reports `onoise_total`/`inoise_total` — the RMS noise integrated over the whole band. The optional **Sum** field (points-per-summary) makes ngspice print a per-device noise breakdown every Nth point; find it in the output viewer's raw ngspice section.

To exclude a specific resistor from the noise budget (an ideal bias element, a Thevenin stand-in for a noiseless source, …), open the Resistor's edit dialog and tick **Thermal noise → noiseless**: its netlist line gains ngspice's `noisy=0` instance flag and it contributes nothing to `.NOISE` results. The floating label shows "(noiseless)" so silenced resistors are visible at a glance; every other analysis is unaffected.

### VC Switch

The VC Switch is a voltage-controlled switch: the front (`+`) and back (`-`) faces are the switched path, and the left (`C+`) / right (`C-`) faces sense a controlling voltage from elsewhere in the circuit. When `V(C+) - V(C-)` rises above **Vt + Vh** the switch closes to **Ron** (default 1 Ω); when it falls below **Vt − Vh** it opens to **Roff** (default 1 TΩ). **Vh** adds hysteresis so a slowly-moving or noisy control voltage doesn't chatter; the optional initial state (`on`/`off`) tells ngspice which side to start from when the control voltage begins inside the hysteresis band.

Defaults (Vt = 2.5 V, Vh = 0, Ron = 1 Ω, Roff = 1e12 Ω) make a usable logic-controlled switch for 0–5 V control signals without touching anything. Switches with identical parameters automatically share one `.model SW(...)` line in the netlist.

### FFT of transient waveforms

Every transient run now computes the spectrum of **every probed signal** automatically, using ngspice itself: the generated `.control` block runs `linearize` (ngspice's transient solver uses a variable timestep, so the waveform is first interpolated onto a uniform grid — mandatory for a DFT) followed by `fft` on each probed vector. The spectra come back alongside the time-domain data; open them with the **FFT** button on the transient graph's toolbar or the **[Open FFT spectrum]** link in chat. The spectrum plots magnitude (V or A) against frequency, log-log by default — the per-plot **Log** toggle switches back to linear. The DC bin is omitted (the time plot already shows the average level); the axis runs up to the Nyquist limit of the linearized data, and ngspice zero-pads to a power of two, so the exact bin spacing is `1/(N·tstep)`.

ngspice applies its default *Hanning* window to reduce spectral leakage from the non-periodic capture. To use a different window, put `set specwindow=rectangular` (or `hamming`, `blackman`, `gaussian`, …) in a **Commands Block** — it executes before the FFT. A clean sine shows a single dominant peak at the sine's frequency; a square wave shows the classic odd-harmonic series (1st, 3rd, 5th, …). During a Param-block or temperature sweep the FFT session overlays one labelled spectrum per swept value, just like the time-domain curves.

### Param Block

The Param Block declares simulation variables — one per line, as `name = value` (parentheses around a declaration are fine too). To use a variable, type its name into a component's value field (or W/L/mult/nf on IC devices). Three value forms exist:

```
One variable per line:   name = value
  Scalar:  Rload = 1k
  Sweep range (start:stop:step):  Rload = 1:5:1
  Sweep list:  Rload = 1,2,3,4,5
Only ONE variable may be swept at a time.
```

Scalars are emitted as `.param` lines in the netlist (so Commands-block expressions can use them) and substituted into every component that references them. A range or list turns that variable into a **sweep**: the analysis runs once per value and the graph overlays one labelled curve per value (`vout@2kΩ`, …). For ordinary value sweeps this happens inside a *single* ngspice process using a `.control` loop (`foreach` → `alterparam` → `reset` → `run`), which is much faster than re-launching ngspice per value — sweeps of IC W/L/mult/nf parameters, multi-temperature runs, and 2D DC sweeps automatically fall back to the classic one-run-per-value engine. Declaring **two** swept variables is an error (the message names both variables), and malformed lines are flagged with their line numbers when you hit Save — nothing is simulated until the block parses cleanly. Old worlds that used the single-variable Parametric block load as-is: the legacy declaration shows up as the first line of the text box.

## Example World

The repository ships with a ready-to-play save in [`example_world/`](example_world) containing a set of **pre-built example circuits**. It's the quickest way to see the mod in action — walk up to a circuit, run its **Simulate Block**, and inspect a working setup instead of wiring everything from scratch. The examples range from simple passive circuits to ones that use the controlled sources, the **Amplifier** block, and the IC/PDK components.

### Loading it
1. Make sure the mod and [ngspice](https://ngspice.sourceforge.io/) are installed (see [Installation](#installation)).
2. Copy the **`Zaza The Creator`** folder from `example_world/` into your Minecraft `saves/` directory:
   - **Windows:** `%appdata%\.minecraft\saves\`
   - **Linux:** `~/.minecraft/saves/`
   - **macOS:** `~/Library/Application Support/minecraft/saves/`
3. Launch the game and open the **Zaza The Creator** world from the singleplayer list.
4. Walk up to a circuit, right-click its **Simulate Block**, and read the results in chat (or open the **Graph screen** for AC/TRAN sweeps).

> **Note:** Some examples use a PDK or external SPICE library and reference `.lib` files by **absolute path**. Those paths point at the machine the world was built on, so for those circuits you'll need to open the relevant component (or the **Simulate Block**'s `.lib` field) and update the path to match your own ngspice/PDK install before they will simulate.

---
 
## Known Issues / TODO
 
 
---
 
## License
 
GPL-3.0 — see [LICENSE](LICENSE).
