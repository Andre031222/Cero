package corvo.core;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/**
 * Los textos de una petición vistos como mapa, para poder escribirlos en una plantilla.
 *
 * <p>Es un {@link Map} y no un objeto con métodos porque el motor de plantillas resuelve
 * {@code t.guardar} mirando primero si el valor es un mapa. Envolverlo así evita añadir sintaxis
 * de llamada a función al lenguaje de plantillas solo para esto.
 *
 * <p>No se materializa: no hay forma barata de listar todas las claves de todos los idiomas, y
 * tampoco hace falta. Solo responde a {@code get}, que es lo único que pregunta una plantilla.
 */
final class Textos extends AbstractMap<String, String> {

    private final Messages messages;
    private final String idioma;

    Textos(Messages messages, String idioma) {
        this.messages = messages;
        this.idioma = idioma;
    }

    @Override
    public String get(Object clave) {
        return clave == null ? null : messages.get(idioma, clave.toString());
    }

    @Override
    public boolean containsKey(Object clave) {
        return clave != null;
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
        return Set.of();
    }
}
