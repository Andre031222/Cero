<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>

<section class="py-10">
    <div class="max-w-4xl mb-10">
        <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b]">DB Test</p>
        <h1 class="text-4xl font-bold tracking-tight mt-3 mb-3 text-ink dark:text-[#f5f5f7]">CRUD sobre tabla <code class="font-mono text-apple">dicTest</code></h1>
        <p class="text-base text-muted dark:text-[#86868b] leading-relaxed">
            Accesos rápidos para evaluar lectura, inserción, actualización y borrado con <code class="font-mono">JxDB</code>.
        </p>
    </div>

    <div class="grid gap-4">

        <div class="bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl p-5">
            <h2 class="text-sm font-semibold text-ink dark:text-[#f5f5f7] mb-3">Conectividad</h2>
            <a class="inline-flex px-4 py-2 rounded-full bg-ink dark:bg-white/10 text-white dark:text-[#f5f5f7] text-xs font-mono font-semibold hover:opacity-80 transition-opacity"
               href="${pageContext.request.contextPath}/demo/dbremote">
                Probar conexión remota
            </a>
        </div>

        <div class="bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl p-5">
            <h2 class="text-sm font-semibold text-ink dark:text-[#f5f5f7] mb-3">Read</h2>
            <div class="flex flex-wrap gap-2">
                <a class="inline-flex px-4 py-2 rounded-full bg-apple text-white text-xs font-mono font-semibold hover:bg-[#0040CC] transition-colors"
                   href="${pageContext.request.contextPath}/demo/test-list">Ver todos</a>
                <a class="inline-flex px-4 py-2 rounded-full border border-black/[0.1] dark:border-white/[0.1] text-xs font-mono text-ink dark:text-[#f5f5f7] hover:border-apple/30 transition-colors"
                   href="${pageContext.request.contextPath}/demo/test-get/1">Ver id=1</a>
            </div>
        </div>

        <div class="bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl p-5">
            <h2 class="text-sm font-semibold text-ink dark:text-[#f5f5f7] mb-1">Create</h2>
            <p class="text-xs text-muted dark:text-[#86868b] mb-3">Inserta un registro con los campos <code class="font-mono">DNI</code> y <code class="font-mono">Nombres</code>.</p>
            <form method="post" action="${pageContext.request.contextPath}/demo/test-add/12345678/Juan%20Perez">
                <button type="submit" class="inline-flex px-4 py-2 rounded-full bg-apple text-white text-xs font-mono font-semibold hover:bg-[#0040CC] transition-colors">
                    Insertar ejemplo
                </button>
            </form>
        </div>

        <div class="bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl p-5">
            <h2 class="text-sm font-semibold text-ink dark:text-[#f5f5f7] mb-1">Update</h2>
            <p class="text-xs text-muted dark:text-[#86868b] mb-3">Actualiza el registro <code class="font-mono">Id=1</code> con diferente nombre.</p>
            <div class="flex flex-wrap gap-2">
                <% for (String[] u : new String[][]{
                    {"/demo/test-update/1/actualizacion%201", "Actualizacion 1"},
                    {"/demo/test-update/1/actualizacion%202", "Actualizacion 2"},
                    {"/demo/test-update/1/actualizacion%203", "Actualizacion 3"},
                }) { %>
                <form method="post" action="${pageContext.request.contextPath}<%= u[0] %>">
                    <button type="submit" class="inline-flex px-4 py-2 rounded-full border border-black/[0.1] dark:border-white/[0.1] text-xs font-mono text-ink dark:text-[#f5f5f7] hover:border-apple/30 transition-colors"><%= u[1] %></button>
                </form>
                <% } %>
            </div>
        </div>

        <div class="bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl p-5">
            <h2 class="text-sm font-semibold text-ink dark:text-[#f5f5f7] mb-1">Delete</h2>
            <p class="text-xs text-muted dark:text-[#86868b] mb-3">Borra el registro <code class="font-mono">Id=1</code>.</p>
            <form method="post" action="${pageContext.request.contextPath}/demo/test-delete/1">
                <button type="submit" class="inline-flex px-4 py-2 rounded-full border border-black/[0.1] dark:border-white/[0.1] text-xs font-mono text-ink dark:text-[#f5f5f7] hover:border-apple/30 transition-colors">
                    Eliminar ejemplo
                </button>
            </form>
        </div>

    </div>

    <div class="mt-8">
        <a class="text-sm font-mono text-apple hover:underline" href="${pageContext.request.contextPath}/">
            Volver a inicio
        </a>
    </div>
</section>

<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
