package dev.toxido.anticheat.app;

import org.fusesource.jansi.AnsiColors;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Terminal splash sahnesi: blok TOXIDO yazisi, soldan saga tarama efekti,
 * altinda diff tabanli Matrix yagmuru ve kucuk bir Enter ipucu.
 */
final class SplashScreen implements AutoCloseable {
    private static final String ESC = "\033[";
    private static final String MATRIX_GLYPHS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<>[]{}/\\|=+-*#@$%&";

    private final PrintStream out;
    private final int width;
    private final int height;
    private final Palette palette;
    private final String[] logo;
    private final int logoWidth;
    private final int logoHeight;
    private final int logoTop;
    private final int logoLeft;
    private final int rainTop;
    private final int rainLeft;
    private final int rainWidth;
    private final int rainHeight;
    private final Rain rain;
    private final SideOrb leftOrb;
    private final SideOrb rightOrb;
    private final long startNanos;

    private boolean started;
    private boolean closed;
    private boolean logoDone;
    private int lastRevealColumns;
    private boolean hintVisible;
    private String error;
    private String drawnError;

    SplashScreen(ConsoleOutput console) {
        this.out = console.out();
        this.width = console.width();
        this.height = console.height();
        this.palette = new Palette(console.colors());

        String[] selected = console.unicode() && width >= 64
            ? AsciiLogo.WIDE
            : AsciiLogo.COMPACT;
        this.logo = selected;
        this.logoHeight = selected.length;
        this.logoWidth = maxWidth(selected);
        this.logoTop = clamp((height - logoHeight) / 4, 2, 5);
        this.logoLeft = Math.max(1, (width - logoWidth) / 2 + 1);

        this.rainTop = logoTop + logoHeight + 2;
        this.rainLeft = 1;
        this.rainWidth = Math.max(20, width);
        int available = height - rainTop - 1;
        this.rainHeight = Math.max(3, available);
        this.rain = new Rain(rainWidth, rainHeight);

        int leftSpace = Math.max(0, logoLeft - 1);
        int rightSpace = Math.max(0, width - (logoLeft + logoWidth - 1));
        int sideSpace = Math.min(leftSpace, rightSpace);
        int radiusX = Math.min(12, Math.max(0, (sideSpace - 4) / 2));
        int radiusY = radiusX >= 9 ? 3 : radiusX >= 5 ? 2 : 1;
        int gap = Math.max(2, sideSpace - radiusX * 2);
        int orbRow = logoTop + (logoHeight - 1) / 2;
        if (radiusX > 0) {
            int leftCenter = logoLeft - gap - radiusX;
            int rightCenter = logoLeft + logoWidth - 1 + gap + radiusX;
            this.leftOrb = new SideOrb(leftCenter, orbRow, radiusX, radiusY, 0, 1);
            this.rightOrb = new SideOrb(rightCenter, orbRow, radiusX, radiusY, 17, -1);
        } else {
            this.leftOrb = null;
            this.rightOrb = null;
        }
        this.startNanos = System.nanoTime();
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        out.print(ESC + "?1049h"
            + ESC + "40m"
            + ESC + "2J"
            + ESC + "H"
            + ESC + "?25l");
        out.print(cursor(height, 1)
            + palette.background()
            + " ".repeat(Math.max(1, width))
            + palette.foregroundReset());
        drawGhost();
        out.flush();
    }

    void render() {
        if (!started || closed) {
            return;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        drawLogoReveal(elapsedMs);
        drawOrbs(elapsedMs);
        rain.update();
        rain.render(out, palette, rainTop, rainLeft);
        drawBottomLine(elapsedMs);
        out.flush();
    }

    void setError(String message) {
        if (message != null && !message.isBlank()) {
            this.error = message;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.print(ESC + "0m" + ESC + "?25h" + ESC + "?1049l");
            out.flush();
        } catch (Exception ignored) {
            // Konsol kapanmis olabilir.
        }
    }

    private void drawGhost() {
        for (int row = 0; row < logoHeight; row++) {
            String line = logo[row];
            for (int column = 0; column < line.length(); column++) {
                char glyph = line.charAt(column);
                if (glyph == ' ') {
                    continue;
                }
                if (glyph == '█') {
                    out.print(cursor(logoTop + row, logoLeft + column)
                        + palette.ghostBackground()
                        + palette.ghostForeground()
                        + "█"
                        + palette.background()
                        + palette.foregroundReset());
                } else {
                    out.print(cursor(logoTop + row, logoLeft + column)
                        + palette.ghostForeground()
                        + glyph
                        + palette.foregroundReset());
                }
            }
        }
    }

    private void drawLogoReveal(long elapsedMs) {
        if (logoDone) {
            return;
        }

        double reveal = Math.min(1.0, elapsedMs / 850.0);
        int columns = (int) Math.round(reveal * logoWidth);
        columns = Math.max(0, Math.min(logoWidth, columns));
        boolean complete = reveal >= 1.0;

        if (columns > lastRevealColumns) {
            drawLogoColumns(lastRevealColumns, columns, false);
        }

        if (lastRevealColumns > 0) {
            int previousEdge = Math.max(0, lastRevealColumns - 3);
            drawLogoColumns(previousEdge, lastRevealColumns, false);
        }

        if (complete) {
            logoDone = true;
            lastRevealColumns = columns;
            return;
        }

        int edgeStart = Math.max(0, columns - 3);
        drawLogoColumns(edgeStart, columns, true);
        lastRevealColumns = columns;
    }

    private void drawLogoColumns(int from, int to, boolean scan) {
        if (to <= from) {
            return;
        }
        for (int row = 0; row < logoHeight; row++) {
            String line = logo[row];
            int limit = Math.min(to, line.length());
            for (int column = Math.max(0, from); column < limit; column++) {
                char glyph = line.charAt(column);
                if (glyph == ' ') {
                    continue;
                }
                int screenRow = logoTop + row;
                int screenColumn = logoLeft + column;
                if (glyph == '█') {
                    out.print(cursor(screenRow, screenColumn)
                        + (scan ? palette.scanBackground() : palette.logoBackground(row))
                        + (scan ? palette.scanForeground() : palette.logoForeground(row))
                        + "█"
                        + palette.background()
                        + palette.foregroundReset());
                } else {
                    out.print(cursor(screenRow, screenColumn)
                        + (scan ? palette.scanForeground() : palette.logoForeground(row))
                        + glyph
                        + palette.foregroundReset());
                }
            }
        }
    }

    private void drawOrbs(long elapsedMs) {
        if (leftOrb != null) {
            leftOrb.draw(out, palette, elapsedMs);
        }
        if (rightOrb != null) {
            rightOrb.draw(out, palette, elapsedMs);
        }
    }

    private void drawBottomLine(long elapsedMs) {
        if (error != null) {
            if (!error.equals(drawnError)) {
                clearHint();
                String text = fit(error, Math.max(1, width - 2));
                int column = Math.max(1, (width - text.length()) / 2 + 1);
                out.print(cursor(height - 1, column)
                    + palette.red()
                    + text
                    + palette.foregroundReset());
                drawnError = error;
            }
            return;
        }

        if (drawnError != null) {
            out.print(cursor(height, 1) + palette.background()
                + " ".repeat(Math.max(1, width - 1))
                + palette.foregroundReset());
            drawnError = null;
        }

        if (hintVisible) {
            return;
        }
        String hint = "[ enter ]";
        int column = Math.max(1, (width - hint.length()) / 2 + 1);
        out.print(cursor(height - 1, column)
            + palette.dim()
            + hint
            + palette.foregroundReset());
        hintVisible = true;
    }

    private void clearHint() {
        if (!hintVisible) {
            return;
        }
        String hint = "[ enter ]";
        int column = Math.max(1, (width - hint.length()) / 2 + 1);
        out.print(cursor(height - 1, column)
            + palette.background()
            + " ".repeat(hint.length())
            + palette.foregroundReset());
        hintVisible = false;
    }

    private static int maxWidth(String[] lines) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, line.length());
        }
        return width;
    }

    private static String fit(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(1, max));
    }

    private static String cursor(int row, int column) {
        return ESC + row + ";" + column + "H";
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Palette {
        private final AnsiColors mode;
        private final int[][] logoRgb = {
            {196, 255, 222},
            {150, 255, 200},
            {105, 245, 178},
            {68, 225, 150},
            {38, 190, 118},
            {22, 150, 86}
        };
        private final int[] logo256 = {159, 121, 85, 49, 35, 22};
        private final int[] logo16 = {92, 92, 92, 32, 32, 32};

        private Palette(AnsiColors mode) {
            this.mode = mode == null ? AnsiColors.Colors16 : mode;
        }

        String logo(int row) {
            if (mode == AnsiColors.TrueColor) {
                int[] rgb = logoRgb[Math.min(row, logoRgb.length - 1)];
                return fgRgb(rgb[0], rgb[1], rgb[2]);
            }
            if (mode == AnsiColors.Colors256) {
                return fg256(logo256[Math.min(row, logo256.length - 1)]);
            }
            return fg16(logo16[Math.min(row, logo16.length - 1)]);
        }

        String logoForeground(int row) {
            return logo(row);
        }

        String logoBackground(int row) {
            if (mode == AnsiColors.TrueColor) {
                int[] rgb = logoRgb[Math.min(row, logoRgb.length - 1)];
                return bgRgb(rgb[0], rgb[1], rgb[2]);
            }
            if (mode == AnsiColors.Colors256) {
                return bg256(logo256[Math.min(row, logo256.length - 1)]);
            }
            return bg16(row < 3 ? 102 : 42);
        }

        String ghostForeground() {
            return ghost();
        }

        String ghostBackground() {
            if (mode == AnsiColors.TrueColor) {
                return bgRgb(0, 35, 18);
            }
            if (mode == AnsiColors.Colors256) {
                return bg256(22);
            }
            return bg16(42);
        }

        String scanForeground() {
            return scan();
        }

        String scanBackground() {
            if (mode == AnsiColors.TrueColor) {
                return bgRgb(238, 255, 247);
            }
            if (mode == AnsiColors.Colors256) {
                return bg256(159);
            }
            return bg16(107);
        }

        String rain(int shade) {
            if (mode == AnsiColors.TrueColor) {
                return switch (shade) {
                    case 4 -> fgRgb(215, 255, 232);
                    case 3 -> fgRgb(120, 255, 180);
                    case 2 -> fgRgb(48, 210, 125);
                    default -> fgRgb(18, 110, 62);
                };
            }
            if (mode == AnsiColors.Colors256) {
                return switch (shade) {
                    case 4 -> fg256(159);
                    case 3 -> fg256(121);
                    case 2 -> fg256(40);
                    default -> fg256(22);
                };
            }
            return switch (shade) {
                case 4 -> fg16(97);
                case 3 -> fg16(92);
                default -> fg16(32);
            };
        }

        String ghost() {
            if (mode == AnsiColors.TrueColor) {
                return fgRgb(0, 58, 30);
            }
            if (mode == AnsiColors.Colors256) {
                return fg256(22);
            }
            return fg16(32);
        }

        String scan() {
            if (mode == AnsiColors.TrueColor) {
                return fgRgb(238, 255, 247);
            }
            if (mode == AnsiColors.Colors256) {
                return fg256(159);
            }
            return fg16(97);
        }

        String dim() {
            if (mode == AnsiColors.TrueColor) {
                return fgRgb(40, 130, 80);
            }
            if (mode == AnsiColors.Colors256) {
                return fg256(35);
            }
            return fg16(32);
        }

        String red() {
            if (mode == AnsiColors.TrueColor) {
                return fgRgb(255, 90, 90);
            }
            if (mode == AnsiColors.Colors256) {
                return fg256(196);
            }
            return fg16(91);
        }

        String background() {
            return ESC + "40m";
        }

        String foregroundReset() {
            return ESC + "39m";
        }

        private static String fgRgb(int red, int green, int blue) {
            return ESC + "38;2;" + red + ";" + green + ";" + blue + "m";
        }

        private static String fg256(int code) {
            return ESC + "38;5;" + code + "m";
        }

        private static String fg16(int code) {
            return ESC + code + "m";
        }

        private static String bgRgb(int red, int green, int blue) {
            return ESC + "48;2;" + red + ";" + green + ";" + blue + "m";
        }

        private static String bg256(int code) {
            return ESC + "48;5;" + code + "m";
        }

        private static String bg16(int code) {
            return ESC + code + "m";
        }
    }

    private static final class SideOrb {
        private final int centerColumn;
        private final int centerRow;
        private final int radiusX;
        private final int radiusY;
        private final int phaseOffset;
        private final int direction;
        private final int[][] points;

        private SideOrb(
            int centerColumn,
            int centerRow,
            int radiusX,
            int radiusY,
            int phaseOffset,
            int direction
        ) {
            this.centerColumn = centerColumn;
            this.centerRow = centerRow;
            this.radiusX = radiusX;
            this.radiusY = radiusY;
            this.phaseOffset = phaseOffset;
            this.direction = direction;
            this.points = buildPoints();
        }

        private int[][] buildPoints() {
            List<int[]> found = new ArrayList<>();
            boolean[][] seen = new boolean[radiusY * 2 + 1][radiusX * 2 + 1];
            for (int step = 0; step < 120; step++) {
                double angle = Math.PI * 2.0 * step / 120.0;
                int x = (int) Math.round(radiusX * Math.cos(angle));
                int y = (int) Math.round(radiusY * Math.sin(angle));
                int row = y + radiusY;
                int column = x + radiusX;
                if (!seen[row][column]) {
                    seen[row][column] = true;
                    found.add(new int[] {x, y});
                }
            }
            return found.toArray(new int[0][]);
        }

        private void draw(PrintStream out, Palette palette, long elapsedMs) {
            int count = points.length;
            if (count == 0) {
                return;
            }
            int step = (int) (elapsedMs / 75L);
            int head = Math.floorMod(step * direction + phaseOffset, count);

            for (int index = 0; index < count; index++) {
                int distance = Math.floorMod(direction * (head - index), count);
                char glyph;
                String color;
                if (distance == 0) {
                    glyph = '●';
                    color = palette.scan();
                } else if (distance <= 2) {
                    glyph = '•';
                    color = palette.rain(3);
                } else if (distance <= 5) {
                    glyph = '·';
                    color = palette.rain(2);
                } else {
                    glyph = '○';
                    color = palette.ghost();
                }
                out.print(cursor(
                    centerRow + points[index][1],
                    centerColumn + points[index][0])
                    + color
                    + glyph
                    + palette.foregroundReset());
            }
        }
    }

    private static final class Rain {
        private final int width;
        private final int height;
        private final char[][] chars;
        private final byte[][] shades;
        private final byte[][] previous;
        private final Column[] columns;
        private final Random random = new Random();

        private Rain(int width, int height) {
            this.width = width;
            this.height = height;
            this.chars = new char[height][width];
            this.shades = new byte[height][width];
            this.previous = new byte[height][width];
            this.columns = new Column[width];
            for (int column = 0; column < width; column++) {
                this.columns[column] = new Column();
                reset(this.columns[column], true);
            }
        }

        private void update() {
            for (int row = 0; row < height; row++) {
                System.arraycopy(shades[row], 0, previous[row], 0, width);
            }

            for (int column = 0; column < width; column++) {
                Column state = columns[column];
                state.head += state.speed;
                if (state.head - state.length > height + 2) {
                    reset(state, false);
                }

                for (int row = 0; row < height; row++) {
                    double distance = state.head - row;
                    int shade = 0;
                    if (distance >= 0 && distance < state.length) {
                        shade = 4 - (int) (distance * 4.0 / state.length);
                        if (shade < 1) {
                            shade = 1;
                        }
                    }

                    if (shade > 0 && shades[row][column] == 0) {
                        chars[row][column] = randomGlyph();
                    } else if (shade == 0) {
                        chars[row][column] = ' ';
                    }
                    shades[row][column] = (byte) shade;
                }
            }
        }

        private void render(PrintStream out, Palette palette, int top, int left) {
            for (int row = 0; row < height; row++) {
                int runStart = -1;
                int runShade = -1;
                StringBuilder run = new StringBuilder();

                for (int column = 0; column < width; column++) {
                    int shade = shades[row][column];
                    if (shade != previous[row][column]) {
                        if (runStart >= 0
                            && column == runStart + run.length()
                            && shade == runShade) {
                            run.append(chars[row][column]);
                        } else {
                            flush(out, palette, top + row, left + runStart, runShade, run);
                            runStart = column;
                            runShade = shade;
                            run.setLength(0);
                            run.append(chars[row][column]);
                        }
                    } else {
                        flush(out, palette, top + row, left + runStart, runShade, run);
                        runStart = -1;
                        run.setLength(0);
                    }
                }

                flush(out, palette, top + row, left + runStart, runShade, run);
            }
        }

        private static void flush(
            PrintStream out,
            Palette palette,
            int row,
            int column,
            int shade,
            StringBuilder run
        ) {
            if (column < 0 || run.length() == 0) {
                return;
            }
            String color = shade == 0 ? palette.background() : palette.rain(shade);
            out.print(cursor(row, column)
                + color
                + run
                + palette.foregroundReset());
        }

        private void reset(Column column, boolean initial) {
            if (initial) {
                if (random.nextBoolean()) {
                    column.head = random.nextDouble() * height;
                } else {
                    column.head = -random.nextDouble() * height * 0.7 - 1;
                }
            } else {
                column.head = -1 - random.nextInt(Math.max(1, height / 2));
            }
            column.speed = 0.45 + random.nextDouble() * 0.85;
            column.length = 7 + random.nextInt(12);
        }

        private char randomGlyph() {
            return MATRIX_GLYPHS.charAt(random.nextInt(MATRIX_GLYPHS.length()));
        }

        private static final class Column {
            private double head;
            private double speed;
            private int length;
        }
    }
}
