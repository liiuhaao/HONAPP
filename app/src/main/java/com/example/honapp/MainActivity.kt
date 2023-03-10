package com.example.honapp

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.honapp.ui.theme.HONAPPTheme
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() , CoroutineScope by MainScope() {

    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_REQUEST_CODE = 0
    }
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
        var dropRate by remember { mutableStateOf(0f) }
        var parityRate by remember { mutableStateOf(0f) }
        var ipAddress by remember { mutableStateOf("106.75.227.236") }
        var port by remember { mutableStateOf("54345") }

        val ipAddresses = listOf("106.75.227.236", "106.75.231.195")
        var menuExpanded by remember { mutableStateOf(false) }


        Column(modifier = Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { newValue ->
                        ipAddress = newValue
                    },
                    label = { Text("IP地址") },
                    modifier = Modifier.weight(2f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.ArrowDropDown, "")
                        }
                    }
                )

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.weight(2f),
                ) {
                    ipAddresses.forEach { address ->
                        DropdownMenuItem(onClick = {
                            ipAddress = address
                            menuExpanded = false
                        }) {
                            Text(address)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { newValue ->
                        port = newValue
                    },
                    label = { Text("端口号") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "模拟丢包率：${dropRate.toInt()}%")
            Slider(
                value = dropRate, onValueChange = { newPosition ->
                    dropRate = newPosition
                },
                valueRange = 0f..100f,
                steps = 100
            )
            Text(text = "冗余率：${parityRate.toInt()}%")
            Slider(
                value = parityRate, onValueChange = { newPosition ->
                    parityRate = newPosition
                },
                valueRange = 0f..100f,
                steps = 100
            )
            Row(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(onClick = { startVpn(dropRate, parityRate, ipAddress, port) }) {
                    Text(text = "开始")
                }
                Spacer(modifier = Modifier.width(20.dp))
                Button(onClick = ::stopVpn) {
                    Text(text = "结束")
                }
            }
        }
    }

    private fun startVpn(dropRate: Float, parityRate: Float, ipAddress: String, port: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            launch {
                val vpnIntent = Intent(this@MainActivity, HONVpnService::class.java)
                vpnIntent.putExtra("DROP_RATE", dropRate.toInt())
                vpnIntent.putExtra("PARITY_RATE", parityRate.toInt())
                vpnIntent.putExtra("IP_ADDRESS", ipAddress)
                vpnIntent.putExtra("PORT", port.toInt())
                startService(vpnIntent)
            }
        }
    }

    private fun stopVpn() {
        launch {
            val intent = Intent(this@MainActivity, HONVpnService::class.java)
            intent.action = HONVpnService.ACTION_STOP_VPN
            startService(intent)
        }
    }
}

