# Plan de publicación

De LuxCore salen **tres artículos**, y salen en orden porque cada uno depende del anterior.

## Lo ya publicado: el paper de JxMVC

`main.pdf` en la raíz del repositorio (sin versionar) es el artículo del **predecesor**, no de
LuxCore:

> *JxMVC: A Zero-Dependency, Security-by-Default MVC Framework for Jakarta EE — Design,
> Implementation Experience, and Empirical Evaluation*
> Software: Practice and Experience · Universidad Nacional del Altiplano, Puno, Perú

Su tesis: un framework MVC de 253 kB, sin dependencias en ejecución, que aplica sanitizado, rate
limiting, CSRF y límites de cuerpo **por construcción** en un pipeline fijo de quince etapas.
Comparado contra Spring Boot, Quarkus y Micronaut con un harness contenedorizado de un solo
comando, sobre **Linux bare-metal con aislamiento de núcleos**.

Ese último detalle importa: **la metodología de medición ya está escrita y validada**. El primer
paper de LuxCore solo tiene que repetirla.

---

## 1 · El framework y su método

**Estado: a una corrida de distancia.**

La tesis no son los números, es el **procedimiento**. Un framework que parecía casado con Tomcat
lo estaba solo con cinco interfaces (`HttpServletRequest`, `HttpServletResponse`, `HttpSession`,
`Cookie`, `Part`); separarlas cuesta 106 ms de arranque en vez de 698, con cero dependencias.

Lo publicable es cómo se verificó que la migración no perdió nada:

- **Comparar cobertura contra el suite heredado** en vez de portarlo. Portar 347 aserciones habría
  duplicado lo que ya existía; buscar el hueco encontró **dos fallos reales**: una redirección
  abierta y una carrera que hacía al pool de conexiones pasarse de su tope.
- **Escribir vectores de conformidad del RFC 9112.** Los 23 casos destaparon **cuatro
  incumplimientos**: no se aceptaba *absolute-form* ni *asterisk-form*, se tragaba `chunked` fuera
  de la última posición y se admitían bytes nulos en valores de cabecera.
- **Desplegar de verdad.** Solo detrás de un proxy aparecieron otros dos: la cookie de sesión sin
  `Secure` porque la aplicación no sabía que el TLS lo terminaba nginx, y un guion de despliegue
  que al reescribir el vhost borraba el bloque HTTPS de certbot.

Eso es un procedimiento reproducible para migrar frameworks, no una anécdota.

**Lo único que bloquea:** los números están medidos en Docker Desktop sobre macOS. La comparación
entre los seis contendientes es justa —condiciones idénticas, misma corrida— pero **las cifras
absolutas no son citables**. Hay que repetir la corrida en Linux sin virtualizar, con la misma
metodología del paper de JxMVC. Es una tarde de trabajo.

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

Empieza a contar desde el despliegue en `https://luxcore.ginit.dev` (2 de agosto de 2026), con
métricas por ruta y log de acceso encendidos desde el primer día.

---

## Orden y dependencias

```
paper de JxMVC (hecho)
        │
        └─► 1 · framework y método ──► 2 · contrato poliglota
                    │
                    └─► 3 · producción (empieza a contar ya, se escribe en meses)
```

El 1 espera una corrida de benchmark en bare-metal. El 2 espera al 1. El 3 corre en paralelo
desde ya, pero solo se puede escribir cuando haya meses de tráfico detrás.
