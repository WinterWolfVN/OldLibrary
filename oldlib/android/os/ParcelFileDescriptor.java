package oldlib.android.os;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class ParcelFileDescriptor {
     // Supports Android 5+, the APIs have been around since Android 5, so just mirror them. 

    private static final Class<?> FRAMEWORK_CLASS = android.os.ParcelFileDescriptor.class;

    private static final Method CREATE_COMM_SOCKET_PAIR;
    private static final Method GET_OR_CREATE_STATUS_BUFFER;
    private static final Method CLOSE_WITH_STATUS;

    static {
        try {
            CREATE_COMM_SOCKET_PAIR = FRAMEWORK_CLASS.getDeclaredMethod("createCommSocketPair");
            GET_OR_CREATE_STATUS_BUFFER = FRAMEWORK_CLASS.getDeclaredMethod("getOrCreateStatusBuffer");
            CLOSE_WITH_STATUS = FRAMEWORK_CLASS.getDeclaredMethod("closeWithStatus", int.class, String.class);
            CREATE_COMM_SOCKET_PAIR.setAccessible(true);
            GET_OR_CREATE_STATUS_BUFFER.setAccessible(true);
            CLOSE_WITH_STATUS.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final android.os.ParcelFileDescriptor mDelegate;

    public ParcelFileDescriptor(
            android.os.ParcelFileDescriptor pfd) {
        if (pfd == null) {
            throw new NullPointerException("pfd == null");
        }
        mDelegate = pfd;
    }

    public static FileDescriptor[] createCommSocketPair()
            throws IOException {
        try {
            return (FileDescriptor[])
                    CREATE_COMM_SOCKET_PAIR.invoke(null);
        } catch (IllegalAccessException e) {
            throw new IOException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(cause);
        }
    }

    public byte[] getOrCreateStatusBuffer() {
        try {
            return (byte[]) GET_OR_CREATE_STATUS_BUFFER.invoke(mDelegate);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    public void closeWithStatus(int status, String message) {
        try {
            CLOSE_WITH_STATUS.invoke(mDelegate, Integer.valueOf(status), message);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        }
    }
}