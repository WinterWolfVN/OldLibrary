package oldlib.dalvik.system;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

public final class DexFile {
    private static final Method DEFINE_CLASS;

    static {
        try {
            DEFINE_CLASS = dalvik.system.DexFile.class.getDeclaredMethod("defineClass", String.class, ClassLoader.class, Object.class, dalvik.system.DexFile.class);
            DEFINE_CLASS.setAccessible(true);
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Object mCookie;

    private DexFile(Object cookie) {
        if (cookie == null) {
            throw new NullPointerException("cookie == null");
        }
        mCookie = cookie;
    }

    public DexFile(ByteBuffer buffer)
            throws IOException {
        if (buffer == null) {
            throw new NullPointerException("buffer == null");
        }
        Object cookie;
        int start = buffer.position();
        int end = buffer.limit();
        if (buffer.isDirect()) {
            cookie = createCookieWithDirectBuffer(buffer, start, end);
        } else if (buffer.hasArray()) {
            int arrayStart = buffer.arrayOffset() + start;
            int arrayEnd = arrayStart + end - start;
            cookie = createCookieWithArray(buffer.array(), arrayStart, arrayEnd);
        } else {
            ByteBuffer duplicate = buffer.duplicate();
            byte[] data = new byte[duplicate.remaining()];
            duplicate.get(data);
            cookie = createCookieWithArray(data, 0, data.length);
        }
        if (cookie == null) {
            throw new IOException("Unable to load dex from memory");
        }
        mCookie = cookie;
    }

    public Class<?> loadClass(String name, ClassLoader loader) {
        try {
            return (Class<?>) DEFINE_CLASS.invoke(null, name, loader, mCookie, null);
        } catch (InvocationTargetException e) {
            return null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static native Object createCookieWithArray(byte[] buffer, int start, int end) throws IOException;
    private static native Object createCookieWithDirectBuffer(ByteBuffer buffer, int start, int end) throws IOException;
}