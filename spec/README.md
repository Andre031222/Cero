# spec — el contrato de Cero

**Estado: borrador 0.1, derivado de la implementación en Java.** Todavía no manda: hoy la
referencia sigue siendo `java/`. Este directorio existe para que deje de serlo.

Hasta ahora el README prometía este contrato y aquí no había nada. Un directorio vacío citado en
la portada es peor que una promesa sin escribir, porque parece que ya existe.

## Para qué es

Cero quiere ser el mismo framework en Java, Rust y C++. Eso solo funciona si «el mismo» está
escrito en algún sitio que no sea código de uno de los tres. Si la referencia es la
implementación en Java, las otras dos no son implementaciones: son traducciones, y heredan hasta
sus accidentes.

El contrato describe **lo que se observa desde fuera**: qué entra por el socket, qué sale, qué
decide el ruteo, qué garantiza el ciclo de vida. No describe cómo se consigue. Un
`ConcurrentHashMap` no es parte del contrato; que dos peticiones simultáneas a la misma ruta no
se pisen la sesión, sí.

## Cómo se escribe

Tres reglas, para que el documento no se convierta en literatura:

1. **Cada requisito es comprobable desde fuera.** Si no se puede escribir una prueba que lo
   verifique hablando HTTP contra el proceso, no es un requisito: es una nota de diseño.
2. **Cada requisito tiene un número estable.** `HTTP-014` sigue siendo `HTTP-014` para siempre.
   Un requisito que se retira se marca retirado; no se reutiliza su número.
3. **Cada requisito nace de una prueba que ya existe.** No se especifica lo que no está probado.
   Esto es lo que separa un contrato de una lista de deseos, y es también por qué este borrador
   se escribe *después* del código y no antes.

## Cómo va a crecer

El contrato se extrae del banco que ya corre: **1 761 aserciones en 88 grupos**. Cada grupo es un
candidato a sección del contrato, y cada aserción a requisito numerado. El trabajo pendiente no
es inventar el contrato — es transcribirlo y decidir, grupo a grupo, qué parte es contrato y qué
parte es detalle de la implementación en Java.

| Área | Grupos de prueba | Estado del contrato |
|---|---|---|
| Protocolo HTTP/1.1 | 6 | [conformidad.md](conformidad.md) · **empezado** |
| Protocolo HTTP/2 | 1 | [http2.md](http2.md) · **inventario, sin numerar** |
| Ruteo y despacho | 5 | por escribir |
| Petición y respuesta | 9 | por escribir |
| Sesiones y cookies | 4 | por escribir |
| Seguridad transversal | 8 | por escribir |
| Plantillas | 7 | fuera del núcleo: contrato aparte |
| Datos | 9 | fuera del núcleo: contrato aparte |
| Observabilidad | 4 | por escribir |

## Lo que este borrador **no** dice todavía

Conviene que conste, porque un contrato incompleto que no admite estarlo hace más daño que uno
que sí:

- **Nada de HTTP/2.** La implementación en Java no lo tiene, así que no hay nada que especificar.
  Cuando lo tenga, será un contrato aparte: h2 no es HTTP/1.1 con otra sintaxis.
- **Nada de la API en el lenguaje.** Que en Java se llame `Cero.app()` y en Rust se llame otra
  cosa es correcto. El contrato es el comportamiento, no los nombres.
- **Nada de rendimiento.** Los números viven en `benchmarks/` y se miden; no se prometen.

## Relación con las implementaciones

```text
spec/                 el contrato — manda a partir de la fase 3
java/                 implementación de referencia hasta entonces
benchmarks/           mide, no especifica
```

Mientras este directorio siga en borrador, **si el contrato y `java/` no coinciden, gana `java/`**
y el contrato está mal escrito. Cuando se invierta esa regla, se dirá aquí y en el LEEME el mismo
día.
