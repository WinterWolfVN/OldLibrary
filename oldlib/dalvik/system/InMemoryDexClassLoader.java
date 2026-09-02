package oldlib.dalvik.system;

import java.nio.ByteBuffer;
import dalvik.system.DexFile;

public final class InMemoryDexClassLoader extends ClassLoader {
    private final DexFile[] dexFile;

    public InMemoryDexClassLoader(ByteBuffer[] buffer, ClassLoader parent) {
        super(parent);
        if (buffer == null) {
           throw new NullPointerException("buffer == null");
        }
        int count = buffer.length;
        int[] positions = new int[count];
        int[] limits = new int[count];
        boolean[] hasArrays = new boolean[count];
        Object[] arrays = new Object[count];
        int[] arrayOffsets = new int[count];
        for (int i = 0; i < count; i++) {
            if (buffer[i] == null) {
               throw new NullPointerException("buffer[" + i + "] == null");
            }
            positions[i] = buffer[i].position();
            limits[i] = buffer[i].limit();
            hasArrays[i] = buffer[i].hasArray();
            if (hasArrays[i]) {
               arrays[i] = buffer[i].array();
               arrayOffsets[i] = buffer[i].arrayOffset();
            }
        }
        this.dexFile = CreateDexFile(buffer, positions, limits, hasArrays, arrays, arrayOffsets);
    }
    
    public InMemoryDexClassLoader(ByteBuffer buffer, ClassLoader parent) {
        this(new ByteBuffer[]{buffer}, parent);
    }

    public InMemoryDexClassLoader(DexFile dexFile, ClassLoader parent) {
        super(parent);
        this.dexFile = new DexFile[] { dexFile };
    }

    public static ByteBuffer ConvertByteToByteBuffer(byte[] dexFile) {
        if (dexFile == null) {
           throw new NullPointerException("dexFile == null");
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(dexFile.length);
        buffer.put(dexFile);
        buffer.position(0);
        buffer.limit(dexFile.length);
        return buffer;
    }
    
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        for (DexFile dex : dexFile) {
            Class<?> result = dex.loadClass(name, this);
            if (result != null) {
                return result;
            }
        }
        throw new ClassNotFoundException(name);
    }

    private static native DexFile[] CreateDexFile(ByteBuffer[] buffers, int[] positions, int[] limits, boolean[] hasArrays, Object[] arrays, int[] arrayOffsets);
    private static native Class<?> NativeBridge(String name, ClassLoader loader);
}