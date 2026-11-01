# Guiones y integración continua

Los guiones y el YAML de CI no llevan comentarios. Lo que explicaban está aquí.

## La orden `cero`

`cero` (macOS y Linux) y `cero.ps1` (Windows, llamado desde `cero.cmd`) ofrecen la misma lista de
órdenes: `new`, `run`, `test`, `package`, `fatjar`, `install`, `dist`, `clean`, `cache`, `status`,
`migraciones`, `migrar`. Solo necesitan Maven y un JDK 25. Las órdenes van en inglés; las de antes
(`correr`, `probar`, `instalar`…) siguen valiendo como alias y no se anuncian. El idioma de los
mensajes sale de `CERO_LANG` y, si no está, del entorno; sin traducción cae al castellano.

- **Java 25 y no 21.** El pom compila con `release 25`; dejar pasar un 21 solo cambia el mensaje
  por el de `javac`, que no dice qué hacer.
- **`dist`.** Arma el código fuente sin `target/` y sin la web, en `dist/`. Compila primero desde
  cero, porque la compilación incremental publicó una vez un paquete que no compilaba en una
  instalación limpia. Después desempaqueta lo armado y corre sus pruebas: se prueba lo que se
  entrega, no el repositorio. `COPYFILE_DISABLE` evita los `._archivo` del `tar` de macOS, y se usa
  `jar` en vez de `zip` porque el JDK ya está.
- **`migraciones --verificar`.** Aplica el lote entero sobre una base vacía y desechable. Usa las
  clases recién compiladas, no los jar de `target/`, donde conviven versiones viejas. El driver lo
  pone quien llama con `CERO_JDBC_JAR`; si no, se usa el H2 de `~/.m2`.
- **`migrar`.** Exige un árbol de git limpio: el propio git es la copia de seguridad (`git diff`
  enseña todo y `git checkout .` lo deshace). En PowerShell, `git` va dentro de un `try` porque
  desde la 7.4 un código distinto de cero en una orden nativa aborta el guion; que la aplicación no
  esté en git no es un error, solo significa que no hay red de seguridad.
- Python solo lo pide `migrar`.

## Integración continua

[`.github/workflows/pruebas.yml`](../.github/workflows/pruebas.yml) tiene un trabajo por cosa que
se juzga:

- **`comentarios`:** `herramientas/comentarios.py --verificar` debe dar cero.
- **`nucleo` (Java).** Corre en Linux con PostgreSQL y MySQL reales. Sin las bases, `MotorTests` se
  omite en silencio y la corrida miente por omisión, así que un paso falla si algún motor quedó sin
  alcanzar. La matriz tiene solo JDK 25 porque el `javac` de 21 no acepta un objetivo mayor que el
  suyo; queda la matriz por si hay que sostener más de una versión. Se usa `pipefail` porque el
  shell de Actions es `bash -e` y sin él un `mvn` en rojo quedaría tapado por el `tee`. Se hace
  `install` y no `test` porque los pasos siguientes piden los artefactos por módulo.
- **h2spec.** Es la suite de HTTP/2 escrita por otros: las pruebas propias comprueban lo que se nos
  ocurrió, esta lo que dice el RFC. La primera pasada encontró 22 fallos que las propias no veían.
  El caso 3.5.2 no aplica: manda `INVALID CONNECTION PREFACE`, que en un puerto compartido con
  HTTP/1.1 es un método inexistente y la respuesta correcta es 501, no un GOAWAY; asume un puerto
  dedicado a h2c. Se lee la línea de totales de h2spec y no se cuentan las «×», porque cada fallo
  sale dos veces.
- **Rust y C++.** Pasan por los mismos jueces que Java y en el mismo sitio. Entran en CI desde el
  primer hito: tenerlas verdes en el portátil de quien las escribe no es tenerlas verdes. Los 23
  vectores del banco son bytes sobre un socket y no saben en qué lenguaje está escrito quien
  responde.
- **Dependencias de prueba.** Las que se distribuyen son cero, pero las de prueba se compilan y
  ejecutan en CI; una vulnerable lo es en la máquina de quien compile. `dependency:list` resuelve
  cada módulo por separado, por eso se instala primero.
