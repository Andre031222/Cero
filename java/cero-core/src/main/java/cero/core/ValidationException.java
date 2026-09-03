package cero.core;

import cero.http.HttpException;

import java.util.LinkedHashMap;
import java.util.Map;

public class ValidationException extends HttpException {

    private final Map<String, String> problems;

    public ValidationException(Map<String, String> problems) {
        super(422, "hay " + problems.size() + " campo(s) inválido(s)");
        this.problems = Map.copyOf(new LinkedHashMap<>(problems));
    }

    public Map<String, String> problems() {
        return problems;
    }
}
