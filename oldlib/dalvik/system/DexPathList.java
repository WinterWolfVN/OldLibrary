package oldlib.dalvik.system;

import java.io.File;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public final class DexPathList {
    private final ClassLoader definingContext;
    private Element[] dexElements;
    private final List<File> nativeLibraryDirectories =  new ArrayList<File>();

    DexPathList(ClassLoader definingContext, String dexPath, String libraryPath, File optimizedDirectory) {
        this.definingContext = definingContext;
        this.dexElements = makeDexElements(dexPath,  optimizedDirectory);
    }

    DexPathList(ClassLoader definingContext, ByteBuffer[] dexFiles) {
        this.definingContext = definingContext;
        this.dexElements = makeInMemoryDexElements(dexFiles);
    }

    private Element[] makeInMemoryDexElements(ByteBuffer[] dexFiles) {
        if (dexFiles == null) {
            throw new NullPointerException("dexFiles == null");
        }
        Element[] elements = new Element[dexFiles.length];
        for (int i = 0; i < dexFiles.length; i++) {
            try {
                elements[i] = new Element(new DexFile(dexFiles[i]));
            } catch (Exception e) {
                throw new RuntimeException("Unable to load dex from memory", e);
            }
        }
        return elements;
    }

    private Element[] makeDexElements(String dexPath, File optimizedDirectory) {
        if (dexPath == null || dexPath.length() == 0) {
            return new Element[0];
        }
        ArrayList<Element> result = new ArrayList<Element>();
        String[] paths = dexPath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        for (String path : paths) {
            if (path.length() == 0) {
                continue;
            }

            try {
                dalvik.system.DexFile dex = dalvik.system.DexFile.loadDex(path, optimizedDirectory == null ? null : optimizedDirectory.getAbsolutePath(), 0);
                if (dex != null) {
                    result.add(new Element(dex));
                }
            } catch (Exception e) {
                throw new RuntimeException("Unable to load " + path, e);
            }
        }
        return result.toArray(new Element[result.size()]);
    }

    Class<?> findClass(String name, List<Throwable> suppressed) {
        for (Element element : dexElements) {
            try {
                Class<?> clazz = element.findClass(name, definingContext);
                if (clazz != null) {
                    return clazz;
                }
            } catch (Throwable e) {
                if (suppressed != null) {
                    suppressed.add(e);
                }
            }
        }
        return null;
    }

    public void addDexPath(String path, List<java.io.IOException> suppressed) {
        Element[] newElements = makeDexElements(path, null);
        Element[] old = dexElements;
        Element[] merged = new Element[old.length +newElements.length];
        System.arraycopy(old, 0, merged, 0, old.length);
        System.arraycopy(newElements, 0, merged, old.length, newElements.length);
        dexElements = merged;
    }

    public String findLibrary(String name) {
        return null;
    }

    public URL findResource(String name) {
        return null;
    }

    public Enumeration<URL> findResources(String name) {
        return Collections.enumeration(Collections.<URL>emptyList());
    }

    public List<File> getNativeLibraryDirectories() {
        return nativeLibraryDirectories;
    }

    public String toString() {
        return "DexPathList[" + dexElements.length + " elements]";
    }

    public static final class Element {
        private final dalvik.system.DexFile nativeDexFile;
        private final DexFile memoryDexFile;
        
        Element(dalvik.system.DexFile dexFile) {
            nativeDexFile = dexFile;
            memoryDexFile = null;
        }
        Element(DexFile dexFile) {
            nativeDexFile = null;
            memoryDexFile = dexFile;
        }
        Class<?> findClass(String name, ClassLoader loader) {
            if (memoryDexFile != null) {
                return memoryDexFile.loadClass(name, loader);
            }
            return nativeDexFile.loadClass(name, loader);
        }

        public String toString() {
            if (memoryDexFile != null) {
                return String.valueOf(memoryDexFile);
            }
            return String.valueOf(nativeDexFile);
        }
    }
}