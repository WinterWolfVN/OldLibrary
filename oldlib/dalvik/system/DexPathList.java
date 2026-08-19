package oldlib.dalvik.system;

import oldlib.dalvik.system.DexFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

public final class DexPathList {
    private final ClassLoader definingContext;
    private Element[] dexElements;
    private final List<File> temporaryDexFiles = new ArrayList<File>();
    private final List<File> nativeLibraryDirectories = new ArrayList<File>();
    
    DexPathList(ClassLoader definingContext, String dexPath, String libraryPath, File optimizedDirectory) {
        this.definingContext = definingContext;
        if (dexPath == null) {
            dexPath = "";
        }
        this.dexElements = makeDexElements(dexPath, optimizedDirectory);
    }
    
    DexPathList(ClassLoader definingContext, ByteBuffer[] dexFiles) {
        this.definingContext = definingContext;
        // this.nativeLibraryDirectories = new File[0];
        // this.systemNativeLibraryDirectories = new File[0];
        // this.nativeLibraryPathElements = new NativeLibraryElement[0];
        this.dexElements = makeInMemoryDexElement(dexFiles);
    }

    private static Element[] makeInMemoryDexElement(ByteBuffer[] dexFiles) {
        Element[] elements = new Element[dexFiles.length];
     for (int i = 0; i < dexFiles.length; i++) {
         try {
             DexFile dex = new DexFile(dexFiles[i]);
             elements[i] = new Element(dex);
         } catch (IOException e) {
             throw new RuntimeException(
                    "Unable to load dex file from memory", e);
         }
     }
     return elements;
}

    private Element[] makeDexElements(String dexPath, File optimizedDirectory) {
        ArrayList<Element> result = new ArrayList<Element>();
        if (dexPath.length() == 0) {
            return new Element[0];
        }
        String[] paths = dexPath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        for (String path : paths) {
            if (path.length() == 0) {
                continue;
            }
            try {
                DexFile dex = DexFile.loadDex(path, optimizedDirectory == null ? null : optimizedDirectory.getAbsolutePath(), 0);
                if (dex != null) {
                    result.add(new Element(dex));
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to load " + path, e);
            }
        }
        return result.toArray(new Element[result.size()]);
    }

    /*
     * Android 7/API 25 has:
     *
     * DexFile.loadClass(
     *     String name,
     *     ClassLoader loader
     * )
     *
     * Do not use loadClassBinaryName().
     */
    Class<?> findClass(String name, List<Throwable> suppressed) {
        for (Element element : dexElements) {
            try {
                Class<?> clazz = element.dexFile.loadClass(name, definingContext);
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

    public void addDexPath(String path, List<IOException> suppressed) {
        Element[] newElements = makeDexElements(path, null);
        Element[] old = dexElements;
        Element[] merged = new Element[old.length + newElements.length];
        System.arraycopy(newElements, 0, merged, 0, newElements.length);
        System.arraycopy(old, 0, merged, newElements.length, old.length);
        dexElements = merged;
    }

    public String findLibrary(String name) {
        return null;
    }

    public URL findResource(String name) {
        return null;
    }

    public Enumeration<URL> findResources(String name)
            throws IOException {
        return Collections.enumeration(
                Collections.<URL>emptyList()
        );
    }

    public List<File>
    getNativeLibraryDirectories() {
        return nativeLibraryDirectories;
    }

    public String toString() {
        return "DexPathList[" + dexElements.length + " elements]";
    }

    public static final class Element {
        final DexFile dexFile;
        Element(DexFile dexFile) {
            this.dexFile = dexFile;
        }
    
        public String toString() {
            return String.valueOf(dexFile);
        }    
    }
}
  
