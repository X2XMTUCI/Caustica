import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Applies the source-equivalent glint rejection to the stable bundled entity collector class. */
public final class PatchEntityGlintCapture {
    private static final String RENDER_TYPES =
            "net/minecraft/client/renderer/rendertype/RenderTypes";
    private static final String RENDER_TYPE_DESC =
            "()Lnet/minecraft/client/renderer/rendertype/RenderType;";

    private PatchEntityGlintCapture() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <input RtEntityCollector.class> <output class>");
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        ClassNode type = new ClassNode();
        new ClassReader(Files.readAllBytes(input)).accept(type, 0);

        MethodNode target = null;
        for (MethodNode method : type.methods) {
            if (method.name.equals("submitModel")
                    && method.desc.startsWith("(Lnet/minecraft/client/model/Model;Ljava/lang/Object;")) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("RtEntityCollector.submitModel not found");
        }

        InsnList guard = new InsnList();
        addGlintGuard(guard, "armorEntityGlint");
        addGlintGuard(guard, "entityGlint");
        addGlintGuard(guard, "glint");
        addGlintGuard(guard, "glintTranslucent");
        target.instructions.insert(guard);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void addGlintGuard(InsnList code, String method) {
        // Every non-match must continue testing the next singleton, not jump past the entire guard.
        LabelNode next = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDER_TYPES, method, RENDER_TYPE_DESC, false));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPNE, next));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(next);
    }
}
