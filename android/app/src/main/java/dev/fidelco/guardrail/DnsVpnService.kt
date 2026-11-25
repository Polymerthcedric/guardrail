package dev.fidelco.guardrail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class DnsVpnService : VpnService() {

    private var running = false
    private var tunFd: ParcelFileDescriptor? = null
    private var upstream: InetSocketAddress? = null
    private var reader: Thread? = null
    private var upstreamSocket: DatagramSocket? = null
    private var callbackRegistered = false
    private var callbackRegisteredAt = 0L
    private var lastRestartAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { restartTunnel() }

    private val networkCallback = object : NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRestart()
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = scheduleRestart()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            unregisterNetworkCallback()
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (running) return START_STICKY
        running = true
        prefs().edit()
            .putBoolean(BootReceiver.PREFS_VPN_WANTED, true)
            .putBoolean(BootReceiver.PREFS_VPN_RUNNING, false)
            .apply()
        start()
        registerNetworkCallback()
        return START_STICKY
    }

    private fun registerNetworkCallback() {
        if (callbackRegistered) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, networkCallback)
            callbackRegistered = true
            callbackRegisteredAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.w(TAG, "network callback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!callbackRegistered) return
        callbackRegistered = false
        handler.removeCallbacks(restartRunnable)
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private fun scheduleRestart() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (now - callbackRegisteredAt < 2500) return
        if (now - lastRestartAt < 2000) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, 1500)
    }

    private fun restartTunnel() {
        if (!running) return
        lastRestartAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "network changed \u2014 re-establishing vpn")
        teardown()
        running = true
        prefs().edit().putBoolean(BootReceiver.PREFS_VPN_RUNNING, false).apply()
        goForeground()
        start()
    }


    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun teardown() {
        running = false
        isRunning = false
        prefs().edit().putBoolean(BootReceiver.PREFS_VPN_RUNNING, false).apply()
        reader?.interrupt()
        upstreamSocket?.close()
        upstreamSocket = null
        tunFd?.close()
        tunFd = null
        reader = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        handler.removeCallbacks(restartRunnable)
        teardown()
        Log.i(TAG, "vpn stopped")
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "vpn revoked by system")
        unregisterNetworkCallback()
        teardown()
        stopSelf()
        super.onRevoke()
    }

    private fun start() {
        val resolvers = resolverAddresses()
        val upstreamAddr = resolvers.firstOrNull()
        if (upstreamAddr == null) {
            Log.w(TAG, "vpn start failed: no upstream")
            stopSelf()
            return
        }
        this.upstream = InetSocketAddress(upstreamAddr, DnsPacket.DNS_PORT)
        Log.i(TAG, "vpn establishing upstream=${upstreamAddress()} resolvers=${resolvers.size}")

        val builder = Builder()
        builder.addAddress("10.1.10.1", 32)
        builder.addAddress("fd00:1:2:3::1", 128)
        val routed = LinkedHashSet<InetAddress>(resolvers)
        for (host in PUBLIC_RESOLVERS) {
            try {
                routed.add(InetAddress.getByName(host))
            } catch (_: Exception) {
            }
        }
        for (addr in routed) {
            builder.addRoute(addr, if (addr.address.size == 4) 32 else 128)
        }
        val advertised = resolvers.ifEmpty { routed.toList() }
        for (addr in advertised) {
            builder.addDnsServer(addr)
        }
        builder.setMtu(1360)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: SecurityException) {
        }

        val fd = builder.establish() ?: let {
            Log.w(TAG, "vpn establish failed (not prepared or revoked)")
            stopSelf()
            return
        }
        tunFd = fd
        val socket = DatagramSocket().apply {
            protect(this)
            soTimeout = 3000
        }
        upstreamSocket = socket
        isRunning = true
        prefs().edit().putBoolean(BootReceiver.PREFS_VPN_RUNNING, true).apply()
        Log.i(TAG, "vpn established")
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)

        reader = Thread {
            val buf = ByteArray(1600)
            var n: Int
            while (running) {
                n = try {
                    input.read(buf)
                } catch (e: Exception) {
                    break
                }
                if (n < 0) break
                if (n == 0) {
                    Thread.sleep(10)
                    continue
                }
                if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "read n=$n")
                try {
                    handlePacket(buf, n, input, output)
                } catch (t: Throwable) {
                    Log.e(TAG, "packet error: ${t.message}")
                }
            }
            Log.i(TAG, "vpn reader exiting")
        }.apply { start() }
    }

    private fun handlePacket(buf: ByteArray, n: Int, input: FileInputStream, output: FileOutputStream) {
        if (n < 28) return
        val meta = DnsPacket.meta(buf, n) ?: return
        val ipLen = meta.ipLen
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            val hex = buf.copyOfRange(0, minOf(n, 48)).joinToString("") { String.format("%02x", it) }
            Log.v(TAG, "pkt n=$n v=${meta.ver} proto=${meta.proto} dst=${DnsPacket.dstPort(buf, ipLen)} hex=$hex")
        }
        if (meta.ver == 4 && DnsPacket.isFragment(buf, ipLen)) return
        val dstPort = DnsPacket.dstPort(buf, ipLen)
        if (meta.proto != DnsPacket.PROTO_UDP || dstPort != DnsPacket.DNS_PORT) {
            // DoT (853), DoH/QUIC (443) and anything else sent to a routed
            // resolver is dropped here, which forces encrypted-DNS clients
            // back to the plaintext resolver path we filter.
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "drop proto=${meta.proto} port=$dstPort")
            return
        }

        val packetEnd = DnsPacket.udpPacketEnd(buf, n, ipLen, meta.ver) ?: return
        val q = DnsPacket.readQuestion(buf, ipLen, packetEnd)
        if (q == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                val dnsHex = buf.copyOfRange(ipLen + 8, minOf(n, ipLen + 60))
                    .joinToString("") { String.format("%02x", it) }
                Log.d(TAG, "questerr dns=$dnsHex")
            }
            return
        }
        val blocked = Blocked.domainBlocked(q.name)
        if (blocked) {
            val reply = DnsPacket.nxdomainReply(buf, ipLen, meta.ver, q)
            output.write(reply)
            output.flush()
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "blocked ${q.name}")
            return
        }

        val socket = upstreamSocket ?: return
        val payload = buf.copyOfRange(ipLen + 8, packetEnd)
        try {
            synchronized(socket) {
                socket.send(DatagramPacket(payload, payload.size, upstream))
                val recv = ByteArray(payload.size + 512)
                val packet = DatagramPacket(recv, recv.size)
                socket.receive(packet)
                val reply = DnsPacket.forwardReply(buf, ipLen, meta.ver, packet.data.copyOf(packet.length))
                output.write(reply)
                output.flush()
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "forwarded ${q.name}")
        } catch (e: Exception) {
            val reply = DnsPacket.nxdomainReply(buf, ipLen, meta.ver, q)
            output.write(reply)
            output.flush()
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "timeout ${q.name}: ${e.message}")
        }
    }

    private fun resolverAddresses(): List<InetAddress> {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val out = LinkedHashSet<InetAddress>()
        fun isUnderlay(network: Network): Boolean {
            return try {
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } catch (_: Exception) { false }
        }

        // Never select this tunnel (or another VPN) as the upstream DNS path.
        // Prefer the active physical network, then retain other physical
        // resolvers as routes for clients that advertise them.
        try {
            cm.activeNetwork?.takeIf(::isUnderlay)?.let { active ->
                cm.getLinkProperties(active)?.dnsServers?.let { out.addAll(it) }
            }
        } catch (_: Exception) {
        }
        for (net in cm.allNetworks) {
            if (!isUnderlay(net)) continue
            try {
                cm.getLinkProperties(net)?.dnsServers?.let { out.addAll(it) }
            } catch (_: Exception) {
            }
        }
        return out.toList()
    }

    private fun upstreamAddress(): String = upstream?.address?.hostAddress ?: "none"


    private fun prefs() =
        getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val content = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, content,
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_channel_name))
            .setContentIntent(pi)
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()
    }

    companion object {
        private const val TAG = "Guardrail"
        private const val CHANNEL_ID = "guardrail_vpn"
        const val ACTION_STOP = "dev.fidelco.guardrail.STOP_VPN"

        // Routed so apps that hardcode a public resolver (and the DoH/DoT
        // endpoints, which share these addresses) cannot bypass the filter.
        private val PUBLIC_RESOLVERS = listOf(
            "8.8.8.8", "8.8.4.4",
            "1.1.1.1", "1.0.0.1",
            "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220",
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2620:fe::fe", "2620:fe::9"
        )
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
