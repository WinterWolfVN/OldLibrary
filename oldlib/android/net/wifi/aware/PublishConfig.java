package oldlib.android.net.wifi.aware;

import android.os.Parcel;
import android.os.Parcelable;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PublishConfig implements Parcelable {
    public static final int PUBLISH_TYPE_UNSOLICITED = 0;
    public static final int PUBLISH_TYPE_SOLICITED = 1;

    final String serviceName;
    final byte[] serviceSpecificInfo;
    final int publishType;

    PublishConfig(String serviceName, byte[] info, int type) {
        this.serviceName = serviceName;
        this.serviceSpecificInfo = info;
        this.publishType = type;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(serviceName);
        dest.writeByteArray(serviceSpecificInfo);
        dest.writeInt(publishType);
    }

    public static final Creator<PublishConfig> CREATOR = new Creator<PublishConfig>() {
        @Override
        public PublishConfig createFromParcel(Parcel in) {
            return new PublishConfig(in.readString(), in.createByteArray(), in.readInt());
        }

        @Override
        public PublishConfig[] newArray(int size) {
            return new PublishConfig[size];
        }
    };

    public static final class Builder {
        private String serviceName = "";
        private byte[] serviceSpecificInfo = new byte[0];
        private int publishType = PUBLISH_TYPE_UNSOLICITED;

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

        public Builder setPublishType(int publishType) {
            this.publishType = publishType;
            return this;
        }

        public PublishConfig build() {
            return new PublishConfig(serviceName, serviceSpecificInfo, publishType);
        }
    }
}