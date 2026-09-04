package cero.core;

import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Los textos de una petición vistos como mapa, para poder escribirlos en una plantilla.
 *
 * <p>Es un {@link Map} y no un objeto con métodos porque el motor de plantillas resuelve
 * {@code t.guardar} mirando primero si el valor es un mapa. Envolverlo así evita añadir sintaxis
 * de llamada a función al lenguaje de plantillas solo para esto.
 *
 * <p>Cumple el contrato de {@link java.util.Map} de verdad, y no solo el {@code get}. Antes
 * {@code containsKey} decía que sí a cualquier cosa y {@code entrySet} volvía vacío, así que
 * {@code size()} valía cero mientras el mapa se declaraba lleno: un {@code if} sobre una clave
 * inexistente era cierto y un {@code for} sobre los textos no recorría nada. Sin dar error, que
 * es lo que lo hacía difícil de ver.
 *
 * <p>Materializarlo cuesta poco: son las claves de un idioma más las del base, y un archivo de
 * textos tiene decenas de líneas, no millones.
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
        return clave != null && messages.tiene(idioma, clave.toString());
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
        Set<Entry<String, String>> entradas = new LinkedHashSet<>();
        for (String clave : messages.claves(idioma)) {
            entradas.add(new SimpleImmutableEntry<>(clave, messages.get(idioma, clave)));
        }
        return entradas;
    }
}
