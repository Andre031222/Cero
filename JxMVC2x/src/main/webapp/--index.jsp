<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@ page import="jakarta.servlet.*" %>
<%@ page import="jakarta.servlet.http.*" %>

<%
    Object code = request.getAttribute("jx_error_code");
    Object message = request.getAttribute("jx_error_message");

    if (code == null) {
    
        response.sendRedirect(request.getContextPath() + "/home/index");
        return;
    }
%>
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Error 404</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script>
        tailwind.config = {
            theme: {
                extend: {
                    colors: {
                        ink: '#0f172a',
                        mist: '#f8fafc',
                        accent: '#0ea5a6'
                    }
                }
            }
        }
    </script>
</head>
<body class="bg-mist text-ink min-h-screen flex items-center justify-center px-6">
    <div class="max-w-xl w-full bg-white border border-slate-200 rounded-3xl p-8 shadow-xl">
        <p class="text-xs uppercase tracking-[0.35em] text-slate-400">No encontrado</p>
        <h1 class="text-3xl font-semibold mt-3">404</h1>
        <p class="text-sm text-slate-600 mt-4">
            <%= message == null ? "Ruta no encontrada" : message.toString() %>
        </p>
        <a class="inline-flex items-center gap-2 text-sm font-semibold text-accent mt-6" href="<%= request.getContextPath() %>/home/index">
            Volver al inicio
        </a>
    </div>
</body>
</html>
