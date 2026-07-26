import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Removes the obsolete self-shadow toggle from the selected baseline's video-options class while
 * preserving every other method and setting byte-for-byte at the class-model level.
 */
public final class RemoveParallaxShadowOption {
    private static final String OWNER = "dev/comfyfluffy/caustica/client/RtVideoOptions";
    private static final String CONFIG_OWNER =
            "dev/comfyfluffy/caustica/CausticaConfig$Rt$Composite";
    private static final String FLOAT_SETTING_DESC =
            "Ldev/comfyfluffy/caustica/CausticaConfig$FloatSetting;";
    private static final String OPTION_DESC = "()Lnet/minecraft/client/OptionInstance;";
    private static final List<String> OPTIONS = List.of(
            "exposureMode", "manualEv", "spp", "maxBounces", "sunSize", "entities", "particles",
            "waterWaves", "parallaxEnabled", "parallaxStrength", "parallaxQuality",
            "parallaxSmoothing", "parallaxDistance", "fogEnabled", "fogDensity", "fogHeightFalloff",
            "fogAnisotropy", "fogDistance", "dlssQuality", "hdrEnabled", "hdrPaperWhite",
            "hdrPeak", "debugView");

    private RemoveParallaxShadowOption() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "usage: <options input> <options output> <composite input> <composite output> "
                            + "<config composite input> <config composite output>");
        }
        patchOptions(Path.of(args[0]), Path.of(args[1]));
        patchComposite(Path.of(args[2]), Path.of(args[3]));
        patchConfigComposite(Path.of(args[4]), Path.of(args[5]));
    }

    private static void patchOptions(Path input, Path output) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(Files.readAllBytes(input)).accept(type, 0);
        if (!OWNER.equals(type.name)) {
            throw new IllegalStateException("unexpected class " + type.name);
        }

        MethodNode runtimeOptions = null;
        MethodNode fogDistance = null;
        for (MethodNode method : type.methods) {
            if ("runtimeOptions".equals(method.name)) {
                runtimeOptions = method;
            } else if ("fogDistance".equals(method.name) && OPTION_DESC.equals(method.desc)) {
                fogDistance = method;
            }
        }
        if (runtimeOptions == null || fogDistance == null) {
            throw new IllegalStateException("runtimeOptions or fogDistance not found");
        }

        // The baseline already has a correctly wired 16..256 block integer slider for fog distance.
        // Clone its bytecode so the new parallax distance option keeps identical Minecraft UI behavior,
        // then point only its labels and persistent FloatSetting at the parallax setting.
        MethodNode parallaxDistance = new MethodNode(Opcodes.ASM9, fogDistance.access,
                "parallaxDistance", fogDistance.desc, fogDistance.signature,
                fogDistance.exceptions == null ? null : fogDistance.exceptions.toArray(String[]::new));
        fogDistance.accept(parallaxDistance);
        int labelReplacements = 0;
        int settingReplacements = 0;
        for (AbstractInsnNode instruction = parallaxDistance.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                if ("caustica.options.rt.fogDistance".equals(value)) {
                    ldc.cst = "caustica.options.rt.parallaxDistance";
                    labelReplacements++;
                } else if ("caustica.options.rt.fogDistance.tooltip".equals(value)) {
                    ldc.cst = "caustica.options.rt.parallaxDistance.tooltip";
                    labelReplacements++;
                }
            } else if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && "dev/comfyfluffy/caustica/CausticaConfig$Rt$Fog".equals(field.owner)
                    && "MAX_DISTANCE".equals(field.name)) {
                field.owner = CONFIG_OWNER;
                field.name = "PARALLAX_DISTANCE";
                field.desc = FLOAT_SETTING_DESC;
                settingReplacements++;
            }
        }
        if (labelReplacements != 2 || settingReplacements != 1) {
            throw new IllegalStateException("failed to clone parallax distance slider");
        }
        type.methods.add(parallaxDistance);

        InsnList code = new InsnList();
        pushInt(code, OPTIONS.size());
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "net/minecraft/client/OptionInstance"));
        for (int index = 0; index < OPTIONS.size(); index++) {
            code.add(new InsnNode(Opcodes.DUP));
            pushInt(code, index);
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, OPTIONS.get(index),
                    OPTION_DESC, false));
            code.add(new InsnNode(Opcodes.AASTORE));
        }
        code.add(new InsnNode(Opcodes.ARETURN));
        runtimeOptions.instructions = code;
        runtimeOptions.tryCatchBlocks.clear();
        if (runtimeOptions.localVariables != null) {
            runtimeOptions.localVariables.clear();
        }

        // The obsolete UI factory itself must not remain callable in the delivered class.
        type.methods.removeIf(method -> "parallaxShadows".equals(method.name));

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void patchComposite(Path input, Path output) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(Files.readAllBytes(input)).accept(type, 0);
        if (!"dev/comfyfluffy/caustica/rt/RtComposite".equals(type.name)) {
            throw new IllegalStateException("unexpected class " + type.name);
        }

        int replacements = 0;
        for (MethodNode method : type.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETSTATIC
                        || !CONFIG_OWNER.equals(field.owner)
                        || !"PARALLAX_SHADOWS".equals(field.name)) {
                    continue;
                }
                AbstractInsnNode valueCall = instruction.getNext();
                if (!(valueCall instanceof MethodInsnNode methodCall)
                        || !"value".equals(methodCall.name)
                        || !"()Z".equals(methodCall.desc)) {
                    throw new IllegalStateException("unexpected PARALLAX_SHADOWS access shape");
                }
                method.instructions.set(instruction, new InsnNode(Opcodes.ICONST_0));
                method.instructions.remove(valueCall);
                replacements++;
            }
        }
        if (replacements != 1) {
            throw new IllegalStateException("expected one PARALLAX_SHADOWS read, found " + replacements);
        }

        int distanceWrites = 0;
        for (MethodNode method : type.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETSTATIC
                        || !CONFIG_OWNER.equals(field.owner)
                        || !"PARALLAX_QUALITY".equals(field.name)) {
                    continue;
                }
                AbstractInsnNode cursor = instruction;
                MethodInsnNode float4Constructor = null;
                while ((cursor = cursor.getNext()) != null) {
                    if (cursor instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESPECIAL
                            && "dev/comfyfluffy/caustica/rt/gen/WorldPushData$Float4".equals(call.owner)
                            && "<init>".equals(call.name)
                            && "(FFFF)V".equals(call.desc)) {
                        float4Constructor = call;
                        break;
                    }
                }
                if (float4Constructor == null
                        || float4Constructor.getPrevious().getOpcode() != Opcodes.FCONST_0) {
                    throw new IllegalStateException("parallax Float4 fourth component not found");
                }
                AbstractInsnNode legacyFourthComponent = float4Constructor.getPrevious();
                InsnList distance = new InsnList();
                distance.add(new FieldInsnNode(Opcodes.GETSTATIC, CONFIG_OWNER,
                        "PARALLAX_DISTANCE", FLOAT_SETTING_DESC));
                distance.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "dev/comfyfluffy/caustica/CausticaConfig$FloatSetting",
                        "value", "()F", false));
                method.instructions.insertBefore(legacyFourthComponent, distance);
                method.instructions.remove(legacyFourthComponent);
                distanceWrites++;
            }
        }
        if (distanceWrites != 1) {
            throw new IllegalStateException("expected one parallax distance write, found " + distanceWrites);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void patchConfigComposite(Path input, Path output) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(Files.readAllBytes(input)).accept(type, 0);
        if (!CONFIG_OWNER.equals(type.name)) {
            throw new IllegalStateException("unexpected class " + type.name);
        }
        if (type.fields.stream().anyMatch(field -> "PARALLAX_DISTANCE".equals(field.name))) {
            throw new IllegalStateException("PARALLAX_DISTANCE already exists");
        }
        type.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "PARALLAX_DISTANCE", FLOAT_SETTING_DESC, null, null));

        MethodNode initializer = type.methods.stream()
                .filter(method -> "<clinit>".equals(method.name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("config initializer not found"));
        AbstractInsnNode returnInstruction = initializer.instructions.getLast();
        if (returnInstruction == null || returnInstruction.getOpcode() != Opcodes.RETURN) {
            throw new IllegalStateException("unexpected config initializer ending");
        }
        InsnList code = new InsnList();
        code.add(new LdcInsnNode("caustica.rt.parallaxDistance"));
        code.add(new LdcInsnNode("parallax.distance"));
        code.add(new LdcInsnNode(64.0f));
        code.add(new LdcInsnNode(16.0f));
        code.add(new LdcInsnNode(256.0f));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/comfyfluffy/caustica/CausticaConfig", "clampedFloat",
                "(Ljava/lang/String;Ljava/lang/String;FFF)" + FLOAT_SETTING_DESC, false));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, CONFIG_OWNER,
                "PARALLAX_DISTANCE", FLOAT_SETTING_DESC));
        initializer.instructions.insertBefore(returnInstruction, code);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void pushInt(InsnList code, int value) {
        if (value >= -1 && value <= 5) {
            code.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else {
            code.add(new IntInsnNode(Opcodes.SIPUSH, value));
        }
    }
}
