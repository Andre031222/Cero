# Plan de publicación

Del proyecto salen **seis artículos**. Dos se pueden escribir ya; los otros cuatro esperan un
experimento, una corrida o tiempo, y aquí queda dicho cuál.

Los manuscritos viven en [`papers/`](../papers/), uno por carpeta, con la plantilla oficial de
Elsevier generada desde `elsarticle.dtx` de CTAN. Van **en inglés**: las revistas lo exigen. El
repositorio sigue en castellano a propósito.

| # | Artículo | Revista prevista | Estado |
|---|---|---|---|
| 1 | Arquitectura y método de verificación | Journal of Systems and Software | **escribible ya** |
| 2 | Seguridad y conformidad del servidor HTTP | Computer Standards & Interfaces | **escribible ya** |
| 3 | Rendimiento comparado | Information and Software Technology | espera bare-metal |
| 4 | Concurrencia con hilos virtuales | Journal of Systems Architecture | falta el experimento |
| 5 | Fiabilidad a largo plazo | Empirical Software Engineering | espera meses |
| 6 | Contrato poliglota | por decidir | fase 3 |

Todas son híbridas de Elsevier o Springer, y la intención es la **ruta de suscripción sin APC**.
Cuartil y política de tasas cambian cada año: hay que reconfirmarlos al enviar, no darlos por
buenos desde aquí.

---

## 1 · Arquitectura y método

**Revista:** Journal of Systems and Software. **Bloqueo:** ninguno.

La tesis no son los números, es el **procedimiento**. Una aplicación web en Java parece atada al
contenedor de servlets y en realidad lo está solo a cinco interfaces (`HttpServletRequest`,
`HttpServletResponse`, `HttpSession`, `Cookie`, `Part`); escribirlas en casa deja el arranque en
**87 ms** —4,4× por debajo del segundo de nueve contendientes— y la cuenta de dependencias en cero.

Lo publicable es cómo se verificó que quitar el contenedor no perdía nada:

- **Buscar la salida sin recorrer, no la línea sin cubrir.** Los dos fallos más graves son el mismo
  patrón: la cookie de sesión no viajaba en **ninguna** respuesta HTTP/2, y la validación de
  `content-length` desaparecía si la respuesta le ganaba la carrera al cuerpo. Ninguna de las 434
  comprobaciones del módulo los veía porque todas entraban por el mismo camino. La cobertura de
  líneas no distingue eso; contar caminos de salida sí.
- **Escribir vectores de conformidad.** 23 casos sobre 13 apartados del RFC 9112 y el 9110
  destaparon **cuatro incumplimientos**.
- **Desplegar de verdad.** Solo detrás de un proxy aparecieron otros dos: la cookie sin `Secure`
  porque la aplicación no sabía que el TLS lo terminaba nginx, y un guion que al reescribir el
  vhost borraba el bloque HTTPS de certbot.
- **Medir con un método que se pueda invalidar.** La columna de memoria comparó durante dos meses
  lo que decidía el recolector, no lo que el framework necesita. Con el tope de montón igualado,
  Cero pasa de **el mejor** de la tabla a **el peor**. Un banco que invierte su conclusión al fijar
  una variable hay que publicarlo con la variable fijada y dicha.

## 2 · Seguridad y conformidad del servidor HTTP

**Revista:** Computer Standards & Interfaces. **Bloqueo:** ninguno.

Es el segundo y no el tercero porque es el que más evidencia tiene reunida, y porque CSI es
exactamente el sitio de un trabajo sobre conformidad con estándares.

- **23 vectores propios** sobre 13 apartados del RFC 9112 y el 9110, en bytes crudos, cada uno
  citando la cláusula que exige. No existe suite libre de conformidad HTTP/1.1 para servidores.
- **h2spec** para HTTP/2: 146 pruebas, 144 pasan, 1 se omite, 1 conocida y explicada.
- **Once hallazgos de una auditoría externa**, dos explotables desde fuera sin credenciales.
- **Dos fallos de la familia del contrabando de peticiones**, encontrados y cerrados con prueba
  propia: la cookie de sesión ausente sobre HTTP/2 y el `content-length` sin validar una vez
  respondido.
- 22 comprobaciones con clientes hostiles y 6 de entradas malformadas.

La pregunta de investigación: **¿qué encuentra una suite escrita desde el texto del RFC que no
encuentra una suite escrita desde el código?** Aquí, cuatro incumplimientos en una implementación
que ya pasaba sus propias pruebas.

## 3 · Rendimiento comparado

**Revista:** Information and Software Technology. **Bloqueo:** la corrida en bare-metal.

Nueve frameworks, 135 mediciones, cero errores y cero respuestas no-2xx. Pero:

- Se midió en Docker Desktop, dentro de una VM. Los **relativos** valen; los **absolutos** no.
- Con el cliente de carga en el mismo equipo, **las peticiones por segundo no distinguen**: los
  seis primeros caen dentro del 2 % unos de otros mientras el abanico de cada uno entre sus cinco
  repeticiones llega al 12,5 %.

O el cliente deja de ser el cuello de botella, o el artículo se apoya solo en arranque, memoria y
tamaño de imagen. Hay guion preparado en [`benchmarks/RUN-ON-LINUX.md`](../benchmarks/RUN-ON-LINUX.md).

## 4 · Concurrencia con hilos virtuales

**Revista:** Journal of Systems Architecture. **Bloqueo:** el experimento no existe.

Hoy hay una afirmación de diseño —un hilo virtual por conexión— y dos evidencias de que aguanta:
media hora de carga continua sin fuga y mil peticiones simultáneas. **Eso es una descripción, no
un resultado.**

Para que haya artículo hace falta medir contra las alternativas reales —hilos de plataforma con
pool, y bucle de eventos— variando la concurrencia y el perfil de bloqueo, que es donde los hilos
virtuales deberían ganar o perder. Es el más flojo de los seis y el que más trabajo nuevo pide.

## 5 · Fiabilidad a largo plazo

**Revista:** Empirical Software Engineering. **Bloqueo:** tiempo, no trabajo.

«Esto lleva N meses sirviendo tráfico real, y esto es lo que se rompió.» El menos vistoso y
probablemente el más citado, porque casi nadie publica el postmortem de su propio framework.
Cuenta desde el **2 de agosto de 2026**, con métricas por ruta y log de acceso desde el primer día.

## 6 · Contrato poliglota

**Bloqueo:** fase 3, sin empezar a propósito.

El contrato definido de forma neutral —rutas, request/response, pipeline, middleware, inyección,
configuración, errores— con un banco de conformidad que cualquier implementación debe pasar, e
implementado en **Java, Rust y C++**.

La pregunta es real: **¿qué supuestos mete un lenguaje en un diseño sin que su autor se dé cuenta?**
Java resuelve el registro de rutas con reflexión y anotaciones en tiempo de ejecución; Rust no tiene
eso. Escribir la segunda implementación es lo que descubre la contaminación. Rust va antes que C++
porque es el que más presión pone sobre el diseño: sin recolector, sin reflexión y con *ownership*.

El árbol del repositorio ya reserva `spec/`, `rust/` y `cpp/`. **No hace falta renombrar nada:**
`java/` no pasa a llamarse «framework-java», porque el nombre del framework es Cero en los tres y
lo que cambia es la implementación, no el producto.

---

## Orden y dependencias

```
1 · arquitectura ──┬──► 3 · rendimiento ──► 6 · contrato poliglota
                   │
2 · seguridad ─────┘
                   
5 · fiabilidad ·············· corre en paralelo desde el 2 de agosto
4 · hilos virtuales ········· independiente, en cuanto haya experimento
```

El 1 y el 2 no dependen de nada y se escriben ahora. El 3 espera una tarde de bare-metal. El 6
espera al 1 y al 3 porque necesita el contrato estabilizado y el banco de conformidad. El 4 y el 5
van por su cuenta.

## Regla

**Bare-metal y resultados reproducibles antes de escribir nada que dependa de ellos.** Ninguna
cifra entra en un manuscrito sin que se pueda rehacer desde `benchmarks/` o desde la batería.
Donde falte el dato va un hueco marcado en rojo, no una estimación con aire de medición.
