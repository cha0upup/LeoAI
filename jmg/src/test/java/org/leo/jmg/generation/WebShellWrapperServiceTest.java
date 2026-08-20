package org.leo.jmg.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.entity.Disguise;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebShellWrapperServiceTest {

    @TempDir
    Path compileOutput;

    private final CoreArtifactGenerationService coreService = new CoreArtifactGenerationService();
    private final WebShellWrapperService wrapperService = new WebShellWrapperService();

    @Test
    void assemblesCompilableHttpAndHttpChunkWrappers() throws Exception {
        assertCompilableWrapper("http", 200, 601L);
        assertCompilableWrapper("httpchunk", 200, 602L);
    }

    @Test
    void rejectsStatusCodesThatCannotCarryChunkStream() throws Exception {
        CoreArtifact artifact = generateCore("httpchunk", 603L);
        WebShellWrapperContract contract = wrapperService.getContract("JSP", "httpchunk");

        assertThrows(IllegalArgumentException.class,
                () -> wrapperService.assemble(artifact, contract.getBaselineTemplate(),
                        "JSP", 204, Collections.emptyList()));
    }

    private void assertCompilableWrapper(String protocol, int responseCode, long seed) throws Exception {
        CoreArtifact artifact = generateCore(protocol, seed);
        WebShellWrapperContract contract = wrapperService.getContract("JSP", protocol);
        WebShellWrapperResult result = wrapperService.assemble(
                artifact, contract.getBaselineTemplate(), "JSP", responseCode,
                Collections.emptyList());

        assertEquals(protocol, result.getMetadata().get("protocol"));
        assertEquals(artifact.getSha256(), result.getMetadata().get("coreSha256"));
        assertTrue(result.getContent().contains(artifact.getCoreClassName()));
        assertFalse(result.getContent().contains("{{"));
        assertJavaCompiles(extractJspScriptlet(result.getContent()));
    }

    private CoreArtifact generateCore(String protocol, long seed) throws Exception {
        return coreService.generate(CoreArtifactGenerationCommand.builder(
                        requestDisguise(), responseDisguise())
                .protocol(protocol)
                .coreClassName("org.demo.GeneratedCore" + seed)
                .targetJavaVersion("8")
                .servletNamespace("javax")
                .obfuscationSeed(seed)
                .build());
    }

    private static String extractJspScriptlet(String jsp) {
        int start = jsp.lastIndexOf("<%") + 2;
        int end = jsp.indexOf("%>", start);
        return jsp.substring(start, end);
    }

    private void assertJavaCompiles(String scriptlet) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null);
        try {
            String source = "class WrapperCompileFixture {\n"
                    + "static class Req { java.io.InputStream getInputStream(){ return new java.io.ByteArrayInputStream(new byte[0]); } }\n"
                    + "static class Resp { void setStatus(int v){} void setHeader(String n,String v){} void setContentType(String v){} java.io.OutputStream getOutputStream(){ return new java.io.ByteArrayOutputStream(); } }\n"
                    + "static class Out { void clear(){} }\n"
                    + "void run(Req request, Resp response, Out out) throws Exception {\n"
                    + scriptlet + "\n}\n}";
            JavaFileObject unit = new StringJavaSource("WrapperCompileFixture", source);
            Boolean compiled = compiler.getTask(null, files, diagnostics,
                    java.util.Arrays.asList("-proc:none", "-d", compileOutput.toString()), null,
                    Collections.singletonList(unit)).call();
            if (!Boolean.TRUE.equals(compiled)) {
                StringBuilder message = new StringBuilder("Wrapper Java 编译失败:\n");
                for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                    message.append(diagnostic).append('\n');
                }
                throw new AssertionError(message.toString());
            }
        } finally {
            files.close();
        }
    }

    private static Disguise requestDisguise() {
        Disguise disguise = new Disguise();
        disguise.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        return disguise;
    }

    private static Disguise responseDisguise() {
        Disguise disguise = new Disguise();
        disguise.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");
        return disguise;
    }

    private static final class StringJavaSource extends SimpleJavaFileObject {
        private final String source;

        private StringJavaSource(String className, String source) {
            super(URI.create("string:///" + className + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
