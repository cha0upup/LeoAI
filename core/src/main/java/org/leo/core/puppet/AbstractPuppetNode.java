package org.leo.core.puppet;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;

import java.util.Map;
import java.util.Set;

/**
 * 抽象 Puppet 节点类
 *
 * 负责节点标识管理、组件管理接口、多语言插件调用接口
 * 具体通信、编码逻辑由子类实现
 */
public abstract class AbstractPuppetNode {



    /** Puppet信息（节点绑定的宿主对象） */
    protected Puppet puppet;

    private User user;



    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }



    /**
     * 设置 Puppet 信息
     */
    public void setPuppet(Puppet puppet) {
        this.puppet = puppet;
    }

    /**
     * 获取 Puppet 信息
     */
    public Puppet getPuppet() {
        return puppet;
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
}
