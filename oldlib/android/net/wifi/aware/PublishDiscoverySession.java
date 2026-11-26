package oldlib.android.net.wifi.aware;

import android.os.Handler;

public class PublishDiscoverySession extends DiscoverySession {

    PublishDiscoverySession(WifiAwareSession parent, int id, PublishConfig cfg, DiscoverySessionCallback cb, Handler h) {
        super(parent, id, cfg.serviceName, cfg.serviceSpecificInfo, cb, h);
    }

    public void updatePublish(PublishConfig publishConfig) {
        if (closed.get() || publishConfig == null) {
            handler.post(callback::onSessionConfigFailed);
            return;
        }
        this.serviceName = publishConfig.serviceName;
        this.serviceInfo = publishConfig.serviceSpecificInfo;
        handler.post(callback::onSessionConfigUpdated);
    }
}