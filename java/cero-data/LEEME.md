# cero-data

Acceso a datos sobre JDBC sin ORM: `Row`, `Db`, `Pool`, `Tx`, `Repository`, migraciones y sesiones
en tabla. Todo por `PreparedStatement`. No depende de ningún driver: el driver lo pone la
aplicación. Los de PostgreSQL y MySQL del `pom.xml` son solo para las pruebas contra motores
reales.

El código no lleva comentarios. Lo que explicaban y el código no dice solo está aquí.

## `Row`

Una fila es un `Map` de solo lectura con búsqueda insensible a mayúsculas. Es un `Map` para que,
anidada en una respuesta, el JSON la escriba como objeto en vez de mirar sus getters. Se construye
con `of` o `from`; las escrituras lanzan.

## `Pool`

- **El hueco se reserva con un CAS.** Comprobar y después incrementar dejaba dos hilos pasando a
  la vez; con el pool lleno se descontaba un hueco nunca reservado y acababa abriendo más
  conexiones que su tope.
- **Una conexión vuelve saneada.** Quien la pida directamente y la devuelva con una transacción
  abierta o con `autoCommit` en falso se la pasaría así al siguiente. `Tx` ya lo hacía bien; esto
  es la red para quien no lo use. Si no se puede sanear, no vuelve.
- **`validateEvery`**: `isValid()` es un viaje a la base. Por defecto se valida en cada préstamo,
  que es lo seguro; con mucho tráfico, unos segundos ahorran un viaje por petición.

## `Tx`

Una transacción anidada se une a la de fuera, así que no puede deshacer solo lo suyo, pero sí
**condenarla**. Si falla y quien la llamó se come la excepción, la de fuera no puede confirmar:
llevaría dentro el trabajo a medias. `setRollbackOnly()` hace lo mismo a mano.

## `Migrations`

Aplica los `.sql` de un directorio, en orden y una sola vez, cada uno en su transacción y anotado
con su huella.

- **Si una ya aplicada cambia, `run()` falla.** Editar una migración corrida deja dos entornos
  creyéndose iguales. Si la corrección es legítima y no cambia el esquema, `repair()` reescribe la
  huella sin reejecutar nada.
- **Una que falla nunca queda anotada**, y se reintenta. Si sus efectos se deshacen depende del
  motor: PostgreSQL tiene DDL transaccional; MySQL, H2 y Oracle confirman en cada DDL, y una
  migración que crea dos tablas y falla en la segunda deja la primera. La regla es **una
  migración, un cambio**. Flyway y Liquibase tienen el mismo límite, por el mismo motivo.
- **El guion se parte respetando literales**, identificadores entre comillas, comentarios
  —anidados en PostgreSQL— y cadenas con etiqueta de dólar. Un `split(";")` parte cualquier
  cuerpo `$$ … $$` y el motor da un error de sintaxis que no señala la causa. Se parte aquí porque
  muchos drivers no aceptan varias sentencias en un `execute`.
- **El fallo dice en qué archivo y en qué sentencia**, que es lo que el motor no sabe.
- **`verify(origen)`** aplica el lote contra una base vacía y desechable, para integración
  continua, y devuelve el resultado en vez de lanzar. La transacción sale de ese origen: con
  `Tx.run()` salía siempre del de por omisión y la migración caía en la base equivocada.
- **Dentro de un jar** no se puede listar un directorio del classpath, así que las migraciones se
  enumeran en un `indice.txt`, una por línea.

## `JdbcSessions`

Sesiones en una tabla, para que sobrevivan al despliegue y las compartan varias instancias. El
almacén por defecto vive en el montón: cada despliegue echa a todo el mundo y no se puede correr
más de una instancia.

- **Los valores van como JSON** y vuelven como texto, números, booleanos, listas y mapas, no como
  la clase original. Es la regla de cualquier sesión distribuida.
- **Se escribe con un `UPDATE` condicional**: si otra petición tocó la fila, se relee y se
  reintenta, y una escritura no borra la de otra. Son 24 reintentos con espera creciente y algo de
  azar; con cinco, veinte escritores sobre la misma fila perdían escrituras.
- **`createTable()`** es cómodo para arrancar; con migraciones, mejor que la tabla salga de ahí.
