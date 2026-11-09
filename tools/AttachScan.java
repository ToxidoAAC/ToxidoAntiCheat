import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

public final class AttachScan {
    public static void main(String[] args) {
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            System.out.println(descriptor.id() + " | " + descriptor.displayName());
        }
    }
}
