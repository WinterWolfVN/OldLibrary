package oldlib.android.os;

public final class Trace {
    // Supports Android 5+, the APIs have been around since Android 5, so just mirror them.
    private static final Class<?> TRACE = android.os.Trace.class;
    private Trace() {
    }

    public static void beginSection(String sectionName) {
        invoke("beginSection", new Class<?>[]{String.class}, sectionName);
    }

    public static void endSection() {
        invoke("endSection", new Class<?>[0]);
    }

    public static void asyncTraceBegin(long traceTag, String methodName, int cookie) {
        invoke("asyncTraceBegin", new Class<?>[]{long.class, String.class, int.class}, traceTag, methodName, cookie);
    }

    public static void asyncTraceEnd(long traceTag, String methodName, int cookie) {
        invoke("asyncTraceEnd", new Class<?>[]{long.class, String.class, int.class}, traceTag, methodName, cookie);
    }

    public static boolean isTagEnabled(long traceTag) {
        return (Boolean) invoke("isTagEnabled", new Class<?>[]{long.class}, traceTag);
    }

    public static void traceBegin(long traceTag, String methodName) {
        invoke("traceBegin", new Class<?>[]{long.class, String.class}, traceTag, methodName);
    }

    public static void traceEnd(long traceTag) {
        invoke("traceEnd", new Class<?>[]{long.class}, traceTag);
    }

    public static void traceCounter(long traceTag, String counterName, int counterValue) {
        invoke("traceCounter", new Class<?>[]{long.class, String.class, int.class}, traceTag, counterName, counterValue);
    }

    private static Object invoke(String name, Class<?>[] parameterTypes, Object... args) {
        try {
            java.lang.reflect.Method method = TRACE.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}