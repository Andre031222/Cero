# Constancia de publicación en GitHub

Documento de control interno. Registra cómo y con qué contenido se publicó este
proyecto en GitHub. No contiene claves ni secretos.

## Datos del repositorio

| Campo | Valor |
|-------|-------|
| Nombre del repositorio | 19.Soft_JXMVC |
| Titular | R. Andre Vilca Solorzano |
| Visibilidad | Privado |
| URL | https://github.com/Andre031222/19.Soft_JXMVC |
| Versión inicial publicada | v3.4.0 |
| Fecha de publicación | 2026-07-29 |
| Stack | Java 17 + Jakarta EE (framework MVC) |

## Qué se subió

- Todo el código fuente de la aplicación.
- Documentación del proyecto (carpeta docs/ y archivos .md).
- Archivos de configuración de ejemplo (.env.example y equivalentes).
- LICENSE (licencia propietaria) y README.md.

## Qué NO se subió (excluido por .gitignore)

- Secretos y configuración local: .env, appsettings.local.json, client_secret*.json, *.key, *.pem, *.pfx.
- Dependencias y artefactos de compilación: node_modules/, vendor/, target/, bin/, obj/, dist/, build/, venv/.
- Configuración de herramientas de IA/asistentes: .claude/, .agents/, CLAUDE.md.
- Datos generados en tiempo de ejecución y logs.

## Historial

El repositorio se publicó con un historial de Git nuevo y limpio. La autoría de
los commits corresponde únicamente a R. Andre Vilca Solorzano. No hay contribuidores
automáticos ni referencias a herramientas de IA.

## Cómo clonar en otra laptop

```bash
git clone https://github.com/Andre031222/19.Soft_JXMVC.git
cd 19.Soft_JXMVC
```

Al ser privado, se requiere haber iniciado sesión con la cuenta Andre031222
(por ejemplo con `gh auth login` o un token de acceso personal). Tras clonar,
copiar los archivos .env.example a .env y completar las credenciales locales
antes de ejecutar.

## Titularidad y registro

Obra de software propietario. Titular de los derechos: R. Andre Vilca Solorzano
(persona natural). Marca del proyecto: AUR Software. Registro en trámite ante la
Dirección de Derecho de Autor (DDA) del INDECOPI, Perú.
