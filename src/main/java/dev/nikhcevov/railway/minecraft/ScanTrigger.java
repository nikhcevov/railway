package dev.nikhcevov.railway.minecraft;

/** Why a scan was started; logged so a scan can be traced back to its caller. */
public enum ScanTrigger {
	/** Automatic queue after server start. */
	STARTUP,
	/** Explicit {@code /rail network scan}. */
	COMMAND,
	/** Automatic scan requested while launching a trip. */
	TRIP_LAUNCH,
	/** Automatic scan requested by {@code /rail route check}. */
	ROUTE_CHECK,
	/** A sign anchored a station and its graph must exist immediately. */
	SIGN_ANCHOR,
	/** Rescan of a connected station after a topology or revision change. */
	RESCAN,
	/** Forced full-network rescan queued by {@code /rail network rescan}. */
	RESCAN_ALL
}
