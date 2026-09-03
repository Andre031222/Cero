package cero.data;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

public final class Rows extends AbstractList<Row> {

    private final List<Row> rows;

    private Rows(List<Row> rows) {
        this.rows = rows;
    }

    public static Rows of(List<Row> rows) {
        return new Rows(new ArrayList<>(rows));
    }

    public static Rows empty() {
        return new Rows(List.of());
    }

    @Override
    public Row get(int index) {
        return rows.get(index);
    }

    @Override
    public int size() {
        return rows.size();
    }

    public Row first() {
        return rows.isEmpty() ? null : rows.get(0);
    }

    public <T> List<T> as(Class<T> type) {
        List<T> mapped = new ArrayList<>(rows.size());
        for (Row row : rows) {
            mapped.add(row.as(type));
        }
        return mapped;
    }

    public List<Object> column(String name) {
        List<Object> values = new ArrayList<>(rows.size());
        for (Row row : rows) {
            values.add(row.get(name));
        }
        return values;
    }

    public String toJson() {
        StringBuilder out = new StringBuilder(64).append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(rows.get(i).toJson());
        }
        return out.append(']').toString();
    }
}
