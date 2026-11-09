package dev.toxido.anticheat.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Locale;

/**
 * Minecraft'in Netty pipeline'ini bulur ve giden paketleri izleyen
 * handler'i {@code packet_handler}'dan once yerlestirir.
 *
 * <p>Minecraft siniflarina bytecode enjekte edilmez. Boylece Fabric
 * KnotClassLoader'in ajan siniflarini cozememesi sorunu ortadan kalkar.
 * Kanal henuz hazir degilse 1.5 saniyede bir yeniden denenir; baglanti
 * koptugunda yeni kanal otomatik olarak yakalanir.</p>
 */
final class NettyHook {
    private static final String HANDLER_NAME = "toxido_packet_hook";
    private static final long RETRY_MS = 1_500L;
    private static final String[] MINECRAFT_NAMES = {
        "net.minecraft.class_310",
        "net.minecraft.client.Minecraft",
        "net.minecraft.client.MinecraftClient"
    };

    /*
     * Fabric 1.21.11 intermediary sabitleri. Bunlar runtime'da arama
     * yapmadan dogrudan kullanilir; boylece method_1562 gibi bir getter
     * null donse bile client world uzerinden network handler'a ulasilir.
     */
    private static final String MC_12111_CLASS = "net.minecraft.class_310";
    private static final String MC_12111_WORLD_FIELD = "field_1724";
    private static final String WORLD_12111_NETWORK_HANDLER_FIELD = "field_3944";
    private static final String NETWORK_HANDLER_12111_CLASS = "net.minecraft.class_634";
    private static final String NETWORK_HANDLER_12111_CONNECTION_METHOD = "method_48296";
    private static final String CONNECTION_12111_CLASS = "net.minecraft.class_2535";
    private static final String CONNECTION_12111_CHANNEL_FIELD = "field_11651";

    private static volatile String lastState = "";
    private static volatile boolean handlerLoadFailed;

    private NettyHook() {
    }

    static void start(Instrumentation instrumentation, AgentClient client) {
        Thread thread = new Thread(
            () -> loop(instrumentation, client),
            "40x1d0AC-netty-hook"
        );
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop(Instrumentation instrumentation, AgentClient client) {
        AgentMain.log("Netty hook thread basladi");
        Class<?> minecraftClass = null;
        while (true) {
            try {
                if (minecraftClass == null) {
                    minecraftClass = findMinecraft(instrumentation);
                }
                if (minecraftClass == null) {
                    state(client, "waiting_for_minecraft_class");
                    sleep();
                    continue;
                }

                Object minecraft = staticInstance(minecraftClass);
                if (minecraft == null) {
                    state(client, "waiting_for_minecraft_instance " + brief(minecraftClass));
                    sleep();
                    continue;
                }

                Object networkHandler = findNetworkHandler(minecraft);
                if (networkHandler == null) {
                    state(client, "waiting_for_network_handler " + brief(minecraft.getClass()));
                    sleep();
                    continue;
                }

                Object connection = findConnection(networkHandler);
                if (connection == null) {
                    state(client, "waiting_for_connection " + brief(networkHandler.getClass()));
                    sleep();
                    continue;
                }

                Object channel = findChannel(connection);
                if (channel == null || !isOpen(channel)) {
                    state(
                        client,
                        "waiting_for_channel " + channelDiagnostic(connection)
                    );
                    sleep();
                    continue;
                }

                Object pipeline = invokeNoArg(channel, "pipeline");
                if (pipeline == null) {
                    state(client, "waiting_for_pipeline " + brief(channel.getClass()));
                    sleep();
                    continue;
                }

                Object packetHandler = pipelineGet(pipeline, "packet_handler");
                if (packetHandler == null) {
                    state(client, "waiting_for_packet_handler " + brief(pipeline.getClass()));
                    sleep();
                    continue;
                }

                if (ensureHandler(pipeline, client)) {
                    lastState = "active";
                    Phase2.nettyActive(
                        "netty:packet_handler",
                        "netty_packet_handler"
                    );
                }
            } catch (Throwable throwable) {
                state(client, "netty_error " + brief(throwable.getClass()) + ": "
                    + trim(throwable.getMessage(), 90));
                AgentMain.debug("[NETTY] error: " + throwable);
            }
            sleep();
        }
    }

    private static boolean ensureHandler(Object pipeline, AgentClient client) {
        Object existing = pipelineGet(pipeline, HANDLER_NAME);
        if (existing != null) {
            if (Proxy.isProxyClass(existing.getClass())
                && Proxy.getInvocationHandler(existing) instanceof OutboundProxy) {
                return true;
            }
            removeHandler(pipeline);
        }

        Object handler;
        try {
            handler = createProxy(pipeline.getClass().getClassLoader());
        } catch (Throwable throwable) {
            handlerLoadFailed = true;
            AgentMain.log("Netty proxy handler olusturulamadi: " + throwable);
            state(client, "netty_handler_failed " + brief(throwable.getClass())
                + ": " + trim(throwable.getMessage(), 90));
            return false;
        }
        if (handler == null) {
            state(client, "netty_class_unavailable "
                + brief(pipeline.getClass().getClassLoader()));
            return false;
        }

        Method addBefore = findMethod(pipeline.getClass(), "addBefore", 3);
        if (addBefore == null) {
            state(client, "netty_add_before_missing");
            return false;
        }

        try {
            addBefore.setAccessible(true);
            addBefore.invoke(
                pipeline, "packet_handler", HANDLER_NAME, handler
            );
            AgentMain.log("Netty packet hook yerlestirildi (proxy): packet_handler");
            AgentMain.debug("[NETTY] handler=toxido_packet_hook added");
            return true;
        } catch (Throwable throwable) {
            AgentMain.log("Netty handler pipeline'a eklenemedi: " + throwable);
            state(client, "netty_pipeline_failed " + brief(throwable.getClass())
                + ": " + trim(throwable.getMessage(), 90));
            return false;
        }
    }

    /**
     * Minecraft'in Netty siniflari Fabric/Knot classloader'inda yuklu.
     * Ajanin kendi Netty kopyasiyla olusturulan bir handler pipeline'a
     * eklenemez (ChannelHandler sinif kimligi farkli). Bu nedenle handler'i
     * oyunun classloader'inda, game Netty arayuzlerini uygulayan bir
     * dinamik proxy olarak olusturuyoruz.
     */
    private static Object createProxy(ClassLoader loader) {
        if (loader == null || handlerLoadFailed) {
            return null;
        }
        try {
            Class<?> outbound = Class.forName(
                "io.netty.channel.ChannelOutboundHandler", false, loader
            );
            return Proxy.newProxyInstance(
                loader,
                new Class<?>[]{outbound},
                new OutboundProxy(loader)
            );
        } catch (Throwable throwable) {
            AgentMain.log("Netty proxy sinifi yuklenemedi: " + throwable);
            return null;
        }
    }

    private static final class OutboundProxy implements InvocationHandler {
        private final Class<?> contextType;

        private OutboundProxy(ClassLoader loader) throws ClassNotFoundException {
            this.contextType = Class.forName(
                "io.netty.channel.ChannelHandlerContext", false, loader
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                return "ToxidoPacketHandler";
            }
            if ("handlerAdded".equals(name) || "handlerRemoved".equals(name)) {
                return null;
            }
            if ("isSharable".equals(name)) {
                return Boolean.FALSE;
            }
            if ("exceptionCaught".equals(name) && args != null && args.length >= 2) {
                Method fire = contextType.getMethod(
                    "fireExceptionCaught", Throwable.class
                );
                fire.invoke(args[0], args[1]);
                return null;
            }
            if ("write".equals(name) && args != null && args.length >= 2) {
                try {
                    HookBridge.onPacket(args[1], "netty:packet_handler");
                } catch (Throwable ignored) {
                    // Paket incelemesi basarisiz olsa da yazma devam etmeli.
                }
            }
            return forward(method, args);
        }

        private Object forward(Method method, Object[] args) throws Throwable {
            if (args == null || args.length == 0) {
                return null;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            Class<?>[] contextParameters =
                new Class<?>[parameterTypes.length - 1];
            System.arraycopy(
                parameterTypes, 1, contextParameters, 0, contextParameters.length
            );
            Object[] contextArgs = new Object[args.length - 1];
            System.arraycopy(args, 1, contextArgs, 0, contextArgs.length);
            try {
                Method target = contextType.getMethod(
                    method.getName(), contextParameters
                );
                target.setAccessible(true);
                return target.invoke(args[0], contextArgs);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
    }

    private static void removeHandler(Object pipeline) {
        Method remove = findMethod(pipeline.getClass(), "remove", 1);
        if (remove == null) {
            return;
        }
        try {
            remove.setAccessible(true);
            remove.invoke(pipeline, HANDLER_NAME);
        } catch (Throwable ignored) {
            // Eski handler zaten kaldirilmis olabilir.
        }
    }

    private static void state(AgentClient client, String reason) {
        String value = trim(reason, 220);
        if (value.equals(lastState)) {
            return;
        }
        lastState = value;
        AgentMain.debug("[NETTY] state=" + value);
        Phase2.nettyWaiting(value);
    }

    private static String brief(Class<?> type) {
        if (type == null) {
            return "-";
        }
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String brief(Object value) {
        return value == null ? "-" : brief(value.getClass());
    }

    private static String brief(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        return brief(loader.getClass());
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    private static String channelDiagnostic(Object connection) {
        StringBuilder builder = new StringBuilder("conn=");
        builder.append(brief(connection.getClass()));
        builder.append(" fields=");
        boolean first = true;
        for (Field field : allFields(connection.getClass())) {
            if (isChannelType(field.getType())) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(field.getName());
            }
        }
        builder.append(" methods=");
        first = true;
        for (Method method : allMethods(connection.getClass())) {
            if (method.getParameterCount() == 0
                && !Modifier.isStatic(method.getModifiers())
                && isChannelType(method.getReturnType())) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(method.getName());
            }
        }
        return builder.toString();
    }

    private static Class<?> findMinecraft(Instrumentation instrumentation) {
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        for (String name : MINECRAFT_NAMES) {
            for (Class<?> candidate : loaded) {
                if (name.equals(candidate.getName())) {
                    return candidate;
                }
            }
        }
        for (Class<?> candidate : loaded) {
            String simple = candidate.getSimpleName();
            if ("Minecraft".equals(simple) || "MinecraftClient".equals(simple)) {
                return candidate;
            }
        }
        return null;
    }

    private static Object staticInstance(Class<?> type) {
        for (Method method : allMethods(type)) {
            if (!Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 0
                || !type.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!"getinstance".equals(name)
                && !"method_1551".equals(name)
                && !"m_91087_".equals(name)
                && !"func_71410_x".equals(name)) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object findNetworkHandler(Object minecraft) {
        if (MC_12111_CLASS.equals(minecraft.getClass().getName())) {
            Object world = readField(minecraft, MC_12111_WORLD_FIELD);
            if (world != null) {
                Object direct = readField(world, WORLD_12111_NETWORK_HANDLER_FIELD);
                if (direct != null
                    && NETWORK_HANDLER_12111_CLASS.equals(direct.getClass().getName())) {
                    return direct;
                }
            }
        }

        Object named = invokeNoArg(
            minecraft,
            "method_1562",
            "getNetworkHandler",
            "getPlayNetworkHandler",
            "m_1562_"
        );
        if (named != null) {
            return named;
        }

        for (Field field : allFields(minecraft.getClass())) {
            Class<?> type = field.getType();
            if (type == minecraft.getClass()) {
                continue;
            }
            if (isConnectionType(type)
                || isNetworkHandlerType(type)
                || hasConnectionReturn(type)) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(minecraft);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        Object world = findWorld(minecraft);
        if (world != null) {
            Object fromWorld = networkHandlerFromWorld(world);
            if (fromWorld != null) {
                return fromWorld;
            }
        }
        return null;
    }

    private static Object findWorld(Object minecraft) {
        for (Field field : allFields(minecraft.getClass())) {
            Class<?> type = field.getType();
            String name = type.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("class_746")
                && !name.contains("clientworld")
                && !name.contains("class_638")) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(minecraft);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object networkHandlerFromWorld(Object world) {
        if (isConnectionType(world.getClass()) || isNetworkHandlerType(world.getClass())) {
            return world;
        }
        Object named = invokeNoArg(
            world,
            "getNetworkHandler",
            "method_1562",
            "m_1562_",
            "getConnection"
        );
        if (named != null) {
            return named;
        }
        for (Field field : allFields(world.getClass())) {
            Class<?> type = field.getType();
            if (!isConnectionType(type) && !isNetworkHandlerType(type)) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(world);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isNetworkHandlerType(Class<?> type) {
        String name = type.getName().toLowerCase(Locale.ROOT);
        return name.contains("class_634")
            || name.contains("class_635")
            || name.contains("networkhandler")
            || name.contains("playnetworkhandler");
    }

    private static boolean hasConnectionReturn(Class<?> type) {
        for (Method method : allMethods(type)) {
            if (method.getParameterCount() == 0
                && !Modifier.isStatic(method.getModifiers())
                && isConnectionType(method.getReturnType())) {
                return true;
            }
        }
        return false;
    }

    private static Object findConnection(Object networkHandler) {
        if (isConnectionType(networkHandler.getClass())) {
            return networkHandler;
        }

        if (NETWORK_HANDLER_12111_CLASS.equals(networkHandler.getClass().getName())) {
            Object direct = invokeNoArg(
                networkHandler,
                NETWORK_HANDLER_12111_CONNECTION_METHOD
            );
            if (direct != null
                && CONNECTION_12111_CLASS.equals(direct.getClass().getName())) {
                return direct;
            }
        }

        Object named = invokeNoArg(
            networkHandler,
            "getConnection",
            "method_48296",
            "method_2877",
            "m_48296_",
            "m_2877_",
            "getClientConnection"
        );
        if (named != null && isConnectionType(named.getClass())) {
            return named;
        }

        for (Field field : allFields(networkHandler.getClass())) {
            if (!isConnectionType(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(networkHandler);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }

        for (Method method : allMethods(networkHandler.getClass())) {
            if (method.getParameterCount() != 0
                || Modifier.isStatic(method.getModifiers())
                || !isConnectionType(method.getReturnType())) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(networkHandler);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object findChannel(Object connection) {
        if (CONNECTION_12111_CLASS.equals(connection.getClass().getName())) {
            Object direct = readField(connection, CONNECTION_12111_CHANNEL_FIELD);
            if (direct != null) {
                return direct;
            }
        }

        Object fallback = null;
        for (Field field : allFields(connection.getClass())) {
            if (!isChannelType(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(connection);
                if (value != null) {
                    if (isOpen(value)) {
                        return value;
                    }
                    if (fallback == null) {
                        fallback = value;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        Object named = invokeNoArg(
            connection,
            "getChannel",
            "channel",
            "method_10745",
            "m_10745_"
        );
        if (named != null && isChannelType(named.getClass())) {
            if (isOpen(named)) {
                return named;
            }
            if (fallback == null) {
                fallback = named;
            }
        }

        for (Method method : allMethods(connection.getClass())) {
            if (method.getParameterCount() != 0
                || Modifier.isStatic(method.getModifiers())
                || !isChannelType(method.getReturnType())) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(connection);
                if (value != null) {
                    if (isOpen(value)) {
                        return value;
                    }
                    if (fallback == null) {
                        fallback = value;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    private static boolean isConnectionType(Class<?> type) {
        String name = type.getName().toLowerCase(Locale.ROOT);
        return name.contains("class_2535")
            || name.contains("clientconnection")
            || name.endsWith(".connection");
    }

    private static boolean isChannelType(Class<?> type) {
        return "io.netty.channel.Channel".equals(type.getName());
    }

    private static boolean isOpen(Object channel) {
        Object value = invokeNoArg(channel, "isOpen");
        return !(value instanceof Boolean) || (Boolean) value;
    }

    private static Object pipelineGet(Object pipeline, String name) {
        Method get = findMethod(pipeline.getClass(), "get", 1);
        if (get == null) {
            return null;
        }
        try {
            get.setAccessible(true);
            return get.invoke(pipeline, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readField(Object target, String name) {
        for (Field field : allFields(target.getClass())) {
            if (!name.equals(field.getName())) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String... names) {
        for (Method method : allMethods(target.getClass())) {
            if (method.getParameterCount() != 0
                || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String actual = method.getName().toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (!actual.equals(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(target);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Method findMethod(
        Class<?> type,
        String name,
        int parameterCount
    ) {
        for (Method method : allMethods(type)) {
            if (method.getParameterCount() == parameterCount
                && name.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    private static Method[] allMethods(Class<?> type) {
        java.util.LinkedHashMap<String, Method> methods =
            new java.util.LinkedHashMap<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                methods.putIfAbsent(signature(method), method);
            }
        }
        for (Method method : type.getMethods()) {
            methods.putIfAbsent(signature(method), method);
        }
        return methods.values().toArray(new Method[0]);
    }

    private static String signature(Method method) {
        StringBuilder builder = new StringBuilder(method.getName());
        for (Class<?> parameter : method.getParameterTypes()) {
            builder.append(';').append(parameter.getName());
        }
        return builder.toString();
    }

    private static Field[] allFields(Class<?> type) {
        java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
        }
        return fields.toArray(new Field[0]);
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_MS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
