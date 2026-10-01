package org.leo.core.agent;

import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.runtime.AgentRuntime;
import org.leo.core.runtime.RuntimeProfile;

import java.util.Map;
import java.util.Set;

/**
 * 抽象 Agent 节点类
 *
 * 负责节点标识管理、组件管理接口、多语言插件调用接口
 * 具体通信、编码逻辑由子类实现
 */
public abstract class AbstractAgentNode implements AutoCloseable {



    /** Agent信息（节点绑定的宿主对象） */
    protected Agent agent;

    private User user;

    /** Runtime facts learned during handshake. */
    private volatile RuntimeProfile runtimeProfile;



    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }



    /**
     * 设置 Agent 信息
     */
    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    /**
     * 获取 Agent 信息
     */
    public Agent getAgent() {
        return agent;
    }

    /** Stable runtime type used by factories, components, plugins and AI routing. */
    public AgentRuntime getRuntime() {
        RuntimeProfile profile = runtimeProfile;
        if (profile != null) {
            return profile.getRuntime();
        }
        return AgentRuntime.from(agent != null ? agent.getType() : null);
    }

    public RuntimeProfile getRuntimeProfile() {
        RuntimeProfile profile = runtimeProfile;
        return profile != null ? profile : RuntimeProfile.minimal(getRuntime());
    }

    public void setRuntimeProfile(RuntimeProfile runtimeProfile) {
        if (runtimeProfile == null) {
            throw new IllegalArgumentException("runtimeProfile不能为空");
        }
        this.runtimeProfile = runtimeProfile;
    }

    public CapabilitySet getCapabilitySet() {
        return getRuntimeProfile().getCapabilities();
    }

    /**
     * 获取已加载组件的标识集合
     */
    public abstract Set<String> getLoadedComponents();

    /**
     * 调用组件
     * @param componentId 组件唯一标识
     * @param params 输入参数
     * @return 组件返回结果
     * @throws Exception 调用失败时抛出异常
     */
    public abstract Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) throws Exception;

    /**
     * 节点健康检查接口
     * @return 健康状态信息
     */
    public abstract Map<String, Object> testConnection() throws Exception;

    /**
     * 加载组件
     * @param componentId 组件唯一标识
     * @throws Exception 加载失败
     */


    /**
     * 卸载组件（可选）
     * @param componentId 组件唯一标识
     * @throws Exception 卸载失败
     */
    public abstract void unloadComponent(String componentId) throws Exception;

    /** Runtime-specific resources are released by concrete nodes. */
    @Override
    public void close() throws Exception {
        // default no-op for stateless/request-scoped runtimes
    }
}
