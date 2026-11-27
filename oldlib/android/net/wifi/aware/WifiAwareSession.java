package oldlib.android.net.wifi.aware;

import android.app.Application;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// Test
// Support Android 5-9
public class WifiAwareSession implements Closeable {
    static final int NAN_PORT = 47009;
    static final byte PKT_BEACON = 1;
    static final byte PKT_SUBSCRIBE_MATCH = 2;
    static final byte PKT_MESSAGE = 3;

    private static final int PACKET_BUFFER_SIZE = 4096;
    private static final int MINIMUM_HEADER_SIZE = 17; // 4B Magic + 1B Type + 4B PeerId + 4B IP + 4B Lengths
    private static final long HEARTBEAT_INTERVAL_MILLIS = 2000L;

    private final WifiAwareManager awareManager;
    private final AtomicBoolean sessionClosed = new AtomicBoolean(false);
    private final Object transmitLock = new Object();

    private final WifiManager systemWifiManager;
    private WifiManager.MulticastLock hardwareMulticastLock;
    private WifiManager.WifiLock highPerformanceWifiLock;

    private volatile byte[] localHostIpBytes = new byte[]{127, 0, 0, 1};
    private volatile InetAddress subnetBroadcastAddress;

    private final FileDescriptor socketDescriptor;
    private final byte[] transmitBuffer = new byte[PACKET_BUFFER_SIZE];
    private final byte[] receiveBuffer = new byte[PACKET_BUFFER_SIZE];

    final Map<Integer, PublishDiscoverySession> publishSessions = new ConcurrentHashMap<>();
    final Map<Integer, SubscribeDiscoverySession> subscribeSessions = new ConcurrentHashMap<>();
    final Map<Integer, InetAddress> knownPeerAddresses = new ConcurrentHashMap<>();

    public WifiAwareSession(WifiAwareManager awareManager) {
        this.awareManager = awareManager;
        this.systemWifiManager = obtainSystemWifiManager();
        acquireWifiHardwareLocks();
        refreshWifiNetworkState();
        try {
            this.socketDescriptor = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP);
            Os.setsockoptInt(this.socketDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1);
            Os.setsockoptInt(this.socketDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_BROADCAST, 1);
            Os.bind(this.socketDescriptor, InetAddress.getByName("0.0.0.0"), NAN_PORT);
        } catch (Exception exception) {
            close();
            throw new RuntimeException("Unable to open network port for WifiAwareSession", exception);
        }
        startPacketReceiverThread();
        startHeartbeatThread();
    }

    // Android API
    public void publish(PublishConfig publishConfig, DiscoverySessionCallback callback, Handler handler) {
        if (sessionClosed.get()) return;
        Handler dispatchHandler = (handler != null) ? handler : new Handler(Looper.getMainLooper());
        final PublishDiscoverySession publisherSession = new PublishDiscoverySession(this, publishConfig.serviceName, publishConfig.serviceSpecificInfo, callback, dispatchHandler);
        final int sessionIdentity = System.identityHashCode(publisherSession);
        publishSessions.put(sessionIdentity, publisherSession);
        dispatchHandler.post(() -> {
            callback.onPublishStarted(publisherSession);
            sendMappedPacket(PKT_BEACON, publisherSession.serviceName, sessionIdentity, publisherSession.serviceInfo, subnetBroadcastAddress, NAN_PORT, publisherSession);
        });
    }

    public void subscribe(SubscribeConfig subscribeConfig, DiscoverySessionCallback callback, Handler handler) {
        if (sessionClosed.get()) return;
        Handler dispatchHandler = (handler != null) ? handler : new Handler(Looper.getMainLooper());
        final SubscribeDiscoverySession subscriberSession = new SubscribeDiscoverySession(this, subscribeConfig.serviceName, subscribeConfig.serviceSpecificInfo, callback, dispatchHandler);
        subscribeSessions.put(System.identityHashCode(subscriberSession), subscriberSession);
        dispatchHandler.post(() -> callback.onSubscribeStarted(subscriberSession));
    }

    void unregisterSession(DiscoverySession session) {
        int sessionIdentity = System.identityHashCode(session);
        publishSessions.remove(sessionIdentity);
        subscribeSessions.remove(sessionIdentity);
    }

    InetAddress resolvePeerAddress(int peerId) {
        return knownPeerAddresses.get(peerId);
    }

    // Encapsulate and transmit NAN frames
    void sendMappedPacket(byte packetType, String serviceName, int senderPeerId, byte[] payload, InetAddress targetAddress, int targetPort, DiscoverySession originSession) {
        if (sessionClosed.get() || socketDescriptor == null || !socketDescriptor.valid()) return;
        byte[] serviceNameBytes = (serviceName != null) ? serviceName.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] payloadBytes = (payload != null) ? payload : new byte[0];
        int effectiveSenderId = (originSession != null) ? System.identityHashCode(originSession) : senderPeerId;
        int totalPacketLength = MINIMUM_HEADER_SIZE + serviceNameBytes.length + payloadBytes.length;
        if (totalPacketLength > transmitBuffer.length) return;

        synchronized (transmitLock) {
            try {
                ByteBuffer packetWriter = ByteBuffer.wrap(transmitBuffer);

                // 1. Framework signature IEEE 802.11 NAN (50-6F-9A-13)
                packetWriter.put((byte) 0x50);
                packetWriter.put((byte) 0x6F);
                packetWriter.put((byte) 0x9A);
                packetWriter.put((byte) 0x13);

                // 2. Packet type + Send session identifier + 4 bytes IPv4 Internal Wi-Fi (serving Os.read)
                packetWriter.put(packetType);
                packetWriter.putInt(effectiveSenderId);
                packetWriter.put(localHostIpBytes, 0, 4);

                // 3. Length and content Service name + Attached data
                packetWriter.putShort((short) serviceNameBytes.length);
                packetWriter.put(serviceNameBytes);
                packetWriter.putShort((short) payloadBytes.length);
                packetWriter.put(payloadBytes);
                InetAddress effectiveTarget = (targetAddress != null) ? targetAddress : subnetBroadcastAddress;
                if (effectiveTarget != null) {
                    Os.sendto(socketDescriptor, transmitBuffer, 0, totalPacketLength, 0, effectiveTarget, targetPort);
                } else {
                    Os.write(socketDescriptor, transmitBuffer, 0, totalPacketLength);
                }
            } catch (Exception ignored) {}
        }
    }

    // Two main threads
    private void startHeartbeatThread() {
        Thread beaconThread = new Thread(() -> {
            try {
                while (!sessionClosed.get()) {
                    refreshWifiNetworkState();
                    for (PublishDiscoverySession publisherSession : publishSessions.values()) {
                        int publisherId = System.identityHashCode(publisherSession);
                        sendMappedPacket(PKT_BEACON, publisherSession.serviceName, publisherId, publisherSession.serviceInfo, subnetBroadcastAddress, NAN_PORT, publisherSession);
                    }
                    Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                }
            } catch (Exception ignored) {}
        }, "WifiAware-Beacon");
        beaconThread.setDaemon(true);
        beaconThread.start();
    }

    private void startPacketReceiverThread() {
        Thread receiverThread = new Thread(() -> {
            while (!sessionClosed.get() && socketDescriptor != null && socketDescriptor.valid()) {
                try {
                    // publisherSessionUse Os.read() directly to read the byte[] array directly from the network port
                    int bytesRead = Os.read(socketDescriptor, receiveBuffer, 0, receiveBuffer.length);
                    if (bytesRead < MINIMUM_HEADER_SIZE) continue;
                    byte[] packetCopy = new byte[bytesRead];
                    System.arraycopy(receiveBuffer, 0, packetCopy, 0, bytesRead);
                    handleIncomingPacket(ByteBuffer.wrap(packetCopy));
                } catch (Exception ignored) {}
            }
        }, "WifiAware-Receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    // Decode NAN signal framework
    private void handleIncomingPacket(ByteBuffer packetBuffer) {
        try {
            if (packetBuffer.remaining() < MINIMUM_HEADER_SIZE) return;
            if (packetBuffer.get() != (byte) 0x50 || packetBuffer.get() != (byte) 0x6F || packetBuffer.get() != (byte) 0x9A || packetBuffer.get() != (byte) 0x13) {
                return;
            }
            byte packetType = packetBuffer.get();
            final int senderPeerId = packetBuffer.getInt();

            // Ignore if the packet is emitted by the sessions on your phone
            if (publishSessions.containsKey(senderPeerId) || subscribeSessions.containsKey(senderPeerId)) {
                return;
            }

            // phoneExtract the 4 IPv4 bytes of the sending machine embedded in the Header
            byte[] embeddedSenderIp = new byte[4];
            packetBuffer.get(embeddedSenderIp);
            knownPeerAddresses.put(senderPeerId, InetAddress.getByAddress(embeddedSenderIp));
            int serviceNameLength = packetBuffer.getShort() & 0xFFFF;
            if (serviceNameLength > packetBuffer.remaining()) return;
            byte[] serviceNameBytes = new byte[serviceNameLength];
            packetBuffer.get(serviceNameBytes);
            String incomingServiceName = new String(serviceNameBytes, StandardCharsets.UTF_8);
            int payloadLength = packetBuffer.getShort() & 0xFFFF;
            if (payloadLength > packetBuffer.remaining()) return;
            final byte[] payloadBytes = new byte[payloadLength];
            packetBuffer.get(payloadBytes);
            final PeerHandle peerHandle = new PeerHandle(senderPeerId);
            if (packetType == PKT_BEACON) {
                List<byte[]> serviceIdFilter = Collections.singletonList(computeServiceIdHash(incomingServiceName));
                for (final SubscribeDiscoverySession subscriberSession : subscribeSessions.values()) {
                    if (incomingServiceName.equalsIgnoreCase(subscriberSession.serviceName)) {
                        subscriberSession.handler.post(() -> subscriberSession.callback.onServiceDiscovered(peerHandle, payloadBytes, serviceIdFilter));
                    }
                }
            } else if (packetType == PKT_MESSAGE || packetType == PKT_SUBSCRIBE_MATCH) {
                for (final SubscribeDiscoverySession subscriberSession : subscribeSessions.values()) {
                    if (incomingServiceName.equalsIgnoreCase(subscriberSession.serviceName)) {
                        subscriberSession.handler.post(() -> subscriberSession.callback.onMessageReceived(
                                peerHandle, payloadBytes));
                    }
                }
                for (final PublishDiscoverySession publisherSession : publishSessions.values()) {
                    if (incomingServiceName.equalsIgnoreCase(publisherSession.serviceName)) {
                        publisherSession.handler.post(() -> publisherSession.callback.onMessageReceived(
                                peerHandle, payloadBytes));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // Manage WifiAware
    private void acquireWifiHardwareLocks() {
        if (systemWifiManager == null) return;
        try {
            hardwareMulticastLock = systemWifiManager.createMulticastLock("WifiAware-Multicast");
            hardwareMulticastLock.setReferenceCounted(false);
            hardwareMulticastLock.acquire();
        } catch (Throwable ignored) {}

        try {
            highPerformanceWifiLock = systemWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WifiAware-HighPerf");
            highPerformanceWifiLock.setReferenceCounted(false);
            highPerformanceWifiLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseWifiHardwareLocks() {
        try {
            if (hardwareMulticastLock != null && hardwareMulticastLock.isHeld()) {
                hardwareMulticastLock.release();
            }
        } catch (Throwable ignored) {}
        try {
            if (highPerformanceWifiLock != null && highPerformanceWifiLock.isHeld()) {
                highPerformanceWifiLock.release();
            }
        } catch (Throwable ignored) {}
    }

    private void refreshWifiNetworkState() {
        try {
            if (systemWifiManager != null) {
                WifiInfo connectionInfo = systemWifiManager.getConnectionInfo();
                DhcpInfo dhcpInfo = systemWifiManager.getDhcpInfo();
                if (connectionInfo != null && connectionInfo.getIpAddress() != 0) {
                    int ipInt = connectionInfo.getIpAddress();
                    this.localHostIpBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array();
                    int netmask = (dhcpInfo != null && dhcpInfo.netmask != 0) ? dhcpInfo.netmask : 0x00FFFFFF;
                    int broadcastInt = (ipInt & netmask) | ~netmask;
                    byte[] broadcastBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(broadcastInt).array();
                    this.subnetBroadcastAddress = InetAddress.getByAddress(broadcastBytes);
                }
            }
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces != null && networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;
                String interfaceName = networkInterface.getName().toLowerCase();
                boolean isWifiOrHotspot = interfaceName.startsWith("wlan") || interfaceName.startsWith("ap") || interfaceName.startsWith("swlan");
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (address instanceof Inet4Address && isWifiOrHotspot) {
                        this.localHostIpBytes = address.getAddress();
                        if (interfaceAddress.getBroadcast() != null) {
                            this.subnetBroadcastAddress = interfaceAddress.getBroadcast();
                        }
                    }
                }
            }
            if (this.subnetBroadcastAddress == null) {
                this.subnetBroadcastAddress = InetAddress.getByName("255.255.255.255");
            }
        } catch (Throwable ignored) {}
    }

    private static WifiManager obtainSystemWifiManager() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication");
            currentApplicationMethod.setAccessible(true);
            Application application = (Application) currentApplicationMethod.invoke(null);
            if (application != null) {
                return (WifiManager) application.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public void close() {
        if (!sessionClosed.compareAndSet(false, true)) return;
        publishSessions.clear();
        subscribeSessions.clear();
        knownPeerAddresses.clear();
        releaseWifiHardwareLocks();
        if (socketDescriptor != null && socketDescriptor.valid()) {
            try { Os.close(socketDescriptor); } catch (Exception ignored) {}
        }
    }

    private static byte[] computeServiceIdHash(String serviceName) {
        byte[] sixByteHash = new byte[6];
        try {
            MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");
            byte[] fullDigest = sha256Digest.digest(serviceName.toLowerCase().getBytes(StandardCharsets.UTF_8));
            System.arraycopy(fullDigest, 0, sixByteHash, 0, 6);
        } catch (Exception ignored) {}
        return sixByteHash;
    }
}