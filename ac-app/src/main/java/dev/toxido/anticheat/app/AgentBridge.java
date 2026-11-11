package dev.toxido.anticheat.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ajan ile uygulama arasindaki loopback IPC koprusu.
 * Uygulama sunucu tarafini acar; ajan baglanip el sikismasi yapar.
 */
public final class AgentBridge implements Closeable {
    public interface Listener {
        void onAgentReady();
        void onHeartbeat();
        void onCapability(JsonObject payload);
        void onEvent(String event, String key, JsonObject payload);
        void onDisconnected(String reason);
    }

    private final ServerSocket server;
    private final String token = UUID.randomUUID().toString();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    /** Her basarili el sikismasinda artar; kopma bildiriminin eskimis olup olmadigini ayirir. */
    private volatile long generation;

    private volatile Socket socket;
    private volatile BufferedWriter out;
    private volatile Listener listener;

    public AgentBridge() throws Exception {
        this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    public int port() {
        return server.getLocalPort();
    }

    public String token() {
        return token;
    }

    public void start(Listener listener) {
        this.listener = listener;
        Thread thread = new Thread(this::acceptLoop, "toxido-ac-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket candidate = server.accept();
                handle(candidate);
            } catch (Exception exception) {
                if (!closed.get()) {
                    // Tek bir kabul hatasi kopruyu kalici olarak kapatmasin.
                    sleepQuietly(200L);
                }
            }
        }
    }

    private void handle(Socket candidate) {
        Listener current = this.listener;
        long sessionGeneration = -1L;
        try {
            candidate.setSoTimeout(30_000);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(candidate.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(candidate.getOutputStream(), StandardCharsets.UTF_8));

            String helloLine = in.readLine();
            if (helloLine == null) {
                closeQuietly(candidate);
                return;
            }

            JsonObject hello = JsonParser.parseString(helloLine).getAsJsonObject();
            if (!"hello".equals(hello.get("type").getAsString())
                || !token.equals(hello.get("token").getAsString())) {
                closeQuietly(candidate);
                return;
            }

            this.socket = candidate;
            this.out = writer;
            sessionGeneration = ++generation;
            this.disconnected.set(false);
            send("{\"type\":\"hello_ack\"}");

            if (current != null) {
                current.onAgentReady();
            }

            String line;
            while ((line = in.readLine()) != null) {
                try {
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    String type = message.get("type").getAsString();
                    if (current == null) {
                        continue;
                    }
                    if ("heartbeat".equals(type)) {
                        current.onHeartbeat();
                    } else if ("capability".equals(type)) {
                        current.onCapability(payload(message));
                    } else if ("event".equals(type)) {
                        String event = message.has("event")
                            ? message.get("event").getAsString() : "unknown";
                        String key = message.has("key")
                            ? message.get("key").getAsString() : "";
                        current.onEvent(event, key, payload(message));
                    }
                } catch (Throwable listenerError) {
                    // Tek bir mesajin islenmesi kopruyu dusurmesin.
                }
            }

            release(candidate);
            scheduleDisconnect("agent_disconnected", sessionGeneration);
        } catch (Exception exception) {
            release(candidate);
            scheduleDisconnect("agent_error", sessionGeneration);
        }
    }

    private void release(Socket candidate) {
        closeQuietly(candidate);
        if (this.socket == candidate) {
            this.socket = null;
            this.out = null;
        }
    }

    /**
     * Kopmayi hemen bildirmez. Ajan kisa sure icinde yeniden baglanirsa
     * oturum kapanmaz; grace suresi sonunda hala baglanti yoksa bildirir.
     */
    private void scheduleDisconnect(String reason, long expectedGeneration) {
        Thread thread = new Thread(() -> {
            sleepQuietly(8000L);
            if (closed.get() || generation != expectedGeneration) {
                return;
            }
            Socket active = this.socket;
            if (active != null && active.isConnected() && !active.isClosed()) {
                return;
            }
            notifyDisconnected(reason);
        }, "toxido-ac-bridge-grace");
        thread.setDaemon(true);
        thread.start();
    }

    private static JsonObject payload(JsonObject message) {
        if (message.has("payload") && message.get("payload").isJsonObject()) {
            return message.getAsJsonObject("payload");
        }
        return new JsonObject();
    }

    public synchronized void send(String json) {
        if (out == null) {
            return;
        }
        try {
            out.write(json);
            out.newLine();
            out.flush();
        } catch (Exception ignored) {
            // Kopru kapanirken yazma hatasi normaldir.
        }
    }

    private void notifyDisconnected(String reason) {
        if (disconnected.compareAndSet(false, true)) {
            Listener current = this.listener;
            if (current != null) {
                current.onDisconnected(reason);
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        closeQuietly(socket);
        try {
            server.close();
        } catch (Exception ignored) {
            // Zaten kapali olabilir.
        }
        notifyDisconnected("bridge_closed");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // Yoksay.
            }
        }
    }
}
