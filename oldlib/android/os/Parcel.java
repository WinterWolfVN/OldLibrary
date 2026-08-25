package oldlib.android.os;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class Parcel {
    // Supports Android 5+, the APIs have been around since Android 5, so just mirror them.

    private static final Class<?> PARCEL = android.os.Parcel.class;
    private static final Method READ_BLOB;
    private static final Method WRITE_BLOB;
    private static final Method WRITE_BLOB_RANGE;

    static {
        try {
            READ_BLOB = PARCEL.getDeclaredMethod("readBlob");
            WRITE_BLOB = PARCEL.getDeclaredMethod("writeBlob", byte[].class);
            WRITE_BLOB_RANGE = PARCEL.getDeclaredMethod("writeBlob", byte[].class, int.class, int.class);
            READ_BLOB.setAccessible(true);
            WRITE_BLOB.setAccessible(true);
            WRITE_BLOB_RANGE.setAccessible(true);
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final android.os.Parcel mParcel;
    public Parcel(android.os.Parcel parcel) {
        if (parcel == null) {
            throw new NullPointerException("parcel == null");
        }
        mParcel = parcel;
    }

    public byte[] readBlob() {
        try {
            return (byte[]) READ_BLOB.invoke(mParcel);
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

    public void writeBlob(byte[] b) {
        try {
            WRITE_BLOB.invoke(mParcel, b);
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

    public void writeBlob(byte[] b, int offset, int len) {
        try {
            WRITE_BLOB_RANGE.invoke(mParcel, b, Integer.valueOf(offset), Integer.valueOf(len));
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

    public android.os.Parcel getDelegate() {
        return mParcel;
    }
}