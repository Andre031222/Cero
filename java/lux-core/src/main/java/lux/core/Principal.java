package lux.core;

import java.util.Set;

public interface Principal {

    String id();

    Set<String> roles();

    default boolean hasRole(String role) {
        return roles().contains(role);
    }

    static Principal of(String id, String... roles) {
        Set<String> granted = Set.of(roles);
        return new Principal() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> roles() {
                return granted;
            }
        };
    }
}
