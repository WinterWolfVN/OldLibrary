package oldlib.dalvik.system;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class BaseDexClassLoader extends ClassLoader {
    private final DexPathList pathList;

    protected BaseDexClassLoader(String dexPath, File optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(parent);
        pathList = new DexPathList(this, dexPath, librarySearchPath, optimizedDirectory);
    }

    protected BaseDexClassLoader(ByteBuffer[] dexFiles, ClassLoader parent) {
        super(parent);
        if (dexFiles == null) {
            throw new NullPointerException("dexFiles == null");
        }
        pathList = new DexPathList(this, dexFiles);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        List<Throwable> suppressed = new ArrayList<Throwable>();
        Class<?> result = pathList.findClass(name, suppressed);
        if (result != null) {
            return result;
        }
        ClassNotFoundException exception = new ClassNotFoundException("Didn't find class \"" + name + "\" on path: " + pathList);
        for (Throwable throwable : suppressed) {
            exception.addSuppressed(throwable);
        }
        throw exception;
    }

    public String findLibrary(String name) {
        return pathList.findLibrary(name);
    }

    protected URL findResource(String name) {
        return pathList.findResource(name);
    }

    protected Enumeration<URL> findResources(String name) throws IOException {
        return pathList.findResources(name);
    }

    public String getLdLibraryPath() {
        StringBuilder result = new StringBuilder();
        for (File file : pathList.getNativeLibraryDirectories()) {
            if (result.length() > 0) {
                result.append(':');
            }
            result.append(file);
        }
        return result.toString();
    }

    public void addDexPath(String path) {
        pathList.addDexPath(path, null);
    }

    public String toString() {
        return getClass().getName() + "[" + pathList + "]";
    }
}