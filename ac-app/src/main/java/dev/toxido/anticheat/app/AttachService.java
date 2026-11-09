package dev.toxido.anticheat.app;

import com.sun.tools.attach.VirtualMachine;

import java.nio.file.Path;

/**
 * JVM Attach API ile ajan jar'ini hedef Minecraft JVM'ine yukler.
 * Mod yukleyici gerektirmez.
 */
public final class AttachService {
    private AttachService() {
    }

    public static void loadAgent(long pid, Path agentJar, String agentArgs) throws Exception {
        VirtualMachine machine = VirtualMachine.attach(Long.toString(pid));
        try {
            machine.loadAgent(agentJar.toAbsolutePath().toString(), agentArgs);
        } finally {
            try {
                machine.detach();
            } catch (Exception ignored) {
                // Detach basarisiz olsa da islem tamamlanmis olabilir.
            }
        }
    }
}
