# JxMVC — Pendientes del paper (SPE) · 2026-08-01

Guía para seguir tú mismo. Tres pendientes: **(1)** correr el benchmark `/db`
en Arch, **(2)** DOI de Zenodo, **(3)** autoría/CRediT/ORCID.

---

## 1. Correr el benchmark `/db` en Arch bare-metal ⬅️ acción principal

**Por qué:** un revisor de SPE dirá que `/plaintext` y `/json` sólo miden
overhead de routing. El endpoint `/db` hace un `SELECT` real sobre H2
in-memory (1000 filas) y serializa a JSON — mide el framework "haciendo
trabajo de app". Ya está implementado en los 5 frameworks (lógica idéntica,
validada: 160 000 llamadas concurrentes, 0 errores).

**IMPORTANTE:** tiene que ser en el **mismo Arch bare-metal** donde corriste
los benchmarks originales. NO en Windows ni WSL (los números no serían
comparables con los del paper).

### Paso 1 — Llevar el código a Arch

- **Si Andre ya hizo `git push`** de los cambios del harness → en Arch:
  ```bash
  cd ruta/al/repo/jxmvc
  git pull
  ```
- **Si NO se ha subido todavía** → pídeselo a Claude ("sube los cambios del
  harness, solo mi autoría") o transfiere la carpeta `benchmarks/` a mano.

  > Estado a 2026-08-01: los cambios (`Db.java` ×5, rutas `/db`, poms con H2,
  > `bench.sh`, `RUN-DB.md`) están **en local**, sin commitear todavía.

### Paso 2 — Correr (Docker debe estar activo)

```bash
cd benchmarks/docker
BENCH_DB=1 ./bench.sh
```

- `BENCH_DB=1` añade el endpoint `/db` a los existentes (`plaintext`, `json`).
- Sin esa variable, el run por defecto NO cambia.
- Aplica el mismo aislamiento que ya usaste (cores fijados, governor
  `performance`, Turbo off).
- Tarda parecido al run original + un poco más por el endpoint extra.

### Paso 3 — Enviar resultados a Claude

Pásale (o pega el contenido de) estos dos archivos:

```
benchmarks/results/RESULTS-docker.md      # tabla con la columna "rps /db"
benchmarks/results/raw-docker.csv         # filas crudas (para latencia media/p99)
```

Claude entonces:
- Agrega la fila/columna `/db` a las **Tablas 3 y 4** del paper.
- Mueve el endpoint de "future work" a **resultado medido**.
- Recompila y sube el PDF actualizado.

### Detalles técnicos (referencia)

- Doc completa: `benchmarks/docker/RUN-DB.md`.
- La referencia GraalVM native (`bench-native.sh`) NO incluye `/db` (H2
  necesita config de reflexión para native-image); `/db` es sólo modo JVM,
  que es la comparación justa.
- Spring desactiva su datasource auto-config para que H2 sea sólo librería
  JDBC (no premiar/castigar su auto-config).

---

## 2. DOI de Zenodo

- Publicar el release **v3.4.0** en Zenodo (conectando el repo de GitHub) →
  obtienes un DOI citable.
- Pasarle el DOI a Claude → reemplaza el placeholder en `main.tex`
  (nota al pie de la sección *Software and data availability*) y en la
  cover letter.

---

## 3. Autoría + CRediT + ORCID (blocker para enviar)

No se puede someter con `[Autoría por confirmar]` ni con el email de
estudiante. Definir con el asesor y pasarle a Claude:

- **Nombres y orden** de los autores (¿asesor primero?).
- **ORCID** de cada autor (gratis en orcid.org).
- **Afiliación** de cada autor.
- **Email de correspondencia** correcto (reemplaza `75521963@est.unap.edu.pe`).

Claude entonces rellena `main.tex`, la tabla CRediT (`CREDIT-template.md`)
y la cover letter.

---

## Estado del paper a 2026-08-01

- **13 páginas**, compila limpio (0 refs/citas indefinidas).
- **38 referencias**, con **10 citas a SPE + 2 a JSS** (todas con DOI real
  verificado por Crossref).
- 2 figuras (pipeline + arquitectura) en paleta de marca con iconos, y
  Listing 1 (controller de ejemplo).
- Tablas 1/3/4 con resaltado de marca; abstract 186 palabras (< 250).
- Carpeta del paper: `D:\Research-Dev\AUP_Papers\13.-JxMVC_SPE\`
- PDF borrador en el release: `JxMVC-paper-draft.pdf` (repo `Andre031222/jxmvc`, v3.4.0).

### Recomendado antes de enviar (no bloqueante)
- Pase final de **inglés nativo** (abstract + intro + discusión) por humano
  o servicio de editing — Claude ya hizo una limpieza fuerte.
