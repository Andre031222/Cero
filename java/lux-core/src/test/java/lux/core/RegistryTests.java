package lux.core;

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
    }
}
