package oldlib.dalvik.system;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import dalvik.system.BaseDexClassLoader;

// Support Android 5-7.1
public class InMemoryDexLoaderCore extends BaseDexClassLoader {
    private static final int MAP_ANONYMOUS = 0x20;
    private static final FileDescriptor noFd = new FileDescriptor();

    private Object mCookie;
    private long[] mInternalCookie;
    private long[] mMappedSizes;

    private static final int pointerSize;
    private static final int headerOffset;
    private static final byte[] templateBytes = new byte[256];
    private static final Method defineClassNativeMethod;
    private static final Method peekByteArrayMethod;
    private static final Method pokeByteArrayMethod;
    private static final int defineMode;

    static {
        try {
            Class<?> vmrClass = Class.forName("dalvik.system.VMRuntime");
            Object vmr = vmrClass.getDeclaredMethod("getRuntime").invoke(null);
            pointerSize = (boolean) vmrClass.getDeclaredMethod("is64Bit").invoke(vmr) ? 8 : 4;
            Class<?> dexClass = Class.forName("dalvik.system.DexFile");
            Method def;
            int mode;
            if (Build.VERSION.SDK_INT == 21) {
                def = dexClass.getDeclaredMethod("defineClassNative", String.class, ClassLoader.class, long.class);
                mode = 1;
            } else if (Build.VERSION.SDK_INT <= 23) {
                def = dexClass.getDeclaredMethod("defineClassNative", String.class, ClassLoader.class, Object.class);
                mode = 2;
            } else {
                def = dexClass.getDeclaredMethod("defineClassNative", String.class, ClassLoader.class, Object.class, dexClass);
                mode = 3;
            }
            def.setAccessible(true);
            defineClassNativeMethod = def;
            defineMode = mode;
            Class<?> memClass = Class.forName("libcore.io.Memory");
            peekByteArrayMethod = memClass.getDeclaredMethod("peekByteArray", long.class, byte[].class, int.class, int.class);
            pokeByteArrayMethod = memClass.getDeclaredMethod("pokeByteArray", long.class, byte[].class, int.class, int.class);
            Object pathList = getField(BaseDexClassLoader.class, InMemoryDexLoaderCore.class.getClassLoader(), "pathList");
            Object[] elements = (Object[]) getField(pathList.getClass(), pathList, "dexElements");
            Object dex = null;
            for (Object element : elements) {
                dex = getField(element.getClass(), element, "dexFile");
                if (dex != null) break;
            }
            Object rawCookie = getField(dex.getClass(), dex, "mCookie");
            long tplPtr;
            if (Build.VERSION.SDK_INT == 21) {
                ByteBuffer tmp = ByteBuffer.wrap(templateBytes).order(ByteOrder.nativeOrder());
                peekByteArrayMethod.invoke(null, (Long) rawCookie, templateBytes, 0, pointerSize);
                peekByteArrayMethod.invoke(null, getPtr(tmp, 0), templateBytes, 0, pointerSize);
                tplPtr = getPtr(tmp, 0);
            } else if (Build.VERSION.SDK_INT == 22) {
                tplPtr = ((long[]) rawCookie)[0];
            } else {
                tplPtr = ((long[]) rawCookie)[1];
            }
            peekByteArrayMethod.invoke(null, tplPtr, templateBytes, 0, 160);
            ByteBuffer tplBuf = ByteBuffer.wrap(templateBytes).order(ByteOrder.nativeOrder());
            long tplBegin = getPtr(tplBuf, pointerSize);
            int hdrOff = -1;
            for (int off = 3 * pointerSize; off <= 152; off += pointerSize) {
                if (getPtr(tplBuf, off) == tplBegin) {
                    hdrOff = off;
                    break;
                }
            }
            if (hdrOff == -1) throw new IllegalStateException("Cannot locate header_ offset");
            headerOffset = hdrOff;
            Arrays.fill(templateBytes, headerOffset - 2 * pointerSize, 256, (byte) 0);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected InMemoryDexLoaderCore(ByteBuffer[] dexBuffers, String librarySearchPath, ClassLoader parent) {
        super(File.pathSeparator, null, librarySearchPath, parent);
        this.mCookie = openInMemoryDexFiles(dexBuffers);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> c = defineClass(name, this, mCookie);
        if (c == null) {
            throw new ClassNotFoundException("Didn't find class \"" + name + "\"");
        }
        return c;
    }

    private Object openInMemoryDexFiles(ByteBuffer[] buffers) {
        try {
            int count = buffers.length;
            mInternalCookie = new long[count + 1];
            mMappedSizes = new long[count + 1];
            long[] dexPtrs = new long[count];
            for (int i = 0; i < count; i++) {
                dexPtrs[i] = createCookieWithDirectBuffer(buffers[i], i);
            }
            if (Build.VERSION.SDK_INT == 21) {
                int vecBytes = (3 + count) * pointerSize;
                long vecPtr = mmapRam(vecBytes, count);
                long arrStart = vecPtr + 3L * pointerSize;
                long arrEnd = arrStart + (long) count * pointerSize;
                byte[] vbBytes = new byte[vecBytes];
                ByteBuffer vb = ByteBuffer.wrap(vbBytes).order(ByteOrder.nativeOrder());
                putPtr(vb, 0, arrStart);
                putPtr(vb, pointerSize, arrEnd);
                putPtr(vb, 2 * pointerSize, arrEnd);
                for (int i = 0; i < count; i++) {
                    putPtr(vb, (3 + i) * pointerSize, dexPtrs[i]);
                }
                pokeByteArrayMethod.invoke(null, vecPtr, vbBytes, 0, vecBytes);
                return vecPtr;
            } else if (Build.VERSION.SDK_INT == 22) {
                return dexPtrs;
            } else {
                long[] cookie = new long[count + 1];
                System.arraycopy(dexPtrs, 0, cookie, 1, count);
                return cookie;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long createCookieWithDirectBuffer(ByteBuffer buffer, int slot) throws Exception {
        ByteBuffer dup = buffer.duplicate();
        int size = dup.remaining();
        byte[] dexBytes = new byte[size];
        dup.get(dexBytes);
        ByteBuffer dex = ByteBuffer.wrap(dexBytes).order(ByteOrder.nativeOrder());
        if (size < 112 || dex.getInt(0) != 0x0a786564) {
            throw new IllegalArgumentException("Invalid DEX magic");
        }
        long structPtr = mmapRam(256 + size, slot);
        long begin = structPtr + 256;
        byte[] sBytes = templateBytes.clone();
        ByteBuffer s = ByteBuffer.wrap(sBytes).order(ByteOrder.nativeOrder());
        putPtr(s, pointerSize, begin);
        putPtr(s, 2 * pointerSize, size);
        s.putInt(headerOffset - 2 * pointerSize, dex.getInt(8));
        putPtr(s, headerOffset, begin);
        putPtr(s, headerOffset + 1 * pointerSize, begin + (dex.getInt(60) & 0xFFFFFFFFL));
        putPtr(s, headerOffset + 2 * pointerSize, begin + (dex.getInt(68) & 0xFFFFFFFFL));
        putPtr(s, headerOffset + 3 * pointerSize, begin + (dex.getInt(84) & 0xFFFFFFFFL));
        putPtr(s, headerOffset + 4 * pointerSize, begin + (dex.getInt(92) & 0xFFFFFFFFL));
        putPtr(s, headerOffset + 5 * pointerSize, begin + (dex.getInt(76) & 0xFFFFFFFFL));
        putPtr(s, headerOffset + 6 * pointerSize, begin + (dex.getInt(100) & 0xFFFFFFFFL));
        pokeByteArrayMethod.invoke(null, structPtr, sBytes, 0, 256);
        pokeByteArrayMethod.invoke(null, begin, dexBytes, 0, size);
        return structPtr;
    }

    private static Class<?> defineClass(String name, ClassLoader loader, Object cookie) throws ClassNotFoundException {
        String slashName = name.replace('.', '/');
        try {
            return (Class<?>) (defineMode == 3 ? defineClassNativeMethod.invoke(null, slashName, loader, cookie, null) : defineClassNativeMethod.invoke(null, slashName, loader, cookie));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof ClassNotFoundException) throw (ClassNotFoundException) cause;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void closeDexFile() {
        if (mInternalCookie != null) {
            for (int i = 0; i < mInternalCookie.length; i++) {
                if (mInternalCookie[i] != 0) {
                    try {
                        Os.munmap(mInternalCookie[i], mMappedSizes[i]);
                    } catch (Exception ignored) {}
                    mInternalCookie[i] = 0;
                }
            }
        }
    }

    private long mmapRam(long byteCount, int slot) throws Exception {
        long addr = Os.mmap(0, byteCount, OsConstants.PROT_READ | OsConstants.PROT_WRITE, OsConstants.MAP_PRIVATE | MAP_ANONYMOUS, noFd, 0);
        mInternalCookie[slot] = addr;
        mMappedSizes[slot] = byteCount;
        return addr;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            closeDexFile();
        } finally {
            super.finalize();
        }
    }

    private static Object getField(Class<?> cls, Object obj, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static long getPtr(ByteBuffer buffer, int off) {
        return pointerSize == 8 ? buffer.getLong(off) : (buffer.getInt(off) & 0xFFFFFFFFL);
    }

    private static void putPtr(ByteBuffer buffer, int off, long val) {
        if (pointerSize == 8) buffer.putLong(off, val);
        else buffer.putInt(off, (int) val);
    }
}