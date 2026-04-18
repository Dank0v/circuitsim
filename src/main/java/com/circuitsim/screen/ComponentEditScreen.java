package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.client.ClientSetup;
import com.circuitsim.network.ComponentUpdatePacket;
import com.circuitsim.network.ModMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ComponentEditScreen extends AbstractContainerScreen<ComponentEditMenu> {

    private EditBox valueField;
    private EditBox sourceTypeField;
    private EditBox frequencyField;
    private EditBox labelField;
    private Button doneButton;
    private Button cancelButton;
    private String componentType;
    private boolean showValue;
    private boolean showSourceType;
    private boolean showFrequency;
    private boolean showLabel;

    public ComponentEditScreen(ComponentEditMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 240;
        this.imageHeight = 180;
    }

    @Override
    protected void init() {
        super.init();

        net.minecraft.core.BlockPos pos = ClientSetup.getLastInteractedPos();
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);

        double currentValue = 0.0;
        String currentSourceType = "DC";
        double currentFrequency = 60.0;
        String currentLabel = "";

        if (be instanceof ComponentBlockEntity cbe) {
            currentValue = cbe.getValue();
            currentSourceType = cbe.getSourceType();
            currentFrequency = cbe.getFrequency();
            currentLabel = cbe.getLabel();
            componentType = cbe.getComponentType();
        } else {
            componentType = "resistor";
        }

        showValue = !"diode".equals(componentType);
        showSourceType = "voltage_source".equals(componentType);
        showFrequency = "voltage_source".equals(componentType);
        showLabel = "probe".equals(componentType);

        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        int fieldX = x + 80;
        int fieldWidth = 150;
        int currentY = y + 24;

        if (showValue) {
            String valueLabel = getValueLabel(componentType);
            valueField = new EditBox(Minecraft.getInstance().font, fieldX, currentY, fieldWidth, 18,
                    Component.literal(valueLabel));
            valueField.setValue(formatValue(currentValue));
            valueField.setMaxLength(32);
            addRenderableWidget(valueField);
            currentY += 30;
        }

        if (showSourceType) {
            sourceTypeField = new EditBox(Minecraft.getInstance().font, fieldX, currentY, fieldWidth, 18,
                    Component.literal("DC/AC"));
            sourceTypeField.setValue(currentSourceType);
            sourceTypeField.setMaxLength(8);
            addRenderableWidget(sourceTypeField);
            currentY += 30;
        }

        if (showFrequency) {
            frequencyField = new EditBox(Minecraft.getInstance().font, fieldX, currentY, fieldWidth, 18,
                    Component.literal("Freq"));
            frequencyField.setValue(formatValue(currentFrequency));
            frequencyField.setMaxLength(32);
            addRenderableWidget(frequencyField);
            currentY += 30;
        }

        if (showLabel) {
            labelField = new EditBox(Minecraft.getInstance().font, fieldX, currentY, fieldWidth, 18,
                    Component.literal("Label"));
            labelField.setValue(currentLabel);
            labelField.setMaxLength(64);
            addRenderableWidget(labelField);
            currentY += 30;
        }

        int buttonY = Math.min(currentY + 10, y + this.imageHeight - 30);

        doneButton = Button.builder(Component.literal("Done"), button -> {
            sendUpdatePacket(pos);
            Minecraft.getInstance().setScreen(null);
        }).bounds(x + 30, buttonY, 80, 20).build();
        addRenderableWidget(doneButton);

        cancelButton = Button.builder(Component.literal("Cancel"), button -> {
            Minecraft.getInstance().setScreen(null);
        }).bounds(x + 130, buttonY, 80, 20).build();
        addRenderableWidget(cancelButton);

        if (showValue && valueField != null) {
            this.setInitialFocus(valueField);
        }
    }

    private void sendUpdatePacket(net.minecraft.core.BlockPos pos) {
        double value = 0.0;
        if (valueField != null) {
            try { value = Double.parseDouble(valueField.getValue()); } catch (NumberFormatException ignored) {}
        }

        String srcType = "DC";
        if (sourceTypeField != null) {
            srcType = "AC".equalsIgnoreCase(sourceTypeField.getValue()) ? "AC" : "DC";
        }

        double freq = 60.0;
        if (frequencyField != null) {
            try { freq = Double.parseDouble(frequencyField.getValue()); } catch (NumberFormatException ignored) {}
        }

        String lbl = "";
        if (labelField != null) {
            lbl = labelField.getValue();
        }

        ModMessages.sendToServer(new ComponentUpdatePacket(pos, value, srcType, freq, lbl));
    }

    private String getValueLabel(String type) {
        return switch (type) {
            case "resistor" -> "Resistance (Ohms)";
            case "capacitor" -> "Capacitance (Farads)";
            case "inductor" -> "Inductance (Henries)";
            case "voltage_source" -> "Voltage (Volts)";
            case "current_source" -> "Current (Amps)";
            default -> "Value";
        };
    }

    private String formatValue(double val) {
        if (val == (long) val) return String.valueOf((long) val);
        return String.valueOf(val);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFF2C2C2C);
        graphics.fill(x, y, x + this.imageWidth, y + 2, 0xFF555555);
        graphics.fill(x, y + this.imageHeight - 2, x + this.imageWidth, y + this.imageHeight, 0xFF555555);
        graphics.fill(x, y, x + 2, y + this.imageHeight, 0xFF555555);
        graphics.fill(x + this.imageWidth - 2, y, x + this.imageWidth, y + this.imageHeight, 0xFF555555);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        graphics.drawCenteredString(Minecraft.getInstance().font,
                "Edit " + getComponentDisplayName(componentType), this.width / 2, y + 8, 0xFFFFFF);

        int currentY = y + 24;
        int labelX = x + 8;

        if (showValue) {
            graphics.drawString(Minecraft.getInstance().font, getValueLabel(componentType) + ":",
                    labelX, currentY + 5, 0xCCCCCC);
            currentY += 30;
        }
        if (showSourceType) {
            graphics.drawString(Minecraft.getInstance().font, "Source Type (DC/AC):",
                    labelX, currentY + 5, 0xCCCCCC);
            currentY += 30;
        }
        if (showFrequency) {
            graphics.drawString(Minecraft.getInstance().font, "Frequency (Hz):",
                    labelX, currentY + 5, 0xCCCCCC);
            currentY += 30;
        }
        if (showLabel) {
            graphics.drawString(Minecraft.getInstance().font, "Probe Label:",
                    labelX, currentY + 5, 0xCCCCCC);
        }
    }

    private String getComponentDisplayName(String type) {
        return switch (type) {
            case "resistor" -> "Resistor";
            case "capacitor" -> "Capacitor";
            case "inductor" -> "Inductor";
            case "voltage_source" -> "Voltage Source";
            case "current_source" -> "Current Source";
            case "diode" -> "Diode";
            case "probe" -> "Probe";
            default -> "Component";
        };
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.getFocused() instanceof EditBox eb) {
            if (eb.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.getFocused() instanceof EditBox eb) {
            if (eb.charTyped(codePoint, modifiers)) return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }
}