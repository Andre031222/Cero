# Plan de publicación

De Cero salen **tres artículos**, y salen en orden porque cada uno depende del anterior.

## 1 · El framework y su método

**Estado: a una corrida de distancia.**

La tesis no son los números, es el **procedimiento**. Una aplicación web en Java parece atada al
contenedor de servlets y en realidad lo está solo a cinco interfaces (`HttpServletRequest`,
`HttpServletResponse`, `HttpSession`, `Cookie`, `Part`); escribirlas en casa deja el arranque en
**87 ms** —4,4× por debajo del segundo de nueve contendientes— y la cuenta de dependencias en cero.

Lo publicable es cómo se verificó que quitar el contenedor no perdía nada por el camino:

- **Buscar el comportamiento sin cubrir** en vez de multiplicar aserciones. El método encontró
  **dos fallos reales**: una redirección abierta y una carrera que hacía al pool de conexiones
  pasarse de su tope.
- **Escribir vectores de conformidad del RFC 9112.** Los 23 casos destaparon **cuatro
  incumplimientos**: no se aceptaba *absolute-form* ni *asterisk-form*, se tragaba `chunked` fuera
  de la última posición y se admitían bytes nulos en valores de cabecera.
- **Desplegar de verdad.** Solo detrás de un proxy aparecieron otros dos: la cookie de sesión sin
  `Secure` porque la aplicación no sabía que el TLS lo terminaba nginx, y un guion de despliegue
  que al reescribir el vhost borraba el bloque HTTPS de certbot.
- **Buscar la salida sin recorrer, no la línea sin cubrir.** Los dos fallos más graves del
  proyecto son el mismo patrón: la cookie de sesión no viajaba en **ninguna** respuesta HTTP/2, y
  la validación de `content-length` desaparecía si la respuesta le ganaba la carrera al cuerpo.
  Ninguna de las 432 pruebas del módulo los veía porque todas entraban por el mismo camino. La
  cobertura de líneas no distingue eso; contar caminos de salida sí.
- **Medir con un método que se pueda invalidar.** La columna de memoria comparaba, sin que nadie
  lo notara durante dos meses, lo que el recolector decidía tomar y no lo que el framework
  necesita: sin `-Xmx`, quien asigna más llena su montón y quien asigna poco no. Con el tope
  igualado, Cero pasa de ser **el mejor** de la tabla a ser **el peor**. Un banco que puede
  invertir su conclusión al fijar una variable es un banco que hay que publicar con la variable
  fijada y dicha.

Eso es un procedimiento reproducible para migrar frameworks, no una anécdota.

**Lo único que bloquea:** los números están medidos en Docker Desktop sobre macOS. La comparación
entre contendientes es justa —condiciones idénticas, misma corrida, nueve frameworks, 135
mediciones sin un error— pero **las cifras absolutas no son citables**. Hay que repetir la corrida
en Linux sin virtualizar, con aislamiento de núcleos. Es una tarde de trabajo.

Y hay algo que la corrida en bare-metal tiene que resolver además de los absolutos: con este
montaje —cliente de carga en el mismo equipo que el servidor— **las peticiones por segundo no
distinguen**. Los seis primeros caen dentro del 2 % unos de otros mientras el abanico de cada uno
entre sus propias cinco repeticiones llega al 12,5 %. O el cliente deja de ser el cuello de
botella, o esa columna no sostiene ninguna afirmación y el artículo se apoya solo en arranque,
memoria y tamaño.

## 2 · El contrato poliglota

**Estado: fase 3, sin empezar a propósito.**

El más ambicioso. Un contrato de framework definido de forma neutral —modelo de rutas, forma del
request/response, ciclo del pipeline, contrato de middleware, inyección, configuración, formato de
errores— con un banco de conformidad que cualquier implementación debe pasar, e implementado en
**Java, Rust y C++**.

La pregunta de investigación es real y no retórica: **¿qué supuestos mete un lenguaje en un diseño
sin que su autor se dé cuenta?** Java resuelve el registro de rutas con reflexión y anotaciones en
tiempo de ejecución. Rust no tiene eso. Escribir la segunda implementación es lo que descubre la
contaminación; escribir solo la primera nunca lo haría.

Rust va antes que C++ por eso mismo: es el que más presión pone sobre el diseño, sin recolector de
basura, sin reflexión y con *ownership*.

**Nota de diseño ya recogida:** el trabajo de quitar la reflexión del camino caliente —resolver la
vinculación de argumentos al registrar la ruta en lugar de en cada petición— es el primer paso
hacia esto, y salió de optimizar rendimiento, no de planificar la fase 3.

## 3 · La experiencia en producción

**Estado: necesita tiempo, no trabajo.**

«Esto lleva N meses sirviendo tráfico real, y esto es lo que se rompió.» El menos glamuroso y
probablemente el más citado, porque casi nadie publica el postmortem de su propio framework.

Empieza a contar desde el primer despliegue (2 de agosto de 2026), con métricas por ruta y log de
acceso encendidos desde el primer día.

---

## Orden y dependencias

```
1 · framework y método ──► 2 · contrato poliglota
        │
        └─► 3 · producción (empieza a contar ya, se escribe en meses)
```

El 1 espera una corrida de benchmark en bare-metal. El 2 espera al 1. El 3 corre en paralelo
desde ya, pero solo se puede escribir cuando haya meses de tráfico detrás.
