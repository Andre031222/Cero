package cero.core;

import java.util.Map;

@FunctionalInterface
public interface ViewRenderer {

    String render(String template, Object model) throws Exception;

    /**
     * Rinde con valores disponibles en toda la plantilla sin pasar por el modelo.
     *
     * <p>Va como método por defecto y no como abstracto para que la interfaz siga siendo
     * funcional: un motor de vistas de terceros escrito como lambda seguiría compilando, y
     * simplemente ignora los globales.
     */
    default String render(String template, Object model, Map<String, Object> globals)
            throws Exception {
        return render(template, model);
    }
}
