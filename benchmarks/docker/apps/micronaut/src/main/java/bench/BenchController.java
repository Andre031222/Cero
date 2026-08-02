package bench;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller
public class BenchController {

    @Get(value = "/plaintext", produces = "text/plain")
    public String plaintext() { return "OK"; }

    @Get(value = "/json", produces = "application/json")
    public String json() { return "{\"message\":\"hello\",\"n\":42}"; }

    @Get(value = "/db", produces = "application/json")
    public String db() { return Db.json(); }
}
