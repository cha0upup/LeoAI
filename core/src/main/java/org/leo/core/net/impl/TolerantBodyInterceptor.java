package org.leo.core.net.impl;

import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 响应体容错拦截器（OkHttp Application Interceptor）。
 *
 * <p>统一处理以下"主体字节已收到但 HTTP 帧结构异常"的场景，避免应用层因
 * {@link EOFException} 直接失败：
 * <ul>
 *   <li>HTTP/1.1 chunked transfer encoding 尾标（{@code 0\r\n\r\n}）或某个 chunk size 行被截断</li>
 *   <li>{@code Content-Length} 声明字节数大于实际写入</li>
 *   <li>响应主体读取过程中对端提前关闭连接（FIN/RST）</li>
 *   <li>其它"已写入主体字节、框架封装尾部不完整"的非标准 HTTP 实现</li>
 * </ul>
 *
 * <p>共同特征：业务字节其实已经写入了 socket，丢失的只是 HTTP 框架封装的尾部。
 * 拦截器使用 {@link InputStream} 逐块累积已收到的字节，捕获中途的 EOFException 后
 * 将已读字节重新封装为一个干净的 {@link ResponseBody} 交回应用层，让业务解码器
 * 有机会基于完整的主体字节继续解析（这对 AES-CBC 等 block-aligned 协议尤其重要：
 * 任何尾部字节丢失都会导致解密失败）。
 *
 * <p>典型触发场景：注入式内存马（Tomcat memshell / Listener memshell 等）以非标准
 * 方式写响应，大响应触发 chunked 编码时尾部不规范；或者部署在不稳定网络环境下，
 * 反向代理（nginx / haproxy）在响应传输中途断开连接。
 *
 * <p>实现说明：使用 {@code body.byteStream()} 配合手动 {@code read(buf)} 循环，
 * 而非 Okio 的 {@code BufferedSource.readAll(Sink)}。后者内部按 segment（8KB）
 * 才 emit 到 sink，在 source.read 抛出 EOFException 时残留在内部 buffer 里的
 * 未满 segment 字节会丢失，会破坏 block-aligned 加密协议（少 1~8191 字节）。
 * InputStream 模式每次 {@code read} 返回的字节立即写入 baos，无内部 emit 缓冲，
 * 能完整保留所有已收到的字节。
 *
 * <p>注：拦截器只容忍 {@link EOFException} 这一类"流提前结束"问题；连接级错误
 * （连接失败、SSL 握手失败等）仍会正常上抛，交由上层重试逻辑处理。
 *
 * @author LeoSpring
 */
public class TolerantBodyInterceptor implements Interceptor {

    private static final Logger logger = LoggerFactory.getLogger(TolerantBodyInterceptor.class);

    /** 读取缓冲区大小（不影响正确性，只影响吞吐） */
    private static final int READ_BUFFER_SIZE = 8192;

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        ResponseBody body = response.body();
        if (body == null) {
            return response;
        }

        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buf = new byte[READ_BUFFER_SIZE];

        try (InputStream is = body.byteStream()) {
            int read;
            while ((read = is.read(buf)) != -1) {
                collected.write(buf, 0, read);
            }
        } catch (EOFException eof) {
            // HTTP 帧尾部截断：业务字节已全部读取写入 collected，缺失的仅是框架封装尾部
            logger.warn("[TolerantBody] HTTP 帧尾部截断，保留已读 {} 字节继续 url={} status={}",
                    collected.size(), response.request().url(), response.code());
        }

        // 重新封装为干净的 ResponseBody。下游 responseBody.bytes() 调用不会再触发
        // chunked 解析路径，因此不会抛 EOFException。
        ResponseBody safe = ResponseBody.create(collected.toByteArray(), body.contentType());
        return response.newBuilder().body(safe).build();
    }
}
