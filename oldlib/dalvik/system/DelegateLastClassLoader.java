package oldlib.dalvik.system;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import dalvik.system.PathClassLoader;

public final class DelegateLastClassLoader extends PathClassLoader {
    private static final ClassLoader BOOT_LOADER = Object.class.getClassLoader();

    private final boolean mDelegateResourceLoading;
    private final InMemoryDexClassLoader mRamLoader;

    public DelegateLastClassLoader(String dexPath, ClassLoader parent) {
        this(dexPath, null, parent, true);
    }

    public DelegateLastClassLoader(String dexPath, String librarySearchPath, ClassLoader parent) {
        this(dexPath, librarySearchPath, parent, true);
    }

    public DelegateLastClassLoader(String dexPath, String librarySearchPath, ClassLoader parent, boolean delegateResourceLoading) {
        super(dexPath, librarySearchPath, parent);
        this.mDelegateResourceLoading = delegateResourceLoading;
        this.mRamLoader = null;
    }

    public DelegateLastClassLoader(ByteBuffer dexBuffer, ClassLoader parent) {
        super(File.pathSeparator, null, parent);
        this.mDelegateResourceLoading = false;
        this.mRamLoader = new InMemoryDexClassLoader(dexBuffer, BOOT_LOADER);
    }

    public DelegateLastClassLoader(ByteBuffer[] dexBuffers, ClassLoader parent) {
        super(File.pathSeparator, null, parent);
        this.mDelegateResourceLoading = false;
        this.mRamLoader = new InMemoryDexClassLoader(dexBuffers, BOOT_LOADER);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (mRamLoader != null) {
            return mRamLoader.findClass(name);
        }
        return super.findClass(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> cl = findLoadedClass(name);
        if (cl != null) {
            return cl;
        }
        try {
            return BOOT_LOADER.loadClass(name);
        } catch (ClassNotFoundException ignored) {}
        ClassNotFoundException fromSuper = null;
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            fromSuper = e;
        }
        ClassLoader parent = getParent();
        if (parent != null) {
            try {
                return parent.loadClass(name);
            } catch (ClassNotFoundException e) {
                throw e;
            }
        }
        throw fromSuper;
    }

    @Override
    public URL getResource(String name) {
        URL resource = BOOT_LOADER.getResource(name);
        if (resource != null) return resource;
        resource = findResource(name);
        if (resource != null) return resource;
        if (mDelegateResourceLoading) {
            ClassLoader parent = getParent();
            return (parent == null) ? null : parent.getResource(name);
        }
        return null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> resources = new ArrayList<>();
        addResources(resources, BOOT_LOADER.getResources(name));
        addResources(resources, findResources(name));
        if (mDelegateResourceLoading && getParent() != null) {
            addResources(resources, getParent().getResources(name));
        }
        return Collections.enumeration(resources);
    }

    private static void addResources(List<URL> out, Enumeration<URL> in) {
        while (in != null && in.hasMoreElements()) {
            out.add(in.nextElement());
        }
    }
}