package dev.toxido.anticheat.app;

/**
 * Iki logo varyanti: genis terminal icin blok harfler, dar veya UTF-8
 * desteklemeyen konsollar icin ince ASCII harfler.
 */
final class AsciiLogo {
    private AsciiLogo() {
    }

    static final String[] WIDE = build(new String[][] {
        { // T
            "████████╗",
            "╚══██╔══╝",
            "   ██║   ",
            "   ██║   ",
            "   ██║   ",
            "   ╚═╝   "
        },
        { // O
            " ██████╗ ",
            "██╔═══██╗",
            "██║   ██║",
            "██║   ██║",
            "╚██████╔╝",
            " ╚═════╝ "
        },
        { // X
            "██╗  ██╗",
            "╚██╗██╔╝",
            " ╚███╔╝ ",
            " ██╔██╗ ",
            "██╔╝ ██╗",
            "╚═╝  ╚═╝"
        },
        { // I
            "██╗",
            "██║",
            "██║",
            "██║",
            "██║",
            "╚═╝"
        },
        { // D
            "██████╗ ",
            "██╔══██╗",
            "██║  ██║",
            "██║  ██║",
            "██████╔╝",
            "╚═════╝ "
        },
        { // O
            " ██████╗ ",
            "██╔═══██╗",
            "██║   ██║",
            "██║   ██║",
            "╚██████╔╝",
            " ╚═════╝ "
        }
    }, 2);

    static final String[] COMPACT = build(new String[][] {
        { // T
            " _____ ",
            "|_   _|",
            "  | |  ",
            "  | |  ",
            "  |_|  "
        },
        { // O
            "  ___  ",
            " / _ \\ ",
            "| | | |",
            "| |_| |",
            " \\___/ "
        },
        { // X
            "__  __",
            "\\ \\/ /",
            " >  < ",
            "/ . \\ ",
            "/_/\\_\\"
        },
        { // I
            " ___ ",
            "|_ _|",
            " | | ",
            " | | ",
            "|___|"
        },
        { // D
            " ____  ",
            "|  _ \\ ",
            "| | | |",
            "| |_| |",
            "|____/ "
        },
        { // O
            "  ___  ",
            " / _ \\ ",
            "| | | |",
            "| |_| |",
            " \\___/ "
        }
    }, 1);

    private static String[] build(String[][] glyphs, int spacing) {
        int rows = glyphs[0].length;
        int[] widths = new int[glyphs.length];
        for (int glyph = 0; glyph < glyphs.length; glyph++) {
            for (String line : glyphs[glyph]) {
                widths[glyph] = Math.max(widths[glyph], line.length());
            }
        }

        String[] result = new String[rows];
        for (int row = 0; row < rows; row++) {
            StringBuilder line = new StringBuilder();
            for (int glyph = 0; glyph < glyphs.length; glyph++) {
                if (glyph > 0) {
                    line.append(" ".repeat(spacing));
                }
                String part = glyphs[glyph][row];
                line.append(part);
                line.append(" ".repeat(widths[glyph] - part.length()));
            }
            result[row] = line.toString();
        }
        return result;
    }
}
