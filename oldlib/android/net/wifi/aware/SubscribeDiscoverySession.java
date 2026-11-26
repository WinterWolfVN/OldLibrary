package oldlib.android.net.wifi.aware;

import android.os.Handler;

public class SubscribeDiscoverySession extends DiscoverySession {

    SubscribeDiscoverySession(WifiAwareSession parent, int id, SubscribeConfig cfg, DiscoverySessionCallback cb, Handler h) {
        super(parent, id, cfg.serviceName, cfg.serviceSpecificInfo, cb, h);
    }

    public void updateSubscribe(SubscribeConfig subscribeConfig) {
        if (closed.get() || subscribeConfig == null) {
            handler.post(callback::onSessionConfigFailed);
            return;
        }
        this.serviceName = subscribeConfig.serviceName;
        this.serviceInfo = subscribeConfig.serviceSpecificInfo;
        handler.post(callback::onSessionConfigUpdated);
    }
}