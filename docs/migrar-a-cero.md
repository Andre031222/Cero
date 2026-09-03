# Migrar una aplicación de LuxCore a Cero 0.4.0

LuxCore pasa a llamarse **Cero**. Cambian los paquetes, las coordenadas de Maven, las claves de
configuración y algunos nombres que se ven desde fuera.

Esta guía es para llevar una aplicación de 0.2.x o 0.3.x a 0.4.0.

---

## Lo primero: no hay prisa

**Las aplicaciones que no migres siguen funcionando.** Los artefactos de Maven son inmutables:
`lux:lux-core:0.3.0` sigue en `~/.m2` y dentro de cada jar desplegado. Renombrar el framework no
entra ahí a cambiar nada.

Puedes migrar una, dejar las otras cinco como están meses, y no pasa nada. La etiqueta `v0.3.0`
del repositorio sigue ahí: si alguna vez hay que recompilar una app vieja en una máquina limpia,
`./lux install` desde esa etiqueta regenera los artefactos antiguos.

Migra de una en una. Con producción de por medio, esa es la forma de que un problema afecte a una
sola aplicación.

---

## Antes de tocar nada

### 1 · Instala Cero 0.4.0

```bash
cd <el-repositorio-de-cero>
./cero install          # compila, prueba e instala en ~/.m2
```

### 2 · Deja limpio el árbol de git de la aplicación

```bash
cd ../mi-app
git status               # no debe quedar nada sin guardar
```

**El guion se niega a correr si hay cambios sin guardar**, y es a propósito: así el propio git es
la copia de seguridad. `git diff` enseña todo lo que tocó y `git checkout .` lo deshace entero.
No hace falta andar copiando carpetas.

### 3 · Renombra la tabla de migraciones — **esto va antes de arrancar la aplicación**

Si tu aplicación usa `Migrations` de `cero-data`, para y lee.

El framework anota en una tabla qué migraciones ya se aplicaron. Esa tabla se llamaba
`lux_migraciones` y ahora se llama `cero_migraciones`. Si arrancas sin renombrarla, **Cero no la
encuentra, da por hecho que no se aplicó ninguna migración y las vuelve a correr todas.**

Eso no falla al compilar. Falla contra tu base de datos.

```sql
ALTER TABLE lux_migraciones RENAME TO cero_migraciones;
```

Lo mismo con la tabla de sesiones, si usas `JdbcSessions` y le pasabas el nombre por defecto:

```sql
ALTER TABLE lux_sesiones RENAME TO cero_sesiones;
```

Si le pasabas un nombre propio —`JdbcSessions.of("mis_sesiones")`— no tienes que hacer nada.

---

## Migrar

```bash
cd <el-repositorio-de-cero>

./cero migrar ../mi-app --probar    # enseña qué cambiaría, sin tocar nada
./cero migrar ../mi-app             # lo aplica
```

### Qué cambia el guion

| | Antes | Después |
|---|---|---|
| Paquetes | `import lux.core.Route` | `import cero.core.Route` |
| Clase de arranque | `Lux.run(8080, …)` | `Cero.run(8080, …)` |
| Servlet | `LuxServlet` | `CeroServlet` |
| groupId | `lux` | `dev.ginit.cero` |
| Artefactos | `lux-core`, `lux-data`… | `cero-core`, `cero-data`… |
| Versión | `0.2.x` / `0.3.x` | `0.4.0` |
| Propiedad del pom | `${lux.version}` | `${cero.version}` |
| Claves de config | `lux.oauth.google.id` | `cero.oauth.google.id` |
| Variables de entorno | `LUX_SERVER_PORT` | `CERO_SERVER_PORT` |

Recorre `.java`, `pom.xml`, `.properties`, `.yml`, `.yaml`, `.env` y `.conf`. Salta `target/`,
`build/`, `.git/` y `node_modules/`.

### Comprobar

```bash
cd ../mi-app
git diff                 # revisa lo que cambió
mvn test                 # y que siga pasando
```

Si algo no cuadra:

```bash
git checkout .           # se deshace entero, como si no hubiera pasado
```

---

## Lo que el guion **no** puede hacer por ti

Tres cosas. Ninguna da error al compilar, y las tres se notan después de desplegar.

### La cookie de sesión cambió de nombre

`LUXSESSION` pasa a ser `CEROSESSION`. El navegador de cada usuario sigue mandando la vieja, que
la aplicación nueva ya no reconoce.

**Consecuencia:** al desplegar, **todo el mundo que estuviera dentro tiene que volver a entrar.**
No se pierde nada, pero conviene desplegar cuando no moleste y avisar si es una aplicación con
gente trabajando.

### Las métricas cambiaron de nombre

| Antes | Después |
|---|---|
| `lux_requests_total` | `cero_requests_total` |
| `lux_request_errors_total` | `cero_request_errors_total` |
| `lux_request_duration_ms` | `cero_request_duration_ms` |
| `lux_uptime_ms` | `cero_uptime_ms` |
| `/lux/metrics` | `/cero/metrics` |
| `/lux/metrics/prometheus` | `/cero/metrics/prometheus` |

**Consecuencia:** el scrapeo de Prometheus apunta a una ruta que ya no existe, y los paneles de
Grafana consultan métricas que dejaron de emitirse. Los paneles no dan error: se quedan vacíos, que
es peor porque parece que la aplicación no recibe tráfico.

Hay que actualizar el `prometheus.yml` y las consultas de los paneles.

### La configuración desplegada, no solo la del repositorio

El guion cambia los `.properties` que estén **dentro** de la aplicación. Si en el servidor tienes
un `application.properties` aparte, o variables `LUX_*` en el `systemd` o en el `docker-compose`,
esas hay que cambiarlas a mano.

```bash
grep -rn 'LUX_\|lux\.' /etc/systemd/system/mi-app.service /opt/apps/mi-app/
```

---

## Por qué no hay capa de compatibilidad

Era la opción evidente: que Cero entendiera los dos nombres durante una temporada. Se descartó a
propósito.

Solo seis aplicaciones usan el framework y son todas nuestras. Aceptar los dos prefijos significaría
arrastrar código muerto **para siempre** en un proyecto cuyo argumento es no tener lastre — y en la
práctica nadie retira nunca esas capas. Convertir seis aplicaciones una vez sale más barato que
cargar con la deuda indefinidamente.

---

## Si algo sale mal

```bash
git checkout .           # deshace la migración entera
```

Y en el `pom.xml`, volver a la versión anterior. Los artefactos viejos siguen en `~/.m2` y la
aplicación compila como antes, porque nunca dejaron de existir.

Si ya habías renombrado las tablas y quieres volver atrás del todo:

```sql
ALTER TABLE cero_migraciones RENAME TO lux_migraciones;
ALTER TABLE cero_sesiones RENAME TO lux_sesiones;
```

---

## Repaso rápido

- [ ] `./cero install` — Cero 0.4.0 en `~/.m2`
- [ ] El árbol de git de la aplicación, limpio
- [ ] `ALTER TABLE lux_migraciones RENAME TO cero_migraciones;`
- [ ] `ALTER TABLE lux_sesiones RENAME TO cero_sesiones;` (si aplica)
- [ ] `./cero migrar ../mi-app --probar`
- [ ] `./cero migrar ../mi-app`
- [ ] `git diff` y `mvn test`
- [ ] Configuración del servidor: `systemd`, `docker-compose`, `.properties` de producción
- [ ] Prometheus y los paneles de Grafana
- [ ] Avisar de que las sesiones se cierran al desplegar
