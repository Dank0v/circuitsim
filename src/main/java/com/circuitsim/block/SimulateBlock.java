package com.circuitsim.block;

import com.circuitsim.screen.ComponentEditScreen;
import com.circuitsim.simulation.CircuitExtractor;
import com.circuitsim.simulation.NetlistBuilder;
import com.circuitsim.simulation.NgSpiceRunner;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class SimulateBlock extends Block {

    public SimulateBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        msg(player, "=== Circuit Simulation ===", ChatFormatting.GOLD);

        CircuitExtractor.ExtractionResult extraction = CircuitExtractor.extract(level, pos);

        if (!extraction.success) {
            msg(player, "Error: " + extraction.errorMessage, ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }

        // Accumulate all text for the book
        List<String> bookLines = new ArrayList<>();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        bookLines.add("CircuitSim Results");
        bookLines.add(timestamp);
        bookLines.add("---");

        if (!extraction.parametricBlocks.isEmpty()) {
            // ---- Parametric sweep mode ----
            for (CircuitExtractor.ParametricInfo param : extraction.parametricBlocks) {
                runParametricSweep(player, level, extraction, param, bookLines);
            }
        } else {
            // ---- Normal single simulation ----
            runNormalSimulation(player, extraction, bookLines);
        }

        // Give the player a written book with all results
        giveResultBook(player, "Sim " + timestamp, bookLines);

        return InteractionResult.CONSUME;
    }

    // -------------------------------------------------------------------------
    // Normal simulation
    // -------------------------------------------------------------------------

    private void runNormalSimulation(Player player,
                                      CircuitExtractor.ExtractionResult extraction,
                                      List<String> bookLines) {

        String netlist = NetlistBuilder.buildNetlist(
                extraction.components, extraction.probes, extraction.currentProbes);

        if (netlist == null || netlist.isEmpty()) {
            msg(player, "No circuit found!", ChatFormatting.RED);
            return;
        }

        // Print netlist to chat and book
        msg(player, "Netlist:", ChatFormatting.YELLOW);
        bookLines.add("=== Netlist ===");
        for (String line : netlist.split("\n")) {
            msg(player, "  " + line, ChatFormatting.WHITE);
            bookLines.add("  " + line);
        }

        NgSpiceRunner.Result result = NgSpiceRunner.run(netlist);

        if (result.error != null) {
            msg(player, "Simulation Error: " + result.error, ChatFormatting.RED);
            bookLines.add("Error: " + result.error);
            return;
        }

        msg(player, "--- Results ---", ChatFormatting.GREEN);
        bookLines.add("=== Results ===");

        for (String line : result.output) {
            msg(player, line, ChatFormatting.GREEN);
            bookLines.add(line.trim());
        }

        // Voltage probes
        for (NetlistBuilder.ProbeInfo probe : extraction.probes) {
            String voltage = result.getNodeVoltage(probe.node);
            String line = "Probe [" + probe.label + "] Node " + probe.node + ": " + voltage;
            msg(player, line, ChatFormatting.AQUA);
            bookLines.add(line);
        }

        // Current probes
        int vmIdx = 1;
        for (NetlistBuilder.CurrentProbeInfo cp : extraction.currentProbes) {
            String current = result.getBranchCurrent("vm" + vmIdx);
            String line = "IProbe [" + cp.label + "]: " + current;
            msg(player, line, ChatFormatting.LIGHT_PURPLE);
            bookLines.add(line);
            vmIdx++;
        }
    }

    // -------------------------------------------------------------------------
    // Parametric sweep
    // -------------------------------------------------------------------------

    private void runParametricSweep(Player player, Level level,
                                     CircuitExtractor.ExtractionResult extraction,
                                     CircuitExtractor.ParametricInfo param,
                                     List<String> bookLines) {

        BlockPos targetPos = param.targetPos;
        Block targetBlock  = level.getBlockState(targetPos).getBlock();

        if (!isParametrizable(targetBlock)) {
            msg(player, "Parametric block is not facing a supported component "
                    + "(need Resistor, Capacitor, Inductor, Voltage/Current Source).", ChatFormatting.RED);
            return;
        }

        // Parse sweep values
        List<Double> sweepValues;
        try {
            sweepValues = parseSweepString(param.sweepString);
        } catch (IllegalArgumentException e) {
            msg(player, "Invalid sweep string: " + e.getMessage(), ChatFormatting.RED);
            return;
        }

        if (sweepValues.isEmpty()) {
            msg(player, "No sweep values — right-click the Parametric block to set them.", ChatFormatting.RED);
            return;
        }

        if (sweepValues.size() > 50) {
            msg(player, "Too many sweep values (" + sweepValues.size() + "); max is 50.", ChatFormatting.RED);
            return;
        }

        // Build effective probe list (auto-generate if none placed)
        List<NetlistBuilder.ProbeInfo> effectiveProbes;
        if (extraction.probes.isEmpty()) {
            Set<Integer> nodes = new LinkedHashSet<>();
            for (NetlistBuilder.CircuitComponent c : extraction.components) {
                if (c.nodeA != 0) nodes.add(c.nodeA);
                if (c.nodeB != 0) nodes.add(c.nodeB);
            }
            effectiveProbes = nodes.stream()
                    .map(n -> new NetlistBuilder.ProbeInfo(n, "Node " + n))
                    .collect(Collectors.toList());
        } else {
            effectiveProbes = extraction.probes;
        }

        String header = "=== Parametric: " + displayName(targetBlock)
                + " sweep (" + sweepValues.size() + " pts) ===";
        msg(player, header, ChatFormatting.GOLD);
        bookLines.add(header);

        // Print the netlist once using the first sweep value as a representative sample
        List<NetlistBuilder.CircuitComponent> sampleSwept = extraction.components.stream()
                .map(c -> c.pos.equals(targetPos)
                        ? new NetlistBuilder.CircuitComponent(
                                c.block, c.pos, c.nodeA, c.nodeB,
                                sweepValues.get(0), c.sourceType, c.frequency)
                        : c)
                .collect(Collectors.toList());
        String sampleNetlist = NetlistBuilder.buildNetlist(sampleSwept, effectiveProbes, extraction.currentProbes);
        String sampleLabel = ComponentEditScreen.formatValue(sweepValues.get(0)) + unit(targetBlock);
        msg(player, "Netlist (sample @ " + sampleLabel + "):", ChatFormatting.YELLOW);
        bookLines.add("=== Netlist (sample @ " + sampleLabel + ") ===");
        for (String line : sampleNetlist.split("\n")) {
            msg(player, "  " + line, ChatFormatting.WHITE);
            bookLines.add("  " + line);
        }

        for (double val : sweepValues) {
            // Replace target component value for this run
            final double sweepVal = val;
            List<NetlistBuilder.CircuitComponent> swept = extraction.components.stream()
                    .map(c -> c.pos.equals(targetPos)
                            ? new NetlistBuilder.CircuitComponent(
                                    c.block, c.pos, c.nodeA, c.nodeB,
                                    sweepVal, c.sourceType, c.frequency)
                            : c)
                    .collect(Collectors.toList());

            String sectionHeader = "--- " + ComponentEditScreen.formatValue(val) + unit(targetBlock) + " ---";
            msg(player, sectionHeader, ChatFormatting.YELLOW);
            bookLines.add(sectionHeader);

            String netlist = NetlistBuilder.buildNetlist(swept, effectiveProbes, extraction.currentProbes);
            NgSpiceRunner.Result result = NgSpiceRunner.run(netlist);

            if (result.error != null) {
                String firstLine = result.error.lines()
                        .filter(l -> !l.isBlank()).findFirst().orElse("unknown error");
                msg(player, "  Error: " + firstLine, ChatFormatting.RED);
                bookLines.add("  Error: " + firstLine);
                continue;
            }

            if (result.values.isEmpty()) {
                msg(player, "  (no results — verify circuit has a Ground block)", ChatFormatting.GRAY);
                bookLines.add("  (no results)");
                continue;
            }

            // Voltage probes
            for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
                String line = "  [" + probe.label + "]: " + result.getNodeVoltage(probe.node);
                msg(player, line, ChatFormatting.AQUA);
                bookLines.add(line);
            }

            // Current probes
            int vmIdx = 1;
            for (NetlistBuilder.CurrentProbeInfo cp : extraction.currentProbes) {
                String line = "  [" + cp.label + "]: " + result.getBranchCurrent("vm" + vmIdx);
                msg(player, line, ChatFormatting.LIGHT_PURPLE);
                bookLines.add(line);
                vmIdx++;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Book creation
    // -------------------------------------------------------------------------

    /**
     * Packs {@code lines} into a written book and adds it to the player's inventory.
     * Each page holds up to {@code LINES_PER_PAGE} lines; the book is capped at 100 pages.
     */
    private static final int LINES_PER_PAGE = 13;
    private static final int MAX_PAGES      = 100;

    private static void giveResultBook(Player player, String title, List<String> lines) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", title);
        tag.putString("author", "CircuitSim");
        tag.putByte("resolved", (byte) 1);

        ListTag pages = new ListTag();
        List<String> pageBuffer = new ArrayList<>();

        for (String line : lines) {
            pageBuffer.add(line);
            if (pageBuffer.size() >= LINES_PER_PAGE) {
                pages.add(pageTag(pageBuffer));
                pageBuffer.clear();
                if (pages.size() >= MAX_PAGES) break;
            }
        }
        if (!pageBuffer.isEmpty() && pages.size() < MAX_PAGES) {
            pages.add(pageTag(pageBuffer));
        }

        if (pages.isEmpty()) {
            pages.add(pageTag(List.of("No results.")));
        }

        tag.put("pages", pages);

        // Add to inventory; if full, drop at feet
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }

        player.displayClientMessage(
                Component.literal("Results saved to a book in your inventory.")
                        .withStyle(ChatFormatting.GRAY), false);
    }

    /** Converts a list of lines to a single JSON-text page tag. */
    private static StringTag pageTag(List<String> lines) {
        String text = String.join("\n", lines);
        return StringTag.valueOf(
                net.minecraft.network.chat.Component.Serializer.toJson(
                        Component.literal(text)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void msg(Player player, String text, ChatFormatting fmt) {
        player.displayClientMessage(Component.literal(text).withStyle(fmt), false);
    }

    private static boolean isParametrizable(Block b) {
        return b instanceof ResistorBlock
                || b instanceof CapacitorBlock
                || b instanceof InductorBlock
                || b instanceof VoltageSourceBlock
                || b instanceof CurrentSourceBlock;
    }

    private static String displayName(Block b) {
        if (b instanceof ResistorBlock)      return "Resistor";
        if (b instanceof CapacitorBlock)     return "Capacitor";
        if (b instanceof InductorBlock)      return "Inductor";
        if (b instanceof VoltageSourceBlock) return "Voltage Source";
        if (b instanceof CurrentSourceBlock) return "Current Source";
        return "Component";
    }

    private static String unit(Block b) {
        if (b instanceof ResistorBlock)      return "\u03A9";
        if (b instanceof CapacitorBlock)     return "F";
        if (b instanceof InductorBlock)      return "H";
        if (b instanceof VoltageSourceBlock) return "V";
        if (b instanceof CurrentSourceBlock) return "A";
        return "";
    }

    /**
     * Parses a sweep string into an ordered list of doubles.
     *
     * <ul>
     *   <li><b>List</b>: {@code 100,200,500,1k,2k}</li>
     *   <li><b>Range</b>: {@code start:stop:step}</li>
     * </ul>
     */
    public static List<Double> parseSweepString(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty sweep string");

        if (s.contains(":")) {
            String[] parts = s.split(":");
            if (parts.length != 3)
                throw new IllegalArgumentException("range must be start:stop:step");

            double start   = ComponentEditScreen.parseSI(parts[0].trim());
            double stop    = ComponentEditScreen.parseSI(parts[1].trim());
            double step    = ComponentEditScreen.parseSI(parts[2].trim());
            if (step == 0) throw new IllegalArgumentException("step cannot be zero");

            List<Double> vals = new ArrayList<>();
            double epsilon = 1e-10 * Math.abs(step);
            if (step > 0) {
                for (double v = start; v <= stop + epsilon && vals.size() < 50; v += step)
                    vals.add(v);
            } else {
                for (double v = start; v >= stop - epsilon && vals.size() < 50; v += step)
                    vals.add(v);
            }
            return vals;
        }

        // Comma-separated list
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .map(t -> {
                    try { return ComponentEditScreen.parseSI(t); }
                    catch (NumberFormatException e) {
                        throw new IllegalArgumentException("cannot parse '" + t + "'");
                    }
                })
                .collect(Collectors.toList());
    }
}