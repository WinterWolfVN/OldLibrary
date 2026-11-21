package oldlib.dalvik.system;

import java.nio.ByteBuffer;

public final class InMemoryDexClassLoader extends InMemoryDexLoaderCore {

    public InMemoryDexClassLoader(ByteBuffer dexBuffer, ClassLoader parent) {
        this(new Object[]{dexBuffer}, null, parent, true);
    }

    public InMemoryDexClassLoader(ByteBuffer[] dexBuffers, ClassLoader parent) {
        this(dexBuffers, null, parent, true);
    }
} 
