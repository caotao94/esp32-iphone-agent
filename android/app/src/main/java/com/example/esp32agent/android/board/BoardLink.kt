package com.example.esp32agent.android.board

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.now
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The BLE central for the board: scan, connect, subscribe, write commands, read the heartbeat.
 * Port of Board/BoardLink.swift (CoreBluetooth) onto Android's BluetoothGatt; docs/ble-protocol.md is the
 * contract. Everything is marshalled onto [scope]'s dispatcher (Main), so [CommandChannel] never sees a
 * binder thread.
 *
 * The iPhone app also uses the `video` characteristic (AirPlay frames over BLE); this app captures the
 * screen itself, so it subscribes to `reply` and `status` only.
 */
@SuppressLint("MissingPermission")  // the caller checks BLUETOOTH_SCAN / CONNECT / location first
class BoardLink(private val context: Context, private val scope: CoroutineScope) : BoardTransport {

    data class Found(val address: String, val name: String, val rssi: Int)

    /** Everything the board screen shows. */
    data class Ui(
        val bluetoothOn: Boolean = false,
        val scanning: Boolean = false,
        val connecting: Boolean = false,
        val linking: Boolean = false,     // connected over the air, discovering / subscribing
        val connected: Boolean = false,   // subscribed and ready for commands
        val found: List<Found> = emptyList(),
        val paired: String? = null,
        val name: String = "",
        val rssi: Int? = null,
        val status: BoardStatus? = null,
        val net: NetConfig? = null,
        val lastError: String = "",
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    val commands = CommandChannel(transport = this)

    var onLinkUp: (() -> Unit)? = null
    var onLinkDown: (() -> Unit)? = null
    var onStatus: ((BoardStatus) -> Unit)? = null
    var log: (String) -> Unit = { DebugLog.write(it) }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var device: BluetoothDevice? = null
    private var ready = false
    private var wantLink = false
    private var retryJob: Job? = null
    private var retryGap = 1000L
    private var scanJob: Job? = null
    private val toSubscribe = ArrayDeque<BluetoothGattCharacteristic>()
    private var netWaiter: CompletableDeferred<NetConfig?>? = null
    private var statusWaiter: CompletableDeferred<BoardStatus?>? = null

    init {
        commands.transport = this
        // Android 12+ can reject this query before the first Bluetooth permission prompt.
        _ui.value = _ui.value.copy(bluetoothOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false))
    }

    val mtu: Int get() = _ui.value.status?.ble?.mtu ?: 0

    // MARK: - scan / pair

    fun scan(seconds: Double = 12.0) {
        val a = adapter
        if (a == null || !a.isEnabled) {
            _ui.value = _ui.value.copy(bluetoothOn = false, lastError = "Turn on Bluetooth to find the board.")
            return
        }
        if (ready || _ui.value.connecting || _ui.value.linking || _ui.value.scanning) return
        _ui.value = _ui.value.copy(bluetoothOn = true, scanning = true, found = emptyList(), lastError = "")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // No ScanFilter: a 128-bit service UUID is often left out of the advertisement, so accept the
        // service or the board's name (see the firmware's ble_begin("ESP32 Agent XXXX")).
        runCatching { a.bluetoothLeScanner.startScan(null, settings, scanCallback) }
            .onFailure { fail("scan failed: ${it.message}") }
        log("BLE scanning for a board")
        scanJob?.cancel()
        scanJob = scope.launch {
            delay((seconds * 1000).toLong())
            stopScan()
            if (_ui.value.found.isEmpty() && !ready) {
                _ui.value = _ui.value.copy(
                    lastError = "No board found. Check its power, keep it nearby, and scan again."
                )
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_ui.value.scanning) _ui.value = _ui.value.copy(scanning = false)
    }

    /** Connect to a board and remember it. Pairing (bonding) happens if the app ever reads `net`. */
    fun pair(address: String) {
        val a = adapter ?: return
        stopScan()
        wantLink = true
        retryGap = 1000L
        val previous = device
        if (previous != null && previous.address != address) {
            closeGatt()
            setDisconnected()
        }
        prefs().edit().putString(KEY_PAIRED, address).apply()
        _ui.value = _ui.value.copy(paired = address, lastError = "", connecting = true)
        device = runCatching { a.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            fail("not a Bluetooth address: $address")
            return
        }
        connect()
    }

    /** Reconnect to the remembered board (app start, or a link that dropped). */
    fun reconnect() {
        val address = _ui.value.paired ?: prefs().getString(KEY_PAIRED, null)
        if (address == null) {
            _ui.value = _ui.value.copy(lastError = "Tap Board to choose a device.")
            return
        }
        if (address != _ui.value.paired) _ui.value = _ui.value.copy(paired = address)
        if (!ready && !wantLink) pair(address)
    }

    /** Ask the board to erase its bonds, then forget it here (Android keeps its own bond in Settings). */
    suspend fun unpair() {
        runCatching { commands.send("unpair") }
        forget()
    }

    /** Forget the board in the app only. */
    fun forget() {
        wantLink = false
        retryJob?.cancel()
        retryJob = null
        closeGatt()
        prefs().edit().remove(KEY_PAIRED).apply()
        _ui.value = Ui(bluetoothOn = adapter?.isEnabled == true, lastError = "Board forgotten.")
        commands.linkDown()
    }

    fun retryDiscovery() {
        forget()
        _ui.value = _ui.value.copy(lastError = "")
        scan()
    }

    private fun connect() {
        val d = device ?: return
        if (adapter?.isEnabled != true) {
            fail("Turn on Bluetooth to reach the board.")
            return
        }
        retryJob?.cancel()
        closeGatt()
        _ui.value = _ui.value.copy(connecting = true, linking = false)
        log("BLE connecting to ${d.address}")
        // Without BLUETOOTH_CONNECT (Android 12+) this throws; report it instead of crashing the app.
        val g = runCatching { d.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (g == null) {
            fail("could not start the connection (is Bluetooth on, and is the permission granted?)")
            return
        }
        gatt = g
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        cmdChar = null
        statusChar = null
        toSubscribe.clear()
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private fun scheduleRetry() {
        if (!wantLink) return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryGap)
            retryGap = (retryGap * 2).coerceAtMost(10_000L)
            if (wantLink && !ready) connect()
        }
    }

    private fun fail(message: String) {
        log(message)
        _ui.value = _ui.value.copy(lastError = message, scanning = false, connecting = false, linking = false)
    }

    private fun setDisconnected() {
        val was = ready
        ready = false
        _ui.value = _ui.value.copy(connected = false, linking = false, connecting = false)
        if (was) {
            commands.linkDown()
            onLinkDown?.invoke()
        }
    }

    private fun setReady(name: String) {
        ready = true
        retryGap = 1000L
        _ui.value = _ui.value.copy(connected = true, connecting = false, linking = false, name = name, lastError = "")
        commands.transport = this
        commands.linkUp()
        log("BLE ready: $name")
        onLinkUp?.invoke()
    }

    private fun prefs() = context.getSharedPreferences("board", Context.MODE_PRIVATE)

    // MARK: - BoardTransport

    override val isConnected: Boolean get() = ready

    override fun write(line: String) {
        val g = gatt ?: throw DeviceError("board not connected")
        val ch = cmdChar ?: throw DeviceError("board not connected")
        if (!ready) throw DeviceError("board not connected")
        val bytes = line.toByteArray(Charsets.UTF_8)
        val ok = writeCharacteristic(g, ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        if (!ok) throw DeviceError("the BLE write was rejected (link busy or gone)")
    }

    override fun writeIfIdle(line: String): Boolean = runCatching { write(line) }.isSuccess

    override val maxWrite: Int get() = (_ui.value.status?.ble?.mtu ?: 23) - 3

    override fun dropLink() {
        log("dropping the BLE link after a timeout")
        runCatching { gatt?.disconnect() }
    }

    // MARK: - reads

    /** The board's `net` characteristic. Reading it needs an encrypted link, so Android may bond first. */
    suspend fun readNet(timeout: Double = 8.0): NetConfig? {
        val g = gatt ?: return null
        val ch = service(g)?.getCharacteristic(BoardUuid.net) ?: return null
        val d = device
        if (d != null && d.bondState != BluetoothDevice.BOND_BONDED) {
            log("bonding with the board (the `net` characteristic is encrypted)")
            runCatching { d.createBond() }
        }
        val waiter = CompletableDeferred<NetConfig?>()
        netWaiter = waiter
        if (!readCharacteristic(g, ch)) {
            netWaiter = null
            return null
        }
        val result = kotlinx.coroutines.withTimeoutOrNull((timeout * 1000).toLong()) { waiter.await() }
        netWaiter = null
        _ui.value = _ui.value.copy(net = result)
        return result
    }

    /** A `status` read always returns the last full JSON, even when the notifies had to be reduced. */
    suspend fun readStatus(timeout: Double = 4.0): BoardStatus? {
        val g = gatt ?: return null
        val ch = statusChar ?: service(g)?.getCharacteristic(BoardUuid.status) ?: return null
        val waiter = CompletableDeferred<BoardStatus?>()
        statusWaiter = waiter
        if (!readCharacteristic(g, ch)) {
            statusWaiter = null
            return null
        }
        val result = kotlinx.coroutines.withTimeoutOrNull((timeout * 1000).toLong()) { waiter.await() }
        statusWaiter = null
        return result
    }

    /** Refresh readiness without forgetting pairing: reconnect if needed, then read a fresh status. */
    suspend fun refreshStatus(): BoardStatus {
        if (adapter?.isEnabled != true) throw DeviceError("Turn on Bluetooth to refresh the board status.")
        if (!ready) {
            if (_ui.value.paired != null) reconnect() else scan()
            val deadline = now() + 8
            while (!ready && now() < deadline &&
                (_ui.value.connecting || _ui.value.linking || _ui.value.scanning)
            ) delay(200)
        }
        if (!ready) {
            val message = _ui.value.lastError.ifEmpty {
                if (_ui.value.paired == null) "Tap Board to choose a device."
                else "Still reconnecting. Keep the board nearby."
            }
            throw DeviceError(message)
        }
        val fresh = readStatus() ?: throw DeviceError("No fresh board status received. Try again.")
        val merged = (_ui.value.status ?: fresh).merged(fresh)
        _ui.value = _ui.value.copy(status = merged)
        return merged
    }

    private fun service(g: BluetoothGatt) = g.services.firstOrNull { it.uuid == BoardUuid.service }

    // MARK: - callbacks

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record: ScanRecord? = result.scanRecord
            val name = record?.deviceName ?: result.device.name ?: BoardUuid.NAME_PREFIX
            val hasService = record?.serviceUuids?.contains(ParcelUuid(BoardUuid.service)) == true
            if (!hasService && !name.startsWith(BoardUuid.NAME_PREFIX)) return
            scope.launch {
                _ui.value = _ui.value.copy(bluetoothOn = true)
                val item = Found(result.device.address, name, result.rssi)
                val found = _ui.value.found.filterNot { it.address == item.address } + item
                _ui.value = _ui.value.copy(found = found, rssi = if (result.rssi < 0) result.rssi else _ui.value.rssi)
                // The remembered board came back into range: connect without waiting for a tap.
                if (item.address == _ui.value.paired && !ready && !_ui.value.linking && !_ui.value.connecting) {
                    stopScan()
                    device = result.device
                    wantLink = true
                    connect()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scope.launch { fail("BLE scan failed (code $errorCode)") }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("BLE connected (status $status), asking for a 517-byte MTU")
                    _ui.value = _ui.value.copy(connecting = false, linking = true)
                    // The board prefers 517 (nimble_cfg.h); a bigger MTU means fewer notifies per command.
                    if (!g.requestMtu(517)) discover(g)
                } else {
                    log("BLE disconnected (status $status)")
                    setDisconnected()
                    scheduleRetry()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                log("BLE mtu $mtu (status $status)")
                discover(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                val svc = service(g)
                if (svc == null) {
                    fail("the board has no agent service (status $status): is this the right device?")
                    runCatching { g.disconnect() }
                    return@launch
                }
                cmdChar = svc.getCharacteristic(BoardUuid.cmd)
                statusChar = svc.getCharacteristic(BoardUuid.status)
                if (cmdChar == null || statusChar == null) {
                    fail("the board's service is missing its cmd/status characteristics")
                    runCatching { g.disconnect() }
                    return@launch
                }
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                toSubscribe.clear()
                listOfNotNull(svc.getCharacteristic(BoardUuid.reply), statusChar).forEach { toSubscribe.addLast(it) }
                subscribeNext(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) log("subscribe failed (status $status)")
                if (!subscribeNext(g)) {
                    val name = device?.name ?: _ui.value.name.ifEmpty { BoardUuid.NAME_PREFIX }
                    setReady(name)
                }
            }
        }

        @Deprecated("API 33 passes the value along; both are handled")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            onNotifyValue(ch.uuid, ch.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotifyValue(ch.uuid, value)
        }

        @Deprecated("API 33 passes the value along; both are handled")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            onReadValue(ch.uuid, ch.value, status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            onReadValue(ch.uuid, value, status)
        }
    }

    /** One subscribe at a time: GATT allows a single outstanding operation. */
    private fun subscribeNext(g: BluetoothGatt): Boolean {
        val ch = toSubscribe.removeFirstOrNull() ?: return false
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(BoardUuid.cccd)
        if (cccd == null) {
            log("no CCCD on ${ch.uuid}")
            return subscribeNext(g)
        }
        if (!writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            log("could not write the CCCD on ${ch.uuid}")
            return subscribeNext(g)
        }
        return true
    }

    private fun onNotifyValue(uuid: UUID, value: ByteArray?) {
        val text = value?.toString(Charsets.UTF_8)?.trim() ?: return
        scope.launch {
            when (uuid) {
                BoardUuid.reply -> commands.handleReply(text)
                BoardUuid.status -> {
                    val s = BoardStatus.decode(text) ?: return@launch
                    val merged = (_ui.value.status ?: s).merged(s)
                    _ui.value = _ui.value.copy(status = merged)
                    statusWaiter?.complete(merged)
                    onStatus?.invoke(merged)
                }
                else -> {}  // `video` is not subscribed: this app captures the screen itself
            }
        }
    }

    private fun onReadValue(uuid: UUID, value: ByteArray?, status: Int) {
        val text = value?.toString(Charsets.UTF_8)?.trim()
        scope.launch {
            when (uuid) {
                BoardUuid.net -> {
                    val net = if (status == BluetoothGatt.GATT_SUCCESS && text != null) NetConfig.decode(text) else null
                    netWaiter?.complete(net)
                    if (net != null) _ui.value = _ui.value.copy(net = net)
                }
                BoardUuid.status -> {
                    val s = if (status == BluetoothGatt.GATT_SUCCESS && text != null) BoardStatus.decode(text) else null
                    if (s != null) {
                        val merged = (_ui.value.status ?: s).merged(s)
                        _ui.value = _ui.value.copy(status = merged)
                        statusWaiter?.complete(merged)
                    } else {
                        statusWaiter?.complete(null)
                    }
                }
                else -> {}
            }
        }
    }

    private fun discover(g: BluetoothGatt) {
        _ui.value = _ui.value.copy(linking = true, connecting = false)
        if (!g.discoverServices()) fail("service discovery did not start")
    }

    companion object {
        private const val KEY_PAIRED = "pairedBoard"

        private fun writeCharacteristic(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int,
        ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = writeType
            ch.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }

        private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }

        private fun readCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.readCharacteristic(ch)
            } else {
                @Suppress("DEPRECATION")
                g.readCharacteristic(ch)
            }
    }
}
