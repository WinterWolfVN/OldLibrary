package oldlib.android.net.wifi.aware;

import android.os.Parcel;
import android.os.Parcelable;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class SubscribeConfig implements Parcelable {
    public static final int SUBSCRIBE_TYPE_PASSIVE = 0;
    public static final int SUBSCRIBE_TYPE_ACTIVE = 1;

    final String serviceName;
    final byte[] serviceSpecificInfo;
    final int subscribeType;

    SubscribeConfig(String serviceName, byte[] info, int type) {
        this.serviceName = serviceName;
        this.serviceSpecificInfo = info;
        this.subscribeType = type;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(serviceName);
        dest.writeByteArray(serviceSpecificInfo);
        dest.writeInt(subscribeType);
    }

    public static final Creator<SubscribeConfig> CREATOR = new Creator<SubscribeConfig>() {
        @Override
        public SubscribeConfig createFromParcel(Parcel in) {
            return new SubscribeConfig(in.readString(), in.createByteArray(), in.readInt());
        }

        @Override
        public SubscribeConfig[] newArray(int size) {
            return new SubscribeConfig[size];
        }
    };

    public static final class Builder {
        private String serviceName = "";
        private byte[] serviceSpecificInfo = new byte[0];
        private int subscribeType = SUBSCRIBE_TYPE_PASSIVE;

        public Builder setServiceName(String serviceName) {
            this.serviceName = Objects.requireNonNull(serviceName, "serviceName == null");
            return this;
        }

        public Builder setServiceSpecificInfo(byte[] info) {
            this.serviceSpecificInfo = info == null ? new byte[0] : info.clone();
            return this;
        }

        public Builder setServiceSpecificInfo(String infoStr) {
            this.serviceSpecificInfo = infoStr == null
                    ? new byte[0]
                    : infoStr.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        public Builder setSubscribeType(int subscribeType) {
            this.subscribeType = subscribeType;
            return this;
        }

        public SubscribeConfig build() {
            return new SubscribeConfig(serviceName, serviceSpecificInfo, subscribeType);
        }
    }
}