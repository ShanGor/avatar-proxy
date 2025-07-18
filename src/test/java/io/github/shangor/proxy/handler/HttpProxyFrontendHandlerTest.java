package io.github.shangor.proxy.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpProxyFrontendHandlerTest {
    @Test
    void testUriPattern() {
        var uri = "http://baidu.com:80/hello-world";
        var matcher = HttpProxyFrontendHandler.URI_PATTERN.matcher(uri);
        assertTrue(matcher.matches());
        assertEquals("http", matcher.group("scheme"));
        assertEquals("baidu.com", matcher.group("host"));
        assertEquals("80", matcher.group("port"));
        uri = "https://baidu.com:443/hello-world";
        matcher = HttpProxyFrontendHandler.URI_PATTERN.matcher(uri);
        assertTrue(matcher.matches());
        assertEquals("https", matcher.group("scheme"));
        uri = "baidu.com:443";
        matcher = HttpProxyFrontendHandler.URI_PATTERN.matcher(uri);
        assertTrue(matcher.matches());
        assertEquals("baidu.com", matcher.group("host"));
        assertEquals("443", matcher.group("port"));
        uri = "baidu.com";
        matcher = HttpProxyFrontendHandler.URI_PATTERN.matcher(uri);
        assertTrue(matcher.matches());
        assertEquals("baidu.com", matcher.group("host"));
        assertNull(matcher.group("port"));
    }
}
