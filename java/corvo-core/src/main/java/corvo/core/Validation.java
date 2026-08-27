package corvo.core;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class Validation {

    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final Map<Class<?>, List<Member>> MEMBERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Rule<?>> RULES = new ConcurrentHashMap<>();
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private Validation() {
    }

    public static void check(Object target) {
        Map<String, String> problems = problems(target);
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
    }

    public static boolean valid(Object target) {
        return problems(target).isEmpty();
    }

    public static Map<String, String> problems(Object target) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (target == null) {
            return problems;
        }
        for (Member member : membersOf(target.getClass())) {
            String problem = inspect(member, member.read(target));
            if (problem != null) {
                problems.putIfAbsent(member.name(), problem);
            }
        }
        return problems;
    }

    private static String inspect(Member member, Object value) {
        Required required = member.annotation(Required.class);
        if (required != null && blank(value)) {
            return required.message();
        }
        if (value == null) {
            return null;
        }

        Length length = member.annotation(Length.class);
        if (length != null) {
            int size = String.valueOf(value).length();
            if (size < length.min() || size > length.max()) {
                return length.message().isEmpty() ? lengthMessage(length) : length.message();
            }
        }

        Range range = member.annotation(Range.class);
        if (range != null) {
            if (!(value instanceof Number number)) {
                return "debe ser numérico";
            }
            double actual = number.doubleValue();
            if (actual < range.min() || actual > range.max()) {
                return range.message().isEmpty() ? rangeMessage(range) : range.message();
            }
        }

        Email email = member.annotation(Email.class);
        if (email != null && !EMAIL.matcher(String.valueOf(value)).matches()) {
            return email.message();
        }

        Match match = member.annotation(Match.class);
        if (match != null) {
            Pattern pattern = PATTERNS.computeIfAbsent(match.value(), Pattern::compile);
            if (!pattern.matcher(String.valueOf(value)).matches()) {
                return match.message();
            }
        }

        OneOf oneOf = member.annotation(OneOf.class);
        if (oneOf != null && !Arrays.asList(oneOf.value()).contains(String.valueOf(value))) {
            return oneOf.message().isEmpty()
                    ? "debe ser uno de: " + String.join(", ", oneOf.value())
                    : oneOf.message();
        }

        Satisfies custom = member.annotation(Satisfies.class);
        if (custom != null) {
            Rule<Object> rule = rule(custom.value());
            if (!rule.test(value)) {
                return rule.message();
            }
        }
        return null;
    }

    private static String lengthMessage(Length length) {
        if (length.max() == Integer.MAX_VALUE) {
            return "debe tener al menos " + length.min() + " caracteres";
        }
        if (length.min() == 0) {
            return "no puede pasar de " + length.max() + " caracteres";
        }
        return "debe tener entre " + length.min() + " y " + length.max() + " caracteres";
    }

    private static String rangeMessage(Range range) {
        if (range.max() == Double.MAX_VALUE) {
            return "debe ser al menos " + trim(range.min());
        }
        if (range.min() == -Double.MAX_VALUE) {
            return "no puede pasar de " + trim(range.max());
        }
        return "debe estar entre " + trim(range.min()) + " y " + trim(range.max());
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static boolean blank(Object value) {
        return value == null
                || value instanceof CharSequence text && text.toString().isBlank()
                || value instanceof java.util.Collection<?> items && items.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Rule<Object> rule(Class<? extends Rule<?>> type) {
        return (Rule<Object>) RULES.computeIfAbsent(type, key -> {
            try {
                var constructor = key.getDeclaredConstructor();
                constructor.setAccessible(true);
                return (Rule<?>) constructor.newInstance();
            } catch (ReflectiveOperationException cause) {
                throw new IllegalStateException(key.getName()
                        + " necesita un constructor sin argumentos", cause);
            }
        });
    }

    private static List<Member> membersOf(Class<?> type) {
        return MEMBERS.computeIfAbsent(type, Validation::scan);
    }

    private static List<Member> scan(Class<?> type) {
        List<Member> members = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                members.add(new Member(component.getName(), component,
                        target -> read(component, target)));
            }
            return List.copyOf(members);
        }
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                members.add(new Member(field.getName(), field, target -> read(field, target)));
            }
        }
        return List.copyOf(members);
    }

    private static Object read(RecordComponent component, Object target) {
        try {
            component.getAccessor().setAccessible(true);
            return component.getAccessor().invoke(target);
        } catch (ReflectiveOperationException cause) {
            return null;
        }
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException cause) {
            return null;
        }
    }

    private record Member(String name, AnnotatedElement element,
                          java.util.function.Function<Object, Object> reader) {

        <A extends java.lang.annotation.Annotation> A annotation(Class<A> type) {
            return element.getAnnotation(type);
        }

        Object read(Object target) {
            return reader.apply(target);
        }
    }
}
