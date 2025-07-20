# Performance Test

### Prepare some app to serve a testing:
```java
@RestController
public class PtServerController {
    @PostMapping(value = "/test", consumes = "text/plain", produces = "text/plain")
    public String forPerformanceTest(@RequestBody String input) {
        return input + "." +  input.length();
    }
}
```

### Run the proxy:
```sh
java -server -DavatarProxy.port=3128 -DavatarProxy.connectTimeoutMillis=10000 -DavatarProxy.relay="google.com->127.0.0.1:4780,youtube.com->http://user:pass@127.0.0.1:4780" -jar target/avatar-proxy-1.0.0-jar-with-dependencies.jar
```

### Run performance test:
```sh
hey -m POST -T "text/plain" -d "hello world, what they fuk you are doing now? i have no idea ffmpeg -i input.mp4 -i output.mp3 -map 0:v -map 1:a -c:v copy -c:a aac output.mp4" -c 5 -n 100000 -q 1000 -x http://localhost:3128  http://localhost:8080/test
```

### Test result
The proxy works well. No memory leakage found.
![Performance Test Result](PT-VM-Usage.png)
