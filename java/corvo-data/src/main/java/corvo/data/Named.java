package corvo.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

record Named(String sql, Object[] values) {

    static Named compile(String source, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(source.length());
        List<Object> values = new ArrayList<>(params.size());

        int at = 0;
        boolean inString = false;
        while (at < source.length()) {
            char c = source.charAt(at);

            if (c == '\'') {
                inString = !inString;
                sql.append(c);
                at++;
                continue;
            }
            if (inString || c != ':' || at + 1 >= source.length()
                    || !isNameStart(source.charAt(at + 1))) {
                sql.append(c);
                at++;
                continue;
            }

            int end = at + 1;
            while (end < source.length() && isNamePart(source.charAt(end))) {
                end++;
            }
            String name = source.substring(at + 1, end);
            if (!params.containsKey(name)) {
                throw new DataException("falta el parámetro ':" + name + "' en la consulta");
            }
            sql.append('?');
            values.add(params.get(name));
            at = end;
        }
        return new Named(sql.toString(), values.toArray());
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
