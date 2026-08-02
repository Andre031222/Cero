<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@taglib uri="http://jxmvc/tags" prefix="jx" %>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>

<section class="max-w-4xl mx-auto px-4 sm:px-6 py-10">
    <div class="max-w-3xl mb-10">
        <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b]">BD Demo</p>
        <h1 class="text-4xl font-bold tracking-tight mt-3 mb-3 text-ink dark:text-[#f5f5f7]">Listado desde DBRow</h1>
        <p class="text-base text-muted dark:text-[#86868b] leading-relaxed">
            Ejemplo simple usando DBRow + JxTagFor / JxTagIf.
        </p>
    </div>

    <div class="bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl overflow-hidden">
        <div class="px-5 py-3.5 border-b border-black/[0.06] dark:border-white/[0.06]">
            <p class="text-xs font-mono text-muted dark:text-[#86868b]">Estado conexión:
                <span class="text-ink dark:text-[#f5f5f7] font-medium">${connState}</span>
            </p>
        </div>
        <jx:if test="${hasRows}">
            <div class="overflow-x-auto">
                <table class="w-full text-xs">
                    <thead>
                        <tr class="border-b border-black/[0.06] dark:border-white/[0.06] bg-black/[0.02] dark:bg-white/[0.02]">
                            <th class="text-left px-5 py-3 font-semibold text-muted dark:text-[#86868b] uppercase tracking-wider">ID</th>
                            <th class="text-left px-5 py-3 font-semibold text-muted dark:text-[#86868b] uppercase tracking-wider">Nombre</th>
                            <th class="text-left px-5 py-3 font-semibold text-muted dark:text-[#86868b] uppercase tracking-wider">Rol</th>
                            <th class="text-left px-5 py-3 font-semibold text-muted dark:text-[#86868b] uppercase tracking-wider">Estado</th>
                        </tr>
                    </thead>
                    <tbody class="divide-y divide-black/[0.05] dark:divide-white/[0.05]">
                        <jx:for items="${rows}" var="row" maxCount="10">
                            <tr class="hover:bg-black/[0.02] dark:hover:bg-white/[0.02] transition-colors">
                                <td class="py-3 px-5 font-semibold text-ink dark:text-[#f5f5f7]">${row.get("id")}</td>
                                <td class="py-3 px-5 text-ink dark:text-[#f5f5f7]">${row.get("nombre")}</td>
                                <td class="py-3 px-5 text-ink dark:text-[#f5f5f7]">${row.get("rol")}</td>
                                <td class="py-3 px-5 text-muted dark:text-[#86868b]">Activo</td>
                            </tr>
                        </jx:for>
                    </tbody>
                </table>
            </div>
        </jx:if>
        <jx:if test="${!hasRows}">
            <p class="px-5 py-4 text-sm text-muted dark:text-[#86868b]">Sin registros.</p>
        </jx:if>
    </div>
</section>

<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
