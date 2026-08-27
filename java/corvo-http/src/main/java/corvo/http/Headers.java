package corvo.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Headers {

    private String[] names;
    private String[] values;
    private int size;

    public Headers() {
        this(12);
    }

    public Headers(int capacity) {
        names = new String[capacity];
        values = new String[capacity];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public String name(int index) {
        return names[index];
    }

    public String value(int index) {
        return values[index];
    }

    public Headers add(String name, String value) {
        if (size == names.length) {
            int grown = size * 2;
            names = Arrays.copyOf(names, grown);
            values = Arrays.copyOf(values, grown);
        }
        names[size] = name;
        values[size] = value;
        size++;
        return this;
    }

    public Headers set(String name, String value) {
        remove(name);
        return add(name, value);
    }

    public Headers remove(String name) {
        int kept = 0;
        for (int i = 0; i < size; i++) {
            if (!names[i].equalsIgnoreCase(name)) {
                names[kept] = names[i];
                values[kept] = values[i];
                kept++;
            }
        }
        Arrays.fill(names, kept, size, null);
        Arrays.fill(values, kept, size, null);
        size = kept;
        return this;
    }

    public String get(String name) {
        for (int i = 0; i < size; i++) {
            if (names[i].equalsIgnoreCase(name)) {
                return values[i];
            }
        }
        return null;
    }

    public String get(String name, String fallback) {
        String found = get(name);
        return found != null ? found : fallback;
    }

    public List<String> all(String name) {
        List<String> found = new ArrayList<>(2);
        for (int i = 0; i < size; i++) {
            if (names[i].equalsIgnoreCase(name)) {
                found.add(values[i]);
            }
        }
        return found;
    }

    public boolean has(String name) {
        return get(name) != null;
    }

    public boolean contains(String name, String token) {
        for (int i = 0; i < size; i++) {
            if (names[i].equalsIgnoreCase(name) && containsToken(values[i], token)) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        Arrays.fill(names, 0, size, null);
        Arrays.fill(values, 0, size, null);
        size = 0;
    }

    private static boolean containsToken(String value, String token) {
        int from = 0;
        while (from < value.length()) {
            int comma = value.indexOf(',', from);
            int end = comma < 0 ? value.length() : comma;
            if (value.substring(from, end).trim().equalsIgnoreCase(token)) {
                return true;
            }
            if (comma < 0) {
                return false;
            }
            from = comma + 1;
        }
        return false;
    }
}
