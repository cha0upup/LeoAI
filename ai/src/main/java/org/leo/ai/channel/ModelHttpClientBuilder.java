package org.leo.ai.channel;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Supplies the same custom authentication headers to blocking and streaming protocols. */
final class ModelHttpClientBuilder implements HttpClientBuilder {
    private final JdkHttpClientBuilder delegate = new JdkHttpClientBuilder();
    private final Map<String, String> headers;

    ModelHttpClientBuilder(Map<String, String> headers, Duration timeout) {
        this.headers = Map.copyOf(headers);
        delegate.connectTimeout(Duration.ofSeconds(15)).readTimeout(timeout);
    }

    public Duration connectTimeout() { return delegate.connectTimeout(); }
    public HttpClientBuilder connectTimeout(Duration timeout) { delegate.connectTimeout(timeout); return this; }
    public Duration readTimeout() { return delegate.readTimeout(); }
    public HttpClientBuilder readTimeout(Duration timeout) { delegate.readTimeout(timeout); return this; }

    public HttpClient build() {
        HttpClient client = delegate.build();
        return new HttpClient() {
            public SuccessfulHttpResponse execute(HttpRequest request) {
                return client.execute(withHeaders(request));
            }

            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                client.execute(withHeaders(request), parser, listener);
            }
        };
    }

    private HttpRequest withHeaders(HttpRequest request) {
        Map<String, List<String>> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (request.headers() != null) merged.putAll(request.headers());
        headers.forEach((name, value) -> merged.put(name, List.of(value)));
        return HttpRequest.builder().method(request.method()).url(request.url()).headers(merged)
                .body(request.body()).formDataFields(request.formDataFields())
                .formDataFiles(request.formDataFiles()).build();
    }
}
