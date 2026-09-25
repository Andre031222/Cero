# Artículos

Seis manuscritos, uno por carpeta. Cada uno se compila solo y comparte la plantilla oficial de
Elsevier (`plantilla/`, generada de `elsarticle.dtx` de CTAN, no una copia de internet).

```
make -C papers/01-arquitectura        # deja main.pdf al lado
```

## Lo que hay y lo que falta

| Carpeta | Revista prevista | Evidencia hoy | Bloqueo |
|---|---|---|---|
| `01-arquitectura` | Journal of Systems and Software | 7 módulos, 153 clases, 19 232 líneas, 0 dependencias, 1 764 comprobaciones | ninguno |
| `02-seguridad-http` | Computer Standards & Interfaces | 23 vectores RFC sobre 13 apartados → 4 incumplimientos; h2spec 146; 11 hallazgos de auditoría | ninguno |
| `03-rendimiento` | Information and Software Technology | 9 frameworks, 135 mediciones | corrida en Linux sin virtualizar |
| `04-hilos-virtuales` | Journal of Systems Architecture | un hilo virtual por conexión, 30 min sin fuga | **el experimento comparativo no existe** |
| `05-fiabilidad` | Empirical Software Engineering | producción desde el 2 de agosto de 2026 | meses de tráfico |
| `06-contrato-poliglota` | por decidir | contrato en `spec/` | implementaciones en Rust y C++ |

## Dos reglas

**En inglés.** Las cinco revistas lo exigen. El repositorio sigue en castellano a propósito; los
manuscritos no.

**Nada que no esté medido.** Ninguna cifra entra en un manuscrito sin que se pueda rehacer desde
`benchmarks/` o desde la batería de pruebas. Donde falte el dato, va un `\todo` visible, no una
estimación con aire de medición.
