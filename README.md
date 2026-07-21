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

## Documentation

**Full documentation lives in the [Wiki](https://github.com/Dank0v/circuitsim/wiki)** — component guides, every analysis type, model-library setup, subcircuits, Monte Carlo, stability analysis, and troubleshooting.

Quick links: [Getting Started](https://github.com/Dank0v/circuitsim/wiki/Getting-Started) · [Simulate Block & Analyses](https://github.com/Dank0v/circuitsim/wiki/Simulate-Block-and-Analyses) · [Model Libraries](https://github.com/Dank0v/circuitsim/wiki/Model-Libraries) · [Troubleshooting](https://github.com/Dank0v/circuitsim/wiki/Troubleshooting)

---

## Features

- **Components** — resistors, capacitors, inductors (with tolerances), DC/SIN/PULSE voltage sources, current source, behavioral (B) sources, diode, VC switch, VCVS/VCCS/CCVS/CCCS controlled sources, transformer, transmission line
- **Transistors** — discrete NMOS/PMOS/NPN/PNP and 4-terminal IC MOSFETs with **SKY130A** PDK support
- **Multi-cell blocks** — op-amp **Amplifier** block and user-defined **Subcircuit** chips made from your own schematics
- **Analyses** — `.OP`, `.DC` (1D/2D), `.AC`, `.TRAN`, `.NOISE`, `.STB` loop-gain stability, temperature sweeps, parameter sweeps, and **Monte Carlo**
- **Model libraries** — HSPICE/PSpice libs plus dedicated **KiCad** and **LTspice** modes that read those tools' own model collections
- **Tools** — graph screen with cursors/FFT/histograms, LTspice-style Measurement Builder, netlist viewer, pre-simulation circuit linter, in-world operating-point annotation on the **K** key

See the [Wiki](https://github.com/Dank0v/circuitsim/wiki) for the full guides.

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

## Example World

The repository ships with a ready-to-play save in [`example_world/`](example_world) containing pre-built example circuits — the quickest way to see the mod in action. Loading instructions and notes are in the [Example World wiki page](https://github.com/Dank0v/circuitsim/wiki/Example-World).

---

## Known Issues / TODO

- **Linux + Flatpak launchers** — when Minecraft runs through a Flatpak launcher (Prism, etc.), the sandbox can't see ngspice or your model-library files by default, so simulations fail with *"ngspice was not found"* or *"Could not find include file"*. Fixed with a `flatpak override` (and, for libraries, matching Linux's case-sensitive filenames): [ngspice not found on Linux (Flatpak)](https://github.com/Dank0v/circuitsim/wiki/ngspice-Not-Found-on-Linux-%28Flatpak%29) · [Library paths on Linux (Flatpak)](https://github.com/Dank0v/circuitsim/wiki/Library-Paths-on-Linux-%28Flatpak%29).

---

## License

GPL-3.0 — see [LICENSE](LICENSE).
