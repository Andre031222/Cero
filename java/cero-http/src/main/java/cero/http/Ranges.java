package cero.http;

/**
 * La cabecera {@code Range} de RFC 9110 §14, en su forma útil: un solo intervalo de bytes.
 *
 * <p>Con varios intervalos se devuelve {@code null} y quien llama sirve el recurso entero, que es
 * lo que el RFC permite explícitamente. {@code multipart/byteranges} no lo pide nadie para lo que
 * sirve esto: reanudar una descarga y buscar dentro de un vídeo.
 */
record Ranges(long desde, long hasta) {

    static final Ranges NO_SATISFACIBLE = new Ranges(-1, -1);

    long longitud() {
        return hasta - desde + 1;
    }

    boolean satisfacible() {
        return desde >= 0;
    }

    /**
     * @return el intervalo pedido, {@link #NO_SATISFACIBLE} si lo pedido cae fuera del recurso,
     *         o {@code null} si no hay que hacer nada especial
     */
    static Ranges parse(String cabecera, long tamano) {
        if (cabecera == null || tamano <= 0) {
            return null;
        }
        String valor = cabecera.trim();
        if (!valor.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }
        String intervalo = valor.substring(6).trim();
        if (intervalo.indexOf(',') >= 0) {
            return null;
        }
        int guion = intervalo.indexOf('-');
        if (guion < 0) {
            return null;
        }

        String izquierda = intervalo.substring(0, guion).trim();
        String derecha = intervalo.substring(guion + 1).trim();
        try {
            if (izquierda.isEmpty()) {
                if (derecha.isEmpty()) {
                    return null;
                }
                long ultimos = Long.parseLong(derecha);
                if (ultimos <= 0) {
                    return NO_SATISFACIBLE;
                }
                return new Ranges(Math.max(0, tamano - ultimos), tamano - 1);
            }
            long desde = Long.parseLong(izquierda);
            if (desde >= tamano) {
                return NO_SATISFACIBLE;
            }
            long hasta = derecha.isEmpty() ? tamano - 1 : Long.parseLong(derecha);
            if (hasta < desde) {
                return NO_SATISFACIBLE;
            }
            return new Ranges(desde, Math.min(hasta, tamano - 1));
        } catch (NumberFormatException malformado) {
            return null;
        }
    }

    String contentRange(long tamano) {
        return "bytes " + desde + "-" + hasta + "/" + tamano;
    }
}
