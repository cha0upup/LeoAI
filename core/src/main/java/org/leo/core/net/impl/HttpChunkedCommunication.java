package org.leo.core.net.impl;

import org.leo.core.net.Communication;
import org.leo.core.net.impl.httpchunk.Http11DuplexChannel;
import org.leo.core.util.request.RefererGenerator;
import org.leo.core.util.request.UserAgentGenerator;

import java.io.Closeable;
import java.net.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP/1.1 full-duplex transport backed by one dedicated chunked connection. */
public class HttpChunkedCommunication implements Communication, Closeable {
    private final Http11DuplexChannel channel;

    public HttpChunkedCommunication(String url, String method, Map<String, String> headers, Proxy proxy)
            throws Exception {
        Map<String, String> requestHeaders = headers == null
                ? new ConcurrentHashMap<String, String>()
                : new ConcurrentHashMap<String, String>(headers);
        requestHeaders.putIfAbsent("User-Agent", UserAgentGenerator.generateRandomUserAgent());
        requestHeaders.putIfAbsent("Referer", RefererGenerator.generateRandomReferer(url));
        this.channel = new Http11DuplexChannel(url, method, requestHeaders, proxy);
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
