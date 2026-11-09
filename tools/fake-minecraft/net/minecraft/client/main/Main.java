package net.minecraft.client.main;

/**
 * Gercek Minecraft olmadan attach/ajan akisini test etmek icin sahte istemci.
 * Calistirma:
 *   javac -d tools/fake-minecraft/classes tools/fake-minecraft/net/minecraft/client/main/Main.java
 *   java -cp tools/fake-minecraft/classes net.minecraft.client.main.Main \
 *        --username TestUser --uuid 00000000-0000-0000-0000-000000000000 --version 1.20.1
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("Fake Minecraft started: " + String.join(" ", args));
        System.out.println("PID: " + ProcessHandle.current().pid());
        Thread.sleep(Long.MAX_VALUE);
    }
}
