package oldlib.dalvik.system;

import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;

public class BaseDexClassLoader extends ClassLoader {

    private final DexPathList pathList;

    protected BaseDexClassLoader(
            String dexPath,
            java.io.File optimizedDirectory,
            String librarySearchPath,
            ClassLoader parent) {
        super(parent);
        this.pathList = new DexPathList(
                this,
                dexPath,
                librarySearchPath,
                optimizedDirectory
        );
    }

    protected BaseDexClassLoader(ByteBuffer[] dexFiles, ClassLoader parent) {
          super(parent);          
            if (dexFiles == null) {
                throw new NullPointerException("dexFiles == null");
            }
            this.pathList = new DexPathList(this, dexFiles);
        }
    
    @Override
    protected Class<?> findClass(String name)
            throws ClassNotFoundException {
        List<Throwable> suppressed =
                new ArrayList<Throwable>();
        Class<?> result =
                pathList.findClass(
                        name,
                        suppressed
                );
        if (result != null) {
            return result;
        }
        ClassNotFoundException e =
                new ClassNotFoundException(
                        "Didn't find class \"" +
                        name +
                        "\" on path: " +
                        pathList
                );
        for (Throwable t : suppressed) {
            e.addSuppressed(t);
        }
        throw e;
    }

    public String findLibrary(String name) {
        return pathList.findLibrary(name);
    }

    protected java.net.URL findResource(String name) {
        return pathList.findResource(name);
    }

    protected java.util.Enumeration<java.net.URL>
    findResources(String name)
            throws java.io.IOException {
        return pathList.findResources(name);
    }

    public String getLdLibraryPath() {
        StringBuilder result =
                new StringBuilder();
        for (java.io.File file :
                pathList.getNativeLibraryDirectories()) {
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
        return getClass().getName() +
                "[" + pathList + "]";
    }
}

