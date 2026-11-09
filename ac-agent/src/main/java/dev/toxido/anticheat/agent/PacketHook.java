package dev.toxido.anticheat.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * ClientConnection.send(Packet) girisinde HookBridge.onPacket cagrisi enjekte eder.
 * TriggerBot'larin dogrudan gonderdigi attack packetlerini yakalar.
 */
public final class PacketHook implements ClassFileTransformer {
    private final String ownerInternal;
    private final String methodName;
    private final String methodDesc;
    private final String label;
    private final boolean instance;

    public PacketHook(VersionResolver.PacketTarget target) {
        this.ownerInternal = target.ownerName.replace('.', '/');
        this.methodName = target.methodName;
        this.methodDesc = target.methodDesc;
        this.label = target.hook();
        this.instance = target.instance;
    }

    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
        if (!ownerInternal.equals(className)) {
            return null;
        }

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
                ) {
                    MethodVisitor delegate = super.visitMethod(
                        access, name, descriptor, signature, exceptions
                    );
                    if (!methodName.equals(name) || !methodDesc.equals(descriptor)) {
                        return delegate;
                    }
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            int slot = instance ? 1 : 0;
                            visitVarInsn(Opcodes.ALOAD, slot);
                            visitLdcInsn(label);
                            visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                Type.getInternalName(HookBridge.class),
                                "onPacket",
                                "(Ljava/lang/Object;Ljava/lang/String;)V",
                                false
                            );
                        }
                    };
                }
            };
            reader.accept(visitor, 0);
            return writer.toByteArray();
        } catch (Throwable throwable) {
            AgentMain.log("Packet hook uygulanamadi: " + throwable);
            return null;
        }
    }
}
