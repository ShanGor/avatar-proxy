package io.github.shangor.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.github.shangor.proxy.config.ProxyConfig.PROXY_PATTERN;
import static org.junit.jupiter.api.Assertions.*;

class ProxyConfigTest {
    @Test
    void testProxyPattern() {
        var m = PROXY_PATTERN.matcher("https://example.com:8080");
        assertTrue(m.matches());
        assertEquals("https", m.group("scheme"));
        assertEquals("example.com", m.group("host"));
        assertEquals(8080, Integer.parseInt(m.group("port")));
        m = PROXY_PATTERN.matcher("http://example.com:8080");
        assertTrue(m.matches());
        assertEquals("http", m.group("scheme"));
        assertEquals("example.com", m.group("host"));
        assertEquals(8080, Integer.parseInt(m.group("port")));
        m = PROXY_PATTERN.matcher("socks5://user1:pass1@example.com:1080");
        assertTrue(m.matches());
        assertEquals("socks5", m.group("scheme"));
        assertEquals("user1", m.group("user"));
        assertEquals("pass1", m.group("pass"));
        assertEquals("example.com", m.group("host"));
        assertEquals(1080, Integer.parseInt(m.group("port")));
    }

    @Test
    void setPort() {
        ProxyConfig config = new ProxyConfig();
        config.setPort(8080);
        assertEquals(8080, config.getPort());
    }

    @Test
    void testAddDomainRelay() {
        ProxyConfig config = new ProxyConfig();
        config.addDomainRelay("example.com", "127.0.0.1", 8080);
        ProxyConfig.RelayProxyConfig relay = config.getRelayForDomain("example.com");
        assertEquals("127.0.0.1", relay.host());
        assertEquals(8080, relay.port());
    }
    @Test
    void testSetDomainRelayMap() {
        ProxyConfig config = new ProxyConfig();
        Map<String, ProxyConfig.RelayProxyConfig> relayMap = new HashMap<>();
        relayMap.put("example.com", new ProxyConfig.RelayProxyConfig("127.0.0.1", 8080, null, null));
        config.setDomainRelayMap(relayMap);
        ProxyConfig.RelayProxyConfig relay = config.getRelayForDomain("example.com");
        assertEquals("127.0.0.1", relay.host());
        // Test the getDomainRelayMap()
        Map<String, ProxyConfig.RelayProxyConfig> domainRelayMap = config.getDomainRelayMap();
        assertEquals(relayMap, domainRelayMap);
    }
}
