package oldlib.libcore.util;

import dalvik.system.VMRuntime;
import sun.misc.Cleaner;

// Support Android 5 - 5.1 ( Android 6 and Android 7 already have )
public class NativeAllocationRegistry {
    private final ClassLoader classLoader;
    private final long freeFunction;
    private final long size;

    public NativeAllocationRegistry(ClassLoader classLoader, long freeFunction, long size) {
        if (size < 0) {
            throw new IllegalArgumentException(
                    "Invalid native allocation size: " + size);
        }
        this.classLoader = classLoader;
        this.freeFunction = freeFunction;
        this.size = size;
    }
    
    public Runnable registerNativeAllocation(Object referent, Allocator allocator) {
        if (referent == null) {
            throw new IllegalArgumentException("referent is null");
        }
        if (allocator == null) {
            throw new IllegalArgumentException("allocator is null");
        }
        registerNativeAllocation(size);
        CleanerThunk thunk = new CleanerThunk(this);
        Cleaner cleaner = Cleaner.create(referent, thunk);
        long nativePtr = allocator.allocate();
        if (nativePtr == 0) {
            cleaner.clean();
            return null;
        }
        thunk.nativePtr = nativePtr;
        return new CleanerRunner(cleaner);
    }

    public Runnable registerNativeAllocation(Object referent, long nativePtr) {
        if (referent == null) {
            throw new IllegalArgumentException("referent is null");
        }
        if (nativePtr == 0) {
            throw new IllegalArgumentException("nativePtr is null");
        }
        try {
            registerNativeAllocation(size);
            Cleaner cleaner = Cleaner.create(referent, new CleanerThunk(this, nativePtr));
            return new CleanerRunner(cleaner);
        } catch (OutOfMemoryError e) {
            applyFreeFunction(freeFunction, nativePtr);
            throw e;
        }
    }

    private static void registerNativeAllocation(long size) {
        VMRuntime.getRuntime().registerNativeAllocation((int) Math.min(size, Integer.MAX_VALUE));
    }

    private static void registerNativeFree(long size) {
        VMRuntime.getRuntime().registerNativeFree((int) Math.min(size, Integer.MAX_VALUE));
    }

    public static native void applyFreeFunction(long freeFunction, long nativePtr);

    public interface Allocator {
        long allocate();
    }

    private static final class CleanerThunk implements Runnable {
        private long nativePtr;
        private final NativeAllocationRegistry registry;
        CleanerThunk(NativeAllocationRegistry registry) {
            this.registry = registry;
            this.nativePtr = 0;
        }
        CleanerThunk(NativeAllocationRegistry registry, long nativePtr) {
            this.registry = registry;
            this.nativePtr = nativePtr;
        }

        @Override
        public void run() {
            if (nativePtr != 0) {
                long ptr = nativePtr;
                nativePtr = 0;
                applyFreeFunction(registry.freeFunction, ptr);
                registerNativeFree(registry.size);
            }
        }
    }

    private static final class CleanerRunner implements Runnable {
        private final Cleaner cleaner;
        CleanerRunner(Cleaner cleaner) {
            this.cleaner = cleaner;
        }

        @Override
        public void run() {
            cleaner.clean();
        }
    }
}