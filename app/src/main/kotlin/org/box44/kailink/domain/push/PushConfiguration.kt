package org.box44.kailink.domain.push

/**
 * Pure configuration of the push chain (no Android/SDK types, JVM-testable).
 *
 * The Matrix pusher registration separates two addresses (see the Matrix
 * Push Gateway specification):
 * - `endpoint` (the distributor's UnifiedPush endpoint) is registered as
 *   `PusherIdentifiers.pushkey`,
 * - `gatewayUrl` is the URL of the Matrix push gateway (e.g. ntfy) that
 *   translates the push message into UnifiedPush, and is registered as
 *   `HttpPusherData.url`.
 */
data class PushConfiguration(
    val gatewayUrl: String = DEFAULT_GATEWAY_URL,
    val appId: String = DEFAULT_APP_ID,
) {
    companion object {
        /** PoC default gateway: ntfy's built-in Matrix push endpoint. */
        const val DEFAULT_GATEWAY_URL = "https://ntfy.sh/_matrix/push/v1/notify"

        const val DEFAULT_APP_ID = "org.box44.kailink"
    }
}
