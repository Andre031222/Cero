/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  v3.1.1   : R. Andre Vilca Solorzano

package jxmvc.core;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Registra automáticamente los endpoints WebSocket anotados con
 * {@link JxMapping.JxWsEndpoint} al arrancar la aplicación.
 *
 * <p>No requiere configuración manual. Se activa automáticamente como
 * {@code @WebListener}. Para cada clase que extienda {@link JxWebSocket}
 * y tenga {@code @JxWsEndpoint}, se registra el endpoint en el contenedor.
 *
 * <p>Ejemplo de endpoint completo:
 * <pre>
 *   &#64;JxMapping.JxWsEndpoint("/ws/chat/{sala}")
 *   public class ChatEndpoint extends JxWebSocket {
 *
 *       &#64;Override
 *       protected void onOpen(Session session, String... pathVars) {
 *           joinRoom(pathVars[0], session);
 *           broadcast(pathVars[0], session.getId() + " conectado");
 *       }
 *
 *       &#64;Override
 *       protected void onMessage(Session session, String message) {
 *           String sala = (String) session.getUserProperties().get("sala");
 *           broadcast(sala, session.getId() + ": " + message);
 *       }
 *
 *       &#64;Override
 *       protected void onClose(Session session) {
 *           String sala = (String) session.getUserProperties().get("sala");
 *           leaveRoom(sala, session);
 *       }
 *   }
 * </pre>
 */
@WebListener
public final class JxWsRegistrar implements ServletContextListener {

    private static final JxLogger log = JxLogger.getLogger(JxWsRegistrar.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();

        ServerContainer wsContainer = (ServerContainer)
                ctx.getAttribute("jakarta.websocket.server.ServerContainer");

        if (wsContainer == null) {
            log.warn("[WS] ServerContainer no disponible — WebSocket desactivado");
            return;
        }

        List<Class<?>> endpoints = scanWsEndpoints(ctx);
        int registered = 0;

        for (Class<?> cls : endpoints) {
            JxMapping.JxWsEndpoint ann = cls.getAnnotation(JxMapping.JxWsEndpoint.class);
            String path = ann.value();

            try {
                ServerEndpointConfig.Builder builder =
                        ServerEndpointConfig.Builder.create(cls, path);

                if (ann.subprotocols().length > 0) {
                    List<String> subs = new ArrayList<>();
                    for (String s : ann.subprotocols()) subs.add(s);
                    builder.subprotocols(subs);
                }

                wsContainer.addEndpoint(builder.build());
                log.info("[WS] Registrado: {} → {}", cls.getSimpleName(), path);
                registered++;

            } catch (DeploymentException e) {
                log.error("[WS] Error registrando {}: {}", cls.getSimpleName(), e.getMessage());
            }
        }

        if (registered > 0)
            log.info("[WS] {} endpoint(s) WebSocket registrado(s)", registered);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {}

    // ── Scanner ───────────────────────────────────────────────────────────

    private List<Class<?>> scanWsEndpoints(ServletContext ctx) {
        List<Class<?>> result = new ArrayList<>();
        try {
            ClassLoader cl    = Thread.currentThread().getContextClassLoader();
            String classesDir = ctx.getRealPath("/WEB-INF/classes");
            if (classesDir == null) return result;

            File root = new File(classesDir);
            if (!root.isDirectory()) return result;

            scanDir(root, root, cl, result);
        } catch (Exception e) {
            log.warn("[WS] Error escaneando clases: {}", e.getMessage());
        }
        return result;
    }

    private void scanDir(File root, File dir, ClassLoader cl, List<Class<?>> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDir(root, f, cl, result);
            } else if (f.getName().endsWith(".class")) {
                String rel  = root.toURI().relativize(f.toURI()).getPath();
                String name = rel.replace('/', '.').replace('\\', '.').replace(".class", "");
                try {
                    Class<?> cls = cl.loadClass(name);
                    if (cls.isAnnotationPresent(JxMapping.JxWsEndpoint.class)
                            && JxWebSocket.class.isAssignableFrom(cls)
                            && !cls.isInterface()
                            && !java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) {
                        result.add(cls);
                    }
                } catch (Throwable e) { log.warn("WS scan: clase omitida: {}", e.getMessage()); }
            }
        }
    }
}
