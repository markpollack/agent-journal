package io.github.markpollack.journal;

/**
 * Converts a stored number to the numeric type a caller asks for. A value written as a {@code Long} is read
 * back from a JSON file as an {@code Integer} when it is small, so an exact type check turns a harmless
 * round trip into a {@link ClassCastException}.
 */
final class StoredNumbers {

    private StoredNumbers() {
    }

    /**
     * Returns {@code value} as {@code type} when it is already one, or when it is a number that converts
     * without losing information; otherwise {@code null}.
     *
     * @param value the stored value
     * @param type the requested type
     * @param <T> the requested type
     * @return the converted value, or {@code null} if it does not convert
     */
    @SuppressWarnings("unchecked")
    static <T> T convert(Object value, Class<T> type) {
        if (type.isInstance(value)) {
            return (T) value;
        }
        if (!(value instanceof Number n)) {
            return null;
        }
        if (type == Double.class) {
            return (T) Double.valueOf(n.doubleValue());
        }
        if (type == Float.class) {
            float f = n.floatValue();
            return Double.compare(f, n.doubleValue()) == 0 ? (T) Float.valueOf(f) : null;
        }
        boolean integral = n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte
                || n instanceof java.math.BigInteger
                || (n.doubleValue() == Math.rint(n.doubleValue()) && !Double.isInfinite(n.doubleValue()));
        if (!integral) {
            return null;
        }
        java.math.BigDecimal exact = n instanceof java.math.BigDecimal bd ? bd : new java.math.BigDecimal(n.toString());
        try {
            if (type == Long.class) {
                return (T) Long.valueOf(exact.longValueExact());
            }
            if (type == Integer.class) {
                return (T) Integer.valueOf(exact.intValueExact());
            }
            if (type == Short.class) {
                return (T) Short.valueOf(exact.shortValueExact());
            }
            if (type == Byte.class) {
                return (T) Byte.valueOf(exact.byteValueExact());
            }
        }
        catch (ArithmeticException outOfRange) {
            return null;
        }
        return null;
    }
}
