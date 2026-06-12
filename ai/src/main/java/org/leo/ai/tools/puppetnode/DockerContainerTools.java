package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DockerContainerTools {

    @Tool("列举 puppet 侧运行中的 Docker 容器。all=true 时包含已停止的容器。返回容器 ID、名称、镜像、状态、端口映射等。")
    public Map<String, Object> listContainers(
            @P("是否包含已停止的容器，默认 true") boolean all) throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.listDockerContainers(all);
    }

    @Tool("获取指定 Docker 容器的详细信息：网络配置、挂载卷、环境变量、启动命令等。适用于容器逃逸检测和凭据搜集。")
    public Map<String, Object> inspectContainer(
            @P("容器 ID 或名称") String containerId) throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.inspectDockerContainer(containerId);
    }

    @Tool("获取 Docker 引擎全局信息：版本、运行时、存储驱动、安全配置（Seccomp/AppArmor）、Root Dir 等。")
    public Map<String, Object> getDockerInfo() throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.getDockerInfo();
    }

    @Tool("在指定 Docker 容器内执行命令（docker exec 等效）。适用于容器内侦察和凭据搜集。")
    public Map<String, Object> execInContainer(
            @P("容器 ID 或名称") String containerId,
            @P("要执行的命令") String cmd) throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
        return node.execInDockerContainer(containerId, cmd);
    }
}
