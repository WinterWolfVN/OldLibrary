package oldlib.dalvik.system;

import android.os.Build;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import dalvik.system.BaseDexClassLoader;

public final class InMemoryDexClassLoader extends BaseDexClassLoader {
    private final List<Object> mPinnedRam = new ArrayList<>();
    private final Object mCookie;

    private static final int PTR_SIZE;
    private static final int HDR_OFF;
    private static final byte[] TEMPLATE_BYTES = new byte[256];
    private static final Object VMR;
    private static final Method NEW_NON_MOVABLE, ADDRESS_OF, DEFINE_CLASS;
    private static final int DEFINE_MODE;

    static {
        try {
            Class<?> vmrCls = Class.forName("dalvik.system.VMRuntime");
            VMR = vmrCls.getDeclaredMethod("getRuntime").invoke(null);
            PTR_SIZE = (boolean) vmrCls.getDeclaredMethod("is64Bit").invoke(VMR) ? 8 : 4;
            NEW_NON_MOVABLE = vmrCls.getDeclaredMethod("newNonMovableArray", Class.class, int.class);
            ADDRESS_OF = vmrCls.getDeclaredMethod("addressOf", Object.class);
            Class<?> dexFile = Class.forName("dalvik.system.DexFile");
            Method def;
            int mode;
            if (Build.VERSION.SDK_INT == 21) {
                def = dexFile.getDeclaredMethod("defineClassNative", String.class, ClassLoader.class, long.class);
                mode = 1;
            } else if (Build.VERSION.SDK_INT <= 23) {
                def = dexFile.getDeclaredMethod("defineClassNative", String.class, ClassLoader.class, Object.class);
                mode = 2;
            } else {
                def = dexFile.getDeclaredMethod("defineClassNative", String.class, ClassLoader.class, Object.class, dexFile);
                mode = 3;
            }
            def.setAccessible(true);
            DEFINE_CLASS = def;
            DEFINE_MODE = mode;
            Method peekBytes = Class.forName("libcore.io.Memory").getDeclaredMethod("peekByteArray", long.class, byte[].class, int.class, int.class);
            Object pathList = getField(BaseDexClassLoader.class, InMemoryDexClassLoader.class.getClassLoader(), "pathList");
            Object[] elems = (Object[]) getField(pathList.getClass(), pathList, "dexElements");
            Object dex = null;
            for (Object elem : elems) {
                dex = getField(elem.getClass(), elem, "dexFile");
                if (dex != null) break;
            }
            Object rawCookie = getField(dex.getClass(), dex, "mCookie");
            long tplPtr;
            if (Build.VERSION.SDK_INT == 21) {
                ByteBuffer tmp = ByteBuffer.wrap(TEMPLATE_BYTES).order(ByteOrder.nativeOrder());
                peekBytes.invoke(null, (Long) rawCookie, TEMPLATE_BYTES, 0, PTR_SIZE);
                peekBytes.invoke(null, getPtr(tmp, 0), TEMPLATE_BYTES, 0, PTR_SIZE);
                tplPtr = getPtr(tmp, 0);
            } else if (Build.VERSION.SDK_INT == 22) {
                tplPtr = ((long[]) rawCookie)[0];
            } else {
                tplPtr = ((long[]) rawCookie)[1];
            }
            peekBytes.invoke(null, tplPtr, TEMPLATE_BYTES, 0, 160);
            ByteBuffer tplBuf = ByteBuffer.wrap(TEMPLATE_BYTES).order(ByteOrder.nativeOrder());
            long tplBegin = getPtr(tplBuf, PTR_SIZE);
            int hdrOff = -1;
            for (int off = 3 * PTR_SIZE; off <= 152; off += PTR_SIZE) {
                if (getPtr(tplBuf, off) == tplBegin) {
                    hdrOff = off;
                    break;
                }
            }
            if (hdrOff == -1) throw new IllegalStateException("Cannot locate header_ offset");
            HDR_OFF = hdrOff;
            Arrays.fill(TEMPLATE_BYTES, HDR_OFF - 2 * PTR_SIZE, 256, (byte) 0);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public InMemoryDexClassLoader(ByteBuffer dexBuffer, ClassLoader parent) {
        this(new Object[]{dexBuffer}, null, parent, true);
    }

    public InMemoryDexClassLoader(ByteBuffer[] dexBuffers, ClassLoader parent) {
        this(dexBuffers, null, parent, true);
    }

    public InMemoryDexClassLoader(byte[] dexBytes, ClassLoader parent) {
        this(new Object[]{dexBytes}, null, parent, true);
    }

    public InMemoryDexClassLoader(byte[][] dexBytes, ClassLoader parent) {
        this(dexBytes, null, parent, true);
    }

    public InMemoryDexClassLoader(Byte[] dexBytes, ClassLoader parent) {
        this(new Object[]{dexBytes}, null, parent, true);
    }
   
    public InMemoryDexClassLoader(Byte[][] dexBytes, ClassLoader parent) {
        this(dexBytes, null, parent, true);
    }

    private InMemoryDexClassLoader(Object[] dexSources, String librarySearchPath, ClassLoader parent, boolean raw) {
        super(File.pathSeparator, null, librarySearchPath, parent);
        try {
            int count = dexSources.length;
            long[] dexPtrs = new long[count];
            for (int i = 0; i < count; i++) {
                dexPtrs[i] = buildRamDex(dexSources[i]);
            }
            if (Build.VERSION.SDK_INT == 21) {
                long[] vecArr = (long[]) NEW_NON_MOVABLE.invoke(VMR, long.class, 3 + count);
                mPinnedRam.add(vecArr);
                long vecPtr = (long) ADDRESS_OF.invoke(VMR, vecArr);
                long arrStart = vecPtr + 3L * PTR_SIZE;
                long arrEnd = arrStart + (long) count * PTR_SIZE;
                ByteBuffer vb = ByteBuffer.allocate((3 + count) * 8).order(ByteOrder.nativeOrder());
                putPtr(vb, 0, arrStart);
                putPtr(vb, PTR_SIZE, arrEnd);
                putPtr(vb, 2 * PTR_SIZE, arrEnd);
                for (int i = 0; i < count; i++) {
                    putPtr(vb, (3 + i) * PTR_SIZE, dexPtrs[i]);
                }
                for (int i = 0; i < vecArr.length; i++) {
                    vecArr[i] = vb.getLong(i << 3);
                }
                mCookie = vecPtr;
            } else if (Build.VERSION.SDK_INT == 22) {
                mCookie = dexPtrs;
            } else {
                long[] cookie = new long[count + 1];
                System.arraycopy(dexPtrs, 0, cookie, 1, count);
                mCookie = cookie;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String slash = name.replace('.', '/');
        try {
            Class<?> c = (Class<?>) (DEFINE_MODE == 3 ? DEFINE_CLASS.invoke(null, slash, this, mCookie, null) : DEFINE_CLASS.invoke(null, slash, this, mCookie));
            if (c != null) return c;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof ClassNotFoundException) throw (ClassNotFoundException) cause;
        } catch (Exception ignored) {}
        throw new ClassNotFoundException(name);
    }

    private long buildRamDex(Object src) throws Exception {
        byte[] dexArr;
        int size;
        if (src instanceof byte[]) {
            byte[] raw = (byte[]) src;
            size = raw.length;
            dexArr = (byte[]) NEW_NON_MOVABLE.invoke(VMR, byte.class, size);
            System.arraycopy(raw, 0, dexArr, 0, size);
        } else if (src instanceof Byte[]) {
            Byte[] boxed = (Byte[]) src;
            size = boxed.length;
            dexArr = (byte[]) NEW_NON_MOVABLE.invoke(VMR, byte.class, size);
            for (int i = 0; i < size; i++) {
                dexArr[i] = boxed[i];
            }
        } else if (src instanceof ByteBuffer) {
            ByteBuffer dup = ((ByteBuffer) src).duplicate();
            size = dup.remaining();
            dexArr = (byte[]) NEW_NON_MOVABLE.invoke(VMR, byte.class, size);
            dup.get(dexArr);
        } else {
            throw new IllegalArgumentException("Unsupported DEX source: " + src);
        }
        ByteBuffer dex = ByteBuffer.wrap(dexArr).order(ByteOrder.nativeOrder());
        if (size < 112 || dex.getInt(0) != 0x0a786564) {
            throw new IllegalArgumentException("Invalid DEX magic");
        }
        mPinnedRam.add(dexArr);
        long begin = (long) ADDRESS_OF.invoke(VMR, dexArr);

        ByteBuffer s = ByteBuffer.wrap(TEMPLATE_BYTES.clone()).order(ByteOrder.nativeOrder());
        putPtr(s, PTR_SIZE, begin);
        putPtr(s, 2 * PTR_SIZE, size);
        s.putInt(HDR_OFF - 2 * PTR_SIZE, dex.getInt(8)); // location_checksum_

        putPtr(s, HDR_OFF, begin);
        putPtr(s, HDR_OFF + 1 * PTR_SIZE, begin + (dex.getInt(60) & 0xFFFFFFFFL)); // string_ids_
        putPtr(s, HDR_OFF + 2 * PTR_SIZE, begin + (dex.getInt(68) & 0xFFFFFFFFL)); // type_ids_
        putPtr(s, HDR_OFF + 3 * PTR_SIZE, begin + (dex.getInt(84) & 0xFFFFFFFFL)); // field_ids_
        putPtr(s, HDR_OFF + 4 * PTR_SIZE, begin + (dex.getInt(92) & 0xFFFFFFFFL)); // method_ids_
        putPtr(s, HDR_OFF + 5 * PTR_SIZE, begin + (dex.getInt(76) & 0xFFFFFFFFL)); // proto_ids_
        putPtr(s, HDR_OFF + 6 * PTR_SIZE, begin + (dex.getInt(100) & 0xFFFFFFFFL));// class_defs_
        long[] structArr = (long[]) NEW_NON_MOVABLE.invoke(VMR, long.class, 32);
        for (int i = 0; i < 32; i++) {
            structArr[i] = s.getLong(i << 3);
        }
        mPinnedRam.add(structArr);
        return (long) ADDRESS_OF.invoke(VMR, structArr);
    }

    private static Object getField(Class<?> cls, Object obj, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static long getPtr(ByteBuffer buf, int off) {
        return PTR_SIZE == 8 ? buf.getLong(off) : (buf.getInt(off) & 0xFFFFFFFFL);
    }

    private static void putPtr(ByteBuffer buf, int off, long val) {
        if (PTR_SIZE == 8) buf.putLong(off, val);
        else buf.putInt(off, (int) val);
    }
}