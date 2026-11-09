import java.util.concurrent.atomic.AtomicInteger;

public final class ProcessScan {
    public static void main(String[] args) {
        AtomicInteger matches = new AtomicInteger();
        ProcessHandle.allProcesses()
            .filter(ProcessHandle::isAlive)
            .forEach(handle -> {
                String commandLine = handle.info().commandLine().orElse("");
                if (commandLine.contains("net.minecraft.client.main.Main")) {
                    matches.incrementAndGet();
                    System.out.println("MATCH pid=" + handle.pid() + " cmd=" + commandLine);
                }
            });
        System.out.println("matches=" + matches.get());
    }
}
