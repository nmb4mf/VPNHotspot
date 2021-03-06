package be.mygod.vpnhotspot

import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.Routing
import be.mygod.vpnhotspot.net.UpstreamMonitor
import be.mygod.vpnhotspot.widget.SmartSnackbar
import com.crashlytics.android.Crashlytics
import java.net.InetAddress
import java.net.InterfaceAddress

class LocalOnlyInterfaceManager(val downstream: String) : UpstreamMonitor.Callback {
    private var routing: Routing? = null
    private var dns = emptyList<InetAddress>()

    init {
        app.cleanRoutings[this] = this::clean
        UpstreamMonitor.registerCallback(this) { initRouting() }
    }

    override fun onAvailable(ifname: String, dns: List<InetAddress>) {
        val routing = routing
        initRouting(ifname, if (routing == null) null else {
            routing.revert()
            routing.hostAddress
        }, dns)
    }
    override fun onLost() {
        val routing = routing ?: return
        routing.revert()
        initRouting(null, routing.hostAddress, emptyList())
    }

    private fun clean() {
        val routing = routing ?: return
        routing.started = false
        initRouting(routing.upstream, routing.hostAddress, dns)
    }

    private fun initRouting(upstream: String? = null, owner: InterfaceAddress? = null,
                            dns: List<InetAddress> = this.dns) {
        this.dns = dns
        try {
            this.routing = Routing(upstream, downstream, owner).apply {
                try {
                    val strict = app.strict
                    if (strict && upstream == null) return@apply    // in this case, nothing to be done
                    if (app.dhcpWorkaround) dhcpWorkaround()
                    ipForward()                                     // local only interfaces need to enable ip_forward
                    rule()
                    forward(strict)
                    if (app.masquerade) masquerade(strict)
                    dnsRedirect(dns)
                } catch (e: Exception) {
                    revert()
                    throw e
                } finally {
                    commit()
                }
            }
        } catch (e: Exception) {
            SmartSnackbar.make(e.localizedMessage).show()
            Crashlytics.logException(e)
            routing = null
        }
    }

    fun stop() {
        UpstreamMonitor.unregisterCallback(this)
        app.cleanRoutings -= this
        routing?.revert()
    }
}
