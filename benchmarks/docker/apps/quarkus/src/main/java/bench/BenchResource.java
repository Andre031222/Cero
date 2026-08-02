package bench;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

@Path("")
public class BenchResource {

    @GET @Path("/plaintext") @Produces("text/plain")
    public String plaintext() { return "OK"; }

    @GET @Path("/json") @Produces("application/json")
    public String json() { return "{\"message\":\"hello\",\"n\":42}"; }

    @GET @Path("/db") @Produces("application/json")
    public String db() { return Db.json(); }
}
