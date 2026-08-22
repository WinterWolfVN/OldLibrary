package oldlib.dalvik.system;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.List;

public final class DexFile {
  private Object mCookie;
  private Object mInternalCookie;
  private String mFileName;

static {
    initInMemoryDexBridge(DexFile.class.getClassLoader());
}
private static native void initInMemoryDexBridge(ClassLoader loader);
    
public DexFile(ByteBuffer buf) throws IOException {
    if (buf == null) {
        throw new NullPointerException("buf == null");
    }
    mCookie = openInMemoryDexFile(buf);
    mInternalCookie = mCookie;
    mFileName = null;
}

private static Class defineClass(String str, ClassLoader classLoader, Object obj, DexFile dexFile, List<Throwable> suppressed) {
        Class result = null;
        try {
            return defineClassNative(str, classLoader, obj, dexFile);
        } catch (NoClassDefFoundError e) {
            if (suppressed == null) {
                return result;
            }
            suppressed.add(e);
            return result;
        } catch (ClassNotFoundException e2) {
            if (suppressed == null) {
                return result;
            }
            suppressed.add(e2);
            return result;
        }
}
    
public static DexFile loadDex(String path, String optimizedDirectory, int flags) throws IOException {
    Object cookie = openDexFile(path, optimizedDirectory, flags);
    return new DexFile(cookie);
}

private DexFile(Object cookie) {
    this.mCookie = cookie;
    this.mInternalCookie = cookie;
    this.mFileName = null;
}
  
private static Object openInMemoryDexFile(ByteBuffer buf) throws IOException {
    int start = buf.position();
    int size = buf.remaining();
    if (buf.isDirect()) {
        return createCookieWithDirectBuffer(buf, start, size);
    }
    if (buf.hasArray()) {
        return createCookieWithArray(buf.array(), buf.arrayOffset() + start, size);
    }
    byte[] tmp = new byte[size];
    ByteBuffer dup = buf.duplicate();
    dup.get(tmp);
    return createCookieWithArray(tmp, 0, size);
    }                                    

public Class<?> loadClass(String name, ClassLoader loader) {
    return loadClassBinaryName(name, loader, null);
  }

public Class<?> loadClassBinaryName(String str, ClassLoader classLoader, List<Throwable> suppressed) {
    return defineClass(str, classLoader, this.mCookie, this, suppressed);
  }
  
private static native Object createCookieWithDirectBuffer(ByteBuffer buf, int start, int end) throws IOException;
private static native Object createCookieWithArray(byte[] buf, int start, int end) throws IOException;
private static native Object openDexFile(String path, String optimizedDirectory, int flags) throws IOException;
private static native Class defineClassNative(String str, ClassLoader classLoader, Object obj, DexFile dexFile) throws ClassNotFoundException, NoClassDefFoundError;
}

              
