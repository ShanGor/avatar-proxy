# Avatar Proxy
A proxy server implemented by Java, which can be used to proxy HTTP requests.

## Features
- Support proxy to HTTP and HTTPS
- Support HTTP Basic Authentication
- Support relay to other proxy servers (Currently only support HTTP)
- Relay proxy server, support HTTP Basic Authentication
- Connection pool to same target servers, reuse connection as much as possible.

## Versions
- v1.1.0: Introduced connection pool. It can reduce the number of connections to the same target server.
- v1.0.1: For each request, there is one separate connection.

## Requirements
- Java 17+

## Usage Example
### Integrate it into your code like below
```java
    public static void main(String[] args) {
        try {
            // Create config for proxy, with default port as 3128
            ProxyConfig config = new ProxyConfig();

            // Add relay proxies
            config.addDomainRelay("youtube.com", "127.0.0.1", 4780);
            config.addDomainRelay("google.com", "127.0.0.1", 4781, "username", "password");

            // Add credentials for relay proxy server 
            config.addCredential("user1", "pass1");  // 添加第一对凭证
            config.addCredential("user2", "pass2");  // 添加第二对凭证
            
            // disable authentication
            // config.enableAuth(false);

            HttpProxyServer server = new HttpProxyServer(config);
            server.start();
        } catch (Exception e) {
            log.error("Failed to start proxy server", e);
        }
    }
```

### Then set proxy in your client
#### Windows
```sh
set https_proxy=http://127.0.0.1:3128
REM or
set https_proxy=http://user1:pass1@127.0.0.1:3128

set http_proxy=%https_proxy%
```
#### Linux
```sh
export https_proxy=http://127.0.0.1:3128
# or
export https_proxy=http://user1:pass1@127.0.0.1:3128
export http_proxy=$https_proxy

```

### VM Option Parameters
```
-DavatarProxy.port=3128
-DavatarProxy.connectTimeoutMillis=5000
-DavatarProxy.basicAuth=user1:pass1,user2:pass2
-DavatarProxy.relay=example.com->127.0.0.1:8080,example.com->http://user:pass@127.0.0.1:8080
```
