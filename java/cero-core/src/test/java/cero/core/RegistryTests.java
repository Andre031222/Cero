package cero.core;

final class RegistryTests {

    private RegistryTests() {
    }

    interface Reloj {
        long ahora();
    }

    static final class RelojFijo implements Reloj {
        @Override
        public long ahora() {
            return 1_000L;
        }
    }

    @Service
    static final class Repositorio {
        String buscar() {
            return "fila";
        }
    }

    @Service
    static final class Servicio {
        @Inject
        Repositorio repositorio;

        String usar() {
            return repositorio.buscar();
        }
    }

    @Service
    static final class ConConstructor {
        private final Repositorio repositorio;

        @Inject
        ConConstructor(Repositorio repositorio) {
            this.repositorio = repositorio;
        }

        String usar() {
            return repositorio.buscar();
        }
    }

    @Service
    static final class CicloA {
        @Inject
        CicloB otro;
    }

    @Service
    static final class CicloB {
        @Inject
        CicloA otro;
    }

    // Cadena honda de servicios. El fallo de "Recursive update" no depende de la
    // profundidad en sí, sino de que dos eslabones caigan en el mismo bin del
    // ConcurrentHashMap; con la cadena larga la colisión deja de ser cuestión de suerte.
    @Service static final class N1 { }
    @Service static final class N2 { @Inject N2(N1 a) { } }
    @Service static final class N3 { @Inject N3(N2 a) { } }
    @Service static final class N4 { @Inject N4(N3 a) { } }
    @Service static final class N5 { @Inject N5(N4 a) { } }
    @Service static final class N6 { @Inject N6(N5 a) { } }
    @Service static final class N7 { @Inject N7(N6 a) { } }
    @Service static final class N8 { @Inject N8(N7 a) { } }
    @Service static final class N9 { @Inject N9(N8 a) { } }
    @Service static final class N10 { @Inject N10(N9 a) { } }

    static final class SinAnotar {
    }

    static void run() {
        Check.group("inyección de dependencias");

        Registry registry = new Registry();

        registry.add(new RelojFijo());
        Check.equal("resuelve por clase concreta", registry.get(RelojFijo.class).ahora(), 1_000L);
        Check.equal("resuelve por interfaz implementada", registry.get(Reloj.class).ahora(), 1_000L);

        Check.equal("crea servicios anotados bajo demanda",
                registry.get(Repositorio.class).buscar(), "fila");
        Check.that("los servicios son singleton",
                registry.get(Repositorio.class) == registry.get(Repositorio.class));

        Check.equal("inyecta por campo", registry.get(Servicio.class).usar(), "fila");
        Check.equal("inyecta por constructor", registry.get(ConConstructor.class).usar(), "fila");

        Check.that("has() distingue lo registrado",
                registry.has(RelojFijo.class) && !registry.has(SinAnotar.class));

        Check.raises("tipo sin registrar ni anotar falla", IllegalStateException.class,
                () -> registry.get(SinAnotar.class));

        Check.raises("dependencia circular se detecta", IllegalStateException.class,
                () -> new Registry().get(CicloA.class));

        Registry sustituto = new Registry();
        sustituto.add(Reloj.class, (Reloj) () -> 7L);
        Check.equal("se puede registrar una implementación por contrato",
                sustituto.get(Reloj.class).ahora(), 7L);

        Registry aislado = new Registry();
        Check.that("create() no cachea",
                aislado.create(Repositorio.class) != aislado.create(Repositorio.class));

        // Regresión: get() resolvía dentro de computeIfAbsent y build() vuelve a entrar en
        // get() por cada dependencia. Un computeIfAbsent anidado sobre el mismo mapa lanza
        // IllegalStateException("Recursive update").
        Registry hondo = new Registry();
        Check.that("resuelve cadenas hondas sin 'Recursive update'",
                hondo.get(N10.class) != null);
        Check.that("y siguen siendo singleton en toda la cadena",
                hondo.get(N5.class) == hondo.get(N5.class));
    }
}
