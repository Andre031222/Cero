# Migrar una aplicación a Cero 0.6.0

Esta guía lleva una aplicación escrita contra una versión anterior a la **0.6.0**, que es la
primera que se publica. Cambian las coordenadas de Maven, el paquete, las claves de configuración
y algunos nombres que se ven desde fuera.

`./cero migrar` hace casi todo. Lo que no puede hacer —y es donde están los tres tropiezos que ya
han costado tiempo— está al final, en [Renombradas que fallan en
silencio](#renombradas-que-fallan-en-silencio).

---

## Lo primero: no hay prisa

**Las aplicaciones que no migres siguen funcionando.** Los artefactos de Maven son inmutables: los
que ya están en `~/.m2` y dentro de cada jar desplegado no cambian porque salga una versión nueva.

Puedes migrar una, dejar las otras cinco como están meses, y no pasa nada. Las etiquetas viejas
del repositorio siguen ahí: si alguna vez hay que recompilar una aplicación antigua en una máquina
limpia, `./cero install` desde su etiqueta regenera los artefactos de entonces.

Migra de una en una. Con producción de por medio, esa es la forma de que un problema afecte a una
sola aplicación.

---

## Antes de tocar nada

### 0 · Comprueba que tienes Java 25

**Cero 0.6.0 pide JDK 25.** Las versiones anteriores se conformaban con 21, así que este es el
único cambio de la versión capaz de impedir que arranque una aplicación que hoy funciona — y no
avisa antes de tiempo: la aplicación compila en tu máquina y falla en el servidor que se quedó
en 21.

```bash
java -version      # tiene que decir 25 o más
```

Va antes que nada porque afecta a dos sitios que no son el mismo: la máquina donde compilas y la
que ejecuta. Si el servidor arranca con un JRE viejo, súbelo **antes** de desplegar el jar nuevo.

### 1 · Instala Cero 0.6.0

**Desde el 5 de septiembre de 2026 este paso te lo puedes saltar.** La 0.6.0 está en Maven
Central, así que Maven se baja los artefactos solo al compilar la aplicación: no hay que clonar
ni compilar el framework para migrar.

Sigue haciendo falta si quieres la orden `cero` a mano —para `cero new` o `cero fatjar`— o si
trabajas sin conexión:

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

### 3 · Mira si alguna tabla se llama distinto ahora

**Solo si usas `Migrations` o `JdbcSessions` con el nombre de tabla por defecto. Compruébalo
antes de tocar la base de datos**, porque una aplicación que le pasa su propio nombre no tiene
nada que hacer aquí, y renombrarle la tabla la rompería.

La comprobación son dos órdenes en el código de la aplicación:

```bash
cd ../mi-app
grep -rn 'Migrations\.\|JdbcSessions\.' src/main/java
```

Lee lo que salga:

- **`Migrations`** anota lo aplicado en `cero_migraciones` **salvo** que se le diga otra cosa con
  `.table("…")`. Si en tu código aparece `.table(` con un nombre propio, esa tabla es tuya y el
  framework no la toca: **no la renombres**.
- **`JdbcSessions`** no tiene nombre por defecto: `JdbcSessions.of("sesiones")` recibe siempre el
  nombre por parámetro. Solo te afecta si el nombre que le pasas es el que traía el ejemplo de la
  documentación antigua. Si es tuyo, **no lo toques**.

Si y solo si estás en el caso por defecto, la tabla de migraciones hay que renombrarla **antes de
arrancar la aplicación nueva**:

```sql
ALTER TABLE <la-que-ya-existe> RENAME TO cero_migraciones;
```

Mira cómo se llama la que tienes antes de escribir la orden —`\dt` en PostgreSQL, `SHOW TABLES` en
MySQL—. Si la aplicación arranca sin encontrarla, **da por hecho que no se aplicó ninguna
migración y las vuelve a correr todas**. Eso no falla al compilar: falla contra tu base de datos.

---

## Migrar

```bash
cd <el-repositorio-de-cero>

./cero migrar ../mi-app --probar    # enseña qué cambiaría, sin tocar nada
./cero migrar ../mi-app             # lo aplica
```

### Qué cambia el guion

Todo lo que lleva el espacio de nombres anterior pasa al actual:

| | Queda como |
|---|---|
| Paquetes | `import cero.core.Route` |
| Clase de arranque | `Cero.run(8080, …)` |
| Servlet | `CeroServlet` |
| groupId | `dev.ginit.cero` |
| Artefactos | `cero-core`, `cero-data`… |
| Versión | `0.6.0` |
| Propiedad del pom | `${cero.version}` |
| Claves de config | `cero.oauth.google.id` |
| Variables de entorno | `CERO_SERVER_PORT` |

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

## Renombradas que fallan en silencio

Tres, y las tres han tropezado a alguien de verdad. **Solo una avisa.**

| Qué | Cuándo se nota | Qué pasa si no lo haces |
|---|---|---|
| Tabla de migraciones | al arrancar | **revienta el arranque**, o vuelve a aplicar las migraciones desde cero |
| Prefijo de las variables de entorno | en producción, días después | se ignoran y entra el valor por omisión |
| Nombre de la cookie de sesión | al desplegar | la cookie del navegador se ignora y empieza sesión nueva |

La primera es la benigna, precisamente porque da la cara. Las otras dos no dan error en ningún
sitio: la configuración simplemente **no se aplica** y el framework sigue con su valor por
omisión, que es exactamente lo que uno creía haber cambiado.

### El prefijo de las variables de entorno

El guion cambia los `.properties` que están **dentro** de la aplicación. Lo que vive en el
servidor —un `application.properties` aparte, las variables del `systemd` o del `docker-compose`,
el `env:` del despliegue— no lo ve nadie.

Una variable con el prefijo antiguo no da error: no lleva el prefijo que Cero busca, así que ni
se mira. El arranque sale limpio y la aplicación corre con los valores por defecto.

En producción eso significó dos cosas a la vez, las dos silenciosas:

- **La cookie de sesión salió sin `Secure`.** La aplicación no se enteró de que el TLS lo terminaba
  el proxy, así que se creyó en claro y no marcó la cookie.
- **El limitador de peticiones contó a todo el mundo junto.** Sin el proxy declarado como de
  confianza, `X-Forwarded-For` se ignora y todas las peticiones llegan con la misma dirección: la
  del proxy. El primero que llegue al tope bloquea el sitio entero.

La orden que lo encuentra, en el servidor y no en el repositorio:

```bash
grep -rn 'CERO_' /etc/systemd/system/mi-app.service /opt/apps/mi-app/
```

Y mientras estás ahí, un aviso que no tiene que ver con la migración pero se confunde con ella:
**las variables de entorno solo alcanzan las claves de una palabra**. `CERO_SERVER_PORT` es
`server.port` y funciona; una clave en camelCase como `server.behindProxy` no se puede escribir
desde el entorno, porque la traducción pasa el nombre a minúsculas y deja de casar. Esas van en el
`application.properties` o como propiedad de la JVM:

```bash
java -Dcero.server.behindProxy=true -jar mi-app.jar
```

Desde la 0.8.0 el arranque avisa de las variables `CERO_*` que no han acabado aplicándose a
ninguna clave, y dice cuál era la correcta cuando la diferencia es solo de mayúsculas.

### La cookie de sesión

La cookie se llama `CEROSESSION`. El navegador de cada usuario sigue mandando la que tenía, que la
aplicación nueva ya no reconoce: la ignora y abre sesión nueva. No hay error, ni en el servidor ni
en el navegador.

**Consecuencia:** al desplegar, **todo el mundo que estuviera dentro tiene que volver a entrar.**
No se pierde nada, pero conviene desplegar cuando no moleste y avisar si es una aplicación con
gente trabajando.

### Las métricas, que no fallan pero se quedan vacías

No es una renombrada silenciosa en el mismo sentido —la ruta vieja da 404— pero el efecto se
parece, así que va aquí. Los nombres actuales son:

| Métrica | Nombre |
|---|---|
| Peticiones | `cero_requests_total` |
| Errores | `cero_request_errors_total` |
| Duración | `cero_request_duration_ms` |
| Tiempo en marcha | `cero_uptime_ms` |
| Endpoints | `/cero/metrics` y `/cero/metrics/prometheus` |

El scrapeo de Prometheus apunta a una ruta que ya no existe, y los paneles de Grafana consultan
métricas que dejaron de emitirse. Los paneles no dan error: se quedan vacíos, que es peor porque
parece que la aplicación no recibe tráfico. Hay que actualizar el `prometheus.yml` y las consultas
de los paneles.

---

## Por qué no hay capa de compatibilidad

Era la opción evidente: que Cero entendiera también los nombres antiguos durante una temporada. Se
descartó a propósito.

Solo seis aplicaciones usan el framework y son todas nuestras. Aceptar dos juegos de nombres
significaría arrastrar código muerto **para siempre** en un proyecto cuyo argumento es no tener
lastre — y en la práctica nadie retira nunca esas capas. Convertir seis aplicaciones una vez sale
más barato que cargar con la deuda indefinidamente.

---

## Si algo sale mal

```bash
git checkout .           # deshace la migración entera
```

Y en el `pom.xml`, volver a la versión anterior. Los artefactos viejos siguen en `~/.m2` y la
aplicación compila como antes, porque nunca dejaron de existir.

Si ya habías renombrado la tabla de migraciones, deshazlo con el nombre que tenía:

```sql
ALTER TABLE cero_migraciones RENAME TO <como-se-llamaba>;
```

---

## Repaso rápido

- [ ] `java -version` dice 25 o más, en la máquina que compila **y** en la que ejecuta
- [ ] El árbol de git de la aplicación, limpio
- [ ] `grep -rn 'Migrations\.\|JdbcSessions\.' src/main/java` — ¿nombre propio o por defecto?
- [ ] Si es por defecto: `ALTER TABLE …` antes de arrancar
- [ ] `./cero migrar ../mi-app --probar`
- [ ] `./cero migrar ../mi-app`
- [ ] `git diff` y `mvn test`
- [ ] Configuración del servidor: `systemd`, `docker-compose`, `.properties` de producción
- [ ] Prometheus y los paneles de Grafana
- [ ] Avisar de que las sesiones se cierran al desplegar
