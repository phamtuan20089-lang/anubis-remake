package net.anubis.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import net.anubis.client.config.ModuleConfigStore;
import net.anubis.client.config.ThemeStore;
import net.anubis.client.module.ClientModule;
import net.anubis.client.module.ModuleCategory;
import net.anubis.client.module.ModuleRegistry;
import net.anubis.client.module.setting.ActionSetting;
import net.anubis.client.module.setting.BooleanSetting;
import net.anubis.client.module.setting.ColorSetting;
import net.anubis.client.module.setting.KeySetting;
import net.anubis.client.module.setting.ModeSetting;
import net.anubis.client.module.setting.NumberSetting;
import net.anubis.client.module.setting.Setting;
import net.anubis.client.module.setting.StringSetting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Touch-friendly ClickGUI built only from vanilla widgets, styled like the desktop panel GUI:
 * one floating panel per category (header with collapse chevron, pink highlighted enabled modules,
 * chevron to open a module's settings), search pill on top and pill buttons on the bottom.
 *
 * Controls: tap a module = toggle, tap its chevron = open settings, tap a header = collapse,
 * drag sideways = pan panels, drag up/down on a panel = scroll that panel.
 */
@Environment(EnvType.CLIENT)
public final class MobileClickGuiScreen extends Screen {
    private static final int UNBOUND = -1;
    private static final int MARGIN = 10;
    private static final int GAP = 8;
    private static final int PANEL_W = 124;
    private static final int HEADER = 18;
    private static final int RH = 15;
    private static final int BAR_Y = 8;
    private static final int BAR_H = 14;
    private static final int PANEL_TOP = 32;
    private static final int CHEVRON_ZONE = 22;

    private static final int COL_PANEL = 0xF0140F1B;
    private static final int COL_HEADER = 0xF01E1729;
    private static final int COL_PILL = 0xF0140F1B;
    private static final int COL_BORDER = 0x30FFFFFF;
    private static final int COL_ROW = 0xFF1D1726;
    private static final int COL_TEXT = 0xFFEDE8F4;
    private static final int COL_DIM = 0xFF8E869C;
    private static final int COL_OFF = 0xFF6B6478;
    private static final int COL_TRACK = 0xFF0C0910;
    private static final int[] PALETTE = {
        0xFFFFFF, 0xFF4040, 0xFF8A00, 0xFFD400, 0x3DDC84, 0x39C6B0, 0x3FA9FF, 0x6C63FF, 0xC85BFF, 0xFF5BB5, 0x9AA3AF, 0x000000
    };

    private record Hit(int x0, int y0, int x1, int y1, Runnable tap, NumberSetting slider, int sx, int sw, boolean overlay) {
        boolean contains(double x, double y) {
            return x >= this.x0 && x < this.x1 && y >= this.y0 && y < this.y1;
        }

        boolean sameRect(Hit o) {
            return o != null && this.x0 == o.x0 && this.y0 == o.y0 && this.x1 == o.x1 && this.y1 == o.y1;
        }
    }

    private record PanelRect(ModuleCategory cat, int x, int y, int w, int h, int bodyTop, int bodyBottom) {
        boolean contains(double px, double py) {
            return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<PanelRect> rects = new ArrayList<>();
    private final Map<ModuleCategory, Float> colScroll = new HashMap<>();
    private final Set<ModuleCategory> collapsed = new HashSet<>();
    private GuiGraphics gfx;
    private int accent = 0xFFFF6EB4;
    private int mouseXNow;
    private int mouseYNow;

    private float panX;
    private int panelTop;
    private int panelBottom;

    private ClientModule selected;
    private float scrollSets;
    private int contentSets = 10000;
    private int popX;
    private int popY;
    private int popW;
    private int popH;
    private int setX;
    private int setW;
    private int cursorY;
    private int clipTop;
    private int clipBottom;
    private boolean inPopup;

    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;
    private boolean moved;
    private int axis;
    private ModuleCategory dragCat;
    private boolean dragPopup;
    private Hit pressedHit;
    private NumberSetting dragNumber;
    private int dragSx;
    private int dragSw;

    private EditBox searchBox;
    private String searchKeep = "";
    private EditBox editBox;
    private StringSetting editing;
    private IntConsumer listening;
    private Object listeningTarget;

    public MobileClickGuiScreen() {
        super(Component.literal("ANUBIS"));
    }

    public boolean capturesKeys() {
        return this.editBox != null || this.listening != null || this.searchBox != null && this.searchBox.isFocused();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        if (this.searchBox != null) {
            this.searchKeep = this.searchBox.getValue();
        }

        this.searchBox = new EditBox(this.font, 0, 0, 100, 12, Component.literal("Search"));
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(48);
        this.searchBox.setHint(Component.literal("SEARCH..."));
        this.searchBox.setValue(this.searchKeep);
    }

    @Override
    public void removed() {
        super.removed();
        ModuleConfigStore.getInstance().saveIfChanged();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, this.width, this.height, 0x55000000);
    }

    // ------------------------------------------------------------------ drawing helpers

    private int a(int alpha) {
        return alpha << 24 | this.accent & 0xFFFFFF;
    }

    /** Rounded rectangle, radius about 3px. */
    private static void rr3(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 3, y, x + w - 3, y + h, c);
        g.fill(x + 1, y + 1, x + 3, y + h - 1, c);
        g.fill(x + w - 3, y + 1, x + w - 1, y + h - 1, c);
        g.fill(x, y + 2, x + 1, y + h - 2, c);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, c);
    }

    /** Rounded rectangle, radius 1px (rows). */
    private static void rr1(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + w, y + h - 1, c);
    }

    private static void box(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        rr3(g, x, y, w, h, border);
        rr3(g, x + 1, y + 1, w - 2, h - 2, fill);
    }

    private static void chevronRight(GuiGraphics g, int x, int y, int c) {
        for (int i = 0; i < 4; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, c);
        }

        for (int i = 1; i < 4; i++) {
            g.fill(x + 3 - i, y + 3 + i, x + 4 - i, y + 4 + i, c);
        }
    }

    private static void chevronDown(GuiGraphics g, int x, int y, int c) {
        for (int i = 0; i < 4; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, c);
        }

        for (int i = 1; i < 4; i++) {
            g.fill(x + 3 + i, y + 3 - i, x + 4 + i, y + 4 - i, c);
        }
    }

    private static String up(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private String fit(String s, int max) {
        if (s == null) {
            return "";
        }

        if (this.font.width(s) <= max) {
            return s;
        }

        return this.font.plainSubstrByWidth(s, Math.max(0, max - this.font.width("..."))) + "...";
    }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(this.font, s, x, y, color);
    }

    private void addHit(int x0, int y0, int x1, int y1, Runnable tap, NumberSetting slider, int sx, int sw) {
        if (y1 <= this.clipTop || y0 >= this.clipBottom) {
            return;
        }

        this.hits.add(new Hit(x0, Math.max(y0, this.clipTop), x1, Math.min(y1, this.clipBottom), tap, slider, sx, sw, this.inPopup));
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int color, Runnable tap, boolean overlay) {
        box(g, x, y, w, h, color, COL_BORDER);
        int tx = x + (w - this.font.width(label)) / 2;
        this.text(g, label, tx, y + (h - this.font.lineHeight) / 2 + 1, COL_TEXT);
        this.hits.add(new Hit(x, y, x + w, y + h, tap, null, 0, 0, overlay));
    }

    private boolean modal() {
        return this.editBox != null || this.selected != null;
    }

    // ------------------------------------------------------------------ data helpers

    private String query() {
        return this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
    }

    private List<ClientModule> modsFor(ModuleCategory c) {
        List<ClientModule> all = ModuleRegistry.inCategory(c);
        String q = this.query();
        if (q.isEmpty()) {
            return all;
        }

        List<ClientModule> out = new ArrayList<>();
        for (ClientModule m : all) {
            if (m.name().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(m);
            }
        }

        return out;
    }

    private List<ModuleCategory> categories() {
        List<ModuleCategory> out = new ArrayList<>();
        for (ModuleCategory c : ModuleCategory.values()) {
            if (!this.modsFor(c).isEmpty()) {
                out.add(c);
            }
        }

        return out;
    }

    private void openSettings(ClientModule m) {
        this.selected = m;
        this.scrollSets = 0.0F;
        this.contentSets = 10000;
        this.cancelEdit();
        this.listening = null;
        this.listeningTarget = null;
    }

    private void closeSettings() {
        this.selected = null;
        this.cancelEdit();
        this.listening = null;
        this.listeningTarget = null;
    }

    private ClientModule hudModule() {
        for (ClientModule m : ModuleRegistry.all()) {
            if (m.category() == ModuleCategory.CLIENT && "HUD".equals(m.name())) {
                return m;
            }
        }

        return null;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (this.selected != null && !ModuleRegistry.all().contains(this.selected)) {
            this.selected = null;
        }

        this.gfx = g;
        this.mouseXNow = mouseX;
        this.mouseYNow = mouseY;
        this.hits.clear();
        this.rects.clear();
        this.inPopup = false;
        this.accent = ThemeAccent.argb();
        this.panelTop = PANEL_TOP;
        this.panelBottom = this.height - (this.editBox != null ? 44 : 26);

        this.renderTopBar(g);
        this.renderPanels(g);
        this.renderBottomBar(g);
        this.renderPopup(g);
        if (this.editBox != null) {
            this.renderEditor(g, mouseX, mouseY, partialTick);
        }
    }

    private void renderTopBar(GuiGraphics g) {
        int pillW = 46;
        this.pill(g, MARGIN, BAR_Y, pillW, BAR_H, "CLOSE", true, this::onClose);
        this.pill(g, this.width - MARGIN - pillW, BAR_Y, pillW, BAR_H, "LAYOUT", true, () -> {
            this.panX = 0.0F;
            this.colScroll.clear();
            this.collapsed.clear();
        });

        int sw = Math.max(90, Math.min(170, this.width - 2 * (MARGIN + pillW + 10)));
        int sx = (this.width - sw) / 2;
        box(g, sx, BAR_Y, sw, BAR_H, COL_PILL, this.searchBox != null && this.searchBox.isFocused() ? this.a(0x90) : COL_BORDER);
        // magnifier
        int ix = sx + 6;
        int iy = BAR_Y + 3;
        int ic = COL_DIM;
        g.fill(ix + 1, iy, ix + 4, iy + 1, ic);
        g.fill(ix + 1, iy + 4, ix + 4, iy + 5, ic);
        g.fill(ix, iy + 1, ix + 1, iy + 4, ic);
        g.fill(ix + 4, iy + 1, ix + 5, iy + 4, ic);
        g.fill(ix + 5, iy + 5, ix + 7, iy + 7, ic);
        if (this.searchBox != null) {
            this.searchBox.setX(sx + 16);
            this.searchBox.setY(BAR_Y + (BAR_H - 12) / 2 + 1);
            this.searchBox.setWidth(sw - 22);
            this.searchBox.render(g, this.mouseXNow, this.mouseYNow, 0.0F);
        }

        this.hits.add(new Hit(sx, BAR_Y, sx + sw, BAR_Y + BAR_H, () -> {
            if (this.searchBox != null) {
                this.searchBox.setFocused(true);
            }
        }, null, 0, 0, false));
    }

    private void pill(GuiGraphics g, int x, int y, int w, int h, String label, boolean active, Runnable tap) {
        boolean hover = active && !this.modal() && this.mouseXNow >= x && this.mouseXNow < x + w && this.mouseYNow >= y && this.mouseYNow < y + h;
        box(g, x, y, w, h, hover ? 0xF02A2038 : COL_PILL, COL_BORDER);
        int tx = x + (w - this.font.width(label)) / 2;
        this.text(g, label, tx, y + (h - this.font.lineHeight) / 2 + 1, active ? COL_TEXT : COL_OFF);
        if (active && tap != null) {
            this.hits.add(new Hit(x, y, x + w, y + h, tap, null, 0, 0, false));
        }
    }

    private void renderBottomBar(GuiGraphics g) {
        if (this.editBox != null) {
            return;
        }

        ClientModule hud = this.hudModule();
        String[] labels = {"CONFIGS", "PROFILE", "HUD", "EMOTES", "SETTINGS"};
        int total = 0;
        int[] ws = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            ws[i] = this.font.width(labels[i]) + 14;
            total += ws[i] + (i > 0 ? 4 : 0);
        }

        int x = (this.width - total) / 2;
        int y = this.height - 20;
        for (int i = 0; i < labels.length; i++) {
            boolean wired = "HUD".equals(labels[i]) && hud != null;
            this.pill(g, x, y, ws[i], BAR_H, labels[i], wired, wired ? () -> this.openSettings(hud) : null);
            x += ws[i] + 4;
        }
    }

    private void renderPanels(GuiGraphics g) {
        List<ModuleCategory> cats = this.categories();
        int n = cats.size();
        if (n == 0) {
            this.text(g, "NO MODULES FOUND", (this.width - this.font.width("NO MODULES FOUND")) / 2, this.panelTop + 20, COL_DIM);
            return;
        }

        int totalW = n * PANEL_W + (n - 1) * GAP;
        int viewW = this.width - MARGIN * 2;
        float maxPan = Math.max(0, totalW - viewW);
        this.panX = Math.max(0.0F, Math.min(this.panX, maxPan));
        int startX = totalW <= viewW ? (this.width - totalW) / 2 : MARGIN - (int)this.panX;
        for (int i = 0; i < n; i++) {
            int x = startX + i * (PANEL_W + GAP);
            if (x + PANEL_W < 0 || x > this.width) {
                continue;
            }

            this.renderPanel(g, cats.get(i), x);
        }

        if (maxPan > 0) {
            int trackX = MARGIN;
            int trackW = viewW;
            int thumbW = Math.max(16, (int)(trackW * (viewW / (float)totalW)));
            int thumbX = trackX + (int)((trackW - thumbW) * (this.panX / maxPan));
            int ty = this.panelBottom + 3;
            g.fill(trackX, ty, trackX + trackW, ty + 1, 0x30FFFFFF);
            g.fill(thumbX, ty, thumbX + thumbW, ty + 1, this.a(0xB0));
        }
    }

    private void renderPanel(GuiGraphics g, ModuleCategory cat, int x) {
        List<ClientModule> mods = this.modsFor(cat);
        boolean col = this.collapsed.contains(cat) && this.query().isEmpty();
        int content = mods.size() * RH + 6;
        int avail = Math.max(0, this.panelBottom - this.panelTop - HEADER);
        int bodyH = col ? 0 : Math.min(content, avail);
        int y = this.panelTop;
        int h = HEADER + bodyH;

        rr3(g, x - 1, y - 1, PANEL_W + 2, h + 2, 0x50000000);
        rr3(g, x, y, PANEL_W, h, COL_PANEL);
        rr3(g, x, y, PANEL_W, HEADER, COL_HEADER);
        if (!col) {
            g.fill(x, y + HEADER - 4, x + PANEL_W, y + HEADER, COL_HEADER);
        }

        // category icon (ring)
        g.fill(x + 7, y + 6, x + 13, y + 12, this.accent);
        g.fill(x + 8, y + 7, x + 12, y + 11, COL_HEADER);
        g.fill(x + 9, y + 8, x + 11, y + 10, this.accent);
        this.text(g, this.fit(up(cat.displayName()), PANEL_W - 40), x + 18, y + 5, COL_TEXT);
        if (col) {
            chevronRight(g, x + PANEL_W - 12, y + 5, COL_DIM);
        } else {
            chevronDown(g, x + PANEL_W - 14, y + 7, COL_DIM);
        }

        if (!col) {
            g.fill(x + 4, y + HEADER - 1, x + PANEL_W - 4, y + HEADER, this.a(0x90));
        }

        this.hits.add(new Hit(x, y, x + PANEL_W, y + HEADER, () -> {
            if (this.collapsed.contains(cat)) {
                this.collapsed.remove(cat);
            } else {
                this.collapsed.add(cat);
            }
        }, null, 0, 0, false));

        int bodyTop = y + HEADER;
        int bodyBottom = bodyTop + bodyH;
        if (bodyH > 0) {
            float sc = this.colScroll.getOrDefault(cat, 0.0F);
            sc = Math.max(0.0F, Math.min(sc, Math.max(0, content - bodyH)));
            this.colScroll.put(cat, sc);
            this.clipTop = bodyTop;
            this.clipBottom = bodyBottom;
            g.enableScissor(x, bodyTop, x + PANEL_W, bodyBottom);
            int ry = bodyTop + 3 - (int)sc;
            for (ClientModule m : mods) {
                if (ry + RH > bodyTop && ry < bodyBottom) {
                    this.renderModuleRow(g, m, x, ry, bodyTop, bodyBottom);
                }

                ry += RH;
            }

            g.disableScissor();
            if (content > bodyH) {
                int th = Math.max(10, bodyH * bodyH / content);
                int ty = bodyTop + (int)((bodyH - th) * (sc / (content - bodyH)));
                g.fill(x + PANEL_W - 2, ty, x + PANEL_W - 1, ty + th, this.a(0x80));
            }
        }

        this.rects.add(new PanelRect(cat, x, y, PANEL_W, h, bodyTop, bodyBottom));
    }

    private void renderModuleRow(GuiGraphics g, ClientModule m, int px, int ry, int bodyTop, int bodyBottom) {
        int rx0 = px + 3;
        int rx1 = px + PANEL_W - 3;
        int rh = RH - 1;
        boolean action = m.isAction();
        boolean on = m.isEnabled() && !action;
        boolean hover = !this.modal() && this.mouseXNow >= rx0 && this.mouseXNow < rx1
            && this.mouseYNow >= Math.max(ry, bodyTop) && this.mouseYNow < Math.min(ry + rh, bodyBottom);
        if (on) {
            rr1(g, rx0, ry, rx1 - rx0, rh, this.a(0x30));
            g.fill(rx0, ry + 1, rx0 + 2, ry + rh - 1, this.accent);
        } else if (hover) {
            rr1(g, rx0, ry, rx1 - rx0, rh, 0x18FFFFFF);
        }

        int tx = rx0 + (on ? 7 : 5);
        this.text(g, this.fit(up(m.name()), rx1 - CHEVRON_ZONE + 6 - tx), tx, ry + 3, on ? this.accent : COL_TEXT);
        chevronRight(g, rx1 - 9, ry + 3, on ? this.accent : COL_DIM);

        this.addHit(rx0, ry, rx1 - CHEVRON_ZONE, ry + rh, () -> {
            if (action) {
                m.onClick();
            } else {
                m.toggle();
            }
        }, null, 0, 0);
        this.addHit(rx1 - CHEVRON_ZONE, ry, rx1, ry + rh, () -> this.openSettings(m), null, 0, 0);
    }

    // ------------------------------------------------------------------ settings popup

    private void renderPopup(GuiGraphics g) {
        if (this.selected == null) {
            return;
        }

        ClientModule m = this.selected;
        this.inPopup = true;
        g.fill(0, 0, this.width, this.height, 0x70000000);

        this.popW = Math.min(250, this.width - 2 * MARGIN);
        this.popX = (this.width - this.popW) / 2;
        int avail = Math.max(HEADER + 20, this.panelBottom - this.panelTop);
        int bodyMax = avail - HEADER;
        int bodyH = Math.max(20, Math.min(this.contentSets, bodyMax));
        this.popH = HEADER + bodyH;
        this.popY = this.panelTop + (avail - this.popH) / 2;
        int x = this.popX;
        int y = this.popY;

        rr3(g, x - 1, y - 1, this.popW + 2, this.popH + 2, 0x60000000);
        rr3(g, x, y, this.popW, this.popH, COL_PANEL);
        rr3(g, x, y, this.popW, HEADER, COL_HEADER);
        g.fill(x, y + HEADER - 4, x + this.popW, y + HEADER, COL_HEADER);
        g.fill(x + 7, y + 6, x + 13, y + 12, this.accent);
        g.fill(x + 8, y + 7, x + 12, y + 11, COL_HEADER);
        g.fill(x + 9, y + 8, x + 11, y + 10, this.accent);
        this.text(g, this.fit(up(m.name()), this.popW - 40), x + 18, y + 5, this.accent);
        chevronDown(g, x + this.popW - 14, y + 7, COL_DIM);
        g.fill(x + 4, y + HEADER - 1, x + this.popW - 4, y + HEADER, this.a(0x90));
        this.hits.add(new Hit(x, y, x + this.popW, y + HEADER, this::closeSettings, null, 0, 0, true));

        this.clipTop = y + HEADER;
        this.clipBottom = y + this.popH;
        this.setX = x;
        this.setW = this.popW;
        int view = this.clipBottom - this.clipTop - 4;
        this.scrollSets = Math.max(0.0F, Math.min(this.scrollSets, Math.max(0, this.contentSets - view - 4)));
        g.enableScissor(x, this.clipTop, x + this.popW, this.clipBottom);
        this.cursorY = this.clipTop + 3 - (int)this.scrollSets;
        int start = this.cursorY;
        int tx = this.setX + 6;
        String desc = m.description();
        if (desc != null && !desc.isEmpty()) {
            List<FormattedCharSequence> lines = this.font.split(Component.literal(desc), this.setW - 12);
            for (FormattedCharSequence line : lines) {
                g.drawString(this.font, line, tx, this.cursorY, COL_DIM);
                this.cursorY += this.font.lineHeight + 1;
            }

            this.cursorY += 3;
        }

        this.row("KEYBIND", this.listeningTarget == m ? "PRESS A KEY..." : up(keyName(m.keybind())), this.accent, () -> {
            this.listeningTarget = m;
            this.listening = m::setKeybind;
        });
        this.row("HOLD MODE", m.isHoldMode() ? "ON" : "OFF", m.isHoldMode() ? this.accent : COL_OFF, () -> m.setHoldMode(!m.isHoldMode()));
        for (Setting s : m.settings()) {
            if (s.isVisible() && !s.isSecret()) {
                this.settingRow(s);
            }
        }

        this.contentSets = this.cursorY - start + 8 + (desc != null && !desc.isEmpty() ? 0 : 0);
        g.disableScissor();
        if (this.contentSets > bodyH) {
            int th = Math.max(10, bodyH * bodyH / this.contentSets);
            int ty = this.clipTop + (int)((bodyH - th) * (this.scrollSets / (this.contentSets - bodyH)));
            g.fill(x + this.popW - 2, ty, x + this.popW - 1, ty + th, this.a(0x80));
        }

        this.inPopup = false;
    }

    private void row(String label, String value, int valueColor, Runnable tap) {
        int x0 = this.setX + 4;
        int x1 = this.setX + this.setW - 4;
        int y = this.cursorY;
        GuiGraphics g = this.gfx;
        rr1(g, x0, y, x1 - x0, RH, COL_ROW);
        int vw = this.font.width(value);
        int maxValue = (int)((x1 - x0) * 0.5F);
        String shownValue = vw > maxValue ? this.fit(value, maxValue) : value;
        int shownW = this.font.width(shownValue);
        this.text(g, this.fit(label, x1 - x0 - shownW - 16), x0 + 5, y + 4, COL_TEXT);
        this.text(g, shownValue, x1 - 5 - shownW, y + 4, valueColor);
        if (tap != null) {
            this.addHit(x0, y, x1, y + RH, tap, null, 0, 0);
        }

        this.cursorY += RH + 2;
    }

    private void settingRow(Setting s) {
        String name = up(s.name());
        if (s instanceof BooleanSetting b) {
            this.row(name, b.get() ? "ON" : "OFF", b.get() ? this.accent : COL_OFF, () -> b.set(!b.get()));
        } else if (s instanceof ModeSetting mode) {
            this.row(name, up(mode.get()), this.accent, mode::next);
        } else if (s instanceof NumberSetting n) {
            this.numberRow(n);
        } else if (s instanceof KeySetting k) {
            this.row(name, this.listeningTarget == k ? "PRESS A KEY..." : up(keyName(k.value())), this.accent, () -> {
                this.listeningTarget = k;
                this.listening = k::setValue;
            });
        } else if (s instanceof ColorSetting c) {
            this.colorRow(c);
        } else if (s instanceof StringSetting str) {
            String value = str.get();
            this.row(name, value == null || value.isEmpty() ? "(EMPTY)" : value, COL_DIM, () -> this.startEdit(str));
        } else if (s instanceof ActionSetting a) {
            this.row(name, "RUN", this.accent, a::run);
        } else {
            this.row(name, "(PC ONLY)", COL_DIM, null);
        }
    }

    private void numberRow(NumberSetting n) {
        int x0 = this.setX + 4;
        int x1 = this.setX + this.setW - 4;
        int y = this.cursorY;
        int h = RH + 10;
        GuiGraphics g = this.gfx;
        rr1(g, x0, y, x1 - x0, h, COL_ROW);
        String value = n.formatted();
        int vw = this.font.width(value);
        this.text(g, this.fit(up(n.name()), x1 - x0 - vw - 16), x0 + 5, y + 4, COL_TEXT);
        this.text(g, value, x1 - 5 - vw, y + 4, this.accent);
        int sx = x0 + 6;
        int sw = x1 - x0 - 12;
        int ty = y + RH + 1;
        g.fill(sx, ty, sx + sw, ty + 4, COL_TRACK);
        float range = n.max() - n.min();
        float t = range <= 0.0F ? 0.0F : Math.max(0.0F, Math.min(1.0F, (n.get() - n.min()) / range));
        int fill = (int)(sw * t);
        g.fill(sx, ty, sx + fill, ty + 4, this.accent);
        g.fill(sx + fill - 2, ty - 2, sx + fill + 2, ty + 6, COL_TEXT);
        this.addHit(x0, ty - 6, x1, ty + 10, null, n, sx, sw);
        this.cursorY += h + 2;
    }

    private void colorRow(ColorSetting c) {
        int rgb = c.argb() & 0xFFFFFF;
        String hex = "#" + String.format("%06X", rgb);
        this.row(up(c.name()), hex, COL_DIM, () -> {
            int next = 0;
            for (int i = 0; i < PALETTE.length; i++) {
                if (PALETTE[i] == (c.argb() & 0xFFFFFF)) {
                    next = (i + 1) % PALETTE.length;
                    break;
                }
            }

            int alpha = c.argb() & 0xFF000000;
            if (alpha == 0) {
                alpha = 0xFF000000;
            }

            c.setArgb(alpha | PALETTE[next]);
        });
        int x1 = this.setX + this.setW - 4;
        int y = this.cursorY - RH - 2;
        int vw = this.font.width(hex);
        this.gfx.fill(x1 - vw - 24, y + 3, x1 - vw - 10, y + RH - 3, 0xFF000000 | rgb);
    }

    private static String keyName(int key) {
        if (key < 0) {
            return "None";
        }

        if (key >= 1000) {
            return "Mouse " + (key - 1000 + 1);
        }

        try {
            return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        } catch (RuntimeException e) {
            return "Key " + key;
        }
    }

    // ------------------------------------------------------------------ string editor

    private void startEdit(StringSetting s) {
        this.cancelEdit();
        this.editing = s;
        this.editBox = new EditBox(this.font, 8, this.height - 30, Math.max(60, this.width - 16 - 140), 20, Component.literal(s.name()));
        this.editBox.setMaxLength(512);
        this.editBox.setValue(s.get() == null ? "" : s.get());
        this.editBox.setFocused(true);
    }

    private void cancelEdit() {
        this.editBox = null;
        this.editing = null;
    }

    private void commitEdit() {
        if (this.editBox != null && this.editing != null) {
            this.editing.set(this.editBox.getValue());
        }

        this.cancelEdit();
    }

    private void renderEditor(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, this.height - 40, this.width, this.height, 0xF00C0910);
        g.fill(0, this.height - 40, this.width, this.height - 39, this.a(0x90));
        if (this.editing != null) {
            this.text(g, this.fit(up(this.editing.name()), this.width - 16), 8, this.height - 38, this.accent);
        }

        this.editBox.setX(8);
        this.editBox.setY(this.height - 28);
        this.editBox.render(g, mouseX, mouseY, partialTick);
        int bx = this.width - 130;
        this.button(g, bx, this.height - 28, 60, 20, "OK", 0xFF1F3B2D, this::commitEdit, true);
        this.button(g, bx + 66, this.height - 28, 60, 20, "CANCEL", 0xFF3B1F2A, this::cancelEdit, true);
    }

    // ------------------------------------------------------------------ input

    private Hit hitAt(double x, double y) {
        boolean modal = this.modal();
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (modal && !h.overlay()) {
                continue;
            }

            if (h.contains(x, y)) {
                return h;
            }
        }

        return null;
    }

    private boolean inPopupRect(double x, double y) {
        return this.selected != null && x >= this.popX && x < this.popX + this.popW && y >= this.popY && y < this.popY + this.popH;
    }

    private PanelRect panelAt(double x, double y) {
        for (PanelRect r : this.rects) {
            if (r.contains(x, y)) {
                return r;
            }
        }

        return null;
    }

    private void scrollCol(ModuleCategory cat, float delta) {
        this.colScroll.put(cat, this.colScroll.getOrDefault(cat, 0.0F) + delta);
    }

    private void applySlider(double mx) {
        if (this.dragNumber == null || this.dragSw <= 0) {
            return;
        }

        float t = (float)Math.max(0.0, Math.min(1.0, (mx - this.dragSx) / this.dragSw));
        this.dragNumber.set(this.dragNumber.min() + t * (this.dragNumber.max() - this.dragNumber.min()));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (this.searchBox != null) {
            this.searchBox.setFocused(false);
        }

        Hit h = this.hitAt(mx, my);
        this.pressX = mx;
        this.pressY = my;
        this.lastX = mx;
        this.lastY = my;
        this.moved = false;
        this.axis = 0;
        this.pressedHit = h;
        this.dragNumber = null;
        this.dragCat = null;
        this.dragPopup = false;
        if (this.editBox == null) {
            if (this.selected != null) {
                this.dragPopup = this.inPopupRect(mx, my);
            } else {
                PanelRect r = this.panelAt(mx, my);
                this.dragCat = r == null ? null : r.cat();
            }
        }

        if (h != null && h.slider() != null) {
            this.dragNumber = h.slider();
            this.dragSx = h.sx();
            this.dragSw = h.sw();
            this.applySlider(mx);
        }

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = event.x();
        double my = event.y();
        if (this.dragNumber != null) {
            this.applySlider(mx);
            return true;
        }

        if (!this.moved && (Math.abs(mx - this.pressX) > 5.0 || Math.abs(my - this.pressY) > 5.0)) {
            this.moved = true;
            this.axis = Math.abs(mx - this.pressX) > Math.abs(my - this.pressY) ? 1 : 2;
        }

        if (this.moved && this.editBox == null) {
            if (this.axis == 1 && this.selected == null) {
                this.panX += (float)(this.lastX - mx);
            } else if (this.axis == 2) {
                float d = (float)(this.lastY - my);
                if (this.dragPopup) {
                    this.scrollSets += d;
                } else if (this.dragCat != null) {
                    this.scrollCol(this.dragCat, d);
                }
            }
        }

        this.lastX = mx;
        this.lastY = my;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean slider = this.dragNumber != null;
        this.dragNumber = null;
        if (!slider && !this.moved) {
            Hit now = this.hitAt(event.x(), event.y());
            if (this.pressedHit != null && this.pressedHit.tap() != null && this.pressedHit.sameRect(now)) {
                now.tap().run();
            } else if (this.pressedHit == null && this.selected != null && this.editBox == null && !this.inPopupRect(event.x(), event.y())) {
                this.closeSettings();
            }
        }

        this.pressedHit = null;
        this.dragCat = null;
        this.dragPopup = false;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.selected != null) {
            if (this.inPopupRect(mouseX, mouseY)) {
                this.scrollSets += (float)(-scrollY * 20.0);
            }

            return true;
        }

        PanelRect r = this.panelAt(mouseX, mouseY);
        if (r != null && scrollY != 0.0) {
            this.scrollCol(r.cat(), (float)(-scrollY * 20.0));
        } else {
            this.panX += (float)(-(scrollY + scrollX) * 30.0);
        }

        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (this.listening != null) {
            if (key == 259 || key == 261) {
                this.listening.accept(UNBOUND);
            } else if (key != 256) {
                this.listening.accept(key);
            }

            this.listening = null;
            this.listeningTarget = null;
            return true;
        }

        if (this.editBox != null) {
            if (key == 256) {
                this.cancelEdit();
                return true;
            }

            if (key == 257 || key == 335) {
                this.commitEdit();
                return true;
            }

            return this.editBox.keyPressed(event);
        }

        if (this.searchBox != null && this.searchBox.isFocused()) {
            if (key == 256 || key == 257 || key == 335) {
                this.searchBox.setFocused(false);
                return true;
            }

            return this.searchBox.keyPressed(event);
        }

        if (key == 256 && this.selected != null) {
            this.closeSettings();
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.editBox != null) {
            return this.editBox.charTyped(event);
        }

        if (this.searchBox != null && this.searchBox.isFocused()) {
            return this.searchBox.charTyped(event);
        }

        return super.charTyped(event);
    }

    /** Accent colour taken from the client theme (falls back to pink). */
    private static final class ThemeAccent {
        private ThemeAccent() {
        }

        static int argb() {
            try {
                return ThemeTint.accent(ThemeStore.get().accent.argbWithAlpha(255)) | 0xFF000000;
            } catch (RuntimeException e) {
                return 0xFFFF6EB4;
            }
        }
    }
}
