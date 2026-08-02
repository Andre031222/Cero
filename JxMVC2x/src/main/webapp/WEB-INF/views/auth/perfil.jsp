<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@ taglib prefix="jx" uri="http://jxmvc/tags" %>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>

<section class="max-w-md mx-auto px-4 sm:px-6 py-16">

    <div class="text-center mb-8">
        <p class="text-xs font-mono uppercase tracking-[0.25em] text-muted dark:text-[#86868b]">Sesión activa</p>
        <h1 class="text-3xl font-bold tracking-tight text-ink dark:text-[#f5f5f7] mt-3">Mi perfil</h1>
    </div>

    <div class="jx-card" style="padding:28px 26px;">
        <div class="flex flex-col items-center text-center">

            <% jxmvc.auth.AppUser u = (jxmvc.auth.AppUser) request.getAttribute("user");
               String pic  = u != null ? u.getPicture() : null;
               String name = u != null && u.getName() != null ? u.getName() : "Usuario";
               String initial = !name.isBlank() ? name.substring(0,1).toUpperCase() : "U";
            %>

            <% if (pic != null && !pic.isBlank()) { %>
            <img src="${jx:esc(user.picture)}" alt="Avatar" referrerpolicy="no-referrer"
                 style="width:76px; height:76px; border-radius:50%; object-fit:cover; border:1px solid rgba(0,0,0,0.08);">
            <% } else { %>
            <div style="width:76px; height:76px; border-radius:50%; display:flex; align-items:center; justify-content:center;
                        font-size:30px; font-weight:700; color:#fff;
                        background:linear-gradient(135deg,#0071E3,#5E5CE6);"><%= initial %></div>
            <% } %>

            <h2 class="text-xl font-semibold text-ink dark:text-[#f5f5f7] mt-4">${jx:esc(user.name)}</h2>
            <p class="text-sm text-muted dark:text-[#86868b] mt-1">${jx:esc(user.email)}</p>

            <div class="flex items-center gap-2 mt-4">
                <span style="display:inline-flex; align-items:center; gap:6px; padding:5px 12px; border-radius:999px;
                             font-size:11px; font-weight:600; background:rgba(0,113,227,0.10); color:#0071E3;">
                    <% if (u != null && "google".equals(u.getProvider())) { %>
                    Vía Google
                    <% } else { %>
                    Correo y contraseña
                    <% } %>
                </span>
                <span style="padding:5px 12px; border-radius:999px; font-size:11px; font-weight:600;
                             background:rgba(48,209,88,0.12); color:#1a9c3e;">Rol ${jx:esc(user.role)}</span>
            </div>
        </div>

        <div class="mt-7 grid gap-2.5">
            <a href="${pageContext.request.contextPath}/auth/logout" class="jx-btn jx-btn-ghost"
               style="width:100%; justify-content:center; padding:11px 18px; font-size:14px;">Cerrar sesión</a>
            <a href="${pageContext.request.contextPath}/" class="text-center text-[13px] text-apple hover:underline mt-1">Volver a inicio</a>
        </div>
    </div>

    <p class="text-center mt-6 text-[12px] text-muted dark:text-[#86868b]">
        Ruta protegida con <span class="font-mono text-ink dark:text-[#f5f5f7]">@JxRequireAuth</span> — el core validó la sesión antes de renderizar.
    </p>
</section>

<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
