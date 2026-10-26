package org.uzel.desktop;

public interface DesktopBackend extends AutoCloseable {
    record Snapshot(boolean running,int knownContacts,int devices,int connectedNeighbors,boolean capacityLimited,String lastError){}
    void start(DesktopConfig config)throws Exception;
    void stop();
    void addContact(String code)throws Exception;
    String ownCode()throws Exception;
    Snapshot snapshot();
    default java.util.List<DeviceDirectoryView.Device> devices(){return java.util.List.of();}
    record SearchResult(int visitedServers,boolean partial){}
    record DiscoveryStatus(String state,String detail,int verifiedDevices){}
    default DiscoveryStatus discoveryStatus(){return new DiscoveryStatus("NOT_STARTED","",0);}
    SearchResult refreshDevices()throws Exception;
    DesktopTrafficService.Snapshot traffic();
    void startSharing()throws Exception;
    void stopSharing();
    void measureUpload()throws Exception;
    void setSharingRate(SharingRatePreference preference)throws Exception;
    String sharingCode()throws Exception;
    void addTrafficPeer(String code)throws Exception;
    boolean canConnect(String fingerprint);
    default boolean hasTrafficRoute(String fingerprint){return false;}
    default String pairingState(){return "OFF";}
    /** A live paired chat stream for this fingerprint, or null when no P2P session exists. */
    default java.net.Socket openChatStream(String fingerprint)throws java.io.IOException {return null;}
    void connect(String fingerprint)throws Exception;
    void disconnect();
    java.util.List<java.nio.file.Path> selectedApplications();
    void selectApplications(java.util.List<java.nio.file.Path> applications)throws Exception;
    default DesktopRoutingMode routingMode(){return DesktopRoutingMode.SELECTED_APPLICATIONS;}
    default void setRoutingMode(DesktopRoutingMode mode)throws Exception {throw new UnsupportedOperationException("Routing scope unavailable");}
    WindowsAppRouter.Availability routingAvailability();
    WindowsAppRouter.Snapshot routingState();
    void close();
}
