package com.vibecoderspace.chrootengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsConfiguratorTest {

    @Test
    fun mergeGroupsKeepsExistingLinesAndSkipsKnownGids() {
        val existing = "root:x:0:\nandroid_app:x:20455:\n"
        val merged = RootfsConfigurator.mergeGroups(
            existing,
            listOf(
                "root:x:0:",
                "android_app:x:20455:",
                "android_cache:x:20123:",
                "android_storage:x:40123:",
            ),
        )

        assertEquals(
            "root:x:0:\nandroid_app:x:20455:\n" +
                "android_cache:x:20123:\nandroid_storage:x:40123:\n",
            merged,
        )
    }

    @Test
    fun mergeGroupsAddsTrailingNewlineWhenNeeded() {
        assertEquals(
            "root:x:0:\nnew:x:1:\n",
            RootfsConfigurator.mergeGroups("root:x:0:", listOf("new:x:1:")),
        )
    }

    @Test
    fun androidGroupsUseTheAppGid() {
        val additions = RootfsConfigurator.androidGroupAdditions(10123L)

        assertTrue(additions.contains("android_cache:x:20123"))
        assertTrue(additions.contains("android_storage:x:50123"))
        assertTrue(additions.contains("android_media:x:60123"))
        assertTrue(additions.contains("android_external_storage:x:1077"))
    }

    @Test
    fun resolvConfListsEveryServer() {
        assertEquals(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n",
            RootfsConfigurator.resolvConf(listOf("1.1.1.1", "8.8.8.8")),
        )
    }

    @Test
    fun hostsContainsLocalhostAndIpv6Entries() {
        val hosts = RootfsConfigurator.hosts()

        assertTrue(hosts.contains("127.0.0.1   localhost"))
        assertTrue(hosts.contains("::1         localhost"))
        assertTrue(hosts.contains("ff02::3     ip6-allhosts"))
    }

    @Test
    fun hostnameIsLocalhost() {
        assertEquals("localhost\n", RootfsConfigurator.hostname())
    }
}
