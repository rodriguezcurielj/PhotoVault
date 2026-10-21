package com.photovault.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Dns
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * A direct, private connection to the Gallery server through the owner's Tailscale network, built into the
 * app (no Tailscale app or VPN needed). It runs the bundled tsproxy program, which joins the tailnet and
 * forwards 127.0.0.1:<port> to the server. While it's up, requests skip the slower public Funnel relay;
 * if it fails they fall back to Funnel automatically.
 */
object Tailnet {
    enum class State { Off, Starting, Relayed, Direct, Failed }

    val state = MutableStateFlow(State.Off)
    @Volatile private var port = 0
    @Volatile private var badUntil = 0L
    private var process: Process? = null

    /** Use the direct route for this request? */
    val usable get() = state.value == State.Direct && port != 0 && System.currentTimeMillis() > badUntil

    /** A direct connection just failed: use Funnel for a minute. */
    fun markBad() { badUntil = System.currentTimeMillis() + 60_000 }

    fun start(ctx: Context) {
        if (process != null || running || Repo.prefs.token == null) return
        running = true
        state.value = State.Starting
        Repo.scope.launch {
            try {
                while (Repo.prefs.token != null) {
                    runOnce(ctx)
                    if (!restartNow) { state.value = State.Failed; delay(30_000) }
                    restartNow = false
                }
            } finally { running = false; state.value = State.Off }
        }
    }

    @Volatile private var running = false
    @Volatile private var restartNow = false

    /** Runs tsproxy until it exits. */
    private suspend fun runOnce(ctx: Context) {
        val dir = File(ctx.filesDir, "tailnet").apply { mkdirs() }
        val joined = File(dir, "tailscaled.state").exists()
        val targetFile = File(dir, "target.txt")
        var key: String? = null
        // First time: the server hands out a one-time key that joins this phone to the tailnet.
        if (!joined || !targetFile.exists()) {
            val join = runCatching { Repo.api.tailnetJoin() }.getOrNull() ?: return
            if (!joined && join.key == null) return
            key = join.key
            targetFile.writeText(join.target)
        }
        val ifaces = File(dir, "interfaces.txt")
        writeInterfaces(ifaces)
        watchNetwork(ctx, ifaces)
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libtsproxy.so")
        val name = "gallery-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val pb = ProcessBuilder(exe.path, "-dir", dir.path, "-hostname", name, "-target", targetFile.readText().trim(),
            "-ifaces", ifaces.path, "-dns", dnsServers(ctx))
        if (!joined && key != null) pb.environment()["TS_AUTHKEY"] = key
        pb.environment()["TS_NO_LOGS_NO_SUPPORT"] = "true" // don't upload Tailscale's debug logs
        val p = runCatching { pb.start() }.getOrNull() ?: return
        process = p
        try {
            // Its stderr is Tailscale's own log; drain it so it never blocks.
            Thread { runCatching { p.errorStream.use { s -> val b = ByteArray(8192); while (s.read(b) >= 0) Unit } } }.start()
            p.inputStream.bufferedReader().forEachLine { line ->
                when {
                    line.startsWith("LISTEN ") -> port = line.substringAfter(' ').trim().toInt()
                    // Joined; but only use it when the path is direct. Through a relay it's often slower than Funnel.
                    line.startsWith("UP ") -> state.value = State.Relayed
                    line == "PATH direct" -> state.value = State.Direct
                    line == "PATH relay" -> state.value = State.Relayed
                }
            }
        } finally {
            p.destroy(); process = null; port = 0
        }
    }

    /** The phone switched networks: start tsproxy fresh so it picks the new path right away. */
    private fun restart() {
        val p = process ?: return
        restartNow = true
        if (state.value == State.Direct || state.value == State.Relayed) state.value = State.Starting
        p.destroy()
    }

    /** Signed out: leave the tailnet on this phone. */
    fun stop(ctx: Context) {
        process?.destroy()
        File(ctx.filesDir, "tailnet").deleteRecursively()
    }

    /** Sends the server's address to 127.0.0.1 while the direct route is up (the port is swapped in [sockets]). */
    fun dns(serverHost: () -> String) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (usable && hostname.equals(serverHost(), ignoreCase = true)) listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
            else Dns.SYSTEM.lookup(hostname)
    }

    /** Connections to 127.0.0.1 on the server's public port go to tsproxy instead. TLS still checks the real name. */
    fun sockets(serverPort: () -> Int) = object : SocketFactory() {
        private val base = getDefault()
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                val a = endpoint as? InetSocketAddress
                val p = port
                if (a != null && p != 0 && a.address?.isLoopbackAddress == true && a.port == serverPort()) super.connect(InetSocketAddress(a.address, p), timeout)
                else super.connect(endpoint, timeout)
            }
        }
        override fun createSocket(host: String?, port: Int) = base.createSocket(host, port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = base.createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress?, port: Int) = base.createSocket(host, port)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = base.createSocket(address, port, localAddress, localPort)
    }

    private fun dnsServers(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.filterIsInstance<Inet4Address>()?.joinToString(",") { it.hostAddress ?: "" } ?: ""
    }

    // Android doesn't let tsproxy list network interfaces itself, so they're written here for it to read.
    private fun writeInterfaces(f: File) {
        val lines = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
            val addrs = ni.interfaceAddresses.mapNotNull { ia -> ia.address?.hostAddress?.substringBefore('%')?.let { "$it/${ia.networkPrefixLength}" } }
            listOf(ni.name, ni.index, runCatching { ni.mtu }.getOrDefault(1500), if (ni.isUp) 1 else 0, if (ni.isLoopback) 1 else 0).joinToString(" ") +
                addrs.joinToString("") { " $it" }
        }
        f.writeText(lines.joinToString("\n"))
    }

    private var watching = false
    private fun watchNetwork(ctx: Context, f: File) {
        if (watching) return
        watching = true
        ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var first = true
            override fun onAvailable(network: Network) {
                runCatching { writeInterfaces(f) }
                if (first) first = false else restart()
            }
            override fun onLost(network: Network) { runCatching { writeInterfaces(f) } }
        })
    }
}
