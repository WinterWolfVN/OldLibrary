package oldlib.android.net.wifi.aware;

import android.os.Handler;
import android.os.Looper;
import java.io.Closeable;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class DiscoverySession implements Closeable {
    final WifiAwareSession parentSession;
    final int sessionId;
    String serviceName;
    byte[] serviceInfo;
    final DiscoverySessionCallback callback;
    final Handler handler;
    final AtomicBoolean closed = new AtomicBoolean(false);

    DiscoverySession(WifiAwareSession parent, int id, String name, byte[] info, DiscoverySessionCallback cb, Handler h) {
        this.parentSession = parent;
        this.sessionId = id;
        this.serviceName = name;
        this.serviceInfo = info;
        this.callback = cb;
        this.handler = h != null ? h : new Handler(Looper.getMainLooper());
    }

    public void sendMessage(PeerHandle peerHandle, int messageId, byte[] message) {
        if (closed.get() || peerHandle == null) {
            handler.post(() -> callback.onMessageSendFailed(messageId));
            return;
        }
        byte[] payload = message == null ? new byte[0] : message;
        parentSession.sendMappedPacket(WifiAwareSession.PKT_MESSAGE, serviceName, messageId, payload, peerHandle.address, peerHandle.port, this);
    }

    public void sendSharedFile(PeerHandle peerHandle, int messageId, File file, long offset, int length) {
        if (closed.get() || peerHandle == null) {
            handler.post(() -> callback.onMessageSendFailed(messageId));
            return;
        }
        parentSession.sendFileViaSharedMmap(
                serviceName,
                messageId,
                file,
                offset,
                length,
                peerHandle.address,
                peerHandle.port,
                this
        );
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            parentSession.removeDiscoverySession(sessionId);
            handler.post(callback::onSessionTerminated);
        }
    }
}