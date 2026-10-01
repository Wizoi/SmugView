package com.smugview.app.data.cast

/**
 * Step 6-8 (R-56, R-57): everything [DefaultCastManager] does on the network or with the Google Cast SDK, behind one seam.
 *
 * The manager decides WHEN (discovery timing, which connect attempt is still wanted, who ends a session); this decides HOW.
 * Production uses the manager's own implementation (SSDP multicast, Roku ECP, DIAL, the Web Companion server, `CastContext`);
 * a unit test passes a fake, so the timing runs on virtual time and no socket or SDK is touched.
 */
internal interface CastIo {
    /** One SSDP scan of the local network. Takes a few seconds in production; must close its socket however it ends. */
    suspend fun ssdpScan(): List<CastDevice>

    /** Whether a Roku at [ip] accepts "play on Roku" (true when it cannot be told). */
    suspend fun rokuSupportsCasting(ip: String): Boolean

    /** Whether an Amazon device at [ip] answers DIAL; when it does not, the Web Companion server is used. */
    suspend fun amazonSupportsDial(ip: String): Boolean

    /** Binds the Web Companion server so only [allowedClientIp] can fetch the media. */
    fun startServer(port: Int, allowedClientIp: String)

    fun stopServer()

    /** Asks the Google Cast framework to use the route [routeId]. Called on the main thread. */
    fun selectGoogleRoute(routeId: String)

    /** Ends the current Google Cast session. Called on the main thread. */
    fun endGoogleSession()
}
