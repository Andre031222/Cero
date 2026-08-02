package bench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@RestController
public class App {
    public static void main(String[] args) { SpringApplication.run(App.class, args); }

    @GetMapping(value = "/plaintext", produces = "text/plain")
    public String plaintext() { return "OK"; }

    @GetMapping(value = "/json", produces = "application/json")
    public String json() { return "{\"message\":\"hello\",\"n\":42}"; }

    @GetMapping(value = "/db", produces = "application/json")
    public String db() { return Db.json(); }
}
