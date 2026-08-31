package com.example.addon.modules;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.example.addon.Tim;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.events.client.screen.EventScreen;
import org.rusherhack.client.api.events.render.EventRender2D;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.render.IRenderer2D;
import org.rusherhack.client.api.render.font.IFontRenderer;
import org.rusherhack.client.api.setting.BindSetting;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.client.api.utils.WorldUtils;
import org.rusherhack.core.bind.key.NullKey;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

public class SignScanner extends ToggleableModule {

    public enum HighlightStyle { GLOW, SPECTRAL, PULSE }
    public enum DateFormat { Readable, Short }
    public enum DateLine { Line_1, Line_2, Line_3, Line_4 }
    public enum MessageProfile { MESSAGE_1, MESSAGE_2 }

    // ── General ──
    private final NumberSetting<Integer> chunks = new NumberSetting<>("chunks", "Radius in chunks to scan for signs.", 16, 1, 64);
    private final BooleanSetting chatMessages = new BooleanSetting("chat-messages", "Notify in chat when a sign is found.", true);
    private final BooleanSetting cacheSignText = new BooleanSetting("cache-sign-text", "Cache sign text to improve performance.", true);
    private final NumberSetting<Integer> updateInterval = new NumberSetting<>("update-interval", "How often to scan for signs (in ticks).", 20, 1, 100).setVisibility(cacheSignText::getValue);

    // ── Filters ──
    private final BooleanSetting censorship = new BooleanSetting("censorship", "Censors bad words on signs.", true);
    private final StringSetting badWords = new StringSetting("banned-words", "Comma-separated words to censor.", "badword1,badword2").setVisibility(censorship::getValue);

    // ── Auto Sign ──
    private final BooleanSetting autoSign = new BooleanSetting("auto-sign", "Writes configured text on signs automatically when the edit screen opens.", false);
    private final EnumSetting<MessageProfile> activeProfile = new EnumSetting<>("active-profile", "Which message profile to use for Auto Sign.", MessageProfile.MESSAGE_1).setVisibility(autoSign::getValue);
    private final BindSetting switchProfileKey = new BindSetting("switch-profile-key", "Press this key to swap between Message 1 and Message 2.", NullKey.INSTANCE).setVisibility(autoSign::getValue);
    private final StringSetting message1Lines = new StringSetting("message-1-lines", "Comma-separated lines for Message 1 (up to 4).", "hello there,,just a wander,,- riths blahblah")
        .setVisibility(() -> autoSign.getValue() && activeProfile.getValue() == MessageProfile.MESSAGE_1);
    private final StringSetting message2Lines = new StringSetting("message-2-lines", "Comma-separated lines for Message 2 (up to 4).", "completely,different,message")
        .setVisibility(() -> autoSign.getValue() && activeProfile.getValue() == MessageProfile.MESSAGE_2);
    private final NumberSetting<Integer> editorDelay = new NumberSetting<>("editor-delay", "Ticks to wait before submitting the sign.", 8, 1, 60).setVisibility(autoSign::getValue);
    private final BooleanSetting autoGlowDye = new BooleanSetting("auto-glow-dye", "Automatically applies a glow ink sac and selected dye to the sign after editing.", false).setVisibility(autoSign::getValue);
    private final EnumSetting<DyeColor> dyeColor = new EnumSetting<>("dye-color", "Which color dye to apply to the sign.", DyeColor.WHITE)
        .setVisibility(() -> autoSign.getValue() && autoGlowDye.getValue());
    private final BooleanSetting autoDate = new BooleanSetting("auto-date", "Automatically stamps the current date onto the sign.", false).setVisibility(autoSign::getValue);
    private final EnumSetting<DateFormat> dateFormat = new EnumSetting<>("date-format", "How the date is formatted.", DateFormat.Readable)
        .setVisibility(() -> autoSign.getValue() && autoDate.getValue());
    private final EnumSetting<DateLine> dateLine = new EnumSetting<>("date-line", "Which line to place the date on.", DateLine.Line_4)
        .setVisibility(() -> autoSign.getValue() && autoDate.getValue());

    // ── Render ──
    private final NumberSetting<Double> scale = new NumberSetting<>("scale", "Scale of the rendered text.", 1.5, 0.1, 5.0);
    private final ColorSetting textColor = new ColorSetting("text-color", "Text color.", new Color(255, 255, 255, 255));
    private final BooleanSetting useSignColor = new BooleanSetting("use-sign-color", "Use the sign's dye color for text.", false);
    private final BooleanSetting background = new BooleanSetting("background", "Render a background behind the text.", true);
    private final ColorSetting backgroundColor = new ColorSetting("background-color", "Background color.", new Color(30, 30, 30, 160)).setVisibility(background::getValue);
    private final EnumSetting<HighlightStyle> highlightStyle = new EnumSetting<>("highlight-style", "Panel highlight style.", HighlightStyle.GLOW).setVisibility(background::getValue);
    private final BooleanSetting merge = new BooleanSetting("merge", "Merge signs that are close together.", true);
    private final NumberSetting<Double> mergeDistance = new NumberSetting<>("merge-distance", "Distance in pixels to merge signs.", 20.0, 0.0, 100.0).setVisibility(merge::getValue);

    // ── Highlight ──
    private final NumberSetting<Integer> glowLayers = new NumberSetting<>("glow-layers", "Bloom layer count.", 4, 1, 8)
        .setVisibility(() -> background.getValue() && (highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE));
    private final NumberSetting<Double> glowSpread = new NumberSetting<>("glow-spread", "How far each bloom layer expands outward (pixels).", 3.0, 0.5, 12.0)
        .setVisibility(() -> background.getValue() && (highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE));
    private final NumberSetting<Integer> glowBaseAlpha = new NumberSetting<>("glow-base-alpha", "Alpha of the innermost glow layer.", 60, 4, 150)
        .setVisibility(() -> background.getValue() && highlightStyle.getValue() == HighlightStyle.GLOW);
    private final ColorSetting glowColor = new ColorSetting("glow-color", "Color of the bloom glow.", new Color(100, 180, 255, 255))
        .setVisibility(() -> background.getValue() && (highlightStyle.getValue() == HighlightStyle.GLOW || highlightStyle.getValue() == HighlightStyle.PULSE));
    private final NumberSetting<Double> pulseSpeed = new NumberSetting<>("pulse-speed", "Pulse cycle speed.", 1.0, 0.1, 5.0)
        .setVisibility(() -> background.getValue() && highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> pulseMinAlpha = new NumberSetting<>("pulse-min-alpha", "Lowest alpha reached during the pulse.", 15, 0, 255)
        .setVisibility(() -> background.getValue() && highlightStyle.getValue() == HighlightStyle.PULSE);
    private final NumberSetting<Integer> pulseMaxAlpha = new NumberSetting<>("pulse-max-alpha", "Peak alpha reached during the pulse.", 220, 15, 255)
        .setVisibility(() -> background.getValue() && highlightStyle.getValue() == HighlightStyle.PULSE);
    private final ColorSetting spectralColor = new ColorSetting("spectral-color", "Color of the spectral outline border.", new Color(255, 255, 255, 255))
        .setVisibility(() -> background.getValue() && highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Double> spectralThickness = new NumberSetting<>("thickness", "Thickness of the spectral border lines (pixels).", 1.5, 0.5, 6.0)
        .setVisibility(() -> background.getValue() && highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Double> spectralExpand = new NumberSetting<>("expand", "How far the border sits beyond the panel edge (pixels).", 2.0, 0.0, 10.0)
        .setVisibility(() -> background.getValue() && highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final BooleanSetting spectralPulse = new BooleanSetting("spectral-pulse", "Pulsate the spectral border alpha over time.", true)
        .setVisibility(() -> background.getValue() && highlightStyle.getValue() == HighlightStyle.SPECTRAL);
    private final NumberSetting<Integer> spectralFillAlpha = new NumberSetting<>("fill-alpha", "Alpha of a faint tinted fill drawn inside the border.", 20, 0, 80)
        .setVisibility(() -> background.getValue() && highlightStyle.getValue() == HighlightStyle.SPECTRAL);

    // ── State ──
    private final Map<BlockPos, List<Component>> signs = new ConcurrentHashMap<>();
    private final Set<BlockPos> notified = new HashSet<>();
    private int timer = 0;
    private int editTimer = 0;
    private BlockEntity pendingSign = null;
    private boolean wasSwitchProfilePressed = false;

    public SignScanner() {
        super("sign-scanner", "Scans and displays sign text.", Tim.CATEGORY);
        this.registerSettings(
            chunks, chatMessages, cacheSignText, updateInterval,
            censorship, badWords,
            autoSign, activeProfile, switchProfileKey, message1Lines, message2Lines, editorDelay,
            autoGlowDye, dyeColor, autoDate, dateFormat, dateLine,
            scale, textColor, useSignColor, background, backgroundColor, highlightStyle, merge, mergeDistance,
            glowLayers, glowSpread, glowBaseAlpha, glowColor, pulseSpeed, pulseMinAlpha, pulseMaxAlpha,
            spectralColor, spectralThickness, spectralExpand, spectralPulse, spectralFillAlpha
        );
    }

    private void info(String fmt, Object... args) { sendNotification(NotificationType.INFO, args.length == 0 ? fmt : String.format(fmt, args)); }
    private void error(String fmt, Object... args) { sendNotification(NotificationType.ERROR, args.length == 0 ? fmt : String.format(fmt, args)); }

    private static List<String> csv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String p : raw.split(",")) out.add(p.trim());
        return out;
    }

    @Override
    public void onEnable() {
        signs.clear();
        notified.clear();
        timer = 0;
        editTimer = 0;
        pendingSign = null;
    }

    // ── Tick ──
    @Subscribe
    private void onTick(EventUpdate event) {
        handleHotkey();

        if (autoSign.getValue() && pendingSign != null) {
            editTimer++;
            if (editTimer >= editorDelay.getValue()) finishEditing();
        }

        if (mc.level == null || mc.player == null) return;

        double dist = chunks.getValue() * 16.0;
        double rangeSq = dist * dist;
        signs.keySet().removeIf(pos -> pos.distToCenterSqr(mc.player.position()) > rangeSq);

        if (cacheSignText.getValue()) {
            if (timer > 0) { timer--; return; }
            timer = updateInterval.getValue();
        }

        try {
            Set<BlockPos> currentSigns = new HashSet<>();
            for (BlockEntity be : WorldUtils.getBlockEntities(false)) {
                SignText[] texts = switch (be) {
                    case HangingSignBlockEntity h -> new SignText[]{ h.getFrontText(), h.getBackText() };
                    case SignBlockEntity s -> new SignText[]{ s.getFrontText(), s.getBackText() };
                    default -> null;
                };
                if (texts == null) continue;
                if (be.getBlockPos().distToCenterSqr(mc.player.position()) > rangeSq) continue;

                List<Component> lineList = new ArrayList<>();
                SignText front = texts[0], back = texts[1];
                if (censorship.getValue()) { front = censorSignText(front); back = censorSignText(back); }

                readSignText(front, lineList);
                readSignText(back, lineList);

                if (lineList.stream().allMatch(t -> t.getString().isBlank())) continue;

                signs.put(be.getBlockPos(), lineList);
                currentSigns.add(be.getBlockPos());

                if (chatMessages.getValue() && !notified.contains(be.getBlockPos())) {
                    List<String> ss = lineList.stream().map(Component::getString).filter(s -> !s.isBlank()).toList();
                    if (!ss.isEmpty()) info("Sign found: " + String.join(" | ", ss));
                    notified.add(be.getBlockPos());
                }
            }
            signs.keySet().retainAll(currentSigns);
            notified.retainAll(currentSigns);
        } catch (Exception ignored) {}
    }

    private void handleHotkey() {
        boolean p = switchProfileKey.getValue().isKeyDown();
        if (p && !wasSwitchProfilePressed && autoSign.getValue()) {
            MessageProfile next = activeProfile.getValue() == MessageProfile.MESSAGE_1 ? MessageProfile.MESSAGE_2 : MessageProfile.MESSAGE_1;
            activeProfile.setValue(next);
            info("Switched Auto Sign profile to: " + next);
        }
        wasSwitchProfilePressed = p;
    }

    @Subscribe
    private void onOpenScreen(EventScreen.Change event) {
        if (!autoSign.getValue()) return;
        if (event.getTo() instanceof AbstractSignEditScreen screen) {
            BlockEntity sign = extractSignEntity(screen);
            if (sign != null) { pendingSign = sign; editTimer = 0; }
        } else {
            pendingSign = null;
        }
    }

    // ── AutoSign ──
    private void finishEditing() {
        if (pendingSign == null) return;
        BlockPos pos = pendingSign.getBlockPos();

        List<String> configured = switch (activeProfile.getValue()) {
            case MESSAGE_1 -> csv(message1Lines.getValue());
            case MESSAGE_2 -> csv(message2Lines.getValue());
        };

        String[] rows = new String[4];
        for (int i = 0; i < 4; i++) rows[i] = (i < configured.size()) ? configured.get(i) : "";

        if (autoDate.getValue()) {
            int lineIndex = dateLine.getValue().ordinal();
            rows[lineIndex] = getCurrentDate();
        }

        mc.player.connection.send(new ServerboundSignUpdatePacket(pos, true, rows[0], rows[1], rows[2], rows[3]));

        if (autoGlowDye.getValue()) {
            int glowSac = org.rusherhack.client.api.utils.InventoryUtils.findItemHotbar(Items.GLOW_INK_SAC);
            if (glowSac != -1) {
                applyHeldToSign(pos, glowSac);
            } else {
                error("Glow Ink Sac not found in hotbar. Disabling auto-glow-dye.");
                autoGlowDye.setValue(false);
            }

            Item dyeItem = DyeItem.byColor(dyeColor.getValue());
            int dyeResult = org.rusherhack.client.api.utils.InventoryUtils.findItemHotbar(dyeItem);
            if (dyeResult != -1) {
                applyHeldToSign(pos, dyeResult);
            } else {
                error("Selected dye (%s) not found in hotbar. Disabling auto-glow-dye.", dyeColor.getValue().getName());
                autoGlowDye.setValue(false);
            }
        }

        mc.player.closeContainer();
        pendingSign = null;
    }

    private void applyHeldToSign(BlockPos pos, int hotbarSlot) {
        int prev = org.rusherhack.client.api.utils.InventoryUtils.getSelectedHotbarSlot();
        org.rusherhack.client.api.utils.InventoryUtils.setHotbarSlot(hotbarSlot);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        mc.player.swing(InteractionHand.MAIN_HAND);
        org.rusherhack.client.api.utils.InventoryUtils.setHotbarSlot(prev);
    }

    private String getCurrentDate() {
        LocalDate date = LocalDate.now();
        if (dateFormat.getValue() == DateFormat.Short) {
            return date.format(DateTimeFormatter.ofPattern("dd/MM/yy"));
        } else {
            String daySuffix = getDaySuffix(date.getDayOfMonth());
            return date.format(DateTimeFormatter.ofPattern("d'" + daySuffix + "' MMMM yyyy"));
        }
    }

    private String getDaySuffix(int day) {
        if (day >= 11 && day <= 13) return "th";
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private static BlockEntity extractSignEntity(AbstractSignEditScreen screen) {
        Class<?> cls = screen.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (SignBlockEntity.class.isAssignableFrom(f.getType()) || HangingSignBlockEntity.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return (BlockEntity) f.get(screen);
                    } catch (Exception ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ── Sign Text Helpers ──
    private void readSignText(SignText signText, List<Component> output) {
        for (Component t : signText.getMessages(false)) {
            Component cleaned = cleanSignText(t);
            if (!cleaned.getString().trim().isEmpty()) output.add(cleaned);
        }
    }

    public boolean shouldCensor() { return isToggled() && censorship.getValue(); }

    public SignText censorSignText(SignText signText) {
        SignText newText = signText;
        for (int i = 0; i < 4; i++) {
            newText = newText.setMessage(i, censorText(signText.getMessage(i, false)));
        }
        return newText;
    }

    private Component censorText(Component text) {
        ComponentContents content = text.getContents();
        if (content instanceof PlainTextContents ptc) content = PlainTextContents.create(censor(ptc.text()));
        MutableComponent result = MutableComponent.create(content).setStyle(text.getStyle());
        for (Component sibling : text.getSiblings()) result.append(censorText(sibling));
        return result;
    }

    public String censor(String input) {
        String working = input;
        for (String bad : csv(badWords.getValue())) {
            if (bad.isEmpty()) continue;
            try { if (working.matches("(?i).*" + bad + ".*")) return "****"; }
            catch (Exception ignored) {}
        }
        working = working.replaceAll("-?\\d+[kKmM]?([\\s,]+-?\\d+[kKmM]?){1,2}", "XXXX");
        return working;
    }

    private Component cleanSignText(Component text) {
        return Component.literal(text.getString().replaceAll("§.", "")).setStyle(text.getStyle());
    }

    // ── Render 2D ──
    @Subscribe
    private void onRender2D(EventRender2D event) {
        if (mc.player == null || mc.level == null) return;

        IRenderer2D r = RusherHackAPI.getRenderer2D();
        IFontRenderer font = r.getFontRenderer();

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float partial = event.getPartialTicks();
        double s = scale.getValue();

        List<SignEntry> entries = new ArrayList<>();
        try {
            for (Map.Entry<BlockPos, List<Component>> entry : signs.entrySet()) {
                BlockPos pos = entry.getKey();
                List<Component> lineList = entry.getValue();
                if (lineList.isEmpty()) continue;

                BlockEntity be = mc.level.getBlockEntity(pos);
                Vec3 vec = (be instanceof HangingSignBlockEntity)
                    ? Vec3.atCenterOf(pos).add(0, -0.2, 0)
                    : Vec3.atCenterOf(pos).add(0, 0.5, 0);

                float[] screen = worldToScreen(vec.x, vec.y, vec.z, partial, sw, sh);
                if (screen == null) continue;
                entries.add(new SignEntry(pos, lineList, screen[0], screen[1]));
            }
        } catch (Exception ignored) {}

        entries.sort(Comparator.comparingDouble(e -> mc.player.distanceToSqr(Vec3.atCenterOf(e.pos))));

        List<SignEntry> grouped = new ArrayList<>();
        double mergeDistSq = mergeDistance.getValue() * mergeDistance.getValue();
        for (SignEntry entry : entries) {
            if (!merge.getValue()) { grouped.add(entry); continue; }
            boolean m = false;
            for (SignEntry group : grouped) {
                double dx = entry.sx - group.sx, dy = entry.sy - group.sy;
                if (dx * dx + dy * dy <= mergeDistSq) { group.count++; m = true; break; }
            }
            if (!m) grouped.add(entry);
        }
        grouped.sort(Comparator.comparingDouble(e -> -mc.player.distanceToSqr(Vec3.atCenterOf(e.pos))));

        r.begin(event.getGraphics().pose());
        try {
            for (SignEntry entry : grouped) renderSign(entry, r, font, s);
        } catch (Exception ignored) {}
        r.end();
    }

    private void renderSign(SignEntry entry, IRenderer2D r, IFontRenderer font, double s) {
        List<Component> linesToRender = new ArrayList<>(entry.lines);
        if (entry.count > 1) linesToRender.add(Component.literal(entry.count + " signs").withStyle(ChatFormatting.YELLOW));

        double lh = font.getFontHeight() * s;
        double maxWidth = 0;
        for (Component t : linesToRender) maxWidth = Math.max(maxWidth, font.getStringWidth(t.getString()) * s);
        double totalH = linesToRender.size() * lh;

        double pad = 4.0;
        double bw = maxWidth + pad * 2;
        double bh = totalH + pad * 2;
        double bx = entry.sx - bw / 2.0;
        double by = entry.sy - bh / 2.0;

        if (background.getValue()) {
            if (highlightStyle.getValue() == HighlightStyle.GLOW) renderGlowHighlight(r, bx, by, bw, bh);
            else if (highlightStyle.getValue() == HighlightStyle.PULSE) renderPulseHighlight(r, bx, by, bw, bh);
            else renderSpectralHighlight(r, bx, by, bw, bh);
            r.drawRectangle(bx, by, bw, bh, backgroundColor.getValue().getRGB());
        }

        double y = by + pad;
        int i = 0;
        for (Component lineText : linesToRender) {
            boolean isMergedCountLine = entry.count > 1 && i == linesToRender.size() - 1;
            String line = lineText.getString();
            double w = font.getStringWidth(line) * s;
            double x = entry.sx - w / 2.0;

            int color;
            if (isMergedCountLine) {
                color = new Color(255, 255, 0, 255).getRGB();
            } else {
                Color c = textColor.getValue();
                if (useSignColor.getValue() && lineText.getStyle().getColor() != null) {
                    int rgb = lineText.getStyle().getColor().getValue();
                    if (rgb != 0) c = new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
                }
                color = c.getRGB();
            }

            font.drawString(line, x, y, color, true);
            y += lh;
            i++;
        }
    }

    private void renderGlowHighlight(IRenderer2D r, double bx, double by, double bw, double bh) {
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        int baseAlpha = glowBaseAlpha.getValue();
        Color gc = glowColor.getValue();
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double t = (double) (i - 1) / layers;
            int layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - t * t)));
            r.drawRectangle(bx - expansion, by - expansion, bw + expansion * 2, bh + expansion * 2, withAlpha(gc, layerAlpha));
        }
    }

    private float getPulseFactor() {
        double speed = pulseSpeed.getValue();
        double t = System.currentTimeMillis() / 1000.0;
        double phase = t * speed * Math.PI * 2.0;
        return (float) ((Math.sin(phase) + 1.0) * 0.5);
    }

    private int applyPulse(int baseAlpha) {
        float f = getPulseFactor();
        int min = pulseMinAlpha.getValue();
        int max = pulseMaxAlpha.getValue();
        return Math.min(255, Math.max(0, (int) (min + (max - min) * f)));
    }

    private void renderPulseHighlight(IRenderer2D r, double bx, double by, double bw, double bh) {
        int layers = glowLayers.getValue();
        double spread = glowSpread.getValue();
        Color gc = glowColor.getValue();
        int pa = applyPulse(gc.getAlpha());
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double taper = 1.0 - ((double) (i - 1) / layers) * 0.6;
            int layerAlpha = Math.max(4, (int) (pa * taper));
            r.drawRectangle(bx - expansion, by - expansion, bw + expansion * 2, bh + expansion * 2, withAlpha(gc, layerAlpha));
        }
    }

    private void renderSpectralHighlight(IRenderer2D r, double bx, double by, double bw, double bh) {
        double expand = spectralExpand.getValue();
        double thickness = spectralThickness.getValue();
        Color sc = spectralColor.getValue();

        double ox = bx - expand, oy = by - expand, ow = bw + expand * 2, oh = bh + expand * 2;

        int lineAlpha = sc.getAlpha();
        int fillAlpha = spectralFillAlpha.getValue();
        if (spectralPulse.getValue()) {
            double pulse = 0.6 + 0.4 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 750.0 * Math.PI));
            lineAlpha = (int) (lineAlpha * pulse);
            fillAlpha = (int) (fillAlpha * pulse);
        }

        if (fillAlpha > 0) r.drawRectangle(ox, oy, ow, oh, withAlpha(sc, fillAlpha));

        int lc = withAlpha(sc, lineAlpha);
        r.drawRectangle(ox, oy, ow, thickness, lc);
        r.drawRectangle(ox, oy + oh - thickness, ow, thickness, lc);
        r.drawRectangle(ox, oy + thickness, thickness, oh - thickness * 2, lc);
        r.drawRectangle(ox + ow - thickness, oy + thickness, thickness, oh - thickness * 2, lc);
    }

    private int withAlpha(Color color, int alpha) {
        int a = Math.min(255, Math.max(0, alpha));
        return (a << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private float[] worldToScreen(double x, double y, double z, float partial, int sw, int sh) {
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 c = cam.getPosition();
        Vector3f pos = new Vector3f((float) (x - c.x), (float) (y - c.y), (float) (z - c.z));
        Quaternionf rot = new Quaternionf(cam.rotation());
        rot.conjugate().transform(pos);
        if (pos.z >= 0f) return null;
        Matrix4f proj = mc.gameRenderer.getProjectionMatrix((float) (double) mc.options.fov().get());
        Vector4f clip = proj.transform(new Vector4f(pos.x, pos.y, pos.z, 1f));
        if (clip.w <= 0f) return null;
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        return new float[]{ (ndcX * 0.5f + 0.5f) * sw, (1f - (ndcY * 0.5f + 0.5f)) * sh };
    }

    private static class SignEntry {
        final BlockPos pos;
        final List<Component> lines;
        final double sx, sy;
        int count = 1;

        SignEntry(BlockPos pos, List<Component> lines, double sx, double sy) {
            this.pos = pos;
            this.lines = lines;
            this.sx = sx;
            this.sy = sy;
        }
    }
}
