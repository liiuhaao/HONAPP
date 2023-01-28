package com.example.honapp

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.honapp.ui.theme.HONAPPTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HONVpn"
        private const val VPN_REQUEST_CODE = 0
    }

    private var show = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HONAPPTheme {
                SetContentView()
            }
        }
    }

    @Composable
    fun SetContentView() {
        var count by remember { mutableStateOf(1) }
        Row(
            modifier = Modifier.padding(20.dp),
        ) {
            Button(onClick = ::startVpn) {
                Text(text = "startVpn")
            }
            Spacer(modifier = Modifier.width(20.dp))
            Button(onClick = ::stopVpn) {
                Text(text = "stopVpn")
            }
        }
    }

    private fun startVpn() {
        intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            val intent = Intent(this, HONVpnService::class.java)
            startService(intent)
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, HONVpnService::class.java)
        intent.putExtra("COMMAND", "STOP")
        startService(intent)
    }
}

