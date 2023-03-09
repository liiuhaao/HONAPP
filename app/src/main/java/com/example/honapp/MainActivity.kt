package com.example.honapp

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.honapp.ui.theme.HONAPPTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
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
        var dropRate by remember { mutableStateOf(0f) }
        var parityRate by remember { mutableStateOf(0f) }
        var ipAddress by remember { mutableStateOf("106.75.227.236") }
        var port by remember { mutableStateOf("54345") }

        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                content = {
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
                        singleLine = true
                    )
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
            )

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
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
            val vpnIntent = Intent(this, HONVpnService::class.java)
            vpnIntent.putExtra("DROP_RATE", dropRate.toInt())
            vpnIntent.putExtra("PARITY_RATE", parityRate.toInt())
            vpnIntent.putExtra("IP_ADDRESS", ipAddress)
            vpnIntent.putExtra("PARITY_RATE", port.toInt())
            startService(vpnIntent)
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, HONVpnService::class.java)
        intent.putExtra("COMMAND", "STOP")
        startService(intent)
    }
}

