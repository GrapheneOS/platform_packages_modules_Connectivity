/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server

import android.net.InetAddresses
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.VpnManager.TYPE_VPN_SERVICE
import android.net.VpnTransportInfo
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.TestableNetworkCallback
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify

private const val VPN_IFNAME = "tun10041"
private const val VPN_IFNAME2 = "tun10042"
private const val WIFI_IFNAME = "wlan0"
private const val TIMEOUT_MS = 1_000L
private const val LONG_TIMEOUT_MS = 5_000

private fun vpnNc() = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_VPN)
        .removeCapability(NET_CAPABILITY_NOT_VPN)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .setTransportInfo(
                VpnTransportInfo(
                        TYPE_VPN_SERVICE,
                        "MySession12345",
                        false /* bypassable */,
                        false /* longLivedTcpConnectionsExpensive */))
        .build()

private fun wifiNc() = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()

private fun nr(transport: Int) = NetworkRequest.Builder()
        .clearCapabilities()
        .addTransportType(transport).apply {
            if (transport != TRANSPORT_VPN) {
                addCapability(NET_CAPABILITY_NOT_VPN)
            }
        }.build()

private fun lp(iface: String, vararg linkAddresses: LinkAddress) = LinkProperties().apply {
    interfaceName = iface
    for (linkAddress in linkAddresses) {
        addLinkAddress(linkAddress)
    }
}

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class CSIngressDiscardRuleTests : CSTest() {
    private val IPV6_ADDRESS = InetAddresses.parseNumericAddress("2001:db8:1::1")
    private val IPV6_LINK_ADDRESS = LinkAddress(IPV6_ADDRESS, 64)
    private val IPV6_ADDRESS2 = InetAddresses.parseNumericAddress("2001:db8:1::2")
    private val IPV6_LINK_ADDRESS2 = LinkAddress(IPV6_ADDRESS2, 64)
    private val IPV6_ADDRESS3 = InetAddresses.parseNumericAddress("2001:db8:1::3")
    private val IPV6_LINK_ADDRESS3 = LinkAddress(IPV6_ADDRESS3, 64)
    private val LOCAL_IPV6_ADDRRESS = InetAddresses.parseNumericAddress("fe80::1234")
    private val LOCAL_IPV6_LINK_ADDRRESS = LinkAddress(LOCAL_IPV6_ADDRRESS, 64)

    @Test
    fun testVpnIngressDiscardRule_UpdateVpnAddress() {
        // non-VPN network whose address will be not duplicated with VPN address
        val wifiNc = wifiNc()
        val wifiLp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS3)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()

        val nr = nr(TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(nr, cb)
        val nc = vpnNc()
        val lp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val agent = Agent(nc = nc, lp = lp)
        agent.connect()
        cb.expectAvailableCallbacks(agent.network, validated = false)

        // IngressDiscardRule is added to the VPN address
        verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        verify(bpfNetMaps, never()).setIngressDiscardRule(LOCAL_IPV6_ADDRRESS, VPN_IFNAME)

        // The VPN address is changed
        val newLp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS2, LOCAL_IPV6_LINK_ADDRRESS)
        agent.sendLinkProperties(newLp)
        cb.expect<LinkPropertiesChanged>(agent.network)

        // IngressDiscardRule is removed from the old VPN address and added to the new VPN address
        verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)
        verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS2, VPN_IFNAME)
        verify(bpfNetMaps, never()).setIngressDiscardRule(LOCAL_IPV6_ADDRRESS, VPN_IFNAME)

        agent.disconnect()
        verify(bpfNetMaps, timeout(TIMEOUT_MS)).removeIngressDiscardRule(IPV6_ADDRESS2)

        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testVpnIngressDiscardRule_UpdateInterfaceName() {
        val inorder = inOrder(bpfNetMaps)

        val nr = nr(TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(nr, cb)
        val nc = vpnNc()
        val lp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val agent = Agent(nc = nc, lp = lp)
        agent.connect()
        cb.expectAvailableCallbacks(agent.network, validated = false)

        // IngressDiscardRule is added to the VPN address
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        inorder.verifyNoMoreInteractions()

        // The VPN interface name is changed
        val newlp = lp(VPN_IFNAME2, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        agent.sendLinkProperties(newlp)
        cb.expect<LinkPropertiesChanged>(agent.network)

        // IngressDiscardRule is updated with the new interface name
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME2)
        inorder.verifyNoMoreInteractions()

        agent.disconnect()
        inorder.verify(bpfNetMaps, timeout(TIMEOUT_MS)).removeIngressDiscardRule(IPV6_ADDRESS)

        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testVpnIngressDiscardRule_DuplicatedIpAddress_UpdateVpnAddress() {
        val inorder = inOrder(bpfNetMaps)

        val wifiNc = wifiNc()
        val wifiLp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()

        // IngressDiscardRule is not added to non-VPN interfaces
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(any(), any())

        val nr = nr(TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)
        val vpnNc = vpnNc()
        val vpnLp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val vpnAgent = Agent(nc = vpnNc, lp = vpnLp)
        vpnAgent.connect()
        cb.expectAvailableCallbacks(vpnAgent.network, validated = false)

        // IngressDiscardRule is not added since the VPN address is duplicated with the Wi-Fi
        // address
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(any(), any())

        // The VPN address is changed to a different address from the Wi-Fi interface
        val newVpnlp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS2, LOCAL_IPV6_LINK_ADDRRESS)
        vpnAgent.sendLinkProperties(newVpnlp)

        // IngressDiscardRule is added to the VPN address since the VPN address is not duplicated
        // with the Wi-Fi address
        cb.expect<LinkPropertiesChanged>(vpnAgent.network)
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS2, VPN_IFNAME)

        // The VPN address is changed back to the same address as the Wi-Fi interface
        vpnAgent.sendLinkProperties(vpnLp)
        cb.expect<LinkPropertiesChanged>(vpnAgent.network)

        // IngressDiscardRule for IPV6_ADDRESS2 is removed but IngressDiscardRule for
        // IPV6_LINK_ADDRESS is not added since Wi-Fi also uses IPV6_LINK_ADDRESS
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS2)
        inorder.verifyNoMoreInteractions()

        vpnAgent.disconnect()
        inorder.verifyNoMoreInteractions()

        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testVpnIngressDiscardRule_DuplicatedIpAddress_UpdateNonVpnAddress() {
        val inorder = inOrder(bpfNetMaps)

        val vpnNc = vpnNc()
        val vpnLp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val vpnAgent = Agent(nc = vpnNc, lp = vpnLp)
        vpnAgent.connect()

        // IngressDiscardRule is added to the VPN address
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        inorder.verifyNoMoreInteractions()

        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)
        val wifiNc = wifiNc()
        val wifiLp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // IngressDiscardRule is removed since the VPN address is duplicated with the Wi-Fi address
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        // The Wi-Fi address is changed to a different address from the VPN interface
        val newWifilp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS2, LOCAL_IPV6_LINK_ADDRRESS)
        wifiAgent.sendLinkProperties(newWifilp)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // IngressDiscardRule is added to the VPN address since the VPN address is not duplicated
        // with the Wi-Fi address
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        inorder.verifyNoMoreInteractions()

        // The Wi-Fi address is changed back to the same address as the VPN interface
        wifiAgent.sendLinkProperties(wifiLp)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // IngressDiscardRule is removed since the VPN address is duplicated with the Wi-Fi address
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        // IngressDiscardRule is added to the VPN address since Wi-Fi is disconnected
        wifiAgent.disconnect()
        inorder.verify(bpfNetMaps, timeout(TIMEOUT_MS))
                .setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)

        vpnAgent.disconnect()
        inorder.verify(bpfNetMaps, timeout(TIMEOUT_MS)).removeIngressDiscardRule(IPV6_ADDRESS)

        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testVpnIngressDiscardRule_UnregisterAfterReplacement() {
        val wifiNc = wifiNc()
        val wifiLp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        wifiAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)
        waitForIdle()

        val vpnNc = vpnNc()
        val vpnLp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val vpnAgent = Agent(nc = vpnNc, lp = vpnLp)
        vpnAgent.connect()

        // IngressDiscardRule is added since the Wi-Fi network is destroyed
        verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)

        // IngressDiscardRule is removed since the VPN network is destroyed
        vpnAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)
        waitForIdle()
        verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)
    }
}
