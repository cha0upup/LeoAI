package org.leo.jmg.generation.pipeline;

import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.jmg.core.LeoCore;
import org.leo.jmg.generation.GenerationRequest;

/**
 * 生成并最小化 LeoCore 字节码。
 */
public final class CoreGenerationPipeline {

    private final GenerationRequest request;

    public CoreGenerationPipeline(GenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("GenerationRequest 不能为空");
        }
        this.request = request;
    }

    public byte[] generate() throws Exception {
        LeoCore leoCore = new LeoCore(
                request.createRequestDisguiseSnapshot(),
                request.createResponseDisguiseSnapshot(), request.getPayloadKey());
        byte[] bytecode = leoCore.genLeoCoreByClassName(
                request.getCoreClassName(), request.getCoreGenerationNames());
        return ClassFileMinimizer.transform(bytecode);
    }
}
