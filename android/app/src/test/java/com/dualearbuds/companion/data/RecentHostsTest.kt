package com.dualearbuds.companion.data

import com.dualearbuds.companion.net.HostKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentHostsTest {
    private fun h(ip: String, key: String = "ABCD2345", name: String = ip, kind: HostKind = HostKind.LAPTOP) =
        RecentHost("http://$ip:8790/?k=$key", name, kind)

    @Test
    fun newestFirstAndCappedAtFive() {
        var list = emptyList<RecentHost>()
        for (i in 1..7) list = RecentHosts.add(list, h("10.0.0.$i"))
        assertEquals(5, list.size)
        assertEquals("10.0.0.7", list.first().name)
        assertEquals("10.0.0.3", list.last().name)
    }

    @Test
    fun sameAddressReplacesOldEntry() {
        var list = RecentHosts.add(emptyList(), h("10.0.0.1", "AAAA2222", "Old"))
        list = RecentHosts.add(list, h("10.0.0.2"))
        list = RecentHosts.add(list, h("10.0.0.1", "BBBB3333", "New", HostKind.PHONE))
        assertEquals(listOf("New", "10.0.0.2"), list.map { it.name })
        assertEquals("http://10.0.0.1:8790/?k=BBBB3333", list.first().link)
        assertEquals(HostKind.PHONE, list.first().kind)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val list = listOf(h("10.0.0.1", name = "LAPTOP\tONE"), h("10.0.0.2", name = "Pixel 8", kind = HostKind.PHONE))
        val back = RecentHosts.decode(RecentHosts.encode(list))
        assertEquals(listOf("LAPTOP ONE", "Pixel 8"), back.map { it.name })
        assertEquals(HostKind.PHONE, back[1].kind)
        assertEquals(list[0].link, back[0].link)
    }

    @Test
    fun decodeIgnoresJunk() {
        assertEquals(emptyList<RecentHost>(), RecentHosts.decode(null))
        assertEquals(1, RecentHosts.decode("garbage\nWEIRD\tName\thttp://1.2.3.4:8790/?k=ABCD2345\n\t\t").size)
        assertEquals(HostKind.UNKNOWN, RecentHosts.decode("WEIRD\tName\thttp://x/?k=ABCD2345")[0].kind)
    }

    @Test
    fun remove() {
        val list = listOf(h("10.0.0.1"), h("10.0.0.2"))
        assertEquals(listOf("10.0.0.2"), RecentHosts.remove(list, "http://10.0.0.1:8790/?k=ZZZZ2222").map { it.name })
    }

    @Test
    fun hostKindFromWire() {
        assertEquals(HostKind.LAPTOP, HostKind.fromWire("windows"))
        assertEquals(HostKind.PHONE, HostKind.fromWire(" Android "))
        assertEquals(HostKind.UNKNOWN, HostKind.fromWire(null))
        assertEquals(HostKind.UNKNOWN, HostKind.fromWire("linux"))
    }
}
