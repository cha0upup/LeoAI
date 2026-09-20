package org.leo.web.controller.puppetnode.proxy;

import org.junit.jupiter.api.Test;
import org.leo.core.puppet.capability.HttpProxyCapable;
import org.leo.core.puppet.capability.LocalForwardCapable;
import org.leo.core.puppet.capability.Socks5ProxyCapable;
import org.leo.web.util.ControllerUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ProxyBindAddressControllerTest {
    @Test
    void forwardsExplicitAddressesThroughEveryCapability() throws Exception {
        var socks = mock(Socks5ProxyCapable.class);
        var http = mock(HttpProxyCapable.class);
        var forward = mock(LocalForwardCapable.class);
        var params = new HashMap<String, Object>(Map.of("sessionId", "test", "port", 1080,
                "localPort", 8888, "bindAddr", " 0.0.0.0 ", "targetHost", "target.internal", "targetPort", 80));
        when(socks.startSocks5Proxy(1080, "0.0.0.0")).thenReturn(Map.of("code", 200, "bindAddr", "0.0.0.0"));
        when(http.startHttpProxy(1080, "0.0.0.0")).thenReturn(Map.of("code", 200, "bindAddr", "0.0.0.0"));
        when(forward.startLocalForward(8888, "0.0.0.0", "target.internal", 80))
                .thenReturn(Map.of("code", 200, "bindAddr", "0.0.0.0"));
        try (var utilities = mockStatic(ControllerUtil.class)) {
            utilities.when(() -> ControllerUtil.requireCapability(params, Socks5ProxyCapable.class)).thenReturn(socks);
            utilities.when(() -> ControllerUtil.requireCapability(params, HttpProxyCapable.class)).thenReturn(http);
            utilities.when(() -> ControllerUtil.requireCapability(params, LocalForwardCapable.class)).thenReturn(forward);

            for (Map<String, Object> response : List.of(
                    new Socks5ProxyController().start(params), new HttpProxyController().start(params),
                    new LocalForwardController().start(params))) {
                assertEquals(200, response.get("code"));
                assertEquals("0.0.0.0", ((Map<?, ?>) response.get("data")).get("bindAddr"));
            }
            verify(socks).startSocks5Proxy(1080, "0.0.0.0");
            verify(http).startHttpProxy(1080, "0.0.0.0");
            verify(forward).startLocalForward(8888, "0.0.0.0", "target.internal", 80);
        }
    }

    @Test
    void rejectsInvalidBindParametersBeforeInvokingANode() {
        try (var utilities = mockStatic(ControllerUtil.class)) {
            for (Object address : new Object[]{12, "not-an-address", "127.0.0.1:1080"}) {
                var params = new HashMap<String, Object>(Map.of("port", 1080, "localPort", 8888,
                        "targetHost", "target.internal", "targetPort", 80, "bindAddr", address));
                assertEquals(400, new Socks5ProxyController().start(params).get("code"));
                assertEquals(400, new HttpProxyController().start(params).get("code"));
                assertEquals(400, new LocalForwardController().start(params).get("code"));
            }
            utilities.verifyNoInteractions();
        }
    }

    @Test
    void omittedBindAddressUsesWildcardDefault() {
        assertEquals("0.0.0.0", ProxyControllerSupport.optionalBindAddress(Map.of("port", 1080)));
    }
}
