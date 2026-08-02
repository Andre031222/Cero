<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@ taglib prefix="jx" uri="http://jxmvc/tags" %>
<%@ include file="/WEB-INF/views/shared/header.jspf" %>
<%
    String err = (String) request.getAttribute("error");
    String errMsg = null;
    if (err != null) {
        switch (err) {
            case "credenciales":  errMsg = "Correo o contraseña incorrectos."; break;
            case "google_off":    errMsg = "El inicio con Google no está configurado en este servidor."; break;
            case "google_cancel": errMsg = "Cancelaste el inicio de sesión con Google."; break;
            case "google_fail":   errMsg = "No se pudo completar el inicio con Google. Inténtalo de nuevo."; break;
            case "state":         errMsg = "La sesión de inicio expiró. Vuelve a intentarlo."; break;
            default:              errMsg = "No se pudo iniciar sesión.";
        }
    }
    Boolean googleEnabled = (Boolean) request.getAttribute("googleEnabled");
%>

<style>
    .jx-auth-input {
        width:100%; padding:11px 14px; border-radius:12px; font-size:14px;
        background:#fff; color:#1D1D1F;
        border:1px solid rgba(0,0,0,0.12); transition:border-color .15s ease, box-shadow .15s ease;
    }
    .jx-auth-input::placeholder { color:#A0A0A6; }
    .jx-auth-input:focus { outline:none; border-color:#0071E3; box-shadow:0 0 0 3px rgba(0,113,227,0.15); }
    .dark .jx-auth-input { background:#1C1C1E; color:#F5F5F7; border-color:rgba(255,255,255,0.14); }
    .jx-auth-label { display:block; font-size:12px; font-weight:600; margin-bottom:6px;
        color:#6E6E73; letter-spacing:.01em; }
    .dark .jx-auth-label { color:#8E8E93; }
    .jx-auth-btn { width:100%; justify-content:center; padding:11px 18px; font-size:14px; }
    .jx-google-btn {
        width:100%; display:inline-flex; align-items:center; justify-content:center; gap:10px;
        padding:11px 18px; border-radius:980px; font-size:14px; font-weight:500;
        background:#fff; color:#1D1D1F; border:1px solid rgba(0,0,0,0.14);
        transition:background .15s ease, box-shadow .15s ease, transform .1s ease;
    }
    .jx-google-btn:hover { background:#F5F5F7; box-shadow:0 2px 8px rgba(0,0,0,0.06); transform:translateY(-1px); }
    .dark .jx-google-btn { background:#1C1C1E; color:#F5F5F7; border-color:rgba(255,255,255,0.16); }
    .dark .jx-google-btn:hover { background:rgba(255,255,255,0.06); }
    .jx-google-btn[aria-disabled="true"] { opacity:.5; pointer-events:none; }
    .jx-divider { display:flex; align-items:center; gap:14px; margin:22px 0; }
    .jx-divider::before, .jx-divider::after { content:''; flex:1; height:1px; background:rgba(0,0,0,0.08); }
    .dark .jx-divider::before, .dark .jx-divider::after { background:rgba(255,255,255,0.08); }
    .jx-divider span { font-size:11px; font-weight:600; letter-spacing:.14em; text-transform:uppercase; color:#A0A0A6; }
</style>

<section class="max-w-md mx-auto px-4 sm:px-6 py-16">

    <div class="text-center mb-8">
        <h1 class="text-3xl font-bold tracking-tight text-ink dark:text-[#f5f5f7]">Iniciar sesión</h1>
        <p class="text-sm text-muted dark:text-[#86868b] mt-2 leading-relaxed">
            Autenticación nativa y con Google, integradas en el core de JxMVC.
        </p>
    </div>

    <div class="jx-card" style="padding:28px 26px;">

        <% if (errMsg != null) { %>
        <div class="mb-5" style="padding:11px 14px; border-radius:12px; font-size:13px;
                    background:rgba(255,45,85,0.10); color:#D70015; border:1px solid rgba(255,45,85,0.25);">
            <%= errMsg %>
        </div>
        <% } %>

        <a href="${pageContext.request.contextPath}/auth/google" class="jx-google-btn"
           <% if (googleEnabled == null || !googleEnabled) { %>aria-disabled="true" title="Configura las credenciales de Google en el servidor"<% } %>>
            <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
                <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
                <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
                <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/>
                <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
            </svg>
            <span>Continuar con Google</span>
        </a>

        <div class="jx-divider"><span>o</span></div>

        <form method="post" action="${pageContext.request.contextPath}/auth/login" class="grid gap-4">
            ${jx:csrf(pageContext.request)}
            <div>
                <label class="jx-auth-label" for="email">Correo</label>
                <input class="jx-auth-input" type="email" id="email" name="email"
                       placeholder="demo@jxmvc.dev" autocomplete="username" required>
            </div>
            <div>
                <label class="jx-auth-label" for="password">Contraseña</label>
                <input class="jx-auth-input" type="password" id="password" name="password"
                       placeholder="••••••••" autocomplete="current-password" required>
            </div>
            <button type="submit" class="jx-btn jx-auth-btn" style="margin-top:4px;">Entrar</button>
        </form>

        <div class="mt-6 text-center" style="padding-top:16px; border-top:1px solid rgba(0,0,0,0.06);">
            <p class="text-[12px] text-muted dark:text-[#86868b]">
                Credencial de demo: <span class="font-mono text-ink dark:text-[#f5f5f7]">demo@jxmvc.dev</span> /
                <span class="font-mono text-ink dark:text-[#f5f5f7]">jxmvc123</span>
            </p>
        </div>
    </div>

    <p class="text-center mt-6">
        <a class="text-[13px] text-apple hover:underline" href="${pageContext.request.contextPath}/">Volver a inicio</a>
    </p>
</section>

<%@ include file="/WEB-INF/views/shared/footer.jspf" %>
