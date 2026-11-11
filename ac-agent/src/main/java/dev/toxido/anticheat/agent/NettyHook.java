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

    private static volatile String lastState = "";
    private static volatile boolean handlerLoadFailed;
    private static volatile String instanceReason = "not_started";
    private static volatile Instrumentation instrumentationRef;
    private static volatile long lastInstanceScanAt;
    private static volatile Object minecraftInstance;
    private static volatile Class<?> minecraftClass;
    private static volatile String stepNote = "baslatiliyor";
    private static volatile long stepSince = System.currentTimeMillis();
    private static volatile String versionHint = "unknown";
    private static volatile String gameDir = "";
    private static volatile String profileName = "";
    private static volatile VersionProfile profile;

    private NettyHook() {
    }

    static void start(Instrumentation instrumentation, AgentClient client) {
        start(instrumentation, client, "unknown", "", "");
    }

    /** Panelden gelen /restart isteginde profil secimini sifirlar. */
    static void restart() {
        profile = null;
        lastState = "";
        handlerLoadFailed = false;
        stepNote = "restart";
        stepSince = System.currentTimeMillis();
        Phase2.nettyHeartbeat("restart");
    }

    static void start(
        Instrumentation instrumentation,
        AgentClient client,
        String hint,
        String directory,
        String requestedProfile
    ) {
        versionHint = hint == null ? "unknown" : hint;
        gameDir = directory == null ? "" : directory;
        profileName = requestedProfile == null ? "" : requestedProfile;
        instrumentationRef = instrumentation;
        Thread thread = new Thread(
            () -> loop(instrumentation, client),
            "40x1d0AC-netty-hook"
        );
        thread.setDaemon(true);
        thread.start();
        startWatchdog();
    }

    /**
     * Netty halkasi bir adimda kilitlenirse bunu gorunur kilar. Halkayi
     * zorla kesmek yerine hangi adimda ve kac saniyedir takildigini raporlar.
     */
    private static void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            long lastWarnAt = 0L;
            while (true) {
                try {
                    Thread.sleep(3000L);
                    long since = stepSince;
                    if (since <= 0L) {
                        continue;
                    }
                    long age = System.currentTimeMillis() - since;
                    if (age < 8000L) {
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastWarnAt < 15000L) {
                        continue;
                    }
                    lastWarnAt = now;
                    long seconds = age / 1000L;
                    AgentMain.log(
                        "UYARI: Netty adimi " + seconds + " sn takildi: " + stepNote
                    );
                    Phase2.nettyNote("kilit:" + stepNote + "@" + seconds + "s");
                } catch (InterruptedException interrupted) {
                    return;
                } catch (Throwable throwable) {
                    AgentMain.log("Netty watchdog hatasi: " + throwable);
                }
            }
        }, "40x1d0AC-netty-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Su an calisan Netty adimini kaydeder; watchdog bunu izler. Bilerek
     * capability'ye yazilmaz: her turda degisen adim adi panoyu ve gunlugu
     * doldururdu. Yalnizca gercek kilitlenme nettyNote ile raporlanir.
     */
    private static void step(String name) {
        stepNote = name;
        stepSince = System.currentTimeMillis();
    }

    private static final long TICK_TIMEOUT_MS = 10000L;
    private static final java.util.concurrent.atomic.AtomicInteger stallCount =
        new java.util.concurrent.atomic.AtomicInteger();
    /** Ayni anda en fazla kac tur thread'i yasayabilir (kilitlenme koruması). */
    private static final java.util.concurrent.atomic.AtomicInteger liveTicks =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_LIVE_TICKS = 4;
    /** Takilan turlar birikse bile bu sinirdan sonra yeni tur acilmaz. */
    private static final int HARD_MAX_LIVE_TICKS = 12;
    /** Bu sure gectiyse, takili turlar olsa da yeni bir deneme acilir. */
    private static final long PROBE_INTERVAL_MS = 20000L;
    private static volatile long lastTickStart;
    /** Teshis: son sinif taramasinda toplam yuklu sinif ve bulunan aday sayisi. */
    private static volatile int scanTotal;
    private static volatile int scanCandidates;

    static int scanTotal() {
        return scanTotal;
    }

    static int scanCandidates() {
        return scanCandidates;
    }

    /**
     * Netty halkasi. Her tur ayri bir thread'de ve sure siniriyla calisir;
     * boylece oyun acilirken classloader kilidine takilan tek bir cagri
     * ajanin geri kalanini (pano, kurulum, heartbeat) dondurmez. Tur
     * zaman asimina ugrarsa yeniden denenir; ust uste uc kez takilirsa
     * profil ve ornek onbellegi sifirlanip bastan aranir.
     */
    private static void loop(Instrumentation instrumentation, AgentClient client) {
        AgentMain.log("Netty hook thread basladi");
        while (true) {
            if (liveTicks.get() >= MAX_LIVE_TICKS) {
                boolean probe = System.currentTimeMillis() - lastTickStart
                    >= PROBE_INTERVAL_MS;
                if (!probe || liveTicks.get() >= HARD_MAX_LIVE_TICKS) {
                    Phase2.nettyNote(
                        "kilit:" + stepNote + "@" + liveTicks.get() + "thread"
                    );
                    sleep();
                    continue;
                }
                AgentMain.log(
                    "Netty taramasi yeniden deneniyor ("
                        + liveTicks.get() + " takili tur)"
                );
            }
            lastTickStart = System.currentTimeMillis();
            Thread tick = new Thread(
                () -> {
                    liveTicks.incrementAndGet();
                    try {
                        tick(instrumentation, client);
                    } finally {
                        liveTicks.decrementAndGet();
                    }
                },
                "40x1d0AC-netty-tick"
            );
            tick.setDaemon(true);
            tick.start();
            try {
                tick.join(TICK_TIMEOUT_MS);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (tick.isAlive()) {
                int stalls = stallCount.incrementAndGet();
                Phase2.nettyNote("kilit:" + stepNote + "@" + stalls + "x");
                if (stalls == 1 || stalls % 5 == 0) {
                    AgentMain.log(
                        "UYARI: Netty adimi " + (TICK_TIMEOUT_MS / 1000L)
                            + " sn icinde bitmedi, yeniden denenecek: " + stepNote
                    );
                }
                if (stalls >= 3) {
                    stallCount.set(0);
                    minecraftInstance = null;
                    minecraftClass = null;
                    profile = null;
                    GameVersionDetector.clearCache();
                    Phase2.nettyNote("sifirla:" + stepNote);
                }
            } else {
                stallCount.set(0);
            }
            sleep();
        }
    }

    private static void tick(Instrumentation instrumentation, AgentClient client) {
        try {
            step("dongu");
            Phase2.nettyHeartbeat(
                profile == null ? "loop:detecting" : "loop:" + profile.id
            );
            if (profile == null) {
                step("surum-tespiti");
                GameVersionDetector.Result early = GameVersionDetector.detect(
                    null, gameDir, profileName
                );
                VersionCatalog.Resolved resolved = VersionProfiles.resolve(
                    early.version, early.loader, early.mapping
                );
                Phase2.versionDetected(early, resolved.profile, resolved);
                if (resolved.ok()) {
                    profile = resolved.profile;
                    VersionProfiles.setActive(profile);
                    AgentMain.log(
                        "Surum profili secildi: " + profile.id
                            + " (" + early.summary() + ")"
                            + " katalog=" + resolved.reason
                            + " dogrulandi=" + profile.verified
                            + " loaderDogrulandi=" + profile.loaderVerified
                            + " eksik=" + (resolved.missing.isEmpty() ? "-" : resolved.missing)
                    );
                }
            }

            if (minecraftInstance == null) {
                step("sinif-arama");
                java.util.List<Class<?>> candidates =
                    findMinecraftClasses(instrumentation, profile);
                if (candidates.isEmpty()) {
                    state(client, "waiting_for_minecraft_class");
                    return;
                }
                minecraftClass = candidates.get(0);
                Object found = null;
                for (Class<?> candidate : candidates) {
                    step("ornek:" + brief(candidate));
                    found = staticInstance(candidate);
                    if (found != null) {
                        minecraftClass = candidate;
                        break;
                    }
                }
                if (found == null) {
                    Phase2.instanceNote(instanceReason);
                    state(
                        client,
                        "waiting_for_minecraft_instance " + brief(minecraftClass)
                    );
                    return;
                }
                minecraftInstance = found;
                Phase2.instanceNote("ok:" + brief(found.getClass()));
                AgentMain.log("Minecraft ornegi bulundu: " + instanceReason);
            }

            if (profile == null || !profileMatches(profile, minecraftClass)) {
                step("profil-duzeltme");
                GameVersionDetector.Result detected = GameVersionDetector.detect(
                    minecraftClass, gameDir, profileName
                );
                VersionCatalog.Resolved resolved = VersionProfiles.resolve(
                    detected.version, detected.loader, detected.mapping
                );
                Phase2.versionDetected(detected, resolved.profile, resolved);
                if (!resolved.ok()) {
                    state(
                        client,
                        "unsupported_version " + detected.summary()
                            + " " + resolved.reason
                    );
                    return;
                }
                VersionProfile selected = resolved.profile;
                if (profile != null && !profile.id.equals(selected.id)) {
                    AgentMain.log(
                        "Surum profili duzeltildi: " + profile.id
                            + " -> " + selected.id + " (" + detected.summary() + ")"
                    );
                }
                profile = selected;
                VersionProfiles.setActive(selected);
                AgentMain.log(
                    "Surum profili secildi: " + selected.id
                        + " (" + detected.summary() + ")"
                        + " katalog=" + resolved.reason
                        + " dogrulandi=" + selected.verified
                        + " loaderDogrulandi=" + selected.loaderVerified
                        + " eksik=" + (resolved.missing.isEmpty() ? "-" : resolved.missing)
                );
            }

            Object minecraft = minecraftInstance;

            step("connection");
            Object connection = null;
            // 26.x istemcilerinde Minecraft.getConnection() dogrudan
            // baglantiya ulasir; network handler ara adimina gerek yok.
            if (profile != null && profile.minecraftConnectionMethod != null) {
                Object direct = invokeNoArg(
                    minecraft, profile.minecraftConnectionMethod
                );
                if (direct != null && isConnectionType(direct.getClass())) {
                    connection = direct;
                }
            }
            if (connection == null) {
                step("network-handler");
                Object networkHandler = findNetworkHandler(minecraft);
                if (networkHandler == null) {
                    state(
                        client,
                        "waiting_for_network_handler " + brief(minecraft.getClass())
                    );
                    return;
                }

                step("connection");
                connection = findConnection(networkHandler);
                if (connection == null) {
                    state(
                        client,
                        "waiting_for_connection " + brief(networkHandler.getClass())
                    );
                    return;
                }
            }

            step("channel");
            Object channel = findChannel(connection);
            if (channel == null || !isOpen(channel)) {
                state(
                    client,
                    "waiting_for_channel " + channelDiagnostic(connection)
                );
                return;
            }

            step("pipeline");
            Object pipeline = invokeNoArg(channel, "pipeline");
            if (pipeline == null) {
                state(client, "waiting_for_pipeline " + brief(channel.getClass()));
                return;
            }

            String handlerName = profile == null
                || profile.pipelineHandler == null
                || profile.pipelineHandler.isEmpty()
                ? "packet_handler"
                : profile.pipelineHandler;
            Object packetHandler = pipelineGet(pipeline, handlerName);
            if (packetHandler == null) {
                state(
                    client,
                    "waiting_for_packet_handler " + handlerName
                        + " " + brief(pipeline.getClass())
                );
                return;
            }

            step("packet-handler");
            if (ensureHandler(pipeline, client, handlerName)) {
                lastState = "active";
                stepSince = 0L;
                Phase2.nettyNote("hazir:netty:" + handlerName);
                Phase2.nettyActive(
                    "netty:" + handlerName,
                    "netty_packet_handler"
                );
            }
        } catch (Throwable throwable) {
            state(client, "netty_error " + brief(throwable.getClass()) + ": "
                + trim(throwable.getMessage(), 90));
            AgentMain.debug("[NETTY] error: " + throwable);
        }
    }

    private static boolean ensureHandler(
        Object pipeline,
        AgentClient client,
        String handlerName
    ) {
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
            handler = createProxy(
                pipeline.getClass().getClassLoader(),
                "netty:" + handlerName
            );
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
                pipeline, handlerName, HANDLER_NAME, handler
            );
            AgentMain.log(
                "Netty packet hook yerlestirildi (proxy): " + handlerName
            );
            AgentMain.debug("[NETTY] handler=toxido_packet_hook added before="
                + handlerName);
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
    private static Object createProxy(ClassLoader loader, String label) {
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
                new OutboundProxy(loader, label)
            );
        } catch (Throwable throwable) {
            AgentMain.log("Netty proxy sinifi yuklenemedi: " + throwable);
            return null;
        }
    }

    private static final class OutboundProxy implements InvocationHandler {
        private final Class<?> contextType;
        private final String label;

        private OutboundProxy(ClassLoader loader, String label)
            throws ClassNotFoundException {
            this.contextType = Class.forName(
                "io.netty.channel.ChannelHandlerContext", false, loader
            );
            this.label = label;
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
                    HookBridge.onPacket(args[1], label);
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
        // Heartbeat dedupe'den once yazilir: ayni durum tekrar etse bile
        // panoda "netty_age" canli kalir.
        Phase2.nettyHeartbeat("state:" + value);
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

    /**
     * Ayni ada sahip birden fazla Minecraft sinifi olabilir (Knot ve oyun
     * sinif yukleyicileri). Hepsini sirayla deneriz; ornegi olan kazanir.
     *
     * <p>Burada Class.forName/loadClass ASLA kullanilmaz: oyun acilirken
     * Fabric'in classloader kilidiyle karsilikli kilitlenme (deadlock)
     * olusuyor. Yalnizca oyun hazir olduktan sonra, JVMTI'nin yuklu sinif
     * listesi okunur.</p>
     */
    private static java.util.List<Class<?>> findMinecraftClasses(
        Instrumentation instrumentation,
        VersionProfile active
    ) {
        if (!GameReadiness.isReady(gameDir)) {
            return new java.util.ArrayList<>();
        }
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        scanTotal = loaded.length;
        java.util.LinkedHashSet<Class<?>> found = new java.util.LinkedHashSet<>();
        if (active != null && active.minecraftClass != null) {
            for (Class<?> candidate : loaded) {
                if (classMatches(candidate, active.minecraftClass)) {
                    found.add(candidate);
                }
            }
        }
        for (String name : MINECRAFT_NAMES) {
            for (Class<?> candidate : loaded) {
                if (name.equals(candidate.getName())) {
                    found.add(candidate);
                }
            }
        }
        if (found.isEmpty()) {
            for (Class<?> candidate : loaded) {
                String simple = candidate.getSimpleName();
                if ("Minecraft".equals(simple) || "MinecraftClient".equals(simple)) {
                    found.add(candidate);
                }
            }
        }
        scanCandidates = found.size();
        return new java.util.ArrayList<>(found);
    }

    private static Object staticInstance(Class<?> type) {
        String failure = "";

        // 1) Statik alanlar once denenir. Minecraft kendi ornegini alanda
        //    tutar; bu yol shock/sinif yukleyici farklarindan etkilenmez.
        for (Field field : allFields(type)) {
            if (!Modifier.isStatic(field.getModifiers())
                || !type.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) {
                    instanceReason = "field:" + field.getName();
                    return value;
                }
            } catch (Throwable throwable) {
                if (failure.isEmpty()) {
                    failure = "field " + field.getName() + " -> " + brief(throwable);
                }
            }
        }

        // 2) Bilinen erisimci adlari (mojang, intermediary, obfuscated).
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
                Object value = method.invoke(null);
                if (value != null) {
                    instanceReason = "method:" + method.getName();
                    return value;
                }
            } catch (Throwable throwable) {
                if (failure.isEmpty()) {
                    failure = "method " + method.getName() + " -> " + brief(throwable);
                }
            }
        }

        // 3) Vanilla obfuscated istemcide erisimci adi da obfuscated olur;
        //    Minecraft donduren static no-arg metodu sekle gore dene.
        for (Method method : allMethods(type)) {
            if (!Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 0
                || !type.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(null);
                if (value != null) {
                    instanceReason = "shape:" + method.getName();
                    return value;
                }
            } catch (Throwable throwable) {
                if (failure.isEmpty()) {
                    failure = "shape " + method.getName() + " -> " + brief(throwable);
                }
            }
        }

        // 4) Tum yuklu siniflarda tarama KALDIRILDI: sinif baslatma kilitlerine
        //    girip Netty halkasini sessizce donduruyordu. Onun yerine ayni ada
        //    sahip sinif adaylari tek tek denenir (findMinecraftClasses).
        instanceReason = failure.isEmpty() ? "no_accessor" : failure;
        return null;
    }

    private static Object findNetworkHandler(Object minecraft) {
        VersionProfile active = profile;
        if (active != null && classMatches(minecraft.getClass(), active.minecraftClass)) {
            Object world = readField(minecraft, active.worldField);
            if (world != null) {
                Object direct = readField(world, active.networkHandlerField);
                if (direct != null
                    && (classMatches(direct.getClass(), active.networkHandlerClass)
                        || isNetworkHandlerType(direct.getClass()))) {
                    return direct;
                }
            }
        }

        Object named = invokeNoArg(
            minecraft,
            "method_1562",
            "getNetworkHandler",
            "getPlayNetworkHandler",
            "getConnection",
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
                && !name.contains("class_638")
                && !name.contains("clientlevel")) {
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
        VersionProfile active = profile;
        if (active != null && classMatches(type, active.networkHandlerClass)) {
            return true;
        }
        String name = type.getName().toLowerCase(Locale.ROOT);
        return name.contains("class_634")
            || name.contains("class_635")
            || name.contains("networkhandler")
            || name.contains("playnetworkhandler")
            || name.contains("clientpacketlistener");
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

        VersionProfile active = profile;
        if (active != null
            && classMatches(networkHandler.getClass(), active.networkHandlerClass)) {
            Object direct = null;
            if (active.connectionMethod != null
                && !active.connectionMethod.isEmpty()) {
                direct = invokeNoArg(networkHandler, active.connectionMethod);
            }
            if (direct == null
                && active.connectionField != null
                && !active.connectionField.isEmpty()) {
                direct = readField(networkHandler, active.connectionField);
            }
            if (direct != null
                && (classMatches(direct.getClass(), active.connectionClass)
                    || isConnectionType(direct.getClass()))) {
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
        VersionProfile active = profile;
        if (active != null
            && classMatches(connection.getClass(), active.connectionClass)) {
            Object direct = null;
            if (active.channelField != null && !active.channelField.isEmpty()) {
                direct = readField(connection, active.channelField);
            }
            if (direct == null
                && active.channelMethod != null
                && !active.channelMethod.isEmpty()) {
                direct = invokeNoArg(connection, active.channelMethod);
            }
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
        VersionProfile active = profile;
        if (active != null && classMatches(type, active.connectionClass)) {
            return true;
        }
        String name = type.getName().toLowerCase(Locale.ROOT);
        return name.contains("class_2535")
            || name.contains("clientconnection")
            || name.endsWith(".connection");
    }

    private static boolean classMatches(Class<?> type, String expected) {
        if (type == null || expected == null || expected.isEmpty()) {
            return false;
        }
        if (expected.equals(type.getName())) {
            return true;
        }
        return expected.indexOf('.') < 0
            && expected.equals(type.getSimpleName());
    }

    /**
     * Bulunan Minecraft sinifi aktif profilin bekledigi sinifla uyusuyor mu?
     * Erken secim loader'i goremedigi icin intermediary profile kilitlenmis
     * olabilir; sinif gorundugunde bu kontrol profilin duzeltilmesini saglar.
     */
    private static boolean profileMatches(VersionProfile active, Class<?> type) {
        if (active == null || active.minecraftClass == null) {
            return true;
        }
        return classMatches(type, active.minecraftClass);
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
