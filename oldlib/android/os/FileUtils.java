package oldlib.os;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

// Support Android 5+
public final class FileUtils {
    private static final Method sendFile;
    private static final long MAX_SEND_CHUNK = 0x7ffff000L; // Linux Kernel 1-shot limit (~2GB)

    static {
        Method sf = null;
        for (Method m : android.system.Os.class.getDeclaredMethods()) {
            if ("sendfile".equals(m.getName()) && m.getParameterTypes().length == 4) {
                m.setAccessible(true);
                sf = m;
                break;
            }
        }
        sendFile = sf;
    }

    private FileUtils() {}

    public static long copy(FileDescriptor in, FileDescriptor out) throws IOException {
        if (sendFile != null) {
            try {
                long total = 0;
                while (true) {
                    long sent = (long) sendFile.invoke(null, out, in, null, MAX_SEND_CHUNK);
                    if (sent <= 0) break;
                    total += sent;
                }
                if (total > 0) return total;
            } catch (InvocationTargetException ignored) {
            } catch (Exception ignored) {}
        }
        return copyFallback16K(new FileInputStream(in), new FileOutputStream(out));
    }

    public static long copy(InputStream in, OutputStream out) throws IOException {
        if (in instanceof FileInputStream && out instanceof FileOutputStream) {
            return copy(((FileInputStream) in).getFD(), ((FileOutputStream) out).getFD());
        }
        return copyFallback16K(in, out);
    }

    public static long copy(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            return copy(in.getFD(), out.getFD());
        }
    }

    private static long copyFallback16K(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }
}