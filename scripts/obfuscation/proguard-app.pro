-dontshrink
-dontoptimize
-dontnote
-dontwarn **

-verbose

# Java 21 class files need StackMapTable frames. ProGuard can only generate
# correct frames when the JDK library classes are available.
-libraryjars <java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.logging.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.desktop.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.sql.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.management.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.net.http.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/jdk.attach.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/jdk.unsupported.jmod(!**.jar;!module-info.class)

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,LineNumberTable,SourceFile,MethodParameters,StackMapTable

# Launcher uses `java -jar`, so Main-Class must stay resolvable.
-keep public class dev.toxido.anticheat.app.Main {
    public static void main(java.lang.String[]);
}

# Gson reflection and Jansi native/resource loading.
-keep class com.google.** { *; }
-keep class org.fusesource.** { *; }

# Records are constructed manually today, but keep them readable for the
# first obfuscated release. They can be renamed after a stability pass.
-keep class dev.toxido.anticheat.app.MinecraftProcess { *; }
-keep class dev.toxido.anticheat.app.ApiClient$StartResult { *; }

-injars ../../ac-app/target/ac-app.jar
-outjars ../../build/obfuscation/ac-app.jar
-printmapping ../../build/obfuscation/mapping-app.txt
