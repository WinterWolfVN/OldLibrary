package oldlib.libcore.io;

import libcore.io.Os;

public static volatile Os os;

public static boolean compareAndSetOs(Os expect, Os update) {
    if (update == null) {
        throw new NullPointerException("update == null");
    }
    if (os != expect) {
        return false;
    }
    synchronized (Libcore.class) {
        if (os == expect) {
            os = update;
            return true;
        }
        return false;
    }
}
