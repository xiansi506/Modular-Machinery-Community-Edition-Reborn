package com.reborn.modularmachinery.menu;

/** Shared slot geometry and hit testing for the menu and its screen. */
public final class ProviderScreenLayout {
    public static final int WIDTH = 256, HEIGHT = 196;
    public static final int GRID_X = 8, GRID_Y = 28, GRID_WIDTH = 162, GRID_HEIGHT = 72;
    public static final int ROW_HEIGHT = 18, VISIBLE_ROWS = 4;
    public static final int SCROLL_X = 164, SCROLL_WIDTH = 6;
    public static final int MANUAL_X = 184, MANUAL_Y = 35, FLUID_X = 224;
    public static final int CONTROLS_X = 179, CONTROLS_WIDTH = 68;
    private ProviderScreenLayout() { }

    public static boolean inGrid(double x, double y) {
        return x >= GRID_X && x < GRID_X + GRID_WIDTH && y >= GRID_Y && y < GRID_Y + GRID_HEIGHT;
    }

    public static int patternSlotAt(double x, double y) {
        if (!inGrid(x, y)) return -1;
        double localX = x - GRID_X, localY = y - GRID_Y;
        int col = (int) (localX / 18), row = (int) (localY / 18);
        return localX % 18 < 16 && localY % 18 < 16 ? col + row * 9 : -1;
    }

    public static Integer parseGroup(String text) {
        try {
            int group = Integer.parseInt(text);
            return group >= -1 && group <= 65535 ? group : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    public static int maxScroll(int entries) { return Math.max(0, entries - VISIBLE_ROWS); }
}
