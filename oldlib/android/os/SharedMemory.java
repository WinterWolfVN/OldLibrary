package oldlib.android.os;

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
        try {
            Object os = getOs();
            Method mmap = os.getClass().getMethod("mmap", long.class, long.class, int.class, int.class, FileDescriptor.class, long.class);
            return (ByteBuffer) mmap.invoke(os, 0L, (long) length, prot, MAP_SHARED, mFileDescriptor, (long) offset);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void unmap(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) return;
        try {
            Object os = getOs();
            Field addressField = java.nio.Buffer.class.getDeclaredField("address");
            addressField.setAccessible(true);
            long address = addressField.getLong(buffer);
            Method munmap = os.getClass().getMethod("munmap", long.class, long.class);
            munmap.invoke(os, address, (long) buffer.capacity());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public void setProtect(int prot) {
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
