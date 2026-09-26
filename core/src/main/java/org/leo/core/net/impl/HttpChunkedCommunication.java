package org.leo.core.net.impl;

import org.leo.core.net.Communication;
import org.leo.core.net.impl.httpchunk.Http11DuplexChannel;
import org.leo.core.util.request.RefererGenerator;
import org.leo.core.util.request.UserAgentGenerator;

import java.io.Closeable;
import java.net.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP/1.1 full-duplex transport backed by one dedicated chunked connection. */
public class HttpChunkedCommunication implements Communication, Closeable {
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final Proxy proxy;
    private final Http11DuplexChannel channel;

    public HttpChunkedCommunication(String url, String method, Map<String, String> headers, Proxy proxy)
            throws Exception {
        this.url = url;
        this.method = method == null || method.isEmpty() ? "POST" : method.toUpperCase(Locale.ROOT);
        this.headers = headers == null
                ? new ConcurrentHashMap<String, String>()
                : new ConcurrentHashMap<String, String>(headers);
        this.proxy = proxy == null ? Proxy.NO_PROXY : proxy;
        this.headers.putIfAbsent("User-Agent", UserAgentGenerator.generateRandomUserAgent());
        this.headers.putIfAbsent("Referer", RefererGenerator.generateRandomReferer(url));
        this.channel = new Http11DuplexChannel(url, this.method, this.headers, this.proxy);
    }

    @Override
    public byte[] sendRequest(byte[] data) throws Exception {
        return channel.sendRequest(data);
    }

    public void heartbeat() throws Exception {
        channel.pingNow();
    }

    @Override
    public void close() {
        channel.close();
    }

    public Http11DuplexChannel.State getState() { return channel.getState(); }
}
