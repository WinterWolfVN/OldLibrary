package android.os;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.MemoryFile;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public final class SharedMemory implements Parcelable, Closeable {

    public static final int PROT_READ = 1;
    public static final int PROT_WRITE = 2;
    public static final int PROT_EXEC = 4;
    private static final int MAP_SHARED = 1;
    private static final int ASHMEM_SET_PROT_MASK = 0x40047708;

    private final FileDescriptor mFileDescriptor;
    private final int mSize;
    private final MemoryFile mMemoryFile;

    private SharedMemory(FileDescriptor fd, int size, MemoryFile memoryFile) {
        this.mFileDescriptor = fd;
        this.mSize = size;
        this.mMemoryFile = memoryFile;
    }

    public static SharedMemory create(String name, int size) {
        try {
            if (size <= 0) throw new IllegalArgumentException("Size must be > 0");
            MemoryFile memoryFile = new MemoryFile(name, size);
            Method getFdMethod = MemoryFile.class.getDeclaredMethod("getFileDescriptor");
            getFdMethod.setAccessible(true);
            FileDescriptor fd = (FileDescriptor) getFdMethod.invoke(memoryFile);
            return new SharedMemory(fd, size, memoryFile);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
    
    private static ByteBuffer createDirectBuffer(int length, long address, FileDescriptor fd, Runnable unmapper, boolean readOnly) throws Exception {
        Class<?> clazz = Class.forName("java.nio.DirectByteBuffer");
        Constructor<?> constructor = clazz.getDeclaredConstructor(int.class, long.class, FileDescriptor.class, Runnable.class, boolean.class);
        constructor.setAccessible(true);
        return (ByteBuffer) constructor.newInstance(length, address, fd, unmapper, readOnly);
    }
    
    public ByteBuffer mapReadOnly() {
        return map(PROT_READ);
    }

    public ByteBuffer mapReadWrite() {
        return map(PROT_READ | PROT_WRITE);
    }

    public ByteBuffer map(int prot) {
        return map(prot, 0, mSize);
    }
    
    public ByteBuffer map(int prot, int offset, int length) {
        if ((prot & ~(PROT_READ | PROT_WRITE | PROT_EXEC)) != 0 || prot == 0) {
            throw new IllegalArgumentException("Invalid protection flags");
        }
        if (offset < 0 || length <= 0 || offset > mSize || length > mSize - offset) {
            throw new IllegalArgumentException("Invalid mapping range");
        }
        long address = 0L;
        boolean mapped = false;
        try {
            Object os = getOs();
            Method mmap = os.getClass().getMethod("mmap", long.class, long.class, int.class, int.class, FileDescriptor.class, long.class);
            address = (Long) mmap.invoke(os, 0L, (long) length, prot, MAP_SHARED, mFileDescriptor, (long) offset);
            mapped = true;
            final long mappedAddress = address;
            final long mappedLength = length;
            Runnable unmapper = new Runnable() {
                @Override
                public void run() {
                    try {
                        Object os = getOs();
                        Method munmap = os.getClass().getMethod("munmap", long.class, long.class);
                        munmap.invoke(os, mappedAddress, mappedLength);
                    } catch (InvocationTargetException e) {
                        throw new RuntimeException(e.getCause());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };
            ByteBuffer buffer = createDirectBuffer(length, address, mFileDescriptor, unmapper, (prot & PROT_WRITE) == 0);
            mapped = false;
            return buffer;
        } catch (InvocationTargetException e) {
            if (mapped) {
                unmapAddress(address, length);
            }
            throw new RuntimeException("Unable to map SharedMemory", e.getCause());
        } catch (Exception e) {
            if (mapped) {
                unmapAddress(address, length);
            }
            throw new RuntimeException("Unable to map SharedMemory", e);
        }
    }
    
    public static void unmap(ByteBuffer buffer) {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be direct");
        }
        try {
            Method cleanerMethod = buffer.getClass().getMethod("cleaner");
            Object cleaner = cleanerMethod.invoke(buffer);
            if (cleaner == null) {
                throw new IllegalStateException("Cleaner is null");
            }
            Method cleanMethod = cleaner.getClass().getMethod("clean");
            cleanMethod.invoke(cleaner);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Unable to unmap buffer", e.getCause());
        } catch (Exception e) {
            throw new RuntimeException("Unable to unmap buffer", e);
        }
    }

    private static void unmapAddress(long address, long length) {
        try {
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Field osField = libcore.getField("os");
            Object os = osField.get(null);
            Method munmap = os.getClass().getMethod("munmap", long.class, long.class);
            munmap.invoke(os, address, length);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("munmap failed", e.getCause());
        } catch (Exception e) {
            throw new RuntimeException("Unable to unmap memory", e);
        }
    }

    public void setProtect(int prot) {
        if ((prot & ~(PROT_READ | PROT_WRITE | PROT_EXEC)) != 0 || prot == 0) {
            throw new IllegalArgumentException("Invalid protection flags");
        }
        try {
            Object os = getOs();
            try {
                Method ashmemSetProt = os.getClass().getMethod("ashmem_set_prot", FileDescriptor.class, int.class);
                ashmemSetProt.invoke(os, mFileDescriptor, prot);
            } catch (NoSuchMethodException e) {
                Method ioctlInt = os.getClass().getMethod("ioctlInt", FileDescriptor.class, int.class, int.class);
                ioctlInt.invoke(os, mFileDescriptor, ASHMEM_SET_PROT_MASK, prot);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object getOs() throws Exception {
        Class<?> libcore = Class.forName("libcore.io.Libcore");
        Field osField = libcore.getField("os");
        return osField.get(null);
    }

    @Override
    public void close() {
        if (mMemoryFile != null) mMemoryFile.close();
    }

    public int getSize() {
        return mSize;
    }

    public FileDescriptor getFileDescriptor() {
        return mFileDescriptor;
    }

    @Override
    public int describeContents() {
        return 1;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }

    public static final Parcelable.Creator<SharedMemory> CREATOR = new Parcelable.Creator<SharedMemory>() {
        @Override
        public SharedMemory createFromParcel(Parcel source) {
            return null;
        }

        @Override
        public SharedMemory[] newArray(int size) {
            return new SharedMemory[size];
        }
    };
} 
