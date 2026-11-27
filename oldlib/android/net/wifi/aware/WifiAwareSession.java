package oldlib.android.net.wifi.aware;

import android.os.Handler;
import android.os.Looper;

import java.io.Closeable;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

// Test
// Support Android 5-9
public class WifiAwareSession implements Closeable {
    static final int NAN_PORT = WifiAwareCore.NAN_PORT;
    static final byte PKT_BEACON = WifiAwareCore.PKT_BEACON;
    static final byte PKT_SUBSCRIBE_MATCH = WifiAwareCore.PKT_SUBSCRIBE_MATCH;
    static final byte PKT_MESSAGE = WifiAwareCore.PKT_MESSAGE;

    private final WifiAwareManager awareManager;
    private final WifiAwareCore coreEngine;
    private final AtomicBoolean sessionClosed = new AtomicBoolean(false);

    public WifiAwareSession(WifiAwareManager awareManager) {
        this.awareManager = awareManager;
        this.coreEngine = new WifiAwareCore(this);
    }

    public void publish(PublishConfig publishConfig, DiscoverySessionCallback callback, Handler handler) {
        if (sessionClosed.get() || publishConfig == null || callback == null) return;
        Handler dispatchHandler = (handler != null) ? handler : new Handler(Looper.getMainLooper());
        coreEngine.startPublish(publishConfig, callback, dispatchHandler);
    }

    public void subscribe(SubscribeConfig subscribeConfig, DiscoverySessionCallback callback, Handler handler) {
        if (sessionClosed.get() || subscribeConfig == null || callback == null) return;
        Handler dispatchHandler = (handler != null) ? handler : new Handler(Looper.getMainLooper());
        coreEngine.startSubscribe(subscribeConfig, callback, dispatchHandler);
    }

    @Override
    public void close() {
        if (!sessionClosed.compareAndSet(false, true)) return;
        coreEngine.shutdown();
    }

    void unregisterSession(DiscoverySession session) {
        coreEngine.unregisterDiscoverySession(session);
    }

    InetAddress resolvePeerAddress(int peerId) {
        return coreEngine.resolvePeerAddress(peerId);
    }

    void sendMappedPacket(byte packetType, String serviceName, int targetPeerId, byte[] payload, InetAddress targetAddress, int targetPort, DiscoverySession originSession) {
        coreEngine.dispatchSessionPacket(packetType, serviceName, targetPeerId, payload, targetAddress, targetPort, originSession);
    }
}