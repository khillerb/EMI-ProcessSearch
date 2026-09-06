package dev.processsearch.screen;

import dev.processsearch.index.tree.ProcessTreeNavigation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The frame every panel in this mod draws: a bordered box over the graph, a heading, a scrolling
 * list, and the ways out.
 *
 * <p>There are five of these -- the machine filter, the siblings list, a machine's recipes, a
 * build plan's summary, and the choice between interchangeable machines -- and they had each grown
 * their own copy of the same border, the same scrollbar, the same clamp, the same click-outside
 * dismissal. Five copies of a scrollbar is five places for it to drift, and it had: the filter
 * panel was the only one without one at all.
 *
 * <p>What a subclass still owns is what actually differs: how wide it wants to be, how tall its
 * content is, what its heading says, how a row is drawn, and what clicking one does.
 */
public abstract class PanelScreen extends Screen {
    protected static final int HEADER_H = 34;
    protected static final int PANEL_MARGIN = 24;

    private static final int PANEL_BG = 0xF01A1A1A;
    private static final int PANEL_BORDER = 0xFF6A6A6A;
    private static final int HEADER_BG = 0xFF141414;
    private static final int HEADER_LINE = 0xFF404040;
    private static final int BACKDROP_DIM = 0xD0000000;
    private static final int SCROLL_TRACK = 0x40FFFFFF;
    private static final int SCROLL_BAR = 0xC0FFFFFF;
    /** Hover, and the tint behind a selected row. Shared so the panels feel like one thing. */
    protected static final int ROW_HOVER = 0x40FFFFFF;

    /**
     * The graph this panel sits over, drawn behind it.
     *
     * <p>Null only when a panel is somehow reached without one, which the ways back handle rather
     * than assume away.
     */
    protected final ProcessGraphScreen parent;

    protected int panelLeft;
    protected int panelTop;
    protected int panelWidth;
    protected int panelHeight;
    protected double scroll;

    protected PanelScreen(Component title, ProcessGraphScreen parent) {
        super(title);
        this.parent = parent;
    }

    // ------------------------------------------------------------ what a subclass decides

    /** The widest this panel should get, before the window squeezes it. */
    protected abstract int preferredWidth();

    /** Total height of the rows, which the scrollbar measures against. */
    protected abstract int contentHeight();

    /**
     * The height to size the frame from, when that differs from the current content.
     *
     * <p>They differ where a row can grow after opening: the scrollbar must track the real total,
     * but the frame must not, or it would jump under the cursor the moment a row expanded -- and
     * again on every window resize, which re-runs {@link #init}.
     */
    protected int framedHeight() {
        return contentHeight();
    }

    /** The line along the top. */
    protected abstract String heading();

    /**
     * Draws the rows.
     *
     * <p>Called inside the scissor with the pose already translated to the list, so a subclass
     * works in coordinates where {@code (0, 0)} is the first row's top left.
     *
     * @param localMouseX mouse position in those same coordinates, which a row of item slots
     *                    needs to know which slot is under the cursor
     * @param localMouseY the same, vertically
     * @param inList      whether the cursor is over the list at all, so hover can be suppressed
     *                    while it is over the header or outside the panel
     */
    protected abstract void drawRows(GuiGraphics graphics, int localMouseX, int localMouseY,
                                     boolean inList, float delta);

    /** @param localY click position in row coordinates */
    protected abstract void clickRow(double localY, int button);

    /** Extra buttons beside Back. The default adds none. */
    protected void addHeaderButtons(int x, int y) {
    }

    // ------------------------------------------------------------ the frame

    @Override
    protected void init() {
        if (parent != null) {
            // The backdrop is a screen that is not the active one, so nothing else will tell it
            // the window changed size.
            parent.resize(minecraft, width, height);
        }
        panelWidth = Math.min(preferredWidth(), Math.max(220, width - PANEL_MARGIN * 2));
        panelHeight = Math.min(height - PANEL_MARGIN * 2,
                Math.max(120, HEADER_H + framedHeight() + 8));
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        addRenderableWidget(Button.builder(Component.literal("< Back"), b -> back())
                .bounds(panelLeft + 4, panelTop + HEADER_H - 24, 56, 20).build());
        addHeaderButtons(panelLeft + 64, panelTop + HEADER_H - 24);
        clampScroll();
    }

    protected int listTop() {
        return panelTop + HEADER_H;
    }

    protected int listBottom() {
        return panelTop + panelHeight - 4;
    }

    protected int listHeight() {
        return listBottom() - listTop();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (parent != null) {
            parent.renderBackdrop(graphics, delta);
            graphics.fill(0, 0, width, height, BACKDROP_DIM);
        } else {
            renderBackground(graphics);
        }

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_BG);
        drawBorder(graphics);

        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1,
                panelTop + HEADER_H, HEADER_BG);
        graphics.drawString(font, font.plainSubstrByWidth(heading(), panelWidth - 8),
                panelLeft + 4, panelTop + 4, 0xFFFFFFFF, false);
        drawHeaderExtras(graphics, delta);
        graphics.hLine(panelLeft, panelLeft + panelWidth - 1, panelTop + HEADER_H, HEADER_LINE);

        graphics.enableScissor(panelLeft, listTop(), panelLeft + panelWidth, listBottom());
        graphics.pose().pushPose();
        graphics.pose().translate(panelLeft, listTop() - scroll, 0);
        drawRows(graphics, mouseX - panelLeft, (int) (mouseY - listTop() + scroll),
                inList(mouseX, mouseY), delta);
        graphics.pose().popPose();
        graphics.disableScissor();

        drawScrollbar(graphics);
        super.render(graphics, mouseX, mouseY, delta);
        drawOverlay(graphics, mouseX, mouseY);
    }

    /** Anything else in the header: an icon, a second line. Drawn in screen coordinates. */
    protected void drawHeaderExtras(GuiGraphics graphics, float delta) {
    }

    /** Anything that must sit above the panel, such as a tooltip. */
    protected void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    protected boolean inList(double mouseX, double mouseY) {
        return mouseX >= panelLeft && mouseX < panelLeft + panelWidth
                && mouseY >= listTop() && mouseY < listBottom();
    }

    private void drawBorder(GuiGraphics graphics) {
        int right = panelLeft + panelWidth - 1;
        int bottom = panelTop + panelHeight - 1;
        graphics.hLine(panelLeft, right, panelTop, PANEL_BORDER);
        graphics.hLine(panelLeft, right, bottom, PANEL_BORDER);
        graphics.vLine(panelLeft, panelTop, bottom, PANEL_BORDER);
        graphics.vLine(right, panelTop, bottom, PANEL_BORDER);
    }

    private void drawScrollbar(GuiGraphics graphics) {
        int viewHeight = listHeight();
        int content = contentHeight();
        if (content <= viewHeight) {
            return;
        }
        int barHeight = Math.max(16, viewHeight * viewHeight / content);
        int barTop = listTop() + (int) (scroll * (viewHeight - barHeight) / (content - viewHeight));
        int right = panelLeft + panelWidth - 3;
        graphics.fill(right - 4, listTop(), right, listBottom(), SCROLL_TRACK);
        graphics.fill(right - 4, barTop, right, barTop + barHeight, SCROLL_BAR);
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        boolean inside = mouseX >= panelLeft && mouseX < panelLeft + panelWidth
                && mouseY >= panelTop && mouseY < panelTop + panelHeight;
        if (!inside) {
            // Clicking the graph behind the panel dismisses it, which is the quickest way back.
            back();
            return true;
        }
        if (mouseY >= listTop() && mouseY < listBottom()) {
            clickRow(mouseY - listTop() + scroll, button);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scroll -= amount * 24;
        clampScroll();
        return true;
    }

    protected void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight() - listHeight()), scroll));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            back();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Back to the graph this panel was opened from. */
    protected void back() {
        if (parent != null) {
            // The same screen object, so its pan and zoom are exactly where they were left.
            minecraft.setScreen(parent);
        } else {
            ProcessTreeNavigation.openGraphScreen();
        }
    }

    @Override
    public void onClose() {
        ProcessTreeNavigation.close();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
