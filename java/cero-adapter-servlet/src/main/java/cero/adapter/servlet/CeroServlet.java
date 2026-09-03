package cero.adapter.servlet;

import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import cero.core.Cero;
import cero.http.Handler;

import java.io.IOException;

@MultipartConfig(maxFileSize = 20_971_520, maxRequestSize = 41_943_040)
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
        RequestOverServlet entrada = new RequestOverServlet(peticion);
        ResponseOverServlet salida = new ResponseOverServlet(respuesta);
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
