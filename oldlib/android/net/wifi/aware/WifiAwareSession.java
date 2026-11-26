package oldlib.android.net.wifi.aware;

import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.lang.reflect.Constructor;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// Test 
// Support Android 5-7.1 (Android 8-9 on some device not has Wifi Aware)
public class WifiAwareSession implements Closeable {
    static final int NAN_PORT = 47009;
    static final byte PKT_BEACON = 1;
    static final byte PKT_SUBSCRIBE_MATCH = 2;
    static final byte PKT_MESSAGE = 3;

    private static final int WINDOW_CAPACITY = 16384; // 16KB per bay
    private static final int TOTAL_SHARED_MEMORY_SIZE = WINDOW_CAPACITY * 3; // Total 48KB
    private static final int MINIMUM_FRAME_HEADER_SIZE = 17; // 4B Magic + 1B Type + 4B PeerId + 4B IP + 4B Lengths
    private static final long HEARTBEAT_INTERVAL_MILLIS = 2000L;

    private final WifiAwareManager awareManager;
    private final AtomicBoolean sessionClosed = new AtomicBoolean(false);
    private final Object transmitLock = new Object();
    private final byte[] localHostIpBytes;

    private final FileDescriptor datagramSocketDescriptor;
    private final FileDescriptor snifferSocketDescriptor;
    private final boolean rawPacketModeEnabled;

    private long sharedMemoryBaseAddress;
    private final ByteBuffer transmitSharedBuffer;
    private final ByteBuffer datagramReceiveBuffer;
    private final ByteBuffer snifferReceiveBuffer;

    final Map<Integer, PublishDiscoverySession> publishSessions = new ConcurrentHashMap<>();
    final Map<Integer, SubscribeDiscoverySession> subscribeSessions = new ConcurrentHashMap<>();
    final Map<Integer, InetAddress> knownPeerAddresses = new ConcurrentHashMap<>();

    public WifiAwareSession(WifiAwareManager awareManager) {
        this.awareManager = awareManager;
        this.localHostIpBytes = resolveLocalHostIpv4();

        try {        
            int protection = OsConstants.PROT_READ | OsConstants.PROT_WRITE;
            int mappingFlags = OsConstants.MAP_SHARED | OsConstants.MAP_ANONYMOUS;
            this.sharedMemoryBaseAddress = Os.mmap(0L, TOTAL_SHARED_MEMORY_SIZE, protection, mappingFlags, new FileDescriptor(), 0L);

            this.transmitSharedBuffer = wrapSharedMemoryWindow(sharedMemoryBaseAddress, WINDOW_CAPACITY);
            this.datagramReceiveBuffer = wrapSharedMemoryWindow(sharedMemoryBaseAddress + WINDOW_CAPACITY, WINDOW_CAPACITY);
            this.snifferReceiveBuffer = wrapSharedMemoryWindow(sharedMemoryBaseAddress + (WINDOW_CAPACITY * 2L), WINDOW_CAPACITY);

            this.datagramSocketDescriptor = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP);
            Os.setsockoptInt(this.datagramSocketDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1);
            Os.setsockoptInt(this.datagramSocketDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_BROADCAST, 1);
            Os.bind(this.datagramSocketDescriptor, InetAddress.getByName("0.0.0.0"), NAN_PORT);
        } catch (Exception exception) {
            close();
            throw new RuntimeException("Unable to initialize shared memory or network port for WifiAwareSession", exception);
        }
        FileDescriptor openedSnifferDescriptor = null;
        boolean afPacketGranted = false;
        try {
            int allEthernetProtocols = Short.reverseBytes((short) OsConstants.ETH_P_ALL) & 0xFFFF;
            openedSnifferDescriptor = Os.socket(OsConstants.AF_PACKET, OsConstants.SOCK_RAW, allEthernetProtocols);
            afPacketGranted = true;
        } catch (Exception permissionDenied) {      
            try {
                openedSnifferDescriptor = Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP);
                Os.setsockoptInt(openedSnifferDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1);
                Os.bind(openedSnifferDescriptor, InetAddress.getByName("::"), NAN_PORT);
                Os.connect(openedSnifferDescriptor, InetAddress.getByName("ff02::1"), NAN_PORT);
            } catch (Exception ignored) {
                openedSnifferDescriptor = null;
            }
        }
        this.snifferSocketDescriptor = openedSnifferDescriptor;
        this.rawPacketModeEnabled = afPacketGranted;

        startUdpReceiverThread();
        if (this.snifferSocketDescriptor != null) {
            startRawCpuSnifferThread();
        }
        startHeartbeatThread();
    }

    public void publish(PublishConfig publishConfig, DiscoverySessionCallback callback, Handler handler) {
        if (sessionClosed.get()) return;
        Handler dispatchHandler = (handler != null) ? handler : new Handler(Looper.getMainLooper());
        final PublishDiscoverySession publisherSession = new PublishDiscoverySession(this, publishConfig.serviceName, publishConfig.serviceSpecificInfo, callback, dispatchHandler);
        final int sessionIdentity = System.identityHashCode(publisherSession);
        publishSessions.put(sessionIdentity, publisherSession);
        dispatchHandler.post(() -> {
            callback.onPublishStarted(publisherSession);
            try {
                InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
                sendMappedPacket(PKT_BEACON, publisherSession.serviceName, sessionIdentity, publisherSession.serviceInfo, broadcastAddress, NAN_PORT, publisherSession);
                if (!rawPacketModeEnabled && snifferSocketDescriptor != null) {
                    sendMappedPacket(PKT_BEACON, publisherSession.serviceName, sessionIdentity, publisherSession.serviceInfo, null, NAN_PORT, publisherSession);
                }
            } catch (Exception ignored) {}
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

    void sendMappedPacket(byte packetType, String serviceName, int senderPeerId, byte[] payload, InetAddress targetAddress, int targetPort, DiscoverySession originSession) {
        if (sessionClosed.get() || transmitSharedBuffer == null) return;
        byte[] serviceNameBytes = (serviceName != null) ? serviceName.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] payloadBytes = (payload != null) ? payload : new byte[0];
        int effectiveSenderId = (originSession != null) ? System.identityHashCode(originSession) : senderPeerId;
        synchronized (transmitLock) {
            try {
                transmitSharedBuffer.clear();

                transmitSharedBuffer.put((byte) 0x50);
                transmitSharedBuffer.put((byte) 0x6F);
                transmitSharedBuffer.put((byte) 0x9A);
                transmitSharedBuffer.put((byte) 0x13);

                transmitSharedBuffer.put(packetType);
                transmitSharedBuffer.putInt(effectiveSenderId);
                transmitSharedBuffer.put(localHostIpBytes, 0, 4);

                transmitSharedBuffer.putShort((short) serviceNameBytes.length);
                transmitSharedBuffer.put(serviceNameBytes);
                transmitSharedBuffer.putShort((short) payloadBytes.length);
                transmitSharedBuffer.put(payloadBytes);
                transmitSharedBuffer.flip();

                if (targetAddress == null) {               
                    if (snifferSocketDescriptor != null && snifferSocketDescriptor.valid()) {
                        Os.write(snifferSocketDescriptor, transmitSharedBuffer);
                    }
                } else if (datagramSocketDescriptor != null && datagramSocketDescriptor.valid()) {              
                    Os.sendto(datagramSocketDescriptor, transmitSharedBuffer, 0, targetAddress, targetPort);
                }
            } catch (Exception ignored) {}
        }
    }

    private void startHeartbeatThread() {
        Thread beaconThread = new Thread(() -> {
            try {
                InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
                while (!sessionClosed.get()) {
                    for (PublishDiscoverySession publisherSession : publishSessions.values()) {
                        int publisherId = System.identityHashCode(publisherSession);                  
                        sendMappedPacket(PKT_BEACON, publisherSession.serviceName, publisherId, publisherSession.serviceInfo, broadcastAddress, NAN_PORT, publisherSession);                  
                        if (!rawPacketModeEnabled && snifferSocketDescriptor != null) {
                            sendMappedPacket(PKT_BEACON, publisherSession.serviceName, publisherId, publisherSession.serviceInfo, null, NAN_PORT, publisherSession);
                        }
                    }
                    Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                }
            } catch (Exception ignored) {}
        }, "Oldlib-WifiAware-Beacon");
        beaconThread.setDaemon(true);
        beaconThread.start();
    }

    private void startUdpReceiverThread() {
        Thread udpReceiverThread = new Thread(() -> {
            InetSocketAddress senderSocketAddress = new InetSocketAddress(0);
            byte[] receivedFrameBytes = new byte[2048];
            while (!sessionClosed.get() && datagramSocketDescriptor != null && datagramSocketDescriptor.valid()) {
                try {
                    datagramReceiveBuffer.clear();
                    int bytesTransferred = Os.recvfrom(datagramSocketDescriptor, datagramReceiveBuffer, 0, senderSocketAddress);
                    if (bytesTransferred < MINIMUM_FRAME_HEADER_SIZE) continue;
                    int copyLength = Math.min(bytesTransferred, receivedFrameBytes.length);
                    datagramReceiveBuffer.position(0);
                    datagramReceiveBuffer.get(receivedFrameBytes, 0, copyLength);
                    handleIncomingMappedPacket(ByteBuffer.wrap(receivedFrameBytes, 0, copyLength), senderSocketAddress.getAddress());
                } catch (Exception ignored) {}
            }
        }, "Oldlib-WifiAware-UdpReceiver");
        udpReceiverThread.setDaemon(true);
        udpReceiverThread.start();
    }

    private void startRawCpuSnifferThread() {
        Thread rawSnifferThread = new Thread(() -> {
            byte[] capturedFrameBytes = new byte[2048];
            while (!sessionClosed.get() && snifferSocketDescriptor != null && snifferSocketDescriptor.valid()) {
                try {
                    snifferReceiveBuffer.clear();              
                    int bytesTransferred = Os.read(snifferSocketDescriptor, snifferReceiveBuffer);
                    if (bytesTransferred < MINIMUM_FRAME_HEADER_SIZE) continue;
                    int copyLength = Math.min(bytesTransferred, capturedFrameBytes.length);
                    snifferReceiveBuffer.position(0);
                    snifferReceiveBuffer.get(capturedFrameBytes, 0, copyLength);
                    int maxScanOffset = rawPacketModeEnabled ? (copyLength - MINIMUM_FRAME_HEADER_SIZE) : 0;
                    for (int offset = 0; offset <= maxScanOffset; offset++) {
                        if (capturedFrameBytes[offset] == (byte) 0x50 && capturedFrameBytes[offset + 1] == (byte) 0x6F && capturedFrameBytes[offset + 2] == (byte) 0x9A && capturedFrameBytes[offset + 3] == (byte) 0x13) {
                            ByteBuffer frameSlice = ByteBuffer.wrap(capturedFrameBytes, offset, copyLength - offset);
                            handleIncomingMappedPacket(frameSlice, null);
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, "Oldlib-WifiAware-RawCpuSniffer");
        rawSnifferThread.setDaemon(true);
        rawSnifferThread.start();
    }

    private void handleIncomingMappedPacket(ByteBuffer packetBuffer, InetAddress externalSenderAddress) {
        try {
            if (packetBuffer.remaining() < MINIMUM_FRAME_HEADER_SIZE) return;       
            if (packetBuffer.get() != (byte) 0x50 || packetBuffer.get() != (byte) 0x6F || packetBuffer.get() != (byte) 0x9A || packetBuffer.get() != (byte) 0x13) {
                return;
            }
            byte packetType = packetBuffer.get();
            final int senderPeerId = packetBuffer.getInt();        
            if (publishSessions.containsKey(senderPeerId) || subscribeSessions.containsKey(senderPeerId)) {
                return;
            }       
            byte[] embeddedSenderIp = new byte[4];
            packetBuffer.get(embeddedSenderIp);
            InetAddress resolvedSenderAddress = (externalSenderAddress instanceof Inet4Address) ? externalSenderAddress : InetAddress.getByAddress(embeddedSenderIp);
            knownPeerAddresses.put(senderPeerId, resolvedSenderAddress);        
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
                    if ( incomingServiceName.equalsIgnoreCase(subscriberSession.serviceName) ) {
                        subscriberSession.handler.post(() -> subscriberSession.callback.onServiceDiscovered(
                                peerHandle, payloadBytes, serviceIdFilter));
                    }
                }
            } else if (packetType == PKT_MESSAGE || packetType == PKT_SUBSCRIBE_MATCH) {
                for (final SubscribeDiscoverySession subscriberSession : subscribeSessions.values()) {
                    if ( incomingServiceName.equalsIgnoreCase(subscriberSession.serviceName) ) {
                        subscriberSession.handler.post(() -> subscriberSession.callback.onMessageReceived(
                                peerHandle, payloadBytes));
                    }
                }
                for (final PublishDiscoverySession publisherSession : publishSessions.values()) {
                    if ( incomingServiceName.equalsIgnoreCase(publisherSession.serviceName) ) {
                        publisherSession.handler.post(() -> publisherSession.callback.onMessageReceived(
                                peerHandle, payloadBytes));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void close() {
        if (!sessionClosed.compareAndSet(false, true)) return;
        publishSessions.clear();
        subscribeSessions.clear();
        knownPeerAddresses.clear();
        if (datagramSocketDescriptor != null && datagramSocketDescriptor.valid()) {
            try { Os.close(datagramSocketDescriptor); } catch (Exception ignored) {}
        }
        if (snifferSocketDescriptor != null && snifferSocketDescriptor.valid()) {
            try { Os.close(snifferSocketDescriptor); } catch (Exception ignored) {}
        }
        if (sharedMemoryBaseAddress != 0L) {
            try { Os.munmap(sharedMemoryBaseAddress, TOTAL_SHARED_MEMORY_SIZE); } catch (Exception ignored) {}
            sharedMemoryBaseAddress = 0L;
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

    private static byte[] resolveLocalHostIpv4() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces != null && networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;
                Enumeration<InetAddress> interfaceAddresses = networkInterface.getInetAddresses();
                while (interfaceAddresses.hasMoreElements()) {
                    InetAddress address = interfaceAddresses.nextElement();
                    if (address instanceof Inet4Address) {
                        return address.getAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return new byte[]{127, 0, 0, 1};
    }

    private static ByteBuffer wrapSharedMemoryWindow(long windowAddress, int capacity) {
        try {
            Class<?> directBufferClass = Class.forName("java.nio.DirectByteBuffer");
            try {
                Constructor<?> constructor = directBufferClass.getDeclaredConstructor(long.class, int.class);
                constructor.setAccessible(true);
                return (ByteBuffer) constructor.newInstance(windowAddress, capacity);
            } catch (NoSuchMethodException ignored) {
                Constructor<?> constructor = directBufferClass.getDeclaredConstructor(int.class, long.class, FileDescriptor.class, Runnable.class, boolean.class);
                constructor.setAccessible(true);
                return (ByteBuffer) constructor.newInstance(capacity, windowAddress, null, null, false);
            }
        } catch (Throwable ignored) {
            return ByteBuffer.allocateDirect(capacity);
        }
    }
}