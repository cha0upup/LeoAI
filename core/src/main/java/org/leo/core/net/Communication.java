package org.leo.core.net;

/**
 * 通信接口
 * 定义网络通信的基本接口，用于发送请求和接收响应
 * 
 * @author LeoSpring
 * @version 2.0
 */
public interface Communication {
    /**
     * 发送请求并接收响应
     * 
     * @param data 要发送的数据
     * @return 服务器响应的数据
     * @throws Exception 如果通信失败
     */
    byte[] sendRequest(byte[] data) throws Exception;
}
