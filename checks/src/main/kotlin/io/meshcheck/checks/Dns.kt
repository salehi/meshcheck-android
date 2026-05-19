package io.meshcheck.checks

import android.content.Context
import org.xbill.DNS.ResolverConfig
import org.xbill.DNS.config.AndroidResolverConfigProvider

/**
 * One-time dnsjava setup. Android has no `/etc/resolv.conf`, so dnsjava needs
 * the device's configured DNS servers handed to it explicitly via a `Context`.
 *
 * Call once at process startup, before any [DnsCheck] runs.
 */
object Dns {
    fun configure(context: Context) {
        AndroidResolverConfigProvider.setContext(context.applicationContext)
        ResolverConfig.refresh()
    }
}
