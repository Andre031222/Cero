<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>

<section class="py-10">
    <div class="max-w-3xl mb-10">
        <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b]">Upload</p>
        <h1 class="text-4xl font-bold tracking-tight mt-3 mb-3 text-ink dark:text-[#f5f5f7]">Subida de archivo</h1>
        <p class="text-base text-muted dark:text-[#86868b] leading-relaxed">
            Demo usando <code class="font-mono">JxRequest.UploadFile</code>.
        </p>
    </div>

    <div class="bg-white dark:bg-white/[0.05] border border-black/[0.06] dark:border-white/[0.06] rounded-2xl p-6 max-w-md">
        <form method="post" action="${pageContext.request.contextPath}/demo/upload" enctype="multipart/form-data" class="space-y-4">
            <input type="file" name="file"
                   class="block w-full text-sm text-ink dark:text-[#f5f5f7] file:mr-3 file:px-4 file:py-2 file:rounded-full file:border-0 file:text-xs file:font-mono file:bg-apple/10 file:text-apple hover:file:bg-apple/20 transition-colors" />
            <button type="submit"
                    class="w-full py-2.5 rounded-2xl bg-apple text-white font-semibold text-sm hover:bg-[#0040CC] active:scale-[0.98] transition-all">
                Subir
            </button>
        </form>
        <p class="text-xs text-muted dark:text-[#86868b] mt-4">Destino: /tmp/uploads</p>
    </div>
</section>

<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
