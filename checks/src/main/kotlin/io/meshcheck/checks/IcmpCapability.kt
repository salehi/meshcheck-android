package io.meshcheck.checks

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

/**
 * Probes whether this device can open an unprivileged ICMP datagram socket —
 * the prerequisite for the `ping` (traceroute) check.
 *
 * Most Android devices allow it because AOSP ships `net.ipv4.ping_group_range`
 * open to all GIDs, but some OEMs restrict it, in which case `Os.socket` throws
 * `ErrnoException(EACCES)`. The app advertises `ping` / `can_send_icmp` only
 * when this returns true, so the platform never assigns a ping task to a device
 * that cannot run it.
 *
 * The probe is pure Kotlin (`android.system.Os` exists from API 21) even though
 * the traceroute itself is native: opening the socket is the real gate, and the
 * native error-queue APIs are only needed for intermediate-hop discovery.
 */
object IcmpCapability {

    /** True iff an `AF_INET` / `IPPROTO_ICMP` datagram socket can be opened. */
    fun canSendIcmp(): Boolean = try {
        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
        Os.close(fd)
        true
    } catch (e: ErrnoException) {
        false
    } catch (e: Throwable) {
        false
    }
}
