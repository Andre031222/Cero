package jxmvc.controllers;

import jxmvc.core.ActionResult;
import jxmvc.core.JxMapping.*;
import jxmvc.service.ProjectZipBuilder;

/**
 * Genera y descarga un proyecto Lux / JxMVC personalizado como ZIP.
 * Accesible desde /home/downloads.
 */
@JxControllerMapping("generate")
public class GeneratorController extends BaseController {

    @JxPostMapping("download")
    public ActionResult download() throws Exception {
        String groupId    = clean(model.param("groupId"),    "com.example");
        String artifactId = clean(model.param("artifactId"), "mi-app");
        String appName    = sanitizeAppName(model.param("appName"), artifactId);
        String db         = validDb(model.param("db"));

        // Paquete base: groupId + artifactId limpio (sin guiones ni caracteres especiales)
        String pkgSuffix = artifactId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        String pkg       = validPackage(groupId.replaceAll("[^a-zA-Z0-9.]", "").toLowerCase()
                         + (pkgSuffix.isEmpty() ? "" : "." + pkgSuffix));

        byte[] zip = ProjectZipBuilder.build(groupId, artifactId, pkg, db, appName);
        view.raw(zip, "application/zip", artifactId + "-lux-starter.zip");
        return null;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String cleaned = value.trim().toLowerCase().replaceAll("[^a-zA-Z0-9.\\-]", "");
        return cleaned.isBlank() ? fallback : cleaned;
    }

    /** Lista blanca de nombre visible: letras, números, espacio y {@code . _ -}, máx 60. */
    private static String sanitizeAppName(String value, String fallback) {
        if (value == null) return fallback;
        String cleaned = value.trim().replaceAll("[^\\p{L}\\p{N} ._-]", "").trim();
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60).trim();
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static String validDb(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase()) {
            case "postgresql", "mysql", "sqlserver" -> value.trim().toLowerCase();
            default -> "none";
        };
    }

    /** Garantiza un nombre de paquete Java válido: segmentos alfanuméricos que empiezan por letra. */
    private static String validPackage(String pkg) {
        if (pkg == null) return "com.example.app";
        StringBuilder sb = new StringBuilder();
        for (String part : pkg.split("\\.")) {
            String seg = part.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (seg.isEmpty()) continue;
            if (!Character.isLetter(seg.charAt(0))) seg = "p" + seg;
            if (sb.length() > 0) sb.append('.');
            sb.append(seg);
        }
        return sb.length() == 0 ? "com.example.app" : sb.toString();
    }
}
