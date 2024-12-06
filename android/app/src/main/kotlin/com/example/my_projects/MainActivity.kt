package com.example.my_projects

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.VpnService
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.multidex.MultiDex
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader
import java.util.ArrayList
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.OpenVPNThread
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper

class MainActivity : FlutterActivity() {
    private lateinit var vpnControlMethod: MethodChannel
    private lateinit var vpnControlEvent: EventChannel
    private lateinit var vpnStatusEvent: EventChannel
    private var vpnStageSink: EventChannel.EventSink? = null
    private var vpnStatusSink: EventChannel.EventSink? = null

    companion object {
        private const val EVENT_CHANNEL_VPN_STAGE = "vpnStage"
        private const val EVENT_CHANNEL_VPN_STATUS = "vpnStatus"
        private const val METHOD_CHANNEL_VPN_CONTROL = "vpnControl"
        private const val VPN_REQUEST_ID = 1
        private const val TAG = "VPN"
    }

    private var vpnProfile: VpnProfile? = null

    private var config: String = ""
    private var username: String = ""
    private var password: String = ""
    private var name: String = ""
    private var dns1: String = VpnProfile.DEFAULT_DNS1
    private var dns2: String = VpnProfile.DEFAULT_DNS2

    private var bypassPackages: ArrayList<String>? = null

    private var attached: Boolean = true

    private var localJson: JSONObject? = null

    override fun finish() {
        vpnControlEvent.setStreamHandler(null)
        vpnControlMethod.setMethodCallHandler(null)
        vpnStatusEvent.setStreamHandler(null)
        super.finish()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        MultiDex.install(this)
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val stage = intent?.getStringExtra("state")
                stage?.let { setStage(it) }

                vpnStatusSink?.let { sink ->
                    try {
                        val duration = intent?.getStringExtra("duration") ?: "00:00:00"
                        val lastPacketReceive = intent?.getStringExtra("lastPacketReceive") ?: "0"
                        val byteIn = intent?.getStringExtra("byteIn") ?: " "
                        val byteOut = intent?.getStringExtra("byteOut") ?: " "

                        val jsonObject = JSONObject().apply {
                            put("duration", duration)
                            put("last_packet_receive", lastPacketReceive)
                            put("byte_in", byteIn)
                            put("byte_out", byteOut)
                        }

                        localJson = jsonObject

                        if (attached) sink.success(jsonObject.toString())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
               broadcastReceiver,
               IntentFilter("connectionState")
        )
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        vpnControlEvent = EventChannel(
               flutterEngine.dartExecutor.binaryMessenger,
               EVENT_CHANNEL_VPN_STAGE
        )
        vpnControlEvent.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                vpnStageSink = events
            }

            override fun onCancel(arguments: Any?) {
                vpnStageSink?.endOfStream()
                vpnStageSink = null
            }
        })

        vpnStatusEvent = EventChannel(
               flutterEngine.dartExecutor.binaryMessenger,
               EVENT_CHANNEL_VPN_STATUS
        )
        vpnStatusEvent.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                vpnStatusSink = events
            }

            override fun onCancel(arguments: Any?) {
                vpnStatusSink = null
            }
        })

        vpnControlMethod = MethodChannel(
               flutterEngine.dartExecutor.binaryMessenger,
               METHOD_CHANNEL_VPN_CONTROL
        )
        vpnControlMethod.setMethodCallHandler { call, result ->
            when (call.method) {
                "stop" -> {
                    OpenVPNThread.stop()
                    setStage("disconnected")
                    result.success(null)
                }
                "start" -> {
                    config = call.argument<String>("config") ?: ""
                    name = call.argument<String>("country") ?: ""
                    username = call.argument<String>("username") ?: ""
                    password = call.argument<String>("password") ?: ""

                    call.argument<String>("dns1")?.let { dns1 = it }
                    call.argument<String>("dns2")?.let { dns2 = it }

                    bypassPackages = call.argument("bypass_packages")

                    if (config.isEmpty() || name.isEmpty()) {
                        Log.e(TAG, "Config not valid!")
                        result.error("INVALID_CONFIG", "Config or name is null", null)
                        return@setMethodCallHandler
                    }

                    prepareVPN()
                    result.success(null)
                }
                "refresh" -> {
                    updateVPNStages()
                    result.success(null)
                }
                "refresh_status" -> {
                    updateVPNStatus()
                    result.success(null)
                }
                "stage" -> {
                    result.success(OpenVPNService.getStatus())
                }
                "kill_switch" -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                        startActivity(intent)
                        result.success(null)
                    } else {
                        result.notImplemented()
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun prepareVPN() {
        if (isConnected()) {
            setStage("prepare")

            try {
                val configParser = ConfigParser()
                configParser.parseConfig(StringReader(config))
                vpnProfile = configParser.convertProfile()
            } catch (e: IOException) {
                e.printStackTrace()
                setStage("disconnected")
                return
            } catch (e: ConfigParser.ConfigParseError) {
                e.printStackTrace()
                setStage("disconnected")
                return
            }

            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, VPN_REQUEST_ID)
            } else {
                startVPN()
            }
        } else {
            setStage("nonetwork")
        }
    }

    private fun startVPN() {
        try {
            setStage("connecting")

            vpnProfile?.let { profile ->
                val checkResult = profile.checkProfile(this)
                if (checkResult != de.blinkt.openvpn.R.string.no_error_found) {
                    throw RemoteException(getString(checkResult))
                }

                profile.mName = name
                profile.mProfileCreator = packageName
                profile.mUsername = username
                profile.mPassword = password
                profile.mDNS1 = dns1
                profile.mDNS2 = dns2

                if (dns1.isNotEmpty() && dns2.isNotEmpty()) {
                    profile.mOverrideDNS = true
                }

                bypassPackages?.let { packages ->
                    if (packages.isNotEmpty()) {
                        profile.mAllowedAppsVpn.addAll(packages)
                        profile.mAllowAppVpnBypass = true
                    }
                }

                ProfileManager.setTemporaryProfile(this, profile)
                VPNLaunchHelper.startOpenVpn(profile, this)
            } ?: run {
                throw RemoteException("VPN Profile is null")
            }
        } catch (e: RemoteException) {
            setStage("disconnected")
            e.printStackTrace()
        }
    }

    private fun updateVPNStages() {
        setStage(OpenVPNService.getStatus())
    }

    private fun updateVPNStatus() {
        localJson?.let { json ->
            if (attached) {
                vpnStatusSink?.success(json.toString())
            }
        }
    }

    private fun isConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nInfo: NetworkInfo? = cm.activeNetworkInfo
        return nInfo?.isConnectedOrConnecting == true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_REQUEST_ID) {
            if (resultCode == RESULT_OK) {
                startVPN()
            } else {
                setStage("denied")
                Toast.makeText(this, "Permission is denied!", Toast.LENGTH_SHORT).show()
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun setStage(stage: String) {
        when (stage.uppercase()) {
            "CONNECTED" -> {
                if (attached) vpnStageSink?.success("connected")
            }
            "DISCONNECTED" -> {
                if (attached) vpnStageSink?.success("disconnected")
            }
            "WAIT" -> {
                if (attached) vpnStageSink?.success("wait_connection")
            }
            "AUTH" -> {
                if (attached) vpnStageSink?.success("authenticating")
            }
            "RECONNECTING" -> {
                if (attached) vpnStageSink?.success("reconnect")
            }
            "NONETWORK" -> {
                if (attached) vpnStageSink?.success("no_connection")
            }
            "CONNECTING" -> {
                if (attached) vpnStageSink?.success("connecting")
            }
            "PREPARE" -> {
                if (attached) vpnStageSink?.success("prepare")
            }
            "DENIED" -> {
                if (attached) vpnStageSink?.success("denied")
            }
            else -> {
                // Неизвестный статус
                Log.w(TAG, "Unknown VPN stage: $stage")
            }
        }
    }
}
