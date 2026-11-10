package dev.toxido.anticheat.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * ServerboundInteractPacket icinden attack bilgisini ve hedef entity'yi cikarir.
 * Tum okumalar reflection ile yapilir; mapping adlari surumden surume degisebilir.
 */
final class PacketAttack {
    static final class Result {
        final boolean attack;
        final int entityId;
        final Object target;
        final String packetClass;

        Result(boolean attack, int entityId, Object target, String packetClass) {
            this.attack = attack;
            this.entityId = entityId;
            this.target = target;
            this.packetClass = packetClass;
        }
    }

    private static final String[] MINECRAFT_NAMES = {
        "net.minecraft.class_310",
        "net.minecraft.client.Minecraft",
        "net.minecraft.client.MinecraftClient"
    };

    private PacketAttack() {
    }

    static Result resolve(Object packet) {
        if (packet == null) {
            return new Result(false, -1, null, "?");
        }

        Class<?> type = packet.getClass();
        String name = type.getName();
        String simple = type.getSimpleName().toLowerCase(Locale.ROOT);
        VersionProfile profile = VersionProfiles.active();
        boolean profilePacket = matchesClass(type, profile == null ? null : profile.packetClass);
        boolean attackPacket = simple.contains("serverboundattackpacket")
            || "net.minecraft.network.protocol.game.ServerboundAttackPacket"
                .equals(name);
        boolean interact = "net.minecraft.class_2824".equals(name)
            || simple.contains("serverboundinteractpacket")
            || simple.contains("playerinteractentityc2spacket")
            || profilePacket;
        if (!interact && !attackPacket) {
            return new Result(false, -1, null, name);
        }

        // 26.x istemcilerinde saldiri, ServerboundInteractPacket'ten ayrilip
        // kendi ServerboundAttackPacket sinifina tasindi.
        boolean attack = attackPacket || isAttack(packet, type)
            || isProfileAttack(packet, type, profile, profilePacket);
        int entityId = entityId(packet, type);
        Object target = entityId < 0 ? null : findTarget(type.getClassLoader(), entityId);
        return new Result(attack, entityId, target, name);
    }

    private static boolean isProfileAttack(
        Object packet,
        Class<?> type,
        VersionProfile profile,
        boolean profilePacket
    ) {
        if (!profilePacket || profile == null) {
            return false;
        }
        // Vanilla obfuscated paketlerde Action enum'u dogrudan isimle
        // bulunamaz; paket alanindaki deger, sinifin ATTACK_ACTION
        // sabitiyle ayni nesne mi diye karsilastirilir.
        try {
            Field actionField = field(type, profile.packetActionField);
            Field attackField = field(type, profile.packetAttackActionField);
            if (actionField == null || attackField == null) {
                return false;
            }
            actionField.setAccessible(true);
            attackField.setAccessible(true);
            Object action = actionField.get(packet);
            Object attackAction = attackField.get(null);
            return action != null && action == attackAction;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAttack(Object packet, Class<?> type) {
        // Mojang: isAttack(); bazi mapping'lerde dogrudan erisilebilir.
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0
                    || method.getReturnType() != boolean.class) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (name.equals("isattack") || name.equals("method_34210")) {
                    method.setAccessible(true);
                    return Boolean.TRUE.equals(method.invoke(packet));
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Field field = field(type, "field_12871", "interaction", "handler");
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            Object handler = field.get(packet);
            if (handler == null) {
                return false;
            }

            String handlerName = handler.getClass().getName().toLowerCase(Locale.ROOT);
            if (handlerName.contains("class_2824$1")
                || handlerName.contains("serverboundinteractpacket$1")) {
                return true;
            }

            for (Method method : handler.getClass().getDeclaredMethods()) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                Class<?> result = method.getReturnType();
                if (!result.isEnum()) {
                    continue;
                }
                method.setAccessible(true);
                Object value = method.invoke(handler);
                if (isAttackType(value, result)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isAttackType(Object value, Class<?> enumType) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).toLowerCase(Locale.ROOT);
        if (text.contains("attack") || text.contains("field_29172")) {
            return true;
        }
        for (Object constant : enumType.getEnumConstants()) {
            if (constant != value) {
                continue;
            }
            String name = ((Enum<?>) constant).name().toLowerCase(Locale.ROOT);
            if (name.contains("attack") || name.equals("field_29172")) {
                return true;
            }
        }
        return false;
    }

    private static int entityId(Object packet, Class<?> type) {
        VersionProfile profile = VersionProfiles.active();
        // 26.x ServerboundAttackPacket bir record'dur ve entityId() metodu
        // ile hedef entity kimligini acik eder.
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 0
                || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != int.class) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("entityid") && !name.equals("getentityid")) {
                continue;
            }
            try {
                method.setAccessible(true);
                return (Integer) method.invoke(packet);
            } catch (Throwable ignored) {
            }
        }

        if (profile != null && matchesClass(type, profile.packetClass)) {
            Field mapped = field(type, profile.packetEntityField);
            if (mapped != null && mapped.getType() == int.class) {
                try {
                    mapped.setAccessible(true);
                    return mapped.getInt(packet);
                } catch (Throwable ignored) {
                }
            }
        }

        Field field = field(type, "field_12870", "entityid", "entity_id", "id");
        if (field != null && field.getType() == int.class) {
            try {
                field.setAccessible(true);
                return field.getInt(packet);
            } catch (Throwable ignored) {
            }
        }

        for (Field candidate : type.getDeclaredFields()) {
            if (candidate.getType() != int.class
                || Modifier.isStatic(candidate.getModifiers())) {
                continue;
            }
            String name = candidate.getName().toLowerCase(Locale.ROOT);
            if (name.contains("id") || name.contains("entity")) {
                try {
                    candidate.setAccessible(true);
                    return candidate.getInt(packet);
                } catch (Throwable ignored) {
                }
            }
        }
        return -1;
    }

    private static Object findTarget(ClassLoader loader, int entityId) {
        try {
            VersionProfile profile = VersionProfiles.active();
            Class<?> minecraft = profile == null || profile.minecraftClass == null
                ? null
                : findClass(loader, new String[] { profile.minecraftClass });
            if (minecraft == null) {
                minecraft = findClass(loader, MINECRAFT_NAMES);
            }
            if (minecraft == null) {
                return null;
            }
            Object client = staticInstance(minecraft);
            if (client == null) {
                return null;
            }

            Object world = fieldByNames(client, "field_1687", "world", "level");
            if (world == null) {
                world = fieldByType(client, "class_638", "clientlevel", "clientworld");
            }
            if (world == null && profile != null) {
                world = worldFromPlayer(client, profile);
            }
            if (world == null) {
                return null;
            }

            Method getEntity = findEntityMethod(world.getClass());
            if (getEntity == null) {
                return null;
            }
            getEntity.setAccessible(true);
            return getEntity.invoke(world, entityId);
        } catch (Throwable throwable) {
            AgentMain.debug("[PACKET] hedef cozulemedi: " + throwable);
            return null;
        }
    }

    private static Object worldFromPlayer(Object client, VersionProfile profile) {
        Object player = fieldByNames(client, profile.worldField);
        if (player == null) {
            return null;
        }
        Object handler = fieldByNames(player, profile.networkHandlerField);
        if (handler == null) {
            return null;
        }
        for (Field candidate : handler.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(candidate.getModifiers())) {
                continue;
            }
            try {
                candidate.setAccessible(true);
                Object value = candidate.get(handler);
                if (value != null && findEntityMethod(value.getClass()) != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Class<?> findClass(ClassLoader loader, String[] names) {
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) {
            }
        }
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object staticInstance(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 0
                || !type.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (name.equals("getinstance")
                || name.equals("method_1551")
                || name.equals("m_91087_")
                || name.equals("func_71410_x")) {
                try {
                    method.setAccessible(true);
                    return method.invoke(null);
                } catch (Throwable ignored) {
                }
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                || !type.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findEntityMethod(Class<?> worldType) {
        Method fallback = null;
        Class<?> current = worldType;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != int.class) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                String result = method.getReturnType().getName().toLowerCase(Locale.ROOT);
                if (!result.contains("entity") && !result.contains("class_1297")) {
                    continue;
                }
                if (name.equals("getentitybyid")
                    || name.equals("getentity")
                    || name.equals("method_8469")
                    || name.equals("m_103904_")) {
                    return method;
                }
                if (fallback == null) {
                    fallback = method;
                }
            }
            current = current.getSuperclass();
        }
        return fallback;
    }

    private static boolean matchesClass(Class<?> type, String expected) {
        if (type == null || expected == null || expected.isEmpty()) {
            return false;
        }
        if (expected.equals(type.getName())) {
            return true;
        }
        return expected.indexOf('.') < 0 && expected.equals(type.getSimpleName());
    }

    private static Field field(Class<?> type, String... names) {
        for (String wanted : names) {
            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredField(wanted);
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
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
            Class<?> current = instance.getClass();
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(wanted);
                    field.setAccessible(true);
                    return field.get(instance);
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object fieldByType(Object instance, String... fragments) {
        if (instance == null) {
            return null;
        }
        for (Field field : instance.getClass().getDeclaredFields()) {
            String type = field.getType().getName().toLowerCase(Locale.ROOT);
            for (String fragment : fragments) {
                if (!type.contains(fragment.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return field.get(instance);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
