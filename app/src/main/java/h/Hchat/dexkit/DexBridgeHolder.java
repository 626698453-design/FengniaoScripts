package h.Hchat.dexkit;

import org.luckypray.dexkit.DexKitBridge;

/** 宿主无关共享实例；不包含任何微信定位器。 */
public final class DexBridgeHolder {
    private final DexKitBridge dexKitBridge;
    private final ClassLoader hostClassLoader;
    private final String apkPath;
    public DexBridgeHolder(DexKitBridge bridge, ClassLoader loader, String path) {
        dexKitBridge = bridge; hostClassLoader = loader; apkPath = path;
    }
    public DexKitBridge getDexKitBridge() { return dexKitBridge; }
    public ClassLoader getHostClassLoader() { return hostClassLoader; }
    public String getApkPath() { return apkPath; }
}
