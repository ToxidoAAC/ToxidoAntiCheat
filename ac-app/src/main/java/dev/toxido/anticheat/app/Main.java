package dev.toxido.anticheat.app;

import java.util.Arrays;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--service".equals(args[0])) {
            ServiceMain.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        boolean preview = args.length > 0 && "--preview".equals(args[0]);
        new TerminalSplash(preview).run();
    }
}
