package org.box44.kailink.domain.push

/**
 * Reine Konfiguration der Push-Kette (keine Android-/SDK-Typen, JVM-testbar).
 *
 * Die Matrix-Pusher-Registrierung trennt zwei Adressen (siehe Matrix
 * Push-Gateway-Spezifikation):
 * - `endpoint` (UnifiedPush-Endpoint des Distributors) wird als
 *   `PusherIdentifiers.pushkey` angemeldet,
 * - `gatewayUrl` ist die URL des Matrix-Push-Gateways (z. B. ntfy), das die
 *   Push-Nachricht in UnifiedPush übersetzt, und wird als
 *   `HttpPusherData.url` angemeldet.
 */
data class PushConfiguration(
    val gatewayUrl: String = DEFAULT_GATEWAY_URL,
    val appId: String = DEFAULT_APP_ID,
) {
    companion object {
        /** Standard-Gateway des PoC: ntfys eingebauter Matrix-Push-Endpoint. */
        const val DEFAULT_GATEWAY_URL = "https://ntfy.sh/_matrix/push/v1/notify"

        const val DEFAULT_APP_ID = "org.box44.kailink"
    }
}
