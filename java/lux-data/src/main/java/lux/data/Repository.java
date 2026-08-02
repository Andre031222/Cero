package lux.data;

import lux.core.Json;

import java.util.ArrayList;
import java.util.List;

public class Repository<T, ID> {

    private final Class<T> type;
    private final Mapping mapping;
    private final Db db;

    public Repository(Class<T> type) {
        this(type, DataSources.DEFAULT);
    }

    public Repository(Class<T> type, String source) {
        this.type = type;
        this.mapping = Mapping.of(type);
        this.db = Db.open(source);
    }

    public String table() {
        return mapping.table();
    }

    public T findById(ID id) {
        Row row = db.selectOne(mapping.table(), mapping.idColumn() + " = ?", id);
        return row == null ? null : toEntity(row);
    }

    public List<T> findAll() {
        return toEntities(db.select(mapping.table()));
    }

    public List<T> findBy(String where, Object... params) {
        return toEntities(db.select(mapping.table(), where, params));
    }

    public T findOneBy(String where, Object... params) {
        Row row = db.selectOne(mapping.table(), where, params);
        return row == null ? null : toEntity(row);
    }

    public Page<T> findPage(int page, int size) {
        return findPage(null, page, size);
    }

    public Page<T> findPage(String where, int page, int size, Object... params) {
        Page<Row> rows = db.page(mapping.table(), where, page, size, params);
        return new Page<>(toEntities(Rows.of(rows.data())), rows.page(), rows.size(), rows.total());
    }

    public long count() {
        return db.count(mapping.table(), null);
    }

    public long countBy(String where, Object... params) {
        return db.count(mapping.table(), where, params);
    }

    public boolean existsById(ID id) {
        return db.count(mapping.table(), mapping.idColumn() + " = ?", id) > 0;
    }

    public long insert(T entity) {
        return db.insert(mapping.table(), mapping.toRow(entity, !mapping.idGenerated()));
    }

    public int update(T entity) {
        Object id = mapping.idOf(entity);
        if (id == null) {
            throw new DataException("no se puede actualizar " + type.getSimpleName() + " sin identificador");
        }
        return db.update(mapping.table(), mapping.toRow(entity, false),
                mapping.idColumn() + " = ?", id);
    }

    public int deleteById(ID id) {
        return db.delete(mapping.table(), mapping.idColumn() + " = ?", id);
    }

    public int deleteBy(String where, Object... params) {
        return db.delete(mapping.table(), where, params);
    }

    protected Db db() {
        return db;
    }

    protected T toEntity(Row row) {
        return Json.bind(mapping.rename(row), type);
    }

    protected List<T> toEntities(Rows rows) {
        List<T> entities = new ArrayList<>(rows.size());
        for (Row row : rows) {
            entities.add(toEntity(row));
        }
        return entities;
    }
}
