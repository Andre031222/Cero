package lux.data;

import java.util.List;

public record Page<T>(List<T> data, int page, int size, long total) {

    public Page {
        if (page < 1) {
            throw new IllegalArgumentException("la página empieza en 1, no en " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("el tamaño de página debe ser al menos 1");
        }
        data = List.copyOf(data);
    }

    public int totalPages() {
        return total == 0 ? 0 : (int) ((total + size - 1) / size);
    }

    public boolean hasNext() {
        return page < totalPages();
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public int offset() {
        return (page - 1) * size;
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    public <R> Page<R> map(java.util.function.Function<T, R> mapper) {
        return new Page<>(data.stream().map(mapper).toList(), page, size, total);
    }
}
