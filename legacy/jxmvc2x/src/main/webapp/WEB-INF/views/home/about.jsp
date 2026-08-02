<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>

<div class="max-w-6xl mx-auto px-4 sm:px-6 py-10">

<%-- ── Encabezado ───────────────────────────────────────────────────── --%>
<div class="mb-12 jx-reveal jx-delay-1">
    <p class="text-[10px] font-mono uppercase tracking-[0.28em] text-muted dark:text-[#8E8E93] mb-3" data-i18n="about.tag">About</p>
    <h1 class="text-4xl sm:text-5xl font-bold tracking-tight mb-4 text-ink dark:text-white">
        Lightning-X MVC <span class="text-apple">3.4.0</span>
    </h1>
    <p class="text-base text-muted dark:text-[#8E8E93] max-w-2xl leading-relaxed" data-i18n="about.desc">
        Framework MVC para Jakarta EE construido desde cero — sin Spring, sin Hibernate,
        sin dependencias en runtime. Un JAR de 253 KB con routing, pool de conexiones,
        validación, JSON, WebSocket, métricas y OpenAPI integrados. Virtual Threads
        detectados automáticamente en Java 21+.
    </p>
</div>

<%-- ── Métricas clave ──────────────────────────────────────────────── --%>
<div class="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-12 jx-reveal jx-delay-2">
    <div class="bg-white dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl px-5 py-5 shadow-card">
        <p class="text-2xl font-bold text-ink dark:text-white">253<span class="text-apple text-sm font-semibold"> KB</span></p>
        <p class="text-[11px] text-muted dark:text-[#8E8E93] mt-1">Tamaño del core</p>
    </div>
    <div class="bg-white dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl px-5 py-5 shadow-card">
        <p class="text-2xl font-bold text-ink dark:text-white">0<span class="text-apple text-sm font-semibold"> deps</span></p>
        <p class="text-[11px] text-muted dark:text-[#8E8E93] mt-1">Dependencias runtime</p>
    </div>
    <div class="bg-white dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl px-5 py-5 shadow-card">
        <p class="text-2xl font-bold text-ink dark:text-white">15</p>
        <p class="text-[11px] text-muted dark:text-[#8E8E93] mt-1">Etapas del pipeline</p>
    </div>
    <div class="bg-white dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl px-5 py-5 shadow-card">
        <p class="text-2xl font-bold text-ink dark:text-white">54</p>
        <p class="text-[11px] text-muted dark:text-[#8E8E93] mt-1">Clases en el core</p>
    </div>
</div>

<%-- ── Pipeline por request (orden real de MainLxServlet) ──────────── --%>
<div class="mb-12 jx-reveal jx-delay-3">
    <p class="text-[10px] font-mono uppercase tracking-[0.28em] text-muted dark:text-[#8E8E93] mb-5" data-i18n="about.pipeline">Pipeline de cada request</p>
    <div class="bg-white dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl overflow-hidden divide-y divide-black/[0.05] dark:divide-white/[0.05] shadow-card">
        <%
        String[][] steps = {
            {"01","Endpoints internos",    "/jx/health · /jx/info · /jx/metrics · /jx/openapi"},
            {"02","Métricas — inicio",     "Timer de latencia por ruta"},
            {"03","Resolución de ruta",     "Convención / anotaciones / plantillas {var}"},
            {"04","Rate limiting",          "@JxRateLimit — ventana deslizante, clave por acción"},
            {"05","Autenticación",          "@JxRequireAuth / @JxRequireRole — fail-closed"},
            {"06","CSRF",                   "Token de sesión en verbos mutadores · @JxCsrfExempt"},
            {"07","CORS",                   "@JxCors global o por controlador/acción"},
            {"08","Contexto",               "JxRequest / JxResponse construidos"},
            {"09","Filtros before",         "@JxFilter · JxFilters.before()"},
            {"10","Instancia + DI",         "Controlador instanciado, @JxInject resuelto"},
            {"11","@JxBeforeAction",        "Interceptores pre-acción por método"},
            {"12","@JxModelAttr",           "Atributos comunes inyectados al modelo"},
            {"13","Invocación",             "@JxAsync / @JxRetry · @JxCacheable · @JxTransactional"},
            {"14","@JxAfterAction + after", "Interceptores post-acción y filtros after"},
            {"15","Render + métricas",      "Negociación de contenido · JSP / JSON / raw · registro final"},
        };
        for (String[] step : steps) {
        %>
        <div class="flex items-start gap-5 px-5 py-3.5 hover:bg-[#F5F5F7] dark:hover:bg-white/[0.04] transition-colors">
            <span class="text-[10px] font-mono text-muted dark:text-[#8E8E93] w-6 shrink-0 pt-px"><%= step[0] %></span>
            <div class="min-w-0">
                <p class="text-sm font-medium text-ink dark:text-white"><%= step[1] %></p>
                <p class="text-xs text-muted dark:text-[#8E8E93] mt-0.5"><%= step[2] %></p>
            </div>
        </div>
        <% } %>
    </div>
</div>

<%-- ── Comparativa ─────────────────────────────────────────────────── --%>
<div class="mb-12 jx-reveal jx-delay-4">
    <p class="text-[10px] font-mono uppercase tracking-[0.28em] text-muted dark:text-[#8E8E93] mb-5" data-i18n="about.compare">Comparativa</p>
    <div class="overflow-x-auto bg-white dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl shadow-card">
        <table class="w-full text-xs">
            <thead>
                <tr class="border-b border-black/[0.06] dark:border-white/[0.06] bg-[#F5F5F7] dark:bg-white/[0.04]">
                    <th class="text-left px-5 py-3 font-semibold text-muted dark:text-[#8E8E93] uppercase tracking-wider">Aspecto</th>
                    <th class="text-center px-4 py-3 font-semibold text-apple">JxMVC 3.4.0</th>
                    <th class="text-center px-4 py-3 font-semibold text-muted dark:text-[#8E8E93]">Spring Boot 3</th>
                    <th class="text-center px-4 py-3 font-semibold text-muted dark:text-[#8E8E93]">Jakarta EE raw</th>
                </tr>
            </thead>
            <tbody class="divide-y divide-black/[0.05] dark:divide-white/[0.05]">
                <%
                String[][] rows = {
                    {"WAR / JAR mínimo",    "253 KB",   "~18 MB",     "~50 KB"},
                    {"Dependencias runtime", "0",        "~50-150+",   "0"},
                    {"Routing",             "Conv+Ann",  "Ann",        "Manual"},
                    {"Pool de conexiones",  "propio",    "HikariCP",   "ninguno"},
                    {"DI",                  "propio",    "Spring DI",  "CDI"},
                    {"Validación",          "propia",    "Bean Val",   "Bean Val"},
                    {"JSON",                "propio",    "Jackson",    "manual"},
                    {"WebSocket",           "built-in",  "ext",        "manual"},
                    {"Métricas",            "built-in",  "Micrometer", "manual"},
                    {"OpenAPI",             "built-in",  "SpringDoc",  "manual"},
                    {"Arranque (aprox.)",   "1.2 s",     "~3-8 s",     "< 300 ms"},
                };
                for (String[] row : rows) {
                %>
                <tr class="hover:bg-[#F5F5F7] dark:hover:bg-white/[0.03] transition-colors">
                    <td class="px-5 py-3 text-ink dark:text-white font-medium"><%= row[0] %></td>
                    <td class="px-4 py-3 text-center font-mono text-apple font-semibold"><%= row[1] %></td>
                    <td class="px-4 py-3 text-center text-muted dark:text-[#8E8E93] font-mono"><%= row[2] %></td>
                    <td class="px-4 py-3 text-center text-muted dark:text-[#8E8E93] font-mono"><%= row[3] %></td>
                </tr>
                <% } %>
            </tbody>
        </table>
    </div>
</div>

<%-- ── Stack técnico ───────────────────────────────────────────────── --%>
<div class="mb-12 jx-reveal jx-delay-4">
    <p class="text-[10px] font-mono uppercase tracking-[0.28em] text-muted dark:text-[#8E8E93] mb-5" data-i18n="about.stack">Stack</p>
    <div class="grid sm:grid-cols-2 lg:grid-cols-3 gap-3">
        <%
        String[][] stack = {
            {"Lenguaje",  "Java 17+ / 21+",                  "Virtual Threads detectados automáticamente en Java 21+"},
            {"Servidor",  "Apache Tomcat 10+",               "Jakarta EE 11, Servlet 6.0"},
            {"Build",     "Maven 3.8+",                      "WAR packaging, cargo-maven3-plugin"},
            {"DB",        "PostgreSQL · MySQL · SQL Server", "Driver JDBC estándar, sin ORM"},
            {"Vistas",    "JSP + Tailwind CSS",              "Sin JSTL requerido — EL puro"},
            {"Testing",   "cargo:run embebido",              "Deploy local en segundos"},
        };
        for (String[] s : stack) {
        %>
        <div class="bg-white dark:bg-[#1C1C1E] border border-black/[0.06] dark:border-white/[0.07] rounded-2xl p-5 shadow-card hover:shadow-cardh hover:border-apple/25 transition-all">
            <p class="text-[10px] font-mono uppercase tracking-wider text-muted dark:text-[#8E8E93] mb-1.5"><%= s[0] %></p>
            <p class="text-sm font-semibold text-ink dark:text-white mb-1"><%= s[1] %></p>
            <p class="text-xs text-muted dark:text-[#8E8E93] leading-relaxed"><%= s[2] %></p>
        </div>
        <% } %>
    </div>
</div>

<%-- ── CTA ──────────────────────────────────────────────────────────── --%>
<div class="flex flex-wrap gap-3 mb-4 jx-reveal jx-delay-4">
    <a href="${pageContext.request.contextPath}/docs"
       class="px-5 py-2.5 bg-apple text-white text-sm font-medium rounded-full hover:bg-[#0077ED] active:scale-[0.97] transition-all shadow-sm"
       data-i18n="about.docs.btn">Ver documentación</a>
    <a href="${pageContext.request.contextPath}/downloads"
       class="px-5 py-2.5 bg-black/[0.05] dark:bg-white/[0.08] text-ink dark:text-white border border-black/[0.09] dark:border-white/[0.09] text-sm font-medium rounded-full hover:bg-black/[0.09] dark:hover:bg-white/[0.12] transition-colors"
       data-i18n="about.dl.btn">Descargar</a>
    <a href="${pageContext.request.contextPath}/jx/info" target="_blank"
       class="px-5 py-2.5 bg-black/[0.05] dark:bg-white/[0.08] text-muted dark:text-[#8E8E93] border border-black/[0.09] dark:border-white/[0.09] text-sm font-mono rounded-full hover:text-ink dark:hover:text-white hover:bg-black/[0.09] dark:hover:bg-white/[0.12] transition-colors">
        /jx/info →</a>
</div>

</div><%-- /container --%>
<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
