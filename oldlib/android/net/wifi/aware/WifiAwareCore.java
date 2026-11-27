package oldlib.android.net.wifi.aware;

import android.app.Application;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class WifiAwareCore {
    private static final String LOG_TAG = "WifiAwareCore";
    
    static final int NAN_PORT = 47009;
    private static final int[] CLUSTER_PORTS = new int[]{47009, 47010, 47011, 47012};
    private static final int SO_REUSEPORT_OPTION = 15;

    static final byte PKT_BEACON = 1;
    static final byte PKT_SUBSCRIBE_MATCH = 2;
    static final byte PKT_MESSAGE = 3;
    static final byte PKT_MATCH_ACK = 4;
    static final byte PKT_SUBSCRIBE_SEEK = 5;

    private static final int PACKET_BUFFER_SIZE = 4096;
    private static final int MAX_SERVICE_NAME_BYTES = 255;
    private static final int MAX_PAYLOAD_BYTES = 1024;
    private static final int MINIMUM_HEADER_SIZE = 21;

    private static final int ACTIVE_POLL_INTERVAL_MILLIS = 2000;
    private static final int IDLE_POLL_INTERVAL_MILLIS = 5000;
    private static final long PEER_EXPIRATION_MILLIS = 12000L;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final AtomicInteger SESSION_ID_GENERATOR = new AtomicInteger((SECURE_RANDOM.nextInt(0x3FFFFFFF)) + 1000);

    private final WifiAwareSession ownerSession;
    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final Object transmitLock = new Object();

    private final WifiManager systemWifiManager;
    private WifiManager.MulticastLock hardwareMulticastLock;
    private WifiManager.WifiLock highPerformanceWifiLock;

    private volatile InetAddress localWifiAddress;
    private volatile InetAddress subnetBroadcastAddress;
    private volatile String activeWifiInterfaceName = "wlan0";
    private int rotatingSubnetSweepCursor = 1;

    private final FileDescriptor socketDescriptor;
    private Thread engineLoopThread;

    private final byte[] transmitBuffer = new byte[PACKET_BUFFER_SIZE];
    private final byte[] receiveBuffer = new byte[PACKET_BUFFER_SIZE];

    private final Map<Integer, PublishDiscoverySession> publishSessions = new ConcurrentHashMap<>();
    private final Map<Integer, SubscribeDiscoverySession> subscribeSessions = new ConcurrentHashMap<>();
    private final Map<DiscoverySession, Integer> sessionToIdMap = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> sessionTokens = new ConcurrentHashMap<>();
    private final Map<Integer, InetAddress> knownPeerAddresses = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> knownPeerPorts = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> knownPeerTokens = new ConcurrentHashMap<>();
    private final Map<Integer, Long> peerLastSeenTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Long> notifiedMatchTimestamps = new ConcurrentHashMap<>();

    WifiAwareCore(WifiAwareSession ownerSession) {
        this.ownerSession = ownerSession;
        this.systemWifiManager = obtainSystemWifiManager();
        acquireWifiHardwareLocks();
        refreshWifiNetworkState();
        FileDescriptor openedSocket = null;
        try {
            openedSocket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP);
            Os.setsockoptInt(openedSocket, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1);
            try {
                Os.setsockoptInt(openedSocket, OsConstants.SOL_SOCKET, SO_REUSEPORT_OPTION, 1);
            } catch (Throwable ignored) {}
            Os.setsockoptInt(openedSocket, OsConstants.SOL_SOCKET, OsConstants.SO_BROADCAST, 1);

            boolean bindSucceeded = false;
            InetAddress wildcardAddress = InetAddress.getByName("0.0.0.0");
            for (int candidatePort : CLUSTER_PORTS) {
                try {
                    Os.bind(openedSocket, wildcardAddress, candidatePort);
                    bindSucceeded = true;
                    break;
                } catch (Exception ignored) {}
            }
            if (!bindSucceeded) {
                Os.bind(openedSocket, wildcardAddress, 0);
            }
        } catch (Exception exception) {
            if (openedSocket != null && openedSocket.valid()) {
                try { Os.close(openedSocket); } catch (Exception ignored) {}
            }
            releaseWifiHardwareLocks();
            throw new RuntimeException("Unable to initialize network port in WifiAwareCore", exception);
        }
        this.socketDescriptor = openedSocket;
        startUnifiedPollEngineThread();
    }

    void startPublish(PublishConfig publishConfig, DiscoverySessionCallback callback, Handler dispatchHandler) {
        if (engineStopped.get()) return;
        final PublishDiscoverySession publisherSession = new PublishDiscoverySession(ownerSession, normalizeServiceName(publishConfig.serviceName), publishConfig.serviceSpecificInfo, callback, dispatchHandler);
        final int publisherId = registerNewSession(publisherSession);
        publishSessions.put(publisherId, publisherSession);
        dispatchHandler.post(() -> {
            if (!engineStopped.get()) {
                callback.onPublishStarted(publisherSession);
                sweepNetworkForSession(PKT_BEACON, publisherSession.serviceName, publisherId, publisherSession.serviceInfo);
            }
        });
    }

    void startSubscribe(SubscribeConfig subscribeConfig, DiscoverySessionCallback callback, Handler dispatchHandler) {
        if (engineStopped.get()) return;
        final SubscribeDiscoverySession subscriberSession = new SubscribeDiscoverySession(ownerSession, normalizeServiceName(subscribeConfig.serviceName), subscribeConfig.serviceSpecificInfo, callback, dispatchHandler);
        final int subscriberId = registerNewSession(subscriberSession);
        subscribeSessions.put(subscriberId, subscriberSession);
        dispatchHandler.post(() -> {
            if (!engineStopped.get()) {
                callback.onSubscribeStarted(subscriberSession);
                sweepNetworkForSession(PKT_SUBSCRIBE_SEEK, subscriberSession.serviceName, subscriberId, subscriberSession.serviceInfo);
            }
        });
    }

    void unregisterDiscoverySession(DiscoverySession session) {
        if (session == null) return;
        Integer sessionId = sessionToIdMap.remove(session);
        if (sessionId != null) {
            publishSessions.remove(sessionId);
            subscribeSessions.remove(sessionId);
            sessionTokens.remove(sessionId);
            String prefix = sessionId + ":";
            String suffix = ":" + sessionId;
            Iterator<String> iterator = notifiedMatchTimestamps.keySet().iterator();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if (key.startsWith(prefix) || key.endsWith(suffix)) {
                    iterator.remove();
                }
            }
        }
    }
    InetAddress resolvePeerAddress(int peerId) {
        return knownPeerAddresses.get(peerId);
    }

    void dispatchSessionPacket(byte packetType, String serviceName, int targetPeerId, byte[] payload, InetAddress targetAddress, int targetPort, DiscoverySession originSession) {
        int senderSessionId = getOrRegisterSessionId(originSession);
        int senderToken = getSessionToken(senderSessionId);
        InetAddress resolvedAddress = (targetAddress != null) ? targetAddress : knownPeerAddresses.get(targetPeerId);
        int resolvedPort = resolvePeerPort(targetPeerId);
        sendProtocolFrame(packetType, serviceName, senderSessionId, targetPeerId, senderToken, payload, resolvedAddress, resolvedPort);
    }

    private int registerNewSession(DiscoverySession session) {
        int generatedId;
        do {
            generatedId = SESSION_ID_GENERATOR.incrementAndGet() & 0x7FFFFFFF;
        } while (generatedId == 0 || publishSessions.containsKey(generatedId) || subscribeSessions.containsKey(generatedId));
        sessionToIdMap.put(session, generatedId);
        sessionTokens.put(generatedId, SECURE_RANDOM.nextInt());
        return generatedId;
    }

    private int getOrRegisterSessionId(DiscoverySession session) {
        if (session == null) return 0;
        Integer existingId = sessionToIdMap.get(session);
        return (existingId != null) ? existingId : registerNewSession(session);
    }

    private void sweepNetworkForSession(byte packetType, String serviceName, int sessionId, byte[] payload) {
        int token = getSessionToken(sessionId);
        InetAddress broadcastTarget = this.subnetBroadcastAddress;
        if (broadcastTarget != null) {
            for (int port : CLUSTER_PORTS) {
                sendProtocolFrame(packetType, serviceName, sessionId, 0, token, payload, broadcastTarget, port);
            }
        }
        for (Map.Entry<Integer, InetAddress> peerEntry : knownPeerAddresses.entrySet()) {
            int peerId = peerEntry.getKey();
            InetAddress peerIp = peerEntry.getValue();
            int peerPort = resolvePeerPort(peerId);
            sendProtocolFrame(packetType, serviceName, sessionId, peerId, token, payload, peerIp, peerPort);
        }
        for (InetAddress neighborIp : collectDiscoveryTargetNeighbors()) {
            sendProtocolFrame(packetType, serviceName, sessionId, 0, token, payload, neighborIp, NAN_PORT);
        }
    }

    private Set<InetAddress> collectDiscoveryTargetNeighbors() {
        Set<InetAddress> targets = new HashSet<>();
        FileDescriptor arpDescriptor = null;
        try {
            arpDescriptor = Os.open("/proc/net/arp", OsConstants.O_RDONLY, 0);
            ByteArrayOutputStream arpBytesBuffer = new ByteArrayOutputStream(2048);
            byte[] readChunk = new byte[1024];
            int bytesRead;
            while ((bytesRead = Os.read(arpDescriptor, readChunk, 0, readChunk.length)) > 0) {
                arpBytesBuffer.write(readChunk, 0, bytesRead);
            }
            String[] arpLines = new String(arpBytesBuffer.toByteArray(), StandardCharsets.UTF_8).split("\n");
            for (int i = 1; i < arpLines.length; i++) {
                String[] columns = arpLines[i].trim().split("\\s+");
                if (columns.length >= 6 && !"0x0".equalsIgnoreCase(columns[2])) {
                    String deviceName = columns[5].toLowerCase(Locale.ROOT);
                    if (deviceName.equals(activeWifiInterfaceName) || deviceName.startsWith("wlan") || deviceName.startsWith("ap") || deviceName.startsWith("swlan")) {
                        InetAddress arpIp = InetAddress.getByName(columns[0]);
                        if (arpIp instanceof Inet4Address && !arpIp.equals(localWifiAddress)) {
                            targets.add(arpIp);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (arpDescriptor != null && arpDescriptor.valid()) {
                try { Os.close(arpDescriptor); } catch (Exception ignored) {}
            }
        }
        InetAddress currentLocalIp = this.localWifiAddress;
        if (currentLocalIp instanceof Inet4Address && !currentLocalIp.isLoopbackAddress()) {
            byte[] baseIpBytes = currentLocalIp.getAddress();
            int myLastOctet = baseIpBytes[3] & 0xFF;
            int[] priorityOffsets = new int[]{-6, -5, -4, -3, -2, -1, 1, 2, 3, 4, 5, 6};
            for (int offset : priorityOffsets) {
                int neighborOctet = myLastOctet + offset;
                if (neighborOctet >= 1 && neighborOctet <= 254) {
                    addCandidateHostIp(targets, baseIpBytes, neighborOctet);
                }
            }
            addCandidateHostIp(targets, baseIpBytes, 1);
            for (int step = 0; step < 8; step++) {
                if (rotatingSubnetSweepCursor != myLastOctet) {
                    addCandidateHostIp(targets, baseIpBytes, rotatingSubnetSweepCursor);
                }
                rotatingSubnetSweepCursor++;
                if (rotatingSubnetSweepCursor > 254) {
                    rotatingSubnetSweepCursor = 2;
                }
            }
        }
        return targets;
    }

    private static void addCandidateHostIp(Set<InetAddress> targetSet, byte[] baseIpBytes, int lastOctet) {
        try {
            byte[] candidateBytes = new byte[]{baseIpBytes[0], baseIpBytes[1], baseIpBytes[2], (byte) lastOctet};
            targetSet.add(InetAddress.getByAddress(candidateBytes));
        } catch (Exception ignored) {}
    }

    private void sendProtocolFrame(byte packetType, String serviceName, int senderSessionId, int recipientPeerId, int sessionToken, byte[] payload, InetAddress targetAddress, int targetPort) {
        if (engineStopped.get() || socketDescriptor == null || !socketDescriptor.valid()) return;
        String normalizedName = normalizeServiceName(serviceName);
        byte[] serviceNameBytes = normalizedName.getBytes(StandardCharsets.UTF_8);
        if (serviceNameBytes.length > MAX_SERVICE_NAME_BYTES) {
            byte[] truncated = new byte[MAX_SERVICE_NAME_BYTES];
            System.arraycopy(serviceNameBytes, 0, truncated, 0, MAX_SERVICE_NAME_BYTES);
            serviceNameBytes = truncated;
        }
        byte[] payloadBytes = (payload != null) ? payload : new byte[0];
        if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
            Log.w(LOG_TAG, "Skip packets due to excess payload " + MAX_PAYLOAD_BYTES + " bytes.");
            return;
        }
        int totalPacketLength = MINIMUM_HEADER_SIZE + serviceNameBytes.length + payloadBytes.length;
        if (totalPacketLength > transmitBuffer.length) return;
        InetAddress effectiveTarget = (targetAddress != null) ? targetAddress : subnetBroadcastAddress;
        if (effectiveTarget == null) return;
        int effectivePort = (targetPort > 0) ? targetPort : NAN_PORT;
        synchronized (transmitLock) {
            try {
                ByteBuffer packetWriter = ByteBuffer.wrap(transmitBuffer);

                packetWriter.put((byte) 0x50);
                packetWriter.put((byte) 0x6F);
                packetWriter.put((byte) 0x9A);
                packetWriter.put((byte) 0x13);

                packetWriter.put(packetType);
                packetWriter.putInt(senderSessionId);
                packetWriter.putInt(recipientPeerId);
                packetWriter.putInt(sessionToken);

                packetWriter.putShort((short) serviceNameBytes.length);
                packetWriter.put(serviceNameBytes);
                packetWriter.putShort((short) payloadBytes.length);
                packetWriter.put(payloadBytes);
                Os.sendto(socketDescriptor, transmitBuffer, 0, totalPacketLength, 0, effectiveTarget, effectivePort);
            } catch (Exception ignored) {}
        }
    }

    private void startUnifiedPollEngineThread() {
        engineLoopThread = new Thread(() -> {
            StructPollfd socketPollFd = new StructPollfd();
            socketPollFd.fd = socketDescriptor;
            socketPollFd.events = (short) OsConstants.POLLIN;
            StructPollfd[] pollDescriptors = new StructPollfd[]{socketPollFd};
            InetSocketAddress senderEndpoint = new InetSocketAddress(0);
            long lastMaintenanceTime = 0L;
            while (!engineStopped.get() && socketDescriptor != null && socketDescriptor.valid()) {
                try {
                    boolean hasActiveSessions = !publishSessions.isEmpty() || !subscribeSessions.isEmpty();
                    int pollTimeout = hasActiveSessions ? ACTIVE_POLL_INTERVAL_MILLIS : IDLE_POLL_INTERVAL_MILLIS;
                    socketPollFd.revents = 0;
                    int readyCount = Os.poll(pollDescriptors, pollTimeout);
                    if (readyCount > 0 && (socketPollFd.revents & OsConstants.POLLIN) != 0) {
                        int bytesRead = Os.recvfrom(socketDescriptor, receiveBuffer, 0, receiveBuffer.length, 0, senderEndpoint);
                        if (bytesRead >= MINIMUM_HEADER_SIZE) {
                            byte[] packetCopy = new byte[bytesRead];
                            System.arraycopy(receiveBuffer, 0, packetCopy, 0, bytesRead);
                            handleIncomingPacket(ByteBuffer.wrap(packetCopy), senderEndpoint.getAddress(), senderEndpoint.getPort());
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (hasActiveSessions && (now - lastMaintenanceTime >= ACTIVE_POLL_INTERVAL_MILLIS)) {
                        lastMaintenanceTime = now;
                        refreshWifiNetworkState();
                        evictExpiredPeersAndMatches(now);
                        for (Map.Entry<Integer, PublishDiscoverySession> entry : publishSessions.entrySet()) {
                            PublishDiscoverySession publisher = entry.getValue();
                            sweepNetworkForSession(PKT_BEACON, publisher.serviceName, entry.getKey(), publisher.serviceInfo);
                        }
                    }
                } catch (Exception exception) {
                    if (engineStopped.get()) break;
                }
            }
        }, "WifiAware-PollEngine");
        engineLoopThread.setDaemon(true);
        engineLoopThread.start();
    }

    private void handleIncomingPacket(ByteBuffer packetBuffer, InetAddress kernelReportedSenderIp, int kernelReportedSenderPort) {
        try {
            if (kernelReportedSenderIp == null || kernelReportedSenderIp.isAnyLocalAddress() || kernelReportedSenderIp.isLoopbackAddress()) {
                return;
            }
            if (packetBuffer.remaining() < MINIMUM_HEADER_SIZE) return;
            if (packetBuffer.get() != (byte) 0x50 || packetBuffer.get() != (byte) 0x6F || packetBuffer.get() != (byte) 0x9A || packetBuffer.get() != (byte) 0x13) {
                return;
            }
            byte packetType = packetBuffer.get();
            final int senderPeerId = packetBuffer.getInt();
            final int recipientPeerId = packetBuffer.getInt();
            final int incomingToken = packetBuffer.getInt();
            if (senderPeerId <= 0 || publishSessions.containsKey(senderPeerId) || subscribeSessions.containsKey(senderPeerId)) {
                return;
            }
            Integer recordedToken = knownPeerTokens.get(senderPeerId);
            if (recordedToken != null && recordedToken != incomingToken) {
                return;
            }
            int serviceNameLength = packetBuffer.getShort() & 0xFFFF;
            if (serviceNameLength > MAX_SERVICE_NAME_BYTES || serviceNameLength > packetBuffer.remaining()) {
                return;
            }
            byte[] serviceNameBytes = new byte[serviceNameLength];
            packetBuffer.get(serviceNameBytes);
            final String incomingServiceName = normalizeServiceName(new String(serviceNameBytes, StandardCharsets.UTF_8));
            int payloadLength = packetBuffer.getShort() & 0xFFFF;
            if (payloadLength > MAX_PAYLOAD_BYTES || payloadLength > packetBuffer.remaining()) {
                return;
            }
            final byte[] payloadBytes = new byte[payloadLength];
            packetBuffer.get(payloadBytes);
            long now = System.currentTimeMillis();
            int effectiveSenderPort = (kernelReportedSenderPort > 0) ? kernelReportedSenderPort : NAN_PORT;
            knownPeerAddresses.put(senderPeerId, kernelReportedSenderIp);
            knownPeerPorts.put(senderPeerId, effectiveSenderPort);
            knownPeerTokens.put(senderPeerId, incomingToken);
            peerLastSeenTimestamps.put(senderPeerId, now);
            final PeerHandle peerHandle = new PeerHandle(senderPeerId);
            if (packetType == PKT_SUBSCRIBE_SEEK) {
                for (Map.Entry<Integer, PublishDiscoverySession> entry : publishSessions.entrySet()) {
                    int publisherId = entry.getKey();
                    PublishDiscoverySession publisher = entry.getValue();
                    if (incomingServiceName.equals(publisher.serviceName)) {
                        sendProtocolFrame(PKT_BEACON, publisher.serviceName, publisherId, senderPeerId, getSessionToken(publisherId), publisher.serviceInfo, kernelReportedSenderIp, effectiveSenderPort);
                    }
                }
            } else if (packetType == PKT_BEACON) {
                List<byte[]> serviceIdFilter = Collections.singletonList(computeServiceIdHash(incomingServiceName));
                for (Map.Entry<Integer, SubscribeDiscoverySession> entry : subscribeSessions.entrySet()) {
                    final int subscriberId = entry.getKey();
                    final SubscribeDiscoverySession subscriberSession = entry.getValue();
                    if (incomingServiceName.equals(subscriberSession.serviceName)) {
                        sendProtocolFrame(PKT_SUBSCRIBE_MATCH, subscriberSession.serviceName, subscriberId, senderPeerId, getSessionToken(subscriberId), subscriberSession.serviceInfo, kernelReportedSenderIp, effectiveSenderPort);
                        String matchKey = subscriberId + ":" + senderPeerId;
                        Long previousTime = notifiedMatchTimestamps.put(matchKey, now);
                        if (previousTime == null) {
                            subscriberSession.handler.post(() -> subscriberSession.callback.onServiceDiscovered(
                                    peerHandle, payloadBytes, serviceIdFilter));
                        }
                    }
                }
            } else if (packetType == PKT_SUBSCRIBE_MATCH) {
                PublishDiscoverySession targetPublisher = publishSessions.get(recipientPeerId);
                if (targetPublisher != null && incomingServiceName.equals(targetPublisher.serviceName)) {
                    sendProtocolFrame(PKT_MATCH_ACK, targetPublisher.serviceName, recipientPeerId, senderPeerId, getSessionToken(recipientPeerId), targetPublisher.serviceInfo, kernelReportedSenderIp, effectiveSenderPort);
                }
            } else if (packetType == PKT_MATCH_ACK) {
                if (subscribeSessions.containsKey(recipientPeerId)) {
                    notifiedMatchTimestamps.put(recipientPeerId + ":" + senderPeerId, now);
                }
            } else if (packetType == PKT_MESSAGE) {
                if (recipientPeerId != 0) {
                    final SubscribeDiscoverySession targetSubscriber = subscribeSessions.get(recipientPeerId);
                    if (targetSubscriber != null && incomingServiceName.equals(targetSubscriber.serviceName)) {
                        targetSubscriber.handler.post(() -> targetSubscriber.callback.onMessageReceived(peerHandle, payloadBytes));
                        return;
                    }
                    final PublishDiscoverySession targetPublisher = publishSessions.get(recipientPeerId);
                    if (targetPublisher != null && incomingServiceName.equals(targetPublisher.serviceName)) {
                        targetPublisher.handler.post(() -> targetPublisher.callback.onMessageReceived(peerHandle, payloadBytes));
                        return;
                    }
                }
                for (final SubscribeDiscoverySession subscriberSession : subscribeSessions.values()) {
                    if (incomingServiceName.equals(subscriberSession.serviceName)) {
                        subscriberSession.handler.post(() -> subscriberSession.callback.onMessageReceived(peerHandle, payloadBytes));
                    }
                }
                for (final PublishDiscoverySession publisherSession : publishSessions.values()) {
                    if (incomingServiceName.equals(publisherSession.serviceName)) {
                        publisherSession.handler.post(() -> publisherSession.callback.onMessageReceived(peerHandle, payloadBytes));
                    }
                }
            }
        } catch (Exception exception) {
            Log.d(LOG_TAG, "Error decoding packet: " + exception.getMessage());
        }
    }

    private void evictExpiredPeersAndMatches(long now) {
        Iterator<Map.Entry<Integer, Long>> peerIterator = peerLastSeenTimestamps.entrySet().iterator();
        while (peerIterator.hasNext()) {
            Map.Entry<Integer, Long> entry = peerIterator.next();
            if (now - entry.getValue() > PEER_EXPIRATION_MILLIS) {
                int expiredPeerId = entry.getKey();
                peerIterator.remove();
                knownPeerAddresses.remove(expiredPeerId);
                knownPeerPorts.remove(expiredPeerId);
                knownPeerTokens.remove(expiredPeerId);
            }
        }
        Iterator<Map.Entry<String, Long>> matchIterator = notifiedMatchTimestamps.entrySet().iterator();
        while (matchIterator.hasNext()) {
            Map.Entry<String, Long> entry = matchIterator.next();
            if (now - entry.getValue() > PEER_EXPIRATION_MILLIS) {
                matchIterator.remove();
            }
        }
    }

    private int getSessionToken(int sessionId) {
        Integer token = sessionTokens.get(sessionId);
        return (token != null) ? token : sessionId;
    }

    private int resolvePeerPort(int peerId) {
        Integer port = knownPeerPorts.get(peerId);
        return (port != null && port > 0) ? port : NAN_PORT;
    }

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
                    byte[] ipBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array();
                    this.localWifiAddress = InetAddress.getByAddress(ipBytes);
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
                String interfaceName = networkInterface.getName().toLowerCase(Locale.ROOT);
                boolean isWifiOrHotspot = interfaceName.startsWith("wlan") || interfaceName.startsWith("ap") || interfaceName.startsWith("swlan");
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (address instanceof Inet4Address && isWifiOrHotspot) {
                        this.activeWifiInterfaceName = interfaceName;
                        this.localWifiAddress = address;
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

    void shutdown() {
        if (!engineStopped.compareAndSet(false, true)) return;
        publishSessions.clear();
        subscribeSessions.clear();
        sessionToIdMap.clear();
        sessionTokens.clear();
        knownPeerAddresses.clear();
        knownPeerPorts.clear();
        knownPeerTokens.clear();
        peerLastSeenTimestamps.clear();
        notifiedMatchTimestamps.clear();
        releaseWifiHardwareLocks();
        if (socketDescriptor != null && socketDescriptor.valid()) {
            try { Os.close(socketDescriptor); } catch (Exception ignored) {}
        }
        if (engineLoopThread != null) {
            engineLoopThread.interrupt();
        }
    }

    private static String normalizeServiceName(String serviceName) {
        return (serviceName != null) ? serviceName.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static byte[] computeServiceIdHash(String serviceName) {
        byte[] sixByteHash = new byte[6];
        try {
            MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");
            byte[] fullDigest = sha256Digest.digest(normalizeServiceName(serviceName).getBytes(StandardCharsets.UTF_8));
            System.arraycopy(fullDigest, 0, sixByteHash, 0, 6);
        } catch (Exception ignored) {}
        return sixByteHash;
    }
}