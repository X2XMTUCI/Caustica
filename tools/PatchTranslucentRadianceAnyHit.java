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
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Applies the tiny RtPipeline SBT routing change to a stable bundled class without rebuilding the
 * entire Minecraft/Fabric dependency graph. Source and bytecode implement the same method body.
 */
public final class PatchTranslucentRadianceAnyHit {
    private PatchTranslucentRadianceAnyHit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <input RtPipeline.class> <output RtPipeline.class>");
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);

        ClassNode type = new ClassNode();
        new ClassReader(Files.readAllBytes(input)).accept(type, 0);
        MethodNode target = null;
        for (MethodNode method : type.methods) {
            if (method.name.equals("hitGroupUsesAnyHit") && method.desc.equals("(I)Z")) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("RtPipeline.hitGroupUsesAnyHit(I)Z not found");
        }

        LabelNode entity = new LabelNode();
        LabelNode shadow = new LabelNode();
        LabelNode yes = new LabelNode();
        InsnList code = new InsnList();

        // if (relativeHitGroup >= SBT_ENTITY_OFFSET) goto entity;
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new IntInsnNodeCompat(Opcodes.BIPUSH, 8));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, entity));

        // int rayType = relativeHitGroup / TERRAIN_BUCKETS;
        // int bucket = relativeHitGroup % TERRAIN_BUCKETS;
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.IDIV));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.IREM));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));

        // Radiance: any-hit on cutout OR translucent.
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNE, shadow));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, yes));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.ICONST_2));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, yes));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));

        // Shadow: any-hit on every non-solid terrain bucket.
        code.add(shadow);
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new JumpInsnNode(Opcodes.IFNE, yes));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));

        // Entity records: only ENTITY_BUCKET_ANY_HIT.
        code.add(entity);
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new IntInsnNodeCompat(Opcodes.BIPUSH, 8));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.IREM));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, yes));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));

        code.add(yes);
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));

        target.instructions.clear();
        target.instructions.add(code);
        target.tryCatchBlocks.clear();
        if (target.localVariables != null) {
            target.localVariables.clear();
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    /** Keeps the generated instruction list readable without a static import collision. */
    private static final class IntInsnNodeCompat extends org.objectweb.asm.tree.IntInsnNode {
        IntInsnNodeCompat(int opcode, int operand) {
            super(opcode, operand);
        }
    }
}
