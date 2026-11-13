package oldlib.dalvik.system;

import android.annotation.SuppressLint;
import android.content.Context;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.PathClassLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.UUID;

// This is just a temporary version; I'm actually very tired from doing so much research.
public class InMemoryDexClassLoader extends BaseDexClassLoader {

    private static final String TEMP_DEX_PREFIX = ".compat_dex_";
    private static volatile boolean sCleanedUp = false;

    private static ClassLoader resolveParent(ClassLoader parent) {
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        if (boot == null) {
            boot = ClassLoader.getSystemClassLoader();
        }
        if (parent == null || parent == ClassLoader.getSystemClassLoader()  || parent instanceof dalvik.system.PathClassLoader || parent.getClass().getName().endsWith("PathClassLoader")) {
            return boot;
        }
        return parent;
    }
    
    /* private static ClassLoader resolveParent(ClassLoader parent) {
        if (parent != null) {
            return parent;
        }
        // Chuẩn hành vi Android 8+: khi parent == null thì phải là BootClassLoader!
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        return boot != null ? boot : ClassLoader.getSystemClassLoader();
    } Dùng bình thường */ 

    public InMemoryDexClassLoader(ByteBuffer[] dexBuffers, String librarySearchPath, ClassLoader parent) {
        super(writeBuffersToDexPath(dexBuffers), null, librarySearchPath, resolveParent(parent));
    }

    public InMemoryDexClassLoader(ByteBuffer dexBuffer, ClassLoader parent) {
        this(new ByteBuffer[]{dexBuffer}, null, parent);
    }

    public InMemoryDexClassLoader(ByteBuffer[] dexBuffers, ClassLoader parent) {
        this(dexBuffers, null, parent);
    }

    public InMemoryDexClassLoader(byte[] dexBytes, ClassLoader parent) {
        this(new ByteBuffer[]{ByteBuffer.wrap(dexBytes)}, null, parent);
    }

    public InMemoryDexClassLoader(byte[] dexBytes) {
        this(new ByteBuffer[]{ByteBuffer.wrap(dexBytes)}, null, null);
    }

    public InMemoryDexClassLoader(ByteBuffer dexBuffer) {
        this(new ByteBuffer[]{dexBuffer}, null, null);
    }

    private static String writeBuffersToDexPath(ByteBuffer[] dexBuffers) {
        File codeCacheDir = getCodeCacheDir();
        ensureCleanupOrphanFiles(codeCacheDir);
        StringBuilder dexPathBuilder = new StringBuilder();
        try {
            for (int i = 0; i < dexBuffers.length; i++) {
                ByteBuffer buffer = dexBuffers[i];
                File tempFile = new File(codeCacheDir, TEMP_DEX_PREFIX + UUID.randomUUID().toString().replace("-", "") + ".dex");
                try (FileOutputStream fos = new FileOutputStream(tempFile);
                     FileChannel channel = fos.getChannel()) {
                    ByteBuffer slice = buffer.duplicate();
                    while (slice.hasRemaining()) {
                        channel.write(slice);
                    }
                    channel.force(true);
                }
                tempFile.setReadable(false, false);
                tempFile.setReadable(true, true);
                tempFile.setWritable(false, false);
                tempFile.setWritable(true, true);
                tempFile.setExecutable(false, false);
                tempFile.deleteOnExit();
                if (i > 0) {
                    dexPathBuilder.append(File.pathSeparator);
                }
                dexPathBuilder.append(tempFile.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error during temporary DEX creation", e);
        }
        return dexPathBuilder.toString();
    }

    @SuppressLint("PrivateApi")
    private static File getCodeCacheDir() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method method = activityThreadClass.getDeclaredMethod("currentApplication");
            Context app = (Context) method.invoke(null);
            if (app != null) {
                return app.getCodeCacheDir();
            }
            Method currentAtMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
            Object currentAt = currentAtMethod.invoke(null);
            Field boundField = activityThreadClass.getDeclaredField("mBoundApplication");
            boundField.setAccessible(true);
            Object boundData = boundField.get(currentAt);
            if (boundData != null) {
                Field infoField = boundData.getClass().getDeclaredField("appInfo");
                infoField.setAccessible(true);
                android.content.pm.ApplicationInfo appInfo = (android.content.pm.ApplicationInfo) infoField.get(boundData);
                if (appInfo != null && appInfo.dataDir != null) {
                    File dir = new File(appInfo.dataDir, "code_cache");
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    return dir;
                }
            }
        } catch (Throwable ignored) {}
        return new File(System.getProperty("java.io.tmpdir", "/data/local/tmp"));
    }

    private static void ensureCleanupOrphanFiles(File codeCacheDir) {
        if (sCleanedUp) return;
        sCleanedUp = true;
        final long now = System.currentTimeMillis();
        new Thread(() -> {
            try {
                File[] orphanFiles = codeCacheDir.listFiles((d, name) -> name.startsWith(TEMP_DEX_PREFIX) && name.endsWith(".dex"));
                if (orphanFiles != null) {
                    for (File f : orphanFiles) {
                        // 10 minutes
                        if (now - f.lastModified() > 600_000L) {
                            f.delete();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }, "CompatDexCleaner").start();
    }
}