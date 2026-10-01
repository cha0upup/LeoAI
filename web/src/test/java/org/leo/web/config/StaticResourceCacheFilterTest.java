package org.leo.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StaticResourceCacheFilterTest {

    private final StaticResourceCacheFilter filter = new StaticResourceCacheFilter();

    @Test
    void preventsCachingTheRootAndSpaRoutes() throws Exception {
        assertHtmlIsNotCached("/");
        assertHtmlIsNotCached("/index.html");
        assertHtmlIsNotCached("/change-password");
        assertHtmlIsNotCached("/main/agent/HOME");
    }

    @Test
    void cachesContentHashedAssetsAsImmutable() throws Exception {
        MockHttpServletResponse response = filter("/assets/LoginView-BPIRt60c.js");

        assertEquals(StaticResourceCacheFilter.HASHED_ASSET_CACHE_CONTROL,
                response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void leavesApiResponsesToTheirOwnCachePolicy() throws Exception {
        MockHttpServletResponse response = filter("/platform/user/status");

        assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    private void assertHtmlIsNotCached(String path) throws Exception {
        MockHttpServletResponse response = filter(path);
        assertEquals(StaticResourceCacheFilter.HTML_CACHE_CONTROL,
                response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("no-cache", response.getHeader(HttpHeaders.PRAGMA));
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", response.getHeader(HttpHeaders.EXPIRES));
    }

    private MockHttpServletResponse filter(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
