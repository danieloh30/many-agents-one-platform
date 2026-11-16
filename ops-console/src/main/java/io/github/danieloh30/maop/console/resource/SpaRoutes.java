package io.github.danieloh30.maop.console.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;

/** Deep links handled by the SPA router return index.html (static assets are served from META-INF/resources). */
@Path("/runs")
public class SpaRoutes {

    @GET
    @Path("/{path: .+}")
    @Produces(MediaType.TEXT_HTML)
    public Response deepLink() {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/resources/index.html");
        if (in == null) {
            return Response.status(404).entity("Console UI is not bundled in this build.").type(MediaType.TEXT_PLAIN).build();
        }
        return Response.ok(in).header("Cache-Control", "no-cache").build();
    }
}
