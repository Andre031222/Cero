<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>

<section class="max-w-2xl mx-auto px-4 sm:px-6 py-10">

    <%-- Encabezado --%>
    <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b] jx-reveal jx-delay-1">
        Framework · HTTP
    </p>
    <h1 class="text-4xl font-bold tracking-tight mt-3 mb-3 text-ink dark:text-[#f5f5f7] jx-reveal jx-delay-2">
        Manejo de errores
    </h1>
    <p class="text-base text-muted dark:text-[#86868b] leading-relaxed jx-reveal jx-delay-3">
        Respuestas controladas para verificar que el framework distingue correctamente cada código HTTP.
    </p>

    <%-- Tabla de pruebas --%>
    <div class="mt-10 bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl overflow-hidden divide-y divide-black/[0.05] dark:divide-white/[0.05] jx-reveal jx-delay-4">

        <%
        String[][] errores = {
            {"403", "Acceso denegado",
             "Lanza JxException(403, ...) de forma intencional",
             "/home/error403", "GET"},
            {"404", "Ruta inexistente",
             "/home/no-existe-ahora — no está registrada",
             "/home/no-existe-ahora", "GET"},
            {"500", "Excepción interna",
             "Lanza JxException.serverError() de forma intencional",
             "/home/error500", "GET"},
        };
        for (String[] e : errores) {
        %>
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between px-5 py-3.5 gap-3 sm:gap-0 hover:bg-black/[0.02] dark:hover:bg-white/[0.02] transition-colors">
            <div class="flex items-center gap-5">
                <span class="text-xs font-mono font-semibold text-apple w-8"><%= e[0] %></span>
                <div>
                    <p class="text-sm font-medium text-ink dark:text-[#f5f5f7]"><%= e[1] %></p>
                    <p class="text-xs text-muted dark:text-[#86868b] mt-0.5 font-mono"><%= e[2] %></p>
                </div>
            </div>
            <a href="${pageContext.request.contextPath}<%= e[3] %>"
               class="shrink-0 text-xs font-mono font-medium px-4 py-1.5 rounded-lg border border-black/[0.08] dark:border-white/[0.08] text-muted dark:text-[#86868b] hover:text-ink dark:hover:text-[#f5f5f7] hover:border-apple/30 transition-colors">
                probar →
            </a>
        </div>
        <% } %>

        <%-- 405 requiere form POST --%>
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between px-5 py-3.5 gap-3 sm:gap-0 hover:bg-black/[0.02] dark:hover:bg-white/[0.02] transition-colors">
            <div class="flex items-center gap-5">
                <span class="text-xs font-mono font-semibold text-apple w-8">405</span>
                <div>
                    <p class="text-sm font-medium text-ink dark:text-[#f5f5f7]">Método no permitido</p>
                    <p class="text-xs text-muted dark:text-[#86868b] mt-0.5 font-mono">/home/ping acepta GET — este formulario envía POST</p>
                </div>
            </div>
            <form method="post" action="${pageContext.request.contextPath}/home/ping">
                <button type="submit"
                        class="shrink-0 text-xs font-mono font-medium px-4 py-1.5 rounded-lg border border-black/[0.08] dark:border-white/[0.08] text-muted dark:text-[#86868b] hover:text-ink dark:hover:text-[#f5f5f7] hover:border-apple/30 transition-colors">
                    probar →
                </button>
            </form>
        </div>

    </div>

    <%-- Nota técnica --%>
    <p class="mt-6 text-xs text-muted dark:text-[#86868b] jx-reveal jx-delay-4 leading-relaxed">
        Las respuestas de error renderizan
        <code class="font-mono bg-black/[0.05] dark:bg-white/[0.05] px-1.5 py-0.5 rounded">WEB-INF/views/shared/error.jsp</code>
        con los atributos
        <code class="font-mono bg-black/[0.05] dark:bg-white/[0.05] px-1.5 py-0.5 rounded">jx_error_code</code>
        y
        <code class="font-mono bg-black/[0.05] dark:bg-white/[0.05] px-1.5 py-0.5 rounded">jx_error_message</code>.
    </p>

</section>

<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
