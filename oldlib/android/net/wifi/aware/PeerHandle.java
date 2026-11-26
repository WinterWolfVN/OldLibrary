package oldlib.android.net.wifi.aware;

import java.net.InetAddress;

public class PeerHandle {
    public final int peerId;
    final int peerInstanceId;
    final InetAddress address;
    final int port;
    final byte[] macAddress;

    public PeerHandle(int peerId, int peerInstanceId, InetAddress address, int port, byte[] macAddress) {
        this.peerId = peerId;
        this.peerInstanceId = peerInstanceId;
        this.address = address;
        this.port = port;
        this.macAddress = macAddress;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public byte[] getMacAddress() {
        return macAddress != null ? macAddress.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerHandle)) return false;
        return peerId == ((PeerHandle) o).peerId;
    }

    @Override
    public int hashCode() {
        return peerId;
    }
}