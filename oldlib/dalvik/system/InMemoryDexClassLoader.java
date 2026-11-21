package oldlib.dalvik.system;

import java.nio.ByteBuffer;

// Support Android 5-7.1
public final class InMemoryDexClassLoader extends InMemoryDexLoaderCore {

    public InMemoryDexClassLoader(ByteBuffer dexBuffer, ClassLoader parent) {
        this(new Object[]{dexBuffer}, null, parent, true);
    }

    public InMemoryDexClassLoader(ByteBuffer[] dexBuffers, ClassLoader parent) {
        this(dexBuffers, null, parent, true);
    }