package fr.mendes.itemreader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.util.Strings;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ItemReaderModClient implements ClientModInitializer {
    private static KeyBinding getItemTextKey;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // Pattern to match Roman numerals at the end of a line
    private static final Pattern ROMAN_NUMERAL_PATTERN = Pattern.compile("^(.+?)\\s+([IVXLCDM]+)$");

    @Override
    public void onInitializeClient() {
        // ── G key: read item in hand (works only outside a GUI, unchanged) ──
        getItemTextKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.itemreader.get_item_text",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                KeyBinding.Category.create(Identifier.of("itemreader", "general"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (getItemTextKey.wasPressed()) {
                if (client.player != null) {
                    ItemStack heldItem = client.player.getMainHandStack();
                    if (!heldItem.isEmpty()) {
                        try {
                            String jsonString = buildItemJson(heldItem);
                            copyToClipboard(client, jsonString);
                            client.player.sendMessage(Text.literal("§aJSON copied to clipboard!"), false);
                        } catch (Exception e) {
                            client.player.sendMessage(Text.literal("§cError: " + e.getMessage()), false);
                        }
                    } else {
                        client.player.sendMessage(Text.literal("§cNo item in hand!"), false);
                    }
                }
            }
        });

        // ── W fr key: read container items via ScreenKeyboardEvents ─────────────
        // ScreenKeyboardEvents fires even while a GUI screen is open,
        // unlike KeyBinding which is blocked by Minecraft when a screen is active.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof GenericContainerScreen)
                    && !(screen instanceof ShulkerBoxScreen)) {
                return; // only register on container screens
            }

            ScreenKeyboardEvents.afterKeyPress(screen).register((s, keyInput) -> {
                if (keyInput.key() != GLFW.GLFW_KEY_Z) return;
                if (client.player == null) return;

                ScreenHandler handler;
                int containerSlotCount;

                if (s instanceof GenericContainerScreen gcs) {
                    handler = gcs.getScreenHandler();
                } else {
                    ShulkerBoxScreen sbs = (ShulkerBoxScreen) s;
                    handler = sbs.getScreenHandler();
                }
                containerSlotCount = handler.slots.size() - 36;

                // Collect non-empty items from the container slots only
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < containerSlotCount; i++) {
                    Slot slot = handler.slots.get(i);
                    if (slot.hasStack() && !slot.getStack().isEmpty()) {
                        items.add(slot.getStack());
                    }
                }

                if (items.isEmpty()) {
                    client.player.sendMessage(Text.literal("§eEmpty container!"), false);
                    return;
                }

                // Header
                client.player.sendMessage(
                        Text.literal("§6§l[ItemReader] §r§7" + items.size()
                                + " item(s) found. Click on a name to copy its JSON:"), false);

                // One clickable line per item
                for (ItemStack stack : items) {
                    try {
                        String jsonString = buildItemJson(stack);
                        String displayName = stack.getName().getString();
                        int count = stack.getCount();
                        String label = (count > 1)
                                ? "§b" + displayName + " §7(x" + count + ")"
                                : "§b" + displayName;

                        MutableText clickable = Text.literal(label)
                                .setStyle(Style.EMPTY
                                        .withClickEvent(new ClickEvent.CopyToClipboard(jsonString))
                                        .withUnderline(true));

                        MutableText line = Text.literal("  ")
                                .append(clickable)
                                .append(Text.literal(" §8[click → copy JSON]"));

                        client.player.sendMessage(line, false);
                    } catch (Exception e) {
                        client.player.sendMessage(
                                Text.literal("§cError reading item: " + e.getMessage()), false);
                    }
                }
            });
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON builder (shared by both keys)
    // ─────────────────────────────────────────────────────────────────────────

    private String buildItemJson(ItemStack heldItem) {
        JsonObject jsonOutput = new JsonObject();
        JsonArray enchantmentsArray = new JsonArray();

        // Add version info
        jsonOutput.addProperty("mod-version", net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("itemreader")
                .map(modContainer -> modContainer.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"));

        // Get custom name
        if (heldItem.contains(DataComponentTypes.CUSTOM_NAME)) {
            Text customName = heldItem.get(DataComponentTypes.CUSTOM_NAME);
            if (customName != null) {
                String htmlName = trimHtml(textToHtml(customName));
                jsonOutput.addProperty("minecraft:custom_name", htmlName);
            }
        }

        // Extract vanilla enchantments
        if (heldItem.contains(DataComponentTypes.ENCHANTMENTS)) {
            ItemEnchantmentsComponent enchantments = heldItem.get(DataComponentTypes.ENCHANTMENTS);
            if (enchantments != null) {
                enchantments.getEnchantments().forEach(enchantment -> {
                    int level = enchantments.getLevel(enchantment);
                    String enchantName = getEnchantmentName(enchantment);

                    JsonObject enchantObj = new JsonObject();
                    enchantObj.addProperty("name", enchantName);
                    enchantObj.addProperty("level", level);
                    enchantObj.addProperty("color", "rgb(170, 170, 170)");

                    enchantmentsArray.add(enchantObj);
                });
            }
        }

        // Extract custom enchantments from lore and build lore HTML
        List<String> loreLines = new ArrayList<>();
        if (heldItem.contains(DataComponentTypes.LORE)) {
            var lore = heldItem.get(DataComponentTypes.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                for (Text loreLine : lore.lines()) {
                    String plainText = loreLine.getString();
                    String htmlLine = textToHtml(loreLine);

                    Matcher matcher = ROMAN_NUMERAL_PATTERN.matcher(plainText.trim());
                    if (matcher.matches()) {
                        String enchantName = toCamelCase(matcher.group(1).trim());
                        String romanLevel = matcher.group(2);
                        int level = romanToInt(romanLevel);
                        String color = extractColorFromHtml(htmlLine);

                        JsonObject enchantObj = new JsonObject();
                        enchantObj.addProperty("name", enchantName);
                        enchantObj.addProperty("level", level);
                        enchantObj.addProperty("color", color);

                        enchantmentsArray.add(enchantObj);
                    } else {
                        loreLines.add(htmlLine);
                    }
                }
            }
        }

        // Build lore HTML (excluding custom enchantments)
        if (!loreLines.isEmpty()) {
            StringBuilder loreHtml = new StringBuilder("<p>");
            for (int i = 0; i < loreLines.size(); i++) {
                if (i > 0) {
                    loreHtml.append("<br>");
                }
                loreHtml.append(loreLines.get(i));
            }
            loreHtml.append("</p>");
            jsonOutput.addProperty("minecraft:lore", trimHtml(loreHtml.toString()));
        }

        jsonOutput.add("minecraft:enchantments", enchantmentsArray);

        // Get item name (registry key)
        var registryKey = heldItem.getRegistryEntry().getKey();
        registryKey.ifPresent(itemRegistryKey -> jsonOutput.addProperty("minecraft:item_name", itemRegistryKey.getValue().toString()));

        return GSON.toJson(jsonOutput);
    }

    /**
     * Gets the enchantment name from RegistryEntry
     */
    private String getEnchantmentName(RegistryEntry<Enchantment> enchantment) {
        String[] parts = enchantment.value().toString().split("Enchantment ");
        if (parts.length > 1) {
            return parts[1];
        }
        return Strings.EMPTY;
    }

    /**
     * Converts Roman numerals to integer
     */
    private int romanToInt(String roman) {
        int result = 0;
        int prevValue = 0;

        for (int i = roman.length() - 1; i >= 0; i--) {
            int value = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };

            if (value < prevValue) {
                result -= value;
            } else {
                result += value;
            }
            prevValue = value;
        }

        return result;
    }

    /**
     * Extracts the first RGB color from HTML string
     */
    private String extractColorFromHtml(String html) {
        Pattern colorPattern = Pattern.compile("color:\\s*rgb\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\)");
        Matcher matcher = colorPattern.matcher(html);
        if (matcher.find()) {
            return "rgb(" + matcher.group(1) + ", " + matcher.group(2) + ", " + matcher.group(3) + ")";
        }
        return "rgb(170, 170, 170)"; // Default gray
    }

    /**
     * Converts Minecraft Text component to HTML with proper styling
     */
    private String textToHtml(Text text) {
        StringBuilder html = new StringBuilder();
        appendTextWithStyle(text, html);
        return html.toString();
    }

    /**
     * Recursively appends text with its style converted to HTML
     * UPDATED: Now includes support for obfuscated style
     */
    private void appendTextWithStyle(Text text, StringBuilder html) {
        String literalContent = "";
        try {
            var content = text.getContent();
            if (content.toString().startsWith("literal{")) {
                String str = content.toString();
                int start = str.indexOf('{') + 1;
                int end = str.indexOf('}');
                if (start > 0 && end > start) {
                    literalContent = str.substring(start, end);
                }
            }
        } catch (Exception ignored) {}

        Style style = text.getStyle();
        boolean hasContent = !literalContent.isEmpty();
        boolean hasSiblings = !text.getSiblings().isEmpty();

        if (!hasContent && !hasSiblings) {
            return;
        }

        // Check for obfuscated style first - it wraps everything else
        boolean isObfuscated = style.isObfuscated();
        if (isObfuscated && (hasContent || hasSiblings)) {
            html.append("<span data-obfuscated=\"true\"");
        }

        boolean hasSpan = false;
        StringBuilder spanStyle = new StringBuilder();

        if (style.getColor() != null) {
            int color = style.getColor().getRgb();
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            spanStyle.append("color: rgb(").append(r).append(", ").append(g).append(", ").append(b).append(");");
            hasSpan = true;
        }

        // If we have obfuscated, add the style attribute to the same span
        if (isObfuscated && (hasContent || hasSiblings)) {
            if (spanStyle.length() > 0) {
                html.append(" style=\"").append(spanStyle).append("\"");
            }
            html.append(">");
        } else if (hasSpan && (hasContent || hasSiblings)) {
            html.append("<span style=\"").append(spanStyle).append("\">");
        }

        if (style.isBold() && (hasContent || hasSiblings)) {
            html.append("<strong>");
        }

        if (style.isItalic() && (hasContent || hasSiblings)) {
            html.append("<em>");
        }

        if (style.isUnderlined() && (hasContent || hasSiblings)) {
            html.append("<u>");
        }

        if (style.isStrikethrough() && (hasContent || hasSiblings)) {
            html.append("<s>");
        }

        if (hasContent) {
            html.append(escapeHtml(literalContent));
        }

        for (Text sibling : text.getSiblings()) {
            appendTextWithStyle(sibling, html);
        }

        if (style.isStrikethrough() && (hasContent || hasSiblings)) {
            html.append("</s>");
        }

        if (style.isUnderlined() && (hasContent || hasSiblings)) {
            html.append("</u>");
        }

        if (style.isItalic() && (hasContent || hasSiblings)) {
            html.append("</em>");
        }

        if (style.isBold() && (hasContent || hasSiblings)) {
            html.append("</strong>");
        }

        // Close the span - either obfuscated or color span
        if (isObfuscated && (hasContent || hasSiblings)) {
            html.append("</span>");
        } else if (hasSpan && (hasContent || hasSiblings)) {
            html.append("</span>");
        }
    }

    /**
     * Trims HTML by removing leading/trailing spaces and <br> tags
     */
    private String trimHtml(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }

        String result = html;

        while (result.startsWith(" ") || result.startsWith("<br>")) {
            if (result.startsWith(" ")) {
                result = result.substring(1);
            } else if (result.startsWith("<br>")) {
                result = result.substring(4);
            }
        }

        while (result.endsWith(" ") || result.endsWith("<br>")) {
            if (result.endsWith(" ")) {
                result = result.substring(0, result.length() - 1);
            } else if (result.endsWith("<br>")) {
                result = result.substring(0, result.length() - 4);
            }
        }

        return result;
    }

    /**
     * Escapes HTML special characters
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }


    /**
     * Converts a snake_case string to a Title Case string
     */
    private String toCamelCase(String input) {
        // Replace underscores with spaces if it's snake_case
        input = input.replace("_", " ");

        // Split by space to handle words individually
        String[] words = input.split("\\s+");
        StringBuilder camelCaseString = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                // Capitalize the first letter and make the rest lowercase
                camelCaseString.append(word.substring(0, 1).toUpperCase());
                camelCaseString.append(word.substring(1).toLowerCase());
                camelCaseString.append(" ");  // Add space between words
            }
        }

        // Remove the trailing space and return the result
        return camelCaseString.toString().trim();
    }

    private void copyToClipboard(MinecraftClient client, String text) {
        try {
            System.out.println(text);
            if (client != null && client.keyboard != null) {
                client.keyboard.setClipboard(text);
            }
        } catch (Exception e) {
            System.err.println("Failed to copy to clipboard: " + e.getMessage());
        }
    }
}