package org.olcbox.app.vpn

data class AndroidSplitTunnelSettings(
    val mode: AndroidSplitTunnelMode = AndroidSplitTunnelMode.AllApps,
    val proxyPackages: Set<String> = emptySet(),
    val bypassPackages: Set<String> = emptySet()
)

enum class AndroidSplitTunnelMode(val value: String) {
    AllApps("all_apps"),
    ProxySelected("proxy_selected"),
    BypassSelected("bypass_selected");

    companion object {
        fun fromValue(value: String?): AndroidSplitTunnelMode {
            // Legacy "bypass_selected" (all except selected) was removed from the UI: treat it as
            // "all apps" so an old preference can never leave the engine in the bypass mode.
            if (value == "bypass_selected") return AllApps
            return entries.firstOrNull { it.value == value } ?: AllApps
        }
    }
}

enum class AndroidSplitTunnelList {
    Proxy,
    Bypass
}

data class AndroidInstalledApp(
    val packageName: String,
    val label: String,
    /** True for apps with no launcher icon (system/background packages that still hold INTERNET). */
    val isSystem: Boolean = false
)
