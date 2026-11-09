package dev.toxido.anticheat.agent;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Enjekte edilen bytecode'un cagirdigi kopru. Bu sinif bootstrap
 * classloader'a eklenen ajan jar'inda bulunur; Minecraft classloader'i
 * tarafindan da gorulebilir.
 */
public final class HookBridge {
    private static volatile Consumer<AttackEvent> sink;
    private static volatile BiConsumer<Object, String> packetSink;

    private HookBridge() {
    }

    public static void setSink(Consumer<AttackEvent> newSink) {
        sink = newSink;
    }

    public static void setPacketSink(BiConsumer<Object, String> newSink) {
        packetSink = newSink;
    }

    public static void onAttack(Object player, Object target, String hook) {
        Consumer<AttackEvent> current = sink;
        if (current != null && player != null && target != null) {
            current.accept(new AttackEvent(System.currentTimeMillis(), player, target, hook));
        }
    }

    public static void onPacket(Object packet, String hook) {
        BiConsumer<Object, String> current = packetSink;
        if (current != null && packet != null) {
            current.accept(packet, hook);
        }
    }
}
