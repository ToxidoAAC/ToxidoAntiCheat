package dev.toxido.anticheat.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

/** Oyun durumunu reflection ile okur; surumden bagimsiz isim aramalari kullanir. */
final class GameState {
    final boolean reliable;
    final boolean screenOpen;
    final boolean attackKeyPressed;
    final boolean targetLiving;
    final boolean targetPlayer;
    final boolean targetArmorStand;
    final boolean targetInTabList;
    final String targetType;
    final String targetName;
    final String targetUuid;

    private GameState(
        boolean reliable,
        boolean screenOpen,
        boolean attackKeyPressed,
        boolean targetLiving,
        boolean targetPlayer,
        boolean targetArmorStand,
        boolean targetInTabList,
        String targetType,
        String targetName,
        String targetUuid
    ) {
        this.reliable = reliable;
        this.screenOpen = screenOpen;
        this.attackKeyPressed = attackKeyPressed;
        this.targetLiving = targetLiving;
        this.targetPlayer = targetPlayer;
        this.targetArmorStand = targetArmorStand;
        this.targetInTabList = targetInTabList;
        this.targetType = targetType;
        this.targetName = targetName;
        this.targetUuid = targetUuid;
    }

    static GameState inspect(VersionResolver.Target target, Object targetObject) {
        boolean screenOpen = false;
        boolean attackKeyPressed = false;
        boolean reliable = false;
        VersionProfile profile = target.profile != null
            ? target.profile
            : VersionProfiles.active();

        try {
            Object minecraft = staticInstance(target.minecraft);
            if (minecraft != null) {
                reliable = true;
                Object screen = profile == null
                    ? null
                    : fieldByNames(minecraft, profile.screenField);
                if (screen == null) {
                    screen = fieldByNames(
                        minecraft, "currentScreen", "field_1755", "screen"
                    );
                }
                if (screen == null && profile != null) {
                    screen = fieldOfType(minecraft, profile.screenClass);
                }
                if (screen == null) {
                    screen = fieldByType(minecraft, "screen", "class_437");
                }
                screenOpen = screen != null;

                Object options = profile == null
                    ? null
                    : fieldByNames(minecraft, profile.optionsField);
                if (options == null) {
                    options = fieldByNames(
                        minecraft, "options", "field_1690", "gameOptions"
                    );
                }
                if (options == null && profile != null) {
                    options = fieldOfType(minecraft, profile.optionsClass);
                }
                if (options == null) {
                    options = fieldByType(
                        minecraft, "class_315", "gameoptions", "options"
                    );
                }
                if (options == null) {
                    options = optionsType(minecraft);
                }
                if (options != null) {
                    // Mojang: keyAttack | Yarn: attackKey | obf: field_1839
                    Object attackKey = profile == null
                        ? null
                        : fieldByNames(options, profile.attackKeyField);
                    if (attackKey == null) {
                        attackKey = fieldByNames(
                            options, "attackKey", "keyAttack", "field_1839"
                        );
                    }
                    if (attackKey == null && profile != null) {
                        attackKey = fieldOfType(options, profile.keyMappingClass);
                    }
                    if (attackKey == null) {
                        attackKey = fieldByTypeAndName(options, "keymapping", "attack");
                    }
                    if (attackKey == null) {
                        attackKey = fieldByTypeAndName(options, "keybinding", "attack");
                    }
                    if (attackKey == null) {
                        attackKey = fieldByTypeAndName(options, "class_304", "attack");
                    }
                    // Mojang isDown | Yarn isPressed | SRG m_90857_
                    String keyDown = profile == null ? null : profile.keyDownMethod;
                    attackKeyPressed = (keyDown != null && bool(attackKey, keyDown))
                        || bool(
                            attackKey,
                            "isPressed",
                            "isDown",
                            "method_1434",
                            "m_90857_",
                            "func_151468_f"
                        );
                }
            }
        } catch (Throwable throwable) {
            AgentMain.log("Oyun durumu okunamadi: " + throwable);
        }

        String type = targetObject.getClass().getName();
        boolean living = (profile != null
            && matchesHierarchy(targetObject.getClass(), profile.livingClass))
            || isLiving(targetObject.getClass());
        boolean player = (profile != null
            && matchesHierarchy(targetObject.getClass(), profile.playerEntityClass))
            || isPlayer(targetObject.getClass());
        boolean armorStand = (profile != null
            && matchesHierarchy(targetObject.getClass(), profile.armorStandClass))
            || hasName(targetObject.getClass(), "armorstand", "class_1531");
        boolean inTabList = true;
        UUID targetUuid = uuid(targetObject);

        if (player) {
            try {
                Object minecraft = staticInstance(target.minecraft);
                Object handler = invokeNoArg(
                    minecraft,
                    "getNetworkHandler",
                    "getConnection",
                    "method_1562",
                    "m_91403_"
                );
                if (handler == null && minecraft != null) {
                    if (profile != null && profile.networkHandlerClass != null) {
                        handler = invokeReturning(minecraft, profile.networkHandlerClass);
                    }
                    if (handler == null) {
                        handler = invokeReturning(
                            minecraft,
                            "clientpacketlistener",
                            "clientplaynetworkhandler",
                            "class_634"
                        );
                    }
                }
                if (handler != null && targetUuid != null) {
                    Object entry = invokeUuid(
                        handler,
                        targetUuid,
                        "getPlayerListEntry",
                        "getPlayerInfo",
                        "method_2871",
                        "m_104955_",
                        "func_175102_a"
                    );
                    if (entry == null) {
                        entry = invokeUuidAny(handler, targetUuid);
                    }
                    inTabList = entry != null;
                }
            } catch (Throwable throwable) {
                AgentMain.log("Tab listesi kontrolu basarisiz: " + throwable);
            }
        }

        return new GameState(
            reliable,
            screenOpen,
            attackKeyPressed,
            living,
            player,
            armorStand,
            inTabList,
            type,
            name(targetObject),
            targetUuid == null ? "" : targetUuid.toString()
        );
    }

    private static Object staticInstance(Class<?> type) {
        if (type == null) {
            return null;
        }
        Method fallback = null;
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0
                    || !type.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (name.equals("getinstance")
                    || name.equals("method_1551")
                    || name.equals("m_91087_")
                    || name.equals("func_71410_x")) {
                    method.setAccessible(true);
                    return method.invoke(null);
                }
                if (fallback == null && name.startsWith("get")) {
                    fallback = method;
                }
            }
            if (fallback != null) {
                fallback.setAccessible(true);
                return fallback.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object optionsType(Object minecraft) {
        if (minecraft == null) {
            return null;
        }
        for (Field field : minecraft.getClass().getDeclaredFields()) {
            String type = field.getType().getName().toLowerCase(Locale.ROOT);
            if (type.contains("gameoptions")
                || type.contains("class_315")
                || type.endsWith(".options")) {
                try {
                    field.setAccessible(true);
                    return field.get(minecraft);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object fieldByNames(Object instance, String... names) {
        if (instance == null) {
            return null;
        }
        for (String wanted : names) {
            Class<?> type = instance.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(wanted);
                    field.setAccessible(true);
                    return field.get(instance);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object fieldByType(Object instance, String... markers) {
        if (instance == null) {
            return null;
        }
        Class<?> type = instance.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                String fieldType = field.getType().getName().toLowerCase(Locale.ROOT);
                for (String marker : markers) {
                    if (fieldType.contains(marker.toLowerCase(Locale.ROOT))) {
                        try {
                            field.setAccessible(true);
                            return field.get(instance);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object fieldByTypeAndName(
        Object instance,
        String typeMarker,
        String nameMarker
    ) {
        if (instance == null) {
            return null;
        }
        String typeNeedle = typeMarker.toLowerCase(Locale.ROOT);
        String nameNeedle = nameMarker.toLowerCase(Locale.ROOT);
        Class<?> type = instance.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                String fieldType = field.getType().getName().toLowerCase(Locale.ROOT);
                String fieldName = field.getName().toLowerCase(Locale.ROOT);
                if (fieldType.contains(typeNeedle) && fieldName.contains(nameNeedle)) {
                    try {
                        field.setAccessible(true);
                        return field.get(instance);
                    } catch (Throwable ignored) {
                    }
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object fieldOfType(Object instance, String className) {
        if (instance == null || className == null || className.isEmpty()) {
            return null;
        }
        Class<?> type = instance.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!matches(field.getType(), className)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return field.get(instance);
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static boolean matches(Class<?> type, String expected) {
        if (type == null || expected == null || expected.isEmpty()) {
            return false;
        }
        if (expected.equals(type.getName())) {
            return true;
        }
        return expected.indexOf('.') < 0 && expected.equals(type.getSimpleName());
    }

    private static boolean matchesHierarchy(Class<?> type, String expected) {
        Class<?> current = type;
        while (current != null) {
            if (matches(current, expected)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static boolean bool(Object instance, String... names) {
        Object value = invokeNoArg(instance, names);
        return value instanceof Boolean && (Boolean) value;
    }

    private static Object invokeNoArg(Object instance, String... names) {
        if (instance == null) {
            return null;
        }
        for (String wanted : names) {
            try {
                Method method = instance.getClass().getMethod(wanted);
                method.setAccessible(true);
                return method.invoke(instance);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeUuid(Object instance, UUID uuid, String... names) {
        if (instance == null) {
            return null;
        }
        for (String wanted : names) {
            try {
                Method method = instance.getClass().getMethod(wanted, UUID.class);
                method.setAccessible(true);
                return method.invoke(instance, uuid);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeUuidAny(Object instance, UUID uuid) {
        if (instance == null) {
            return null;
        }
        for (Method method : instance.getClass().getMethods()) {
            if (method.getParameterCount() != 1
                || method.getParameterTypes()[0] != UUID.class
                || method.getReturnType() == void.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(instance, uuid);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeReturning(Object instance, String... typeMarkers) {
        if (instance == null) {
            return null;
        }
        for (Method method : instance.getClass().getMethods()) {
            if (method.getParameterCount() != 0
                || method.getReturnType() == void.class) {
                continue;
            }
            String typeName = method.getReturnType().getName().toLowerCase(Locale.ROOT);
            for (String marker : typeMarkers) {
                if (!typeName.contains(marker.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(instance);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isLiving(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName().toLowerCase(Locale.ROOT);
            if (name.contains("livingentity")
                || name.contains("class_1309")
                || name.contains("entityliving")
                || name.contains("entitylivingbase")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlayer(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName().toLowerCase(Locale.ROOT);
            if (name.contains("player") || name.contains("class_1657")) {
                return true;
            }
            if (name.contains("livingentity") || name.contains("class_1309")) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasName(Class<?> type, String... needles) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName().toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (name.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String name(Object target) {
        try {
            Object name = invokeNoArg(
                target,
                "getName",
                "method_5477",
                "m_7755_",
                "func_70005_c_"
            );
            if (name == null) {
                name = invokeNoArg(target, "getDisplayName", "method_5476", "m_5476_");
            }
            if (name != null) {
                Object text = invokeNoArg(name, "getString", "method_5414", "m_7532_", "asString");
                return text == null ? name.toString() : text.toString();
            }
        } catch (Throwable ignored) {
        }
        return target.getClass().getSimpleName();
    }

    private static UUID uuid(Object target) {
        try {
            Object value = invokeNoArg(
                target,
                "getUuid",
                "getUUID",
                "method_5667",
                "m_20148_",
                "func_110124_au"
            );
            if (value instanceof UUID uuid) {
                return uuid;
            }
            for (Method method : target.getClass().getMethods()) {
                if (method.getParameterCount() != 0
                    || method.getReturnType() != UUID.class) {
                    continue;
                }
                method.setAccessible(true);
                Object scanned = method.invoke(target);
                if (scanned instanceof UUID uuid) {
                    return uuid;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}