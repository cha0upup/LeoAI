import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Read-only comparison: ignore source/debug tables, retain every runtime attribute. */
public final class ComponentClassComparator {

    private static byte[] withoutDebugTables(Path path) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitSource(String source, String debug) {
                // Source names and source maps do not affect component execution.
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitLineNumber(int line, Label start) {
                    }

                    @Override
                    public void visitLocalVariable(String name, String descriptor, String signature,
                                                   Label start, Label end, int index) {
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected generated and payload directories");
        Path generated = Paths.get(args[0]);
        Path payloads = Paths.get(args[1]);
        List<Path> classes;
        try (java.util.stream.Stream<Path> files = Files.list(generated)) {
            classes = files.filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted().collect(Collectors.toList());
        }
        if (classes.isEmpty()) throw new IllegalStateException("No generated component classes");
        boolean matches = true;
        for (Path source : classes) {
            String name = source.getFileName().toString().replaceFirst("\\.class$", ".payload");
            Path payload = payloads.resolve(name);
            if (!Files.isRegularFile(payload)
                    || !Arrays.equals(withoutDebugTables(source), withoutDebugTables(payload))) {
                System.err.println("Component runtime bytecode differs: " + name);
                matches = false;
            }
        }
        if (!matches) System.exit(1);
    }
}
