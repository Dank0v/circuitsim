package com.circuitsim.block;

import com.circuitsim.screen.ComponentEditScreen;
import com.circuitsim.simulation.CircuitExtractor;
import com.circuitsim.simulation.NetlistBuilder;
import com.circuitsim.simulation.NgSpiceRunner;
import com.circuitsim.simulation.ParametricResultCache;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
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

        List<String>    bookLines           = new ArrayList<>();
        List<Component> graphPageComponents = new ArrayList<>();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        bookLines.add("CircuitSim Results");
        bookLines.add(timestamp);
        bookLines.add("---");

        if (!extraction.parametricBlocks.isEmpty()) {
            for (CircuitExtractor.ParametricInfo param : extraction.parametricBlocks) {
                runParametricSweep(player, level, extraction, param, bookLines, graphPageComponents);
            }
        } else {
            runNormalSimulation(player, extraction, bookLines);
        }

        giveResultBook(player, "Sim " + timestamp, bookLines, graphPageComponents);
        return InteractionResult.CONSUME;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Normal simulation
    // ─────────────────────────────────────────────────────────────────────────

    private void runNormalSimulation(Player player,
                                      CircuitExtractor.ExtractionResult extraction,
                                      List<String> bookLines) {

        String netlist = NetlistBuilder.buildNetlist(
                extraction.components, extraction.probes, extraction.currentProbes);

        if (netlist == null || netlist.isEmpty()) {
            msg(player, "No circuit found!", ChatFormatting.RED);
            return;
        }

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

        for (NetlistBuilder.ProbeInfo probe : extraction.probes) {
            String line = "Probe [" + probe.label + "] Node " + probe.node
                    + ": " + result.getNodeVoltage(probe.node);
            msg(player, line, ChatFormatting.AQUA);
            bookLines.add(line);
        }

        int vmIdx = 1;
        for (NetlistBuilder.CurrentProbeInfo cp : extraction.currentProbes) {
            String line = "IProbe [" + cp.label + "]: " + result.getBranchCurrent("vm" + vmIdx);
            msg(player, line, ChatFormatting.LIGHT_PURPLE);
            bookLines.add(line);
            vmIdx++;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parametric sweep
    // ─────────────────────────────────────────────────────────────────────────

    private void runParametricSweep(Player player, Level level,
                                     CircuitExtractor.ExtractionResult extraction,
                                     CircuitExtractor.ParametricInfo param,
                                     List<String> bookLines,
                                     List<Component> graphPageComponents) {

        BlockPos targetPos   = param.targetPos;
        Block    targetBlock = level.getBlockState(targetPos).getBlock();

        if (!isParametrizable(targetBlock)) {
            msg(player, "Parametric block is not facing a supported component.", ChatFormatting.RED);
            return;
        }

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

        // Build effective probes (auto-generate if none placed)
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

        List<NetlistBuilder.CurrentProbeInfo> cpList = extraction.currentProbes;

        // ── data collectors (only filled for successful sweep points) ─────────
        List<Double>              validSweep   = new ArrayList<>();
        Map<String, List<Double>> voltageData  = new LinkedHashMap<>();
        Map<String, List<Double>> currentData  = new LinkedHashMap<>();
        for (NetlistBuilder.ProbeInfo p : effectiveProbes) voltageData.put(p.label, new ArrayList<>());
        for (NetlistBuilder.CurrentProbeInfo cp : cpList)  currentData.put(cp.label, new ArrayList<>());

        // ── header + sample netlist ───────────────────────────────────────────
        String header = "=== Parametric: " + displayName(targetBlock)
                + " sweep (" + sweepValues.size() + " pts) ===";
        msg(player, header, ChatFormatting.GOLD);
        bookLines.add(header);

        double first       = sweepValues.get(0);
        String sampleLabel = ComponentEditScreen.formatValue(first) + unit(targetBlock);
        String sampleNl    = NetlistBuilder.buildNetlist(
                swapValue(extraction.components, targetPos, first), effectiveProbes, cpList);

        msg(player, "Netlist (sample @ " + sampleLabel + "):", ChatFormatting.YELLOW);
        bookLines.add("=== Netlist (sample @ " + sampleLabel + ") ===");
        for (String line : sampleNl.split("\n")) {
            msg(player, "  " + line, ChatFormatting.WHITE);
            bookLines.add("  " + line);
        }

        // ── sweep loop ────────────────────────────────────────────────────────
        for (double val : sweepValues) {
            String sectionHeader = "--- " + ComponentEditScreen.formatValue(val) + unit(targetBlock) + " ---";
            msg(player, sectionHeader, ChatFormatting.YELLOW);
            bookLines.add(sectionHeader);

            NgSpiceRunner.Result result = NgSpiceRunner.run(
                    NetlistBuilder.buildNetlist(swapValue(extraction.components, targetPos, val),
                            effectiveProbes, cpList));

            if (result.error != null) {
                String firstLine = result.error.lines().filter(l -> !l.isBlank())
                        .findFirst().orElse("unknown error");
                msg(player, "  Error: " + firstLine, ChatFormatting.RED);
                bookLines.add("  Error: " + firstLine);
                continue;
            }
            if (result.values.isEmpty()) {
                msg(player, "  (no results — verify circuit has a Ground block)", ChatFormatting.GRAY);
                bookLines.add("  (no results)");
                continue;
            }

            // Valid point — record sweep value and probe values
            validSweep.add(val);

            for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
                String line = "  [" + probe.label + "]: " + result.getNodeVoltage(probe.node);
                msg(player, line, ChatFormatting.AQUA);
                bookLines.add(line);
                Double v = result.values.get("v(" + probe.node + ")");
                voltageData.get(probe.label).add(v != null ? v : 0.0);
            }

            for (int k = 0; k < cpList.size(); k++) {
                String line = "  [" + cpList.get(k).label + "]: "
                        + result.getBranchCurrent("vm" + (k + 1));
                msg(player, line, ChatFormatting.LIGHT_PURPLE);
                bookLines.add(line);
                Double i = result.values.get("i(vm" + (k + 1) + ")");
                currentData.get(cpList.get(k).label).add(i != null ? i : 0.0);
            }
        }

        if (validSweep.isEmpty()) return;

        // ── cache + clickable links ────────────────────────────────────────────
        int sessionId = ParametricResultCache.store(new ParametricResultCache.ResultSet(
                displayName(targetBlock), unit(targetBlock), validSweep, voltageData, currentData));

        // Collect all probe names in order
        List<String> allNames = new ArrayList<>();
        allNames.addAll(voltageData.keySet());
        allNames.addAll(currentData.keySet());

        msg(player, "--- Graphs - click a name to open ---", ChatFormatting.DARK_AQUA);

        graphPageComponents.add(Component.literal("-- Graphs --\n").withStyle(ChatFormatting.GOLD));

        for (int idx = 0; idx < allNames.size(); idx++) {
            String  name    = allNames.get(idx);
            boolean isVolt  = voltageData.containsKey(name);
            String  cmd     = "/circuitsim graph " + sessionId + " " + idx;
            String  label   = "  Plot " + name + (isVolt ? " (V)" : " (A)");

            // Chat: underlined, coloured, clickable
            player.displayClientMessage(
                    Component.literal(label).withStyle(Style.EMPTY
                            .withColor(isVolt ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))),
                    false);

            // Book graph page
            graphPageComponents.add(
                    Component.literal("Plot " + name + (isVolt ? " (V)" : " (A)") + "\n")
                            .withStyle(Style.EMPTY
                                    .withColor(isVolt ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE)
                                    .withUnderlined(true)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Book creation
    // ─────────────────────────────────────────────────────────────────────────

    private static final int LINES_PER_PAGE = 13;
    private static final int MAX_PAGES      = 100;

    private static void giveResultBook(Player player, String title,
                                        List<String> lines,
                                        List<Component> graphPageComponents) {
        ItemStack   book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag  = book.getOrCreateTag();
        tag.putString("title", title);
        tag.putString("author", "CircuitSim");
        tag.putByte("resolved", (byte) 1);

        ListTag      pages      = new ListTag();
        List<String> pageBuf    = new ArrayList<>();

        for (String line : lines) {
            pageBuf.add(line);
            if (pageBuf.size() >= LINES_PER_PAGE) {
                pages.add(plainPage(pageBuf));
                pageBuf.clear();
                if (pages.size() >= MAX_PAGES) break;
            }
        }
        if (!pageBuf.isEmpty() && pages.size() < MAX_PAGES) pages.add(plainPage(pageBuf));

        // Clickable graph-links page (rich text)
        if (!graphPageComponents.isEmpty() && pages.size() < MAX_PAGES) {
            MutableComponent root = Component.empty();
            for (Component c : graphPageComponents) root.append(c);
            pages.add(StringTag.valueOf(Component.Serializer.toJson(root)));
        }

        if (pages.isEmpty()) pages.add(plainPage(List.of("No results.")));
        tag.put("pages", pages);

        if (!player.getInventory().add(book)) player.drop(book, false);
        msg(player, "Results saved to a book in your inventory.", ChatFormatting.GRAY);
    }

    private static StringTag plainPage(List<String> lines) {
        return StringTag.valueOf(
                Component.Serializer.toJson(Component.literal(String.join("\n", lines))));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static List<NetlistBuilder.CircuitComponent> swapValue(
            List<NetlistBuilder.CircuitComponent> components, BlockPos targetPos, double newVal) {
        return components.stream()
                .map(c -> c.pos.equals(targetPos)
                        ? new NetlistBuilder.CircuitComponent(
                                c.block, c.pos, c.nodeA, c.nodeB, newVal, c.sourceType, c.frequency)
                        : c)
                .collect(Collectors.toList());
    }

    private static void msg(Player player, String text, ChatFormatting fmt) {
        player.displayClientMessage(Component.literal(text).withStyle(fmt), false);
    }

    private static boolean isParametrizable(Block b) {
        return b instanceof ResistorBlock || b instanceof CapacitorBlock
                || b instanceof InductorBlock || b instanceof VoltageSourceBlock
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

    public static List<Double> parseSweepString(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty sweep string");

        if (s.contains(":")) {
            String[] parts = s.split(":");
            if (parts.length != 3) throw new IllegalArgumentException("range must be start:stop:step");
            double start = ComponentEditScreen.parseSI(parts[0].trim());
            double stop  = ComponentEditScreen.parseSI(parts[1].trim());
            double step  = ComponentEditScreen.parseSI(parts[2].trim());
            if (step == 0) throw new IllegalArgumentException("step cannot be zero");
            List<Double> vals = new ArrayList<>();
            double eps = 1e-10 * Math.abs(step);
            if (step > 0) { for (double v = start; v <= stop + eps && vals.size() < 50; v += step) vals.add(v); }
            else          { for (double v = start; v >= stop - eps && vals.size() < 50; v += step) vals.add(v); }
            return vals;
        }

        return Arrays.stream(s.split(",")).map(String::trim).filter(t -> !t.isEmpty())
                .map(t -> { try { return ComponentEditScreen.parseSI(t); }
                            catch (NumberFormatException e) { throw new IllegalArgumentException("cannot parse '" + t + "'"); }})
                .collect(Collectors.toList());
    }
}