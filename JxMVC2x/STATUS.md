# JxMVC2x — Sitio oficial + generador / plantilla de arranque

## Estructura

```
jxmvc/
├── controllers/
│   ├── BaseController    — Controlador base de la app (helpers comunes)
│   ├── HomeController    — Rutas principales (@JxControllerMain)
│   └── DemoController    — Demos: CORS, sesión, BD, subida
└── models/
    └── TestModel         — Modelo ejemplo que extiende JxDB
```

## Uso del framework (resumen rápido)

```java
// Controller mínimo
@JxControllerMapping("users")
public class UserController extends BaseController {

    @JxGetMapping("list")
    public ActionResult list() {
        try (JxDB db = db()) {
            return json(db.getTable("users"));
        }
    }

    @JxPostMapping("save")
    public ActionResult save() {
        String name = requireParam("name");
        try (JxDB db = db()) {
            long id = db.insert("users", DBRow.of("name", name));
            return jsonOk(id);
        }
    }

    @JxPutMapping("update")
    public ActionResult update() {
        int id   = model.paramInt("id", 0);
        String n = requireParam("name");
        try (JxDB db = db()) {
            db.update("users", DBRow.of("name", n), "id = ?", id);
            return ok();
        }
    }

    @JxDeleteMapping("delete")
    public ActionResult delete() {
        int id = model.paramInt("id", 0);
        try (JxDB db = db()) {
            db.delete("users", "id = ?", id);
            return ok();
        }
    }
}
```

## Respuestas disponibles

| Método          | Resultado                        |
|-----------------|----------------------------------|
| `view("path")`  | Renderiza `/WEB-INF/views/path.jsp` |
| `text("...")`   | `text/plain`                     |
| `json(obj)`     | `application/json` via JxJson    |
| `redirect("/")`  | HTTP 302                        |
| `ok()`          | `text/plain` "OK"               |
| `jsonOk(data)`  | `{"ok":true,"data":...}`        |
| `jsonError(s,m)`| `{"ok":false,"error":"..."}`    |

## Rutas convenidas

- Sin anotación = convención `/controlador/accion/arg0/arg1`
- `model.arg(0)` → argumento de ruta (sanitizado)
- `model.argRaw(0)` → argumento de ruta (sin sanitizar)
- `model.param("x")` → param GET/POST (sanitizado)
- `model.paramInt("x", 0)` → param tipado con default
