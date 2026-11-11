package cero.adapter.servlet;

import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import cero.core.Cero;
import cero.http.Handler;

import java.io.IOException;

@MultipartConfig(maxFileSize = 10L << 20, maxRequestSize = 10L << 20)
public class CeroServlet extends HttpServlet {

    private final transient Handler handler;

    public CeroServlet(Cero aplicacion) {
        this.handler = aplicacion.handler();
    }

    public CeroServlet(Handler handler) {
        this.handler = handler;
    }

    @Override
    protected void service(HttpServletRequest peticion, HttpServletResponse respuesta)
            throws IOException {
        var entrada = new RequestOverServlet(peticion);
        var salida = new ResponseOverServlet(respuesta);
        try {
            handler.handle(entrada, salida);
        } catch (IOException fallo) {
            throw fallo;
        } catch (Exception fallo) {
            if (!salida.committed()) {
                respuesta.setStatus(500);
                respuesta.setContentType("text/plain; charset=utf-8");
                respuesta.getWriter().write("error interno");
            }
        }
    }
}
