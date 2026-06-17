# CircuitSim — A Minecraft Circuit Simulation Mod

> **This mod was built entirely with AI assistance.**

CircuitSim is a Minecraft Forge mod for version **1.20.1** that lets you build and simulate real electronic circuits inside the game. Place component blocks in the world, connect them with wires, and right-click the **Simulate Block** to run a full SPICE simulation powered by [ngspice](https://ngspice.sourceforge.io/).

**Non-inverting amplifier** — an op-amp circuit built from blocks and simulated live.

![Non-inverting amplifier](gifs/non_inverting.gif)

**Miller amplifier** — a two-stage Miller-compensated amplifier and its frequency response.

![Miller amplifier](gifs/miller_amp.gif)

**MOSFET output characteristics** — a DC sweep producing a transistor's I–V output curves.

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
- **Subcircuit** — 5×5 block that instantiates *your own* saved schematic as a reusable black-box component, with 12 perimeter pins (see [User-defined subcircuits](#user-defined-subcircuits))
- **Subcircuit Converter** + **Subcircuit Chip** — turn any circuit you built into a portable chip item and back again

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
- **ngspice compat modes:** `none`, `hsa`, `psa`
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
 
- **Voltage Probe** — place adjacent to a wire node. Right-click to give it a label. The simulation will report the voltage at that node. The probe dialog also has a **"Subcircuit pin"** toggle (plus a pin-order number) used when converting a circuit into a reusable subcircuit — see [User-defined subcircuits](#user-defined-subcircuits).
- **Current Probe** — place in series (between two wires) to measure current through that branch.
---

## Feature Guides

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

### User-defined subcircuits

Build a circuit once, then reuse it as a single black-box block — the mod turns your in-world schematic into a real SPICE `.subckt` and instantiates it with an `X` line, exactly like the built-in Amplifier but defined entirely by you. Three pieces work together:

**1. Mark the terminals (Voltage Probe → "Subcircuit pin").** A subcircuit needs named external pins. Place a **Voltage Probe** on each net you want to expose, give it a label (that label becomes the pin name), and tick **"Subcircuit pin"** in the probe dialog. The optional **pin order** number controls the order pins appear in the `.subckt` line and, in turn, which physical pin they map to on the 5×5 block (order 1 → pin 1, order 2 → pin 2, …; unordered pins come last). You can mark 1–12 pins. A pin net must be named and must not be ground.

**2. Convert (Subcircuit Converter).** Place a **Subcircuit Converter** block touching your schematic (it connects to wires like the Simulate block), right-click it, type a name, and hit **Convert to Subcircuit**. The mod walks the connected circuit, generates a self-contained `.subckt <name> <pins…> … .ends` definition (analysis-independent — full SIN/PULSE specs, models, etc.), snapshots the exact block layout, then **removes the schematic** (and the converter) and hands you a **Subcircuit Chip** item. The chip's tooltip shows the name, pin count, and block count.

**3. Use the chip.** A chip can be used two ways:
- **Right-click the ground** with it to **rebuild the original schematic** block-for-block (values, probe labels, even multiblocks like nested amplifiers are restored). The chip is consumed.
- **Insert it into a Subcircuit block.** Place the 5×5 **Subcircuit** block, right-click to open it, and drop the chip into the **Chip** slot. The second slot shows the generated `.subckt` netlist (scrollable, read-only, with a **Copy** button); the third slot is a reserved preview. The block's top texture changes to reflect the pin count, and a floating name tag appears above it (toggle with the labels key).

**Pins on the 5×5 block.** The block exposes **12 pins** around its perimeter — two on each corner cell (on its two outward faces) and one in the middle of each edge — numbered **clockwise from the top-left corner's top face**. Wire connects visually and electrically only to the first *N* pins, where *N* is the loaded chip's terminal count; the rest are inert. Wire it into a larger circuit (with a Ground and a Simulate block) and run any analysis — the netlist gets both the embedded `.subckt … .ends` definition and the `X… <name>` instance, so OP / AC / DC / TRAN / NOISE and sweeps all work.

> **Limitations:** Param/Commands blocks *inside* a converted schematic are not carried into the subcircuit (a subcircuit is just devices). Give each subcircuit a distinct name — two **unnamed** subcircuits in the same circuit would both auto-name `subckt1` and collide.

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
