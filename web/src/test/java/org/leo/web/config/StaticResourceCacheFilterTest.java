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
        assertHtmlIsNotCached("/main/team.v2/settings");
    }

    @Test
    void cachesContentHashedAssetsAsImmutable() throws Exception {
        MockHttpServletResponse response = filter("/assets/main-Example01.js");

        assertEquals("public, max-age=31536000, immutable", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void leavesApiAndOtherFilesToTheirOwnCachePolicy() throws Exception {
        assertNull(filter("/platform/user/status").getHeader(HttpHeaders.CACHE_CONTROL));
        assertNull(filter("/main/team.v2/settings.json").getHeader(HttpHeaders.CACHE_CONTROL));
    }

    private void assertHtmlIsNotCached(String path) throws Exception {
        MockHttpServletResponse response = filter(path);
        assertEquals("no-cache, no-store, must-revalidate", response.getHeader(HttpHeaders.CACHE_CONTROL), path);
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
