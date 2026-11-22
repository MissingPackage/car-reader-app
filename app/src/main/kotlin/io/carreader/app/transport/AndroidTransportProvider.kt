package io.carreader.app.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.carreader.core.transport.Transport
import io.carreader.core.transport.TransportException
import java.net.Socket

class AndroidTransportProvider(context: Context) : TransportProvider {
    private val context = context.applicationContext

    override fun create(target: ConnectTarget): Transport = when (target) {
        is ConnectTarget.BluetoothClassic -> {
            val adapter = bluetoothAdapter(context) ?: throw TransportException("Bluetooth is not available on this phone")
            BluetoothClassicTransport(adapter, adapter.getRemoteDevice(target.address), target.name)
        }
        is ConnectTarget.Ble -> {
            val adapter = bluetoothAdapter(context) ?: throw TransportException("Bluetooth is not available on this phone")
            BleTransport(context, adapter.getRemoteDevice(target.address), target.name)
        }
        is ConnectTarget.Wifi -> TcpTransport(target.host, target.port, socketFactory = ::wifiSocket)
        is ConnectTarget.Demo -> DemoTransports.create(target.car)
    }

    /**
     * A socket bound to the Wi-Fi network when there is one. OBD Wi-Fi adapters create an access point without
     * internet; Android then prefers mobile data for new sockets unless they are bound explicitly.
     */
    @Suppress("DEPRECATION")
    private fun wifiSocket(): Socket {
        val socket = Socket()
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return socket
        val wifi = runCatching {
            cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrNull()
        if (wifi != null) runCatching { wifi.bindSocket(socket) }
        return socket
    }

    companion object {
        fun bluetoothAdapter(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter
    }
}
