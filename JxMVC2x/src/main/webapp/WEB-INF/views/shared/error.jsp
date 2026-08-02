<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"
%><%@ taglib prefix="jx" uri="http://jxmvc/tags"
%><%-- Reafirmar el status HTTP (el forward puede resetearlo a 200) --%><%
    Object code = request.getAttribute("jx_error_code");
    if (code instanceof Integer) response.setStatus((Integer) code);
%><!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${jx_error_code} · Lux</title>
    <style>
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
        :root {
            --bg: #fafafa;
            --fg: #0f172a;
            --muted: #94a3b8;
            --border: #e2e8f0;
            --mono: 'Menlo', 'Consolas', monospace;
            --sans: 'Inter', 'Segoe UI', system-ui, sans-serif;
        }
        @media (prefers-color-scheme: dark) {
            :root {
                --bg: #0a0f1a;
                --fg: #f1f5f9;
                --muted: #64748b;
                --border: #1e293b;
            }
        }
        body {
            font-family: var(--sans);
            background: var(--bg);
            color: var(--fg);
            min-height: 100svh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 1.5rem;
        }
        .card {
            border: 1px solid var(--border);
            border-radius: 1rem;
            padding: 2.5rem 2rem;
            width: 100%;
            max-width: 22rem;
            text-align: center;
        }
        .code {
            font-family: var(--mono);
            font-size: 3rem;
            font-weight: 600;
            letter-spacing: -0.03em;
            line-height: 1;
        }
        .label {
            font-size: 0.65rem;
            letter-spacing: 0.25em;
            text-transform: uppercase;
            color: var(--muted);
            margin-top: 0.75rem;
        }
        .msg {
            font-size: 0.8rem;
            color: var(--muted);
            margin-top: 0.5rem;
        }
        .divider {
            height: 1px;
            background: var(--border);
            margin: 1.5rem 0;
        }
        .back {
            font-size: 0.75rem;
            color: var(--muted);
            text-decoration: none;
            letter-spacing: 0.05em;
        }
        .back:hover { color: var(--fg); }
    </style>
</head>
<body>
    <div class="card">
        <p class="code">${jx_error_code}</p>
        <p class="label">
            ${ jx_error_code == 404 ? 'No encontrado' :
               jx_error_code == 405 ? 'Metodo no permitido' :
               jx_error_code == 403 ? 'Acceso denegado' :
               jx_error_code == 401 ? 'No autorizado' :
               jx_error_code == 429 ? 'Demasiadas peticiones' :
               'Error interno' }
        </p>
        <p class="msg">${jx:esc(jx_error_message)}</p>
        <div class="divider"></div>
        <a class="back" href="${pageContext.request.contextPath}/">volver al inicio</a>
    </div>
</body>
</html>
