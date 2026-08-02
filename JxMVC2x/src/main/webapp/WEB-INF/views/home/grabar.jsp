<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@ taglib prefix="jx" uri="http://jxmvc/tags" %>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>

<section class="max-w-4xl mx-auto px-4 sm:px-6 py-10">
    <div class="max-w-2xl mb-10">
        <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b]">Demo</p>
        <h1 class="text-4xl font-bold tracking-tight mt-3 mb-3 text-ink dark:text-[#f5f5f7]">Captura de argumentos</h1>
        <p class="text-base text-muted dark:text-[#86868b] leading-relaxed">
            Probando rutas dinámicas y sanitización básica.
        </p>
    </div>

    <div class="bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl p-6 mb-6">
        <div class="grid gap-5">
            <div>
                <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b] mb-1">Ruta invocada</p>
                <p class="text-lg font-semibold text-ink dark:text-[#f5f5f7]">${jx:esc(route)}</p>
            </div>
            <div class="grid md:grid-cols-2 gap-5">
                <div>
                    <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b] mb-1">Valor capturado</p>
                    <p class="text-lg font-semibold text-ink dark:text-[#f5f5f7]">${jx:esc(value)}</p>
                </div>
                <div>
                    <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b] mb-1">Cantidad de argumentos</p>
                    <p class="text-lg font-semibold text-ink dark:text-[#f5f5f7]">${argsCount}</p>
                </div>
            </div>
        </div>
    </div>

    <div class="bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl overflow-hidden">
        <div class="px-5 py-3.5 border-b border-black/[0.06] dark:border-white/[0.06]">
            <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b]">Quick links</p>
        </div>
        <ul class="divide-y divide-black/[0.05] dark:divide-white/[0.05]">
            <% for (String[] link : new String[][]{
                {"/home/about",        "About"},
                {"/home/ping",         "Ping"},
                {"/home/grabar/datos", "Captura de datos"},
                {"/home/bd",           "BD Demo"},
            }) { %>
            <li class="flex items-center justify-between px-5 py-3 hover:bg-black/[0.02] dark:hover:bg-white/[0.02] transition-colors">
                <code class="text-xs font-mono text-ink dark:text-[#f5f5f7]"><%= link[0] %></code>
                <a class="text-xs font-mono text-apple hover:underline" href="${pageContext.request.contextPath}<%= link[0] %>">abrir →</a>
            </li>
            <% } %>
        </ul>
    </div>

    <div class="mt-8">
        <a class="text-sm font-mono text-apple hover:underline" href="${pageContext.request.contextPath}/">
            Volver a inicio
        </a>
    </div>
</section>

<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
