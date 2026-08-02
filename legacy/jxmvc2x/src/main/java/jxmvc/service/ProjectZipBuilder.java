package jxmvc.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Genera un proyecto Lux (JxMVC) listo para usar como archivo ZIP en memoria.
 * Sin dependencias externas — usa java.util.zip del JDK.
 */
public final class ProjectZipBuilder {

    private ProjectZipBuilder() {}

    /**
     * @param groupId    ej: com.miempresa
     * @param artifactId ej: mi-app
     * @param pkg        ej: com.miempresa.miapp  (paquete base limpio)
     * @param db         "none" | "postgresql" | "mysql" | "sqlserver"
     * @param appName    ej: Mi Aplicacion
     */
    public static byte[] build(String groupId, String artifactId,
                                String pkg, String db, String appName) throws Exception {
        String pkgPath = pkg.replace('.', '/');

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {

            add(zip, "pom.xml",                                          pom(groupId, artifactId, db));
            add(zip, "src/main/resources/application.properties",        props2(pkg, db));
            add(zip, "src/main/webapp/WEB-INF/web.xml",                  webXml());
            add(zip, "src/main/webapp/WEB-INF/views/shared/header.jspf", headerJspf(appName));
            add(zip, "src/main/webapp/WEB-INF/views/shared/footer.jspf", footerJspf());
            add(zip, "src/main/webapp/WEB-INF/views/shared/error.jsp",   errorJsp());
            add(zip, "src/main/webapp/WEB-INF/views/home/index.jsp",     indexJsp(appName));
            add(zip, "src/main/webapp/assets/.gitkeep",                  "");

            add(zip, "src/main/java/" + pkgPath + "/controllers/BaseController.java",
                     baseController(pkg));
            add(zip, "src/main/java/" + pkgPath + "/controllers/HomeController.java",
                     homeController(pkg, appName));

            if (!"none".equals(db)) {
                add(zip, "src/main/java/" + pkgPath + "/models/Sample.java",
                         sampleModel(pkg));
            }

            add(zip, "README.md", readme(appName, artifactId, db));
        }
        return buf.toByteArray();
    }

    private static void add(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ── Templates ─────────────────────────────────────────────────────────

    private static String pom(String groupId, String artifactId, String db) {
        String dbDep = switch (db) {
            case "postgresql" -> """
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <version>42.7.4</version>
                        </dependency>""";
            case "mysql" -> """
                        <dependency>
                            <groupId>com.mysql</groupId>
                            <artifactId>mysql-connector-j</artifactId>
                            <version>9.1.0</version>
                        </dependency>""";
            case "sqlserver" -> """
                        <dependency>
                            <groupId>com.microsoft.sqlserver</groupId>
                            <artifactId>mssql-jdbc</artifactId>
                            <version>12.6.1.jre11</version>
                        </dependency>""";
            default -> "";
        };

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0</version>
                    <packaging>war</packaging>

                    <properties>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>jakarta.platform</groupId>
                            <artifactId>jakarta.jakartaee-api</artifactId>
                            <version>10.0.0</version>
                            <scope>provided</scope>
                        </dependency>
                        <dependency>
                            <groupId>jxmvc</groupId>
                            <artifactId>jxmvc-core</artifactId>
                            <version>3.4.0</version>
                        </dependency>
                %s
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.12.1</version>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-war-plugin</artifactId>
                                <version>3.4.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(groupId, artifactId, dbDep);
    }

    private static String props2(String pkg, String db) {
        String dbSection = switch (db) {
            case "postgresql" -> """
                    jxmvc.db.url              = jdbc:postgresql://localhost:5432/mi_db
                    jxmvc.db.user             = postgres
                    jxmvc.db.pass             = secret
                    jxmvc.pool.enabled        = true
                    jxmvc.pool.size           = 10
                    jxmvc.pool.timeout        = 5
                    """;
            case "mysql" -> """
                    jxmvc.db.url              = jdbc:mysql://localhost:3306/mi_db
                    jxmvc.db.user             = root
                    jxmvc.db.pass             = secret
                    jxmvc.pool.enabled        = true
                    jxmvc.pool.size           = 10
                    jxmvc.pool.timeout        = 5
                    """;
            case "sqlserver" -> """
                    jxmvc.db.url              = jdbc:sqlserver://localhost:1433;databaseName=mi_db
                    jxmvc.db.user             = sa
                    jxmvc.db.pass             = secret
                    jxmvc.pool.enabled        = true
                    jxmvc.pool.size           = 10
                    jxmvc.pool.timeout        = 5
                    """;
            default -> "# Sin base de datos configurada\n";
        };

        return "# Lux / JxMVC 3.4.0\n"
             + "jxmvc.controllers.package = " + pkg + "\n\n"
             + "# Base de datos\n" + dbSection + "\n"
             + "jxmvc.log.level           = INFO\n"
             + "jxmvc.profile             = dev\n"
             + "jxmvc.async.threads       = 8\n\n"
             + "# Seguridad y endurecimiento (v3.4.0)\n"
             + "jxmvc.security.frame-options = SAMEORIGIN\n"
             + "jxmvc.security.hsts          = false\n"
             + "jxmvc.security.csrf          = false      # token CSRF por sesión (jx:csrf en JSP)\n"
             + "jxmvc.body.maxBytes          = 10485760   # tope del body no-multipart (413 al exceder)\n"
             + "jxmvc.redirect.external      = false      # permitir redirecciones a otros dominios\n"
             + "jxmvc.trustProxy             = false      # confiar en X-Forwarded-For (solo tras proxy propio)\n";
    }

    private static String webXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
                         version="6.0">

                    <servlet>
                        <servlet-name>MainLxServlet</servlet-name>
                        <servlet-class>jxmvc.core.MainLxServlet</servlet-class>
                        <load-on-startup>1</load-on-startup>
                        <multipart-config>
                            <max-file-size>10485760</max-file-size>
                            <max-request-size>20971520</max-request-size>
                        </multipart-config>
                    </servlet>

                    <servlet-mapping>
                        <servlet-name>MainLxServlet</servlet-name>
                        <url-pattern>/</url-pattern>
                    </servlet-mapping>

                    <servlet-mapping>
                        <servlet-name>default</servlet-name>
                        <url-pattern>/assets/*</url-pattern>
                    </servlet-mapping>

                    <session-config>
                        <session-timeout>30</session-timeout>
                        <cookie-config>
                            <http-only>true</http-only>
                            <secure>false</secure>
                        </cookie-config>
                        <tracking-mode>COOKIE</tracking-mode>
                    </session-config>
                </web-app>
                """;
    }

    private static String baseController(String pkg) {
        return """
                package %s.controllers;

                import jxmvc.core.ActionResult;
                import jxmvc.core.JxController;
                import jxmvc.core.JxDB;
                import jxmvc.core.JxJson;

                public abstract class BaseController extends JxController {

                    protected JxDB db() { return new JxDB(); }

                    protected ActionResult jsonOk(Object data) {
                        return json("{\\"ok\\":true,\\"data\\":" + JxJson.toJson(data) + "}");
                    }

                    protected ActionResult jsonError(int status, String message) {
                        view.status(status);
                        return json("{\\"ok\\":false,\\"error\\":" + JxJson.toJson(message) + "}");
                    }

                    protected String requireParam(String name) {
                        String v = model.param(name);
                        if (v == null || v.isBlank())
                            throw jxmvc.core.JxException.badRequest("Parametro requerido: " + name);
                        return v;
                    }
                }
                """.formatted(pkg);
    }

    private static String homeController(String pkg, String appName) {
        return """
                package %s.controllers;

                import jxmvc.core.ActionResult;
                import jxmvc.core.JxMapping.*;

                @JxControllerMain
                @JxControllerMapping("home")
                public class HomeController extends BaseController {

                    @JxGetMapping("index")
                    public ActionResult index() {
                        model.setVar("appName", "%s");
                        model.setVar("version", "3.4.0");
                        return view("home/index");
                    }

                    @JxGetMapping("ping")
                    public ActionResult ping() {
                        return text("pong!");
                    }
                }
                """.formatted(pkg, appName);
    }

    private static String sampleModel(String pkg) {
        return """
                package %s.models;

                import jxmvc.core.JxModel;
                import jxmvc.core.JxDB;
                import jxmvc.core.DBRow;
                import jxmvc.core.DBRowSet;

                public class Sample extends JxModel {
                    private static final String T = "sample";

                    public static DBRowSet todos() {
                        try (JxDB db = db()) {
                            return db.query("SELECT * FROM " + T);
                        } catch (Exception e) { return new DBRowSet(); }
                    }

                    public static DBRow porId(Object id) {
                        try (JxDB db = db()) {
                            return db.queryRow("SELECT * FROM " + T + " WHERE id = ?", id);
                        } catch (Exception e) { return null; }
                    }

                    public static long guardar(DBRow datos) {
                        try (JxDB db = db()) { return db.insert(T, datos); }
                        catch (Exception e) { return -1; }
                    }

                    public static int eliminar(Object id) {
                        try (JxDB db = db()) {
                            return db.exec("DELETE FROM " + T + " WHERE id = ?", id);
                        } catch (Exception e) { return 0; }
                    }
                }
                """.formatted(pkg);
    }

    private static String headerJspf(String appName) {
        return """
                <%%@page pageEncoding="UTF-8"%%>
                <!DOCTYPE html>
                <html lang="es">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>${empty title ? '%1$s' : title}</title>
                    <script src="https://cdn.tailwindcss.com"></script>
                </head>
                <body class="bg-slate-50 text-slate-900 min-h-screen font-sans">
                    <header class="border-b border-slate-200 px-6 py-4 flex items-center gap-4">
                        <span class="font-semibold text-lg">%2$s</span>
                        <nav class="ml-6 flex gap-4 text-sm text-slate-600">
                            <a href="${pageContext.request.contextPath}/">Inicio</a>
                        </nav>
                    </header>
                    <main class="max-w-4xl mx-auto px-6 py-10">
                """.formatted(appName, appName);
    }

    private static String footerJspf() {
        return """
                    </main>
                    <footer class="text-center text-xs text-slate-400 py-8">
                        Powered by <strong>Lux / JxMVC 3.4.0</strong>
                    </footer>
                </body>
                </html>
                """;
    }

    private static String indexJsp(String appName) {
        return """
                <%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
                <%@ include file="/WEB-INF/views/shared/header.jspf" %>

                <h1 class="text-3xl font-bold">${appName}</h1>
                <p class="mt-3 text-slate-600">Proyecto generado con Lux / JxMVC ${version}</p>

                <div class="mt-8 grid gap-4 sm:grid-cols-2">
                    <div class="border border-slate-200 rounded-xl p-5">
                        <p class="font-semibold">Health check</p>
                        <a href="${pageContext.request.contextPath}/jx/health"
                           class="text-sm text-blue-600 mt-1 block">/jx/health</a>
                    </div>
                    <div class="border border-slate-200 rounded-xl p-5">
                        <p class="font-semibold">Ping</p>
                        <a href="${pageContext.request.contextPath}/home/ping"
                           class="text-sm text-blue-600 mt-1 block">/home/ping</a>
                    </div>
                </div>

                <%@ include file="/WEB-INF/views/shared/footer.jspf" %>
                """;
    }

    private static String errorJsp() {
        return """
                <%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
                <%@ include file="/WEB-INF/views/shared/header.jspf" %>
                <div class="text-center py-20">
                    <p class="text-6xl font-bold text-slate-300">${jx_error_code}</p>
                    <p class="mt-4 text-slate-600">${jx_error_message}</p>
                    <a href="${pageContext.request.contextPath}/"
                       class="mt-6 inline-block text-sm text-blue-600">Volver al inicio</a>
                </div>
                <%@ include file="/WEB-INF/views/shared/footer.jspf" %>
                """;
    }

    private static String readme(String appName, String artifactId, String db) {
        String dbSection = db.equals("none") ? "" : (
            "\n### Database\n\n"
            + "Edit `src/main/resources/application.properties` and set your connection details:\n\n"
            + "```properties\n"
            + "jxmvc.db.url    = jdbc:" + dbJdbcScheme(db) + "://localhost/" + artifactId.replaceAll("[^a-zA-Z0-9]","_") + "\n"
            + "jxmvc.db.user   = <user>\n"
            + "jxmvc.db.pass   = <password>\n"
            + "jxmvc.pool.enabled = true\n"
            + "jxmvc.pool.size    = 10\n"
            + "```\n\n"
            + "Make sure **" + dbLabel(db) + "** is running and the database exists before starting the application.\n"
        );

        return "# " + appName + "\n\n"
             + "Generated with **[JxMVC](https://jxmvc.ginit.dev) 3.4.0** — "
             + "a zero-dependency MVC framework for Jakarta EE.\n\n"
             + "---\n\n"
             + "## Prerequisites\n\n"
             + "| Tool | Version |\n"
             + "|---|---|\n"
             + "| JDK | 17 or later |\n"
             + "| Maven | 3.8 or later |\n"
             + "| Apache Tomcat | 10.1 or later (Jakarta EE 10 / Servlet 6.0) |\n"
             + (db.equals("none") ? "" : "| " + dbLabel(db) + " | latest stable |\n")
             + "\n"
             + "## Quick Start\n\n"
             + "```bash\n"
             + "# 1. Install the framework JAR into your local Maven repository (once per machine)\n"
             + "mvn install -f path/to/JxMVC.Core/pom.xml\n\n"
             + "# 2. Package the application\n"
             + "mvn package\n\n"
             + "# 3. Deploy to Tomcat\n"
             + "cp target/" + artifactId + "-1.0.war $CATALINA_HOME/webapps/\n"
             + "$CATALINA_HOME/bin/catalina.sh run\n"
             + "```\n\n"
             + "The application will be available at `http://localhost:8080/" + artifactId + "/`.\n\n"
             + dbSection
             + "\n## Project Structure\n\n"
             + "```\n"
             + artifactId + "/\n"
             + "  src/main/\n"
             + "    java/\n"
             + "      controllers/\n"
             + "        BaseController.java     # Shared helpers for all controllers\n"
             + "        HomeController.java     # Example controller — routes: /home/index, /home/ping\n"
             + (db.equals("none") ? "" :
               "      models/\n"
             + "        Sample.java             # Active record — CRUD methods, no field re-declarations\n")
             + "    resources/\n"
             + "      application.properties   # Framework configuration\n"
             + "    webapp/\n"
             + "      WEB-INF/\n"
             + "        web.xml                # Servlet mapping\n"
             + "        views/\n"
             + "          shared/\n"
             + "            header.jspf        # Included layout header\n"
             + "            footer.jspf        # Included layout footer\n"
             + "            error.jsp          # Global error view\n"
             + "          home/\n"
             + "            index.jsp          # View for HomeController.index()\n"
             + "      assets/                  # Static files (CSS, JS, images)\n"
             + "```\n\n"
             + "## Creating a Model + Controller\n\n"
             + "### Model (Active Record — no field re-declarations)\n\n"
             + "```java\n"
             + "public class Producto extends JxModel {\n"
             + "    private static final String T = \"productos\";\n\n"
             + "    public static DBRowSet todos() {\n"
             + "        try (JxDB db = db()) { return db.query(\"SELECT * FROM \" + T); }\n"
             + "        catch (Exception e) { return new DBRowSet(); }\n"
             + "    }\n\n"
             + "    public static DBRow porId(Object id) {\n"
             + "        try (JxDB db = db()) {\n"
             + "            return db.queryRow(\"SELECT * FROM \" + T + \" WHERE id = ?\", id);\n"
             + "        } catch (Exception e) { return null; }\n"
             + "    }\n"
             + "}\n"
             + "```\n\n"
             + "### Controller\n\n"
             + "```java\n"
             + "@JxControllerMapping(\"productos\")\n"
             + "public class ProductoController extends BaseController {\n\n"
             + "    // GET /productos/list\n"
             + "    @JxGetMapping(\"list\")\n"
             + "    public ActionResult list() {\n"
             + "        model.setVar(\"items\", Producto.todos().asList());\n"
             + "        return view(\"productos/list\");\n"
             + "    }\n\n"
             + "    // GET /productos/detail/{id}\n"
             + "    @JxGetMapping(\"detail/{id}\")\n"
             + "    public ActionResult detail(long id) {\n"
             + "        DBRow item = Producto.porId(id);\n"
             + "        model.setVar(\"nombre\", item.GetString(\"nombre\"));\n"
             + "        model.setVar(\"precio\", item.GetDouble(\"precio\"));\n"
             + "        return view(\"productos/detail\");\n"
             + "    }\n"
             + "}\n"
             + "```\n\n"
             + "## Configuration Reference\n\n"
             + "All settings live in `src/main/resources/application.properties`.\n\n"
             + "```properties\n"
             + "# Core\n"
             + "jxmvc.controllers.package = com.example." + artifactId.replaceAll("[^a-zA-Z0-9]","") + "\n"
             + "jxmvc.profile             = dev          # dev | prod | test\n"
             + "jxmvc.log.level           = INFO\n\n"
             + "# Connection pool\n"
             + "jxmvc.db.url              = jdbc:...\n"
             + "jxmvc.db.user             = \n"
             + "jxmvc.db.pass             = \n"
             + "jxmvc.pool.enabled        = false\n"
             + "jxmvc.pool.size           = 10\n"
             + "jxmvc.pool.timeout        = 5            # seconds\n\n"
             + "# Async\n"
             + "jxmvc.async.threads       = 8\n\n"
             + "# Security headers\n"
             + "jxmvc.security.frame-options = SAMEORIGIN\n"
             + "jxmvc.security.hsts          = false\n"
             + "```\n\n"
             + "## System Endpoints\n\n"
             + "These endpoints are built into the framework and require no controller code.\n\n"
             + "| Endpoint | Method | Description |\n"
             + "|---|---|---|\n"
             + "| `/jx/health` | GET | Pool status, uptime, active threads |\n"
             + "| `/jx/info` | GET | Framework version, active profile, JVM and server info |\n"
             + "| `/jx/metrics` | GET | Per-route: total requests, errors, mean latency |\n"
             + "| `/jx/openapi` | GET | OpenAPI 3.0 spec auto-generated from annotations |\n\n"
             + "## Pipeline\n\n"
             + "Every request passes through 14 ordered stages:\n\n"
             + "```\n"
             + "01  Internal endpoints    /jx/*\n"
             + "02  Metrics start         latency timer per route\n"
             + "03  Rate limiting         @JxRateLimit — sliding window per IP + route\n"
             + "04  Route resolution      convention / annotations / {var} templates\n"
             + "05  Profile guard         JxProfile.is() — enable/disable by environment\n"
             + "06  Authentication        @JxRequireAuth / @JxRequireRole\n"
             + "07  CORS                  @JxCors — per controller or per action\n"
             + "08  Before filters        JxFilters.add() — before/after por request\n"
             + "09  Instantiation + DI    controller created, @JxInject resolved\n"
             + "10  @JxBeforeAction       pre-action interceptors\n"
             + "11  @JxModelAttr          shared model attributes injected\n"
             + "12  Action invocation     @JxAsync (background) · @JxRetry (retries)\n"
             + "13  @JxAfterAction        post-action interceptors + after filters\n"
             + "14  Render + metrics      content negotiation · JSP / JSON / raw · final record\n"
             + "```\n\n"
             + "## Deployment\n\n"
             + "### Standalone Tomcat\n\n"
             + "```bash\n"
             + "mvn package\n"
             + "cp target/" + artifactId + "-1.0.war $CATALINA_HOME/webapps/ROOT.war\n"
             + "```\n\n"
             + "### Docker\n\n"
             + "```dockerfile\n"
             + "FROM tomcat:10.1-jdk17\n"
             + "COPY target/" + artifactId + "-1.0.war /usr/local/tomcat/webapps/ROOT.war\n"
             + "EXPOSE 8080\n"
             + "CMD [\"catalina.sh\", \"run\"]\n"
             + "```\n\n"
             + "```bash\n"
             + "docker build -t " + artifactId + " .\n"
             + "docker run -p 8080:8080 " + artifactId + "\n"
             + "```\n\n"
             + "## License\n\n"
             + "MIT — see [LICENSE](LICENSE) for details.\n";
    }

    private static String dbLabel(String db) {
        return switch (db) {
            case "postgresql" -> "PostgreSQL";
            case "mysql"      -> "MySQL";
            case "sqlserver"  -> "SQL Server";
            default           -> "";
        };
    }

    private static String dbJdbcScheme(String db) {
        return switch (db) {
            case "postgresql" -> "postgresql";
            case "mysql"      -> "mysql";
            case "sqlserver"  -> "sqlserver";
            default           -> "your-db";
        };
    }
}
