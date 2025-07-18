package io.github.shangor.proxy.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProxyConfigTest {

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
        relayMap.put("example.com", new ProxyConfig.RelayProxyConfig("127.0.0.1", 8080));
        config.setDomainRelayMap(relayMap);
        ProxyConfig.RelayProxyConfig relay = config.getRelayForDomain("example.com");
        assertEquals("127.0.0.1", relay.host());
        // Test the getDomainRelayMap()
        Map<String, ProxyConfig.RelayProxyConfig> domainRelayMap = config.getDomainRelayMap();
        assertEquals(relayMap, domainRelayMap);
    }
}
