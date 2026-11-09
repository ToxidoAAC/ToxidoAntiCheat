package dev.toxido.anticheat.app;

import org.fusesource.jansi.AnsiColors;
import org.fusesource.jansi.AnsiConsole;
import org.fusesource.jansi.AnsiPrintStream;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Konsol cikisi icin ortak katman. Windows kod sayfasini UTF-8'e alir,
 * boyutlari tespit eder ve Jansi ile sanal terminali etkinlestirir.
 */
final class ConsoleOutput implements AutoCloseable {
    private static final Pattern CODE_PAGE = Pattern.compile("(\\d{3,5})");

    private final PrintStream out;
    private final boolean unicode;
    private final int width;
    private final int height;
    private final AnsiColors colors;
    private final int originalCodePage;
    private final boolean jansiInstalled;
    private boolean closed;

    private ConsoleOutput(
        PrintStream out,
        boolean unicode,
        int width,
        int height,
        AnsiColors colors,
        int originalCodePage,
        boolean jansiInstalled
    ) {
        this.out = out;
        this.unicode = unicode;
        this.width = width;
        this.height = height;
        this.colors = colors;
        this.originalCodePage = originalCodePage;
        this.jansiInstalled = jansiInstalled;
    }

    static ConsoleOutput open() {
        boolean jansiInstalled = false;
        try {
            AnsiConsole.systemInstall();
            jansiInstalled = true;
        } catch (Throwable ignored) {
            // Jansi olmadan duz ANSI ile devam edilir.
        }

        AnsiColors colors = AnsiColors.Colors16;
        try {
            AnsiPrintStream jansiOut = AnsiConsole.out();
            if (jansiOut != null && jansiOut.getColors() != null) {
                colors = jansiOut.getColors();
            }
        } catch (Throwable ignored) {
            // Renk modu tespit edilemezse 16 renk kullanilir.
        }

        int originalCodePage = isWindows() ? queryCodePage() : 65001;
        int currentCodePage = originalCodePage;
        if (isWindows() && originalCodePage != 65001) {
            setCodePage(65001);
            currentCodePage = queryCodePage();
        }

        boolean unicode = currentCodePage == 65001 || stdoutIsUtf8();
        int width = detectWidth();
        int height = detectHeight();

        PrintStream utf8 = new PrintStream(
            new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

        return new ConsoleOutput(
            utf8, unicode, width, height, colors, originalCodePage, jansiInstalled);
    }

    PrintStream out() {
        return out;
    }

    boolean unicode() {
        return unicode;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    AnsiColors colors() {
        return colors;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.flush();
        } catch (Exception ignored) {
            // Konsol kapanmis olabilir.
        }
        if (isWindows() && originalCodePage > 0 && originalCodePage != 65001) {
            setCodePage(originalCodePage);
        }
        if (jansiInstalled) {
            try {
                AnsiConsole.systemUninstall();
            } catch (Throwable ignored) {
                // Jansi kurulu degildiyse yoksay.
            }
        }
    }

    private static int detectWidth() {
        int width = 0;
        try {
            width = AnsiConsole.getTerminalWidth();
        } catch (Throwable ignored) {
            // Asagidaki ortam degiskenlerine bakilir.
        }
        if (width <= 0) {
            width = intEnv("COLUMNS");
        }
        if (width <= 0) {
            width = 100;
        }
        return clamp(width, 24, 160);
    }

    private static int detectHeight() {
        int height = intEnv("LINES");
        if (height <= 0) {
            height = 30;
        }
        return clamp(height, 16, 50);
    }

    private static int intEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int queryCodePage() {
        try {
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", "chcp");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            byte[] bytes = process.getInputStream().readAllBytes();
            process.waitFor(2, TimeUnit.SECONDS);
            Matcher matcher = CODE_PAGE.matcher(new String(bytes, StandardCharsets.ISO_8859_1));
            int codePage = 0;
            while (matcher.find()) {
                codePage = Integer.parseInt(matcher.group(1));
            }
            return codePage;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void setCodePage(int codePage) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                "cmd.exe", "/c", "chcp", Integer.toString(codePage));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Kod sayfasi degistirilemezse ASCII logoya dusulur.
        }
    }

    private static boolean stdoutIsUtf8() {
        String encoding = System.getProperty("stdout.encoding", "");
        if (encoding.isBlank()) {
            encoding = System.getProperty("sun.stdout.encoding", "");
        }
        return encoding.toLowerCase(Locale.ROOT).replace("-", "").contains("utf8");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
