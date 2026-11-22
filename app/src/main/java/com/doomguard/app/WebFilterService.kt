package com.doomguard.app

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Start, stop and health checks for the website filter. */
object WebFilter {
    fun start(ctx: Context) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WebFilterService::class.java))
        } catch (e: Exception) {
            Log.w("DoomGuard", "could not start website filter", e)
        }
    }

    /** Restarts the filter if it should be on but isn't. Does nothing if the user hasn't granted the VPN consent. */
    fun ensure(ctx: Context) {
        try {
            if (Store.webFilterActive() && !WebFilterService.running && VpnService.prepare(ctx) == null) start(ctx)
        } catch (e: Exception) {
            Log.w("DoomGuard", "ensure website filter failed", e)
        }
    }

    /** True when Private DNS is set to a specific server, which can send lookups around the filter. */
    fun privateDnsStrict(ctx: Context): Boolean = try {
        Settings.Global.getString(ctx.contentResolver, "private_dns_mode") == "hostname"
    } catch (e: Exception) {
        false
    }
}

/** Looks a host name up in the bundled lists and the user's own list. */
class DomainBlocker(private val ctx: Context) {
    @Volatile private var adult: Set<String> = emptySet()
    @Volatile private var custom: Set<String> = emptySet()

    // Only the Firefox "canary" name is answered with "no such domain" (it tells Firefox to switch its own secure DNS
    // off). The public secure-DNS provider names (dns.google, cloudflare-dns.com and so on) must stay resolvable:
    // blocking them made Chrome fail with DNS_PROBE_FINISHED_BAD_SECURE_CONFIG and every page stop loading.
    private val bypass = setOf("use-application-dns.net")

    fun load() {
        adult = readList("blocklist_adult.txt")
        reloadCustom()
    }

    // The phone's own Private DNS server name must always resolve, or Android reports "Private DNS server cannot be
    // accessed" and every app goes offline. Never block it, even if it is one of the names listed in `bypass`.
    @Volatile private var privateDnsHost: String = ""

    fun reloadCustom() {
        custom = Store.blockedCustomSet()
        privateDnsHost = try {
            if (Settings.Global.getString(ctx.contentResolver, "private_dns_mode") == "hostname")
                (Settings.Global.getString(ctx.contentResolver, "private_dns_specifier") ?: "").lowercase().trim()
            else ""
        } catch (e: Exception) { "" }
    }

    private fun readList(name: String): Set<String> {
        val out = HashSet<String>(100_000)
        try {
            ctx.assets.open(name).bufferedReader().useLines { lines ->
                lines.forEach { l -> if (l.isNotBlank()) out.add(l.trim()) }
            }
        } catch (e: Exception) {
            Log.w("DoomGuard", "could not read $name", e)
        }
        return out
    }

    /** 0 = allowed, 1 = adult, 3 = your own list, 4 = a DNS-bypass service. */
    fun check(host: String): Int {
        var d = host.lowercase().trimEnd('.')
        val pd = privateDnsHost
        if (pd.isNotEmpty() && (d == pd || d.endsWith(".$pd"))) return 0
        while (true) {
            if (d in custom) return 3
            if (d in adult) return 1
            if (d in bypass) return 4
            val i = d.indexOf('.')
            if (i < 0) return 0
            d = d.substring(i + 1)
            if (d.indexOf('.') < 0) return 0 // never match a bare top-level domain
        }
    }
}

/**
 * A local VPN that only captures DNS lookups. Lookups for blocked sites get "no such domain" back; everything else
 * is forwarded to the phone's normal DNS server. Other traffic never goes through the tunnel.
 */
class WebFilterService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.doomguard.app.action.STOP_FILTER"
        private const val VPN_ADDR = "10.77.0.1"
        private const val DNS_ADDR = "10.77.0.2"
        private val FALLBACK_DNS: InetAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

        @Volatile
        var running = false
    }

    private var tun: ParcelFileDescriptor? = null
    private var pool: ExecutorService? = null
    @Volatile private var alive = false
    @Volatile private var upstream: InetAddress? = null
    private lateinit var blocker: DomainBlocker

    private val adultCount = AtomicInteger()
    private val customCount = AtomicInteger()
    private var logAdult = 0
    private var logCustom = 0
    private var lastEventLog = 0L
    private var lastDnsWarnDay = ""

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.ensure(this)
        blocker = DomainBlocker(this)
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, Notifier.CH_MON)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("DoomGuard protection is active")
            .setContentText("Tap to open")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // nothing shown on the lock screen
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A foreground service must call startForeground promptly, even if it is about to stop.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(3, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(3, notification())
        }
        if (intent?.action == ACTION_STOP || !Store.webFilterActive()) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!alive) startFilter()
        return START_STICKY
    }

    private fun startFilter() {
        alive = true
        pool = Executors.newFixedThreadPool(16)
        Thread({
            try {
                blocker.load()
                refreshUpstream()
                val builder = Builder()
                    .setSession("DoomGuard website filter")
                    .addAddress(VPN_ADDR, 32)
                    .addDnsServer(DNS_ADDR)
                    .addRoute(DNS_ADDR, 32)
                    .setMtu(1500)
                if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
                val fd = builder.establish()
                if (fd == null) {
                    Store.logEvent("tamper", "Website filter could not start (VPN permission missing?)")
                    alive = false
                    stopSelf()
                    return@Thread
                }
                tun = fd
                running = true
                startHousekeeping()
                loop(fd)
            } catch (e: Exception) {
                Log.e("DoomGuard", "filter crashed", e)
            } finally {
                running = false
            }
        }, "filter-loop").start()
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (alive) {
            val n = try { input.read(buf) } catch (e: IOException) { -1 }
            if (n < 0) break
            if (n > 0) {
                try {
                    handle(buf, n, output)
                } catch (e: Exception) {
                    Log.w("DoomGuard", "bad packet", e)
                }
            }
        }
    }

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun handle(p: ByteArray, len: Int, out: FileOutputStream) {
        if (len < 28 || (p[0].toInt() shr 4) != 4) return // IPv4 only
        val ihl = (p[0].toInt() and 0x0F) * 4
        if ((p[9].toInt() and 0xFF) == 6) { // TCP to the fake DNS address (e.g. DNS-over-TLS probes): refuse at once
            resetTcp(p, len, ihl, out)
            return
        }
        if ((p[9].toInt() and 0xFF) != 17 || len < ihl + 8) return // UDP only
        if (u16(p, ihl + 2) != 53) return
        val srcPort = u16(p, ihl)
        val dnsStart = ihl + 8
        val dnsLen = minOf(u16(p, ihl + 4) - 8, len - dnsStart)
        if (dnsLen < 12) return
        val dns = p.copyOfRange(dnsStart, dnsStart + dnsLen)
        val clientIp = p.copyOfRange(12, 16)
        val serverIp = p.copyOfRange(16, 20)

        val q = parseQuestion(dns)
        val verdict = if (q != null) blocker.check(q.first) else 0
        if (verdict != 0) {
            when (verdict) {
                1 -> adultCount.incrementAndGet()
                3 -> customCount.incrementAndGet()
            }
            reply(out, clientIp, serverIp, srcPort, nxdomain(dns, q!!.second))
        } else {
            try {
                pool?.execute {
                    val r = forward(dns)
                    if (r != null) reply(out, clientIp, serverIp, srcPort, r)
                }
            } catch (e: Exception) {
                // pool already shut down
            }
        }
    }

    /**
     * Android may try DNS-over-TLS (TCP 853) or TCP DNS against the filter's address. Nothing listens there, so a
     * silent drop would make every lookup wait for a timeout. Answering with a reset makes it fall back to plain UDP at once.
     */
    private fun resetTcp(p: ByteArray, len: Int, ihl: Int, out: FileOutputStream) {
        if (len < ihl + 20) return
        val flags = p[ihl + 13].toInt() and 0xFF
        if ((flags and 0x04) != 0) return // already a reset
        val seq = ((p[ihl + 4].toLong() and 0xFF) shl 24) or ((p[ihl + 5].toLong() and 0xFF) shl 16) or
            ((p[ihl + 6].toLong() and 0xFF) shl 8) or (p[ihl + 7].toLong() and 0xFF)
        val ackIn = p.copyOfRange(ihl + 8, ihl + 12)
        val syn = (flags and 0x02) != 0
        val b = ByteArray(40)
        b[0] = 0x45; b[2] = 0; b[3] = 40; b[8] = 64; b[9] = 6
        System.arraycopy(p, 16, b, 12, 4) // from the address the app tried
        System.arraycopy(p, 12, b, 16, 4)
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val ipc = sum.inv() and 0xFFFF
        b[10] = (ipc shr 8).toByte(); b[11] = ipc.toByte()
        b[20] = p[ihl + 2]; b[21] = p[ihl + 3] // ports swapped
        b[22] = p[ihl]; b[23] = p[ihl + 1]
        if (syn) {
            val ack = (seq + 1) and 0xFFFFFFFFL
            b[28] = (ack shr 24).toByte(); b[29] = (ack shr 16).toByte(); b[30] = (ack shr 8).toByte(); b[31] = ack.toByte()
            b[33] = 0x14.toByte() // RST + ACK
        } else {
            System.arraycopy(ackIn, 0, b, 24, 4) // sequence = the ack they sent
            b[33] = 0x04 // RST
        }
        b[32] = 0x50 // header length 20 bytes
        var t = 0
        for (i in 12 until 20 step 2) t += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        t += 6 + 20
        for (i in 20 until 40 step 2) t += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        while ((t shr 16) != 0) t = (t and 0xFFFF) + (t shr 16)
        val tc = t.inv() and 0xFFFF
        b[36] = (tc shr 8).toByte(); b[37] = tc.toByte()
        synchronized(out) {
            try { out.write(b) } catch (e: IOException) { /* tunnel closed */ }
        }
    }

    /** Reads the first question of a DNS query: its host name and the offset where the question ends. */
    private fun parseQuestion(d: ByteArray): Pair<String, Int>? {
        if (u16(d, 4) < 1) return null
        var pos = 12
        val sb = StringBuilder()
        while (true) {
            if (pos >= d.size) return null
            val l = d[pos].toInt() and 0xFF
            if (l == 0) { pos++; break }
            if (l >= 0xC0 || pos + 1 + l > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..l) sb.append((d[pos + i].toInt() and 0xFF).toChar())
            pos += l + 1
        }
        if (pos + 4 > d.size) return null
        return sb.toString() to (pos + 4)
    }

    /** "No such domain": same id and question, QR and RA set, RCODE 3, no answers. */
    private fun nxdomain(q: ByteArray, qEnd: Int): ByteArray {
        val r = q.copyOfRange(0, qEnd)
        r[2] = (0x80 or (r[2].toInt() and 0x79)).toByte() // QR + keep opcode and RD
        r[3] = 0x83.toByte()                                // RA + RCODE 3
        r[4] = 0; r[5] = 1                                  // one question
        for (i in 6..11) r[i] = 0                           // no answers, authority or additional
        return r
    }

    private fun forward(query: ByteArray): ByteArray? {
        for (target in listOfNotNull(upstream, FALLBACK_DNS).distinct()) {
            val s = DatagramSocket()
            try {
                protect(s) // keep this socket outside the tunnel
                s.soTimeout = 2000
                s.connect(InetSocketAddress(target, 53))
                s.send(DatagramPacket(query, query.size))
                val buf = ByteArray(4096)
                val pk = DatagramPacket(buf, buf.size)
                s.receive(pk)
                return buf.copyOf(pk.length)
            } catch (e: Exception) {
                // try the next server
            } finally {
                s.close()
            }
        }
        return null
    }

    /** Wraps a DNS answer in IPv4 + UDP headers (addresses and ports swapped) and writes it to the tunnel. */
    private fun reply(out: FileOutputStream, clientIp: ByteArray, serverIp: ByteArray, clientPort: Int, dns: ByteArray) {
        val total = 28 + dns.size
        val b = ByteArray(total)
        b[0] = 0x45
        b[2] = (total shr 8).toByte(); b[3] = total.toByte()
        b[8] = 64; b[9] = 17
        System.arraycopy(serverIp, 0, b, 12, 4)
        System.arraycopy(clientIp, 0, b, 16, 4)
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = sum.inv() and 0xFFFF
        b[10] = (c shr 8).toByte(); b[11] = c.toByte()
        b[20] = 0; b[21] = 53
        b[22] = (clientPort shr 8).toByte(); b[23] = clientPort.toByte()
        val udpLen = 8 + dns.size
        b[24] = (udpLen shr 8).toByte(); b[25] = udpLen.toByte() // UDP checksum 0 = none, allowed over IPv4
        System.arraycopy(dns, 0, b, 28, dns.size)
        synchronized(out) {
            try { out.write(b) } catch (e: IOException) { /* tunnel closed */ }
        }
    }

    /** Uses the DNS server of the real (non-VPN) network, so the filter doesn't change who answers your lookups. */
    private fun refreshUpstream() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val dns = cm.getLinkProperties(n)?.dnsServers?.firstOrNull { it is Inet4Address }
                if (dns != null) { upstream = dns; return }
            }
        } catch (e: Exception) {
            // keep the old value, or the fallback
        }
    }

    private fun startHousekeeping() {
        Thread({
            while (alive) {
                try { Thread.sleep(15_000) } catch (e: InterruptedException) { break }
                if (!alive) break
                flush(false)
                blocker.reloadCustom()
                refreshUpstream()
                if (!Store.webFilterActive()) { // the 24-hour wait ended
                    shutdown()
                    stopSelf()
                    break
                }
                checkPrivateDns()
            }
        }, "filter-housekeeping").also { it.isDaemon = true }.start()
    }

    private fun checkPrivateDns() {
        if (!WebFilter.privateDnsStrict(this) || lastDnsWarnDay == today()) return
        lastDnsWarnDay = today()
        // Logged for the guardian, but deliberately not announced with a notification that explains the workaround.
        Store.logEvent("tamper", "Private DNS was set to a custom server")
    }

    /** Saves counts, and writes at most one summary line to the guardian log every 30 minutes. */
    private fun flush(force: Boolean) {
        val a = adultCount.getAndSet(0)
        val c = customCount.getAndSet(0)
        if (a + c > 0) Store.addBlocked(today(), a + c)
        logAdult += a; logCustom += c
        val now = System.currentTimeMillis()
        val pending = logAdult + logCustom
        if (pending > 0 && (force || now - lastEventLog > 30 * 60_000L)) {
            val parts = ArrayList<String>()
            if (logAdult > 0) parts += "$logAdult adult"
            if (logCustom > 0) parts += "$logCustom from your own list"
            Store.logEvent("blocked", "Website filter blocked $pending attempts (${parts.joinToString(", ")})")
            logAdult = 0; logCustom = 0
            lastEventLog = now
        }
    }

    private fun shutdown() {
        if (!alive && tun == null) return
        alive = false
        running = false
        try { flush(true) } catch (e: Exception) { /* store not ready */ }
        try { tun?.close() } catch (e: IOException) { /* already closed */ }
        tun = null
        pool?.shutdownNow()
        pool = null
    }

    /** The system turned the VPN off (user switched it off in Settings, or another VPN app took over). */
    override fun onRevoke() {
        if (Store.webFilterActive()) {
            Store.logEvent("tamper", "Website filter was turned off in system settings, or another VPN replaced it")
            Notifier.post(
                this, 2020, Notifier.CH_NUDGE,
                "Website filter is off",
                "Tap to open DoomGuard and turn it back on."
            )
            Watchdog.schedule(this, 20_000L)
        }
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        if (Store.webFilterActive()) Watchdog.schedule(this, 10_000L)
        super.onDestroy()
    }
}
