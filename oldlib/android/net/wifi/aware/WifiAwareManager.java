package oldlib.android.net.wifi.aware;

import android.os.Handler;
import android.os.Looper;
import java.util.Objects;

public class WifiAwareManager {
    public static final String ACTION_WIFI_AWARE_STATE_CHANGED = "android.net.wifi.aware.action.WIFI_AWARE_STATE_CHANGED";
    private static final WifiAwareManager instance = new WifiAwareManager();

    public static WifiAwareManager getInstance() {
        return instance;
    }

    public boolean isAvailable() {
        return true;
    }

    public void attach(AttachCallback attachCallback, Handler handler) {
        Objects.requireNonNull(attachCallback, "attachCallback == null");
        Handler h = handler != null ? handler : new Handler(Looper.getMainLooper());
        try {
            WifiAwareSession session = new WifiAwareSession();
            h.post(() -> attachCallback.onAttached(session));
        } catch (Exception e) {
            h.post(attachCallback::onAttachFailed);
        }
    }
}