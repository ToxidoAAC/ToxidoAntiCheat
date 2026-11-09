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

# Agent-Class and Premain-Class in the manifest point at this class.
-keep class dev.toxido.anticheat.agent.AgentMain { *; }
-keep class dev.toxido.anticheat.agent.HookBridge { public *; }
-keep class dev.toxido.anticheat.agent.AttackEvent { *; }
-keep class dev.toxido.anticheat.agent.asm.** { *; }

-injars ../../ac-agent/target/ac-agent.jar
-outjars ../../build/obfuscation/ac-agent.jar
-printmapping ../../build/obfuscation/mapping-agent.txt
