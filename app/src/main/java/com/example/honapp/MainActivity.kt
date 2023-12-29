package com.example.honapp

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.honapp.ui.theme.HONAPPTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

class MainActivity : ComponentActivity(), CoroutineScope by MainScope() {

    companion object {
        private const val TAG = "HONMainActivity"
        private const val VPN_REQUEST_CODE = 0
        private const val SYNC_PORT = 34543
        private const val REQUEST_PORT = 33333
        private const val TYPE_SYNC_CONFIG: Byte = 0x01
        private const val TYPE_REQUEST_DATA: Byte = 0x02
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HONAPPTheme {
                SetContentView()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("UnusedMaterialScaffoldPaddingParameter", "UnspecifiedRegisterReceiverFlag")
    @Composable
    fun SetContentView() {

        // 导航栏
        val navController = rememberNavController()
        val items = listOf("VPN", "测试")

        // 选取的APP
        var selectedApp by remember { mutableStateOf<AppInfo?>(null) }

        var showDialog by remember { mutableStateOf(false) }

        // 配置
        var config by remember {
            mutableStateOf(
                HONConfig(
                    dropRate = 0,
                    dataNum = 5,
                    parityNum = 0,
                    rxNum = 100,
                    encodeTimeout = 1000000,
                    decodeTimeout = 1000000,
                    rxTimeout = 100000,
                    primaryProbability = 80,
                    ipAddress = "106.75.241.183",
                    port = "54345"
                )
            )
        }

        // ip地址选取
        val ipAddresses = listOf("106.75.241.183", "106.75.227.194", "202.120.87.33")
        var menuExpanded by remember { mutableStateOf(false) }

        // 选择那一条路是主路
        val primaryChannel = remember { mutableStateOf("Cellular") }

        // 显示同步状态
        val syncResult = remember { mutableStateOf("") }

        // 时延测试的配置
        var serverAddress by remember { mutableStateOf("106.75.227.194") }
        var serverPort by remember { mutableStateOf("33333") }
        var packetSize by remember { mutableStateOf(1024) }
        var numTests by remember { mutableStateOf(100) }
        var numThreads by remember { mutableStateOf(10) }

        // 时延测试的输出
        var showResult by remember { mutableStateOf(false) }
        var averageLatency by remember { mutableStateOf(0L) }
        var latencyList by remember { mutableStateOf<List<Long>?>(null) }
        var testJob: Job? = null

        val serverData = remember { mutableStateOf<Map<String, String>?>(null) }
        val clientData = remember { mutableStateOf<Map<String, String>?>(null) }

        // 好像是用来监听vpnIntent的广播，用来接收数据的，gpt写的
        val context = LocalContext.current
        DisposableEffect(Unit) {
            val vpnDataReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == "com.example.ACTION_DATA_AVAILABLE") {
                        val bundle = intent.getBundleExtra("honVpnData")
                        val dataMap = bundle?.keySet()?.associateWith { key ->
                            bundle.getString(key).orEmpty()
                        }
                        clientData.value = dataMap
                    }
                }
            }

            val filter = IntentFilter("com.example.ACTION_DATA_AVAILABLE")
            context.registerReceiver(vpnDataReceiver, filter)

            onDispose {
                context.unregisterReceiver(vpnDataReceiver)
            }
        }

        // 视图，分了两个页面，一个是VPN，一个是测试
        Scaffold(bottomBar = {
            BottomNavigation {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination?.route

                items.forEach { item ->
                    BottomNavigationItem(icon = {
                        when (item) {
                            "VPN" -> Icon(
                                Icons.Default.Home, contentDescription = null
                            )

                            "测试" -> Icon(
                                Icons.Default.Send, contentDescription = null
                            )

                            else -> Unit
                        }
                    },
                        label = { Text(item) },
                        selected = currentDestination == item,
                        onClick = {
                            navController.navigate(item) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        })
                }
            }
        }) {
            NavHost(navController = navController, startDestination = "VPN") {
                // VPN页面
                composable("VPN") {
                    if (syncResult.value.isNotEmpty()) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("同步中......")
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            },
                            text = {
                                Column {
                                    Text(" ${config.toJson()}")
                                }
                            },
                            buttons = {},
                        )
                    }

                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedTextField(value = config.ipAddress!!,
                                onValueChange = { newValue ->
                                    config = config.copy(ipAddress = newValue)
                                },
                                label = { Text("IP地址") },
                                modifier = Modifier.weight(2f),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Next
                                ),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Filled.ArrowDropDown, "")
                                    }
                                })

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                modifier = Modifier.weight(2f),
                            ) {
                                ipAddresses.forEach { address ->
                                    DropdownMenuItem(onClick = {
                                        config = config.copy(ipAddress = address)
                                        menuExpanded = false
                                    }) {
                                        Text(address)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = config.port!!,
                                onValueChange = { newValue ->
                                    config = config.copy(port = newValue)
                                },
                                label = { Text("端口号") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
                                ),
                                singleLine = true
                            )
                        }

                        Row {
                            OutlinedTextField(
                                value = config.dataNum.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toIntOrNull()
                                    config = if (value != null) {
                                        config.copy(dataNum = value)
                                    } else {
                                        config.copy(dataNum = 0)
                                    }
                                },
                                label = { Text("dataNum") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = config.parityNum.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toIntOrNull()
                                    config = if (value != null) {
                                        config.copy(parityNum = value)
                                    } else {
                                        config.copy(parityNum = 0)
                                    }
                                },
                                label = { Text("parityNum") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = config.rxNum.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toIntOrNull()
                                    config = if (value != null) {
                                        config.copy(rxNum = value)
                                    } else {
                                        config.copy(rxNum = 0)
                                    }
                                },
                                label = { Text("rxNum") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )

                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row {
                            OutlinedTextField(
                                value = config.encodeTimeout.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toLongOrNull()
                                    config = if (value != null) {
                                        config.copy(encodeTimeout = value)
                                    } else {
                                        config.copy(encodeTimeout = 0)
                                    }
                                },
                                label = { Text("编码超时 (微秒)") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = config.decodeTimeout.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toLongOrNull()
                                    config = if (value != null) {
                                        config.copy(decodeTimeout = value)
                                    } else {
                                        config.copy(decodeTimeout = 0)
                                    }
                                },
                                label = { Text("解码超时 (微秒)") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = config.rxTimeout.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toLongOrNull()
                                    config = if (value != null) {
                                        config.copy(rxTimeout = value)
                                    } else {
                                        config.copy(rxTimeout = 0)
                                    }
                                },
                                label = { Text("保序超时 (微秒)") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                        Row {
                            OutlinedTextField(
                                value = config.primaryProbability.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toIntOrNull()
                                    config = if (value != null) {
                                        config.copy(primaryProbability = value)
                                    } else {
                                        config.copy(primaryProbability = 0)
                                    }
                                },
                                label = { Text("保守发包率") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = config.dropRate.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toIntOrNull()
                                    config = if (value != null) {
                                        config.copy(dropRate = value)
                                    } else {
                                        config.copy(dropRate = 0)
                                    }
                                },
                                label = { Text("模拟丢包率") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Text("主路选择：", modifier = Modifier.align(Alignment.CenterVertically))
                            Spacer(modifier = Modifier.width(10.dp))
                            Switch(checked = primaryChannel.value == "Wifi",
                                onCheckedChange = { isChecked ->
                                    primaryChannel.value = if (isChecked) "Wifi" else "Cellular"
                                })
                            Text(
                                if (primaryChannel.value == "Wifi") "Wifi" else "Cellular",
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(start = 8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Button(onClick = {
                                launch {
                                    // 先结束vpn，然后再同步配置，最后开始vpn
                                    stopVpn()
                                    syncResult.value = "Loading"
                                    syncResult.value = syncConfig(config)
                                    syncResult.value = ""
                                    startVpn(config, primaryChannel.value, selectedApp?.packageName)
                                }
                            }) {
                                Text(text = "开始")
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Button(onClick = ::stopVpn) {
                                Text(text = "结束")
                            }
                        }


                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Button(onClick = { showDialog = true }) {
                                Text(text = "选择应用")
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Button(onClick = { selectedApp = null }) {
                                Text(text = "清空选择")
                            }

                            if (showDialog) {
                                AppPickerDialog(onDismissRequest = { showDialog = false }) { app ->
                                    selectedApp = app
                                    showDialog = false
                                }
                            }
                        }


                        selectedApp?.let { app ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .padding(top = 16.dp)
                                    .fillMaxWidth()
                            ) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(50.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(app.name)
                            }
                        }

                    }
                }
                // 测试页面
                composable("测试") {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = serverAddress,
                                onValueChange = { newValue ->
                                    serverAddress = newValue
                                },
                                label = { Text("IP地址") },
                                modifier = Modifier.weight(2f),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Next
                                ),
                                singleLine = true,
                            )

                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = serverPort,
                                onValueChange = { newValue ->
                                    serverPort = newValue
                                },
                                label = { Text("端口号") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
                                ),
                                singleLine = true
                            )
                        }

                        Row(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = packetSize.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toIntOrNull()
                                    packetSize = value ?: 0
                                },
                                label = { Text("包的大小") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = numTests.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toIntOrNull()
                                    numTests = value ?: 0
                                },
                                label = { Text("测试次数") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = numThreads.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toIntOrNull()
                                    numThreads = value ?: 0
                                },
                                label = { Text("线程个数") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }


                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Button(
                                onClick = {
                                    showResult = true
                                    averageLatency = 0
                                    latencyList = mutableListOf()
                                    testJob = CoroutineScope(Dispatchers.IO).launch {
                                        for (thread in 0 until numThreads) {
                                            launch {
                                                for (i in 0 until numTests) {
                                                    Log.d(TAG, "test thread=$thread, i=$i")
                                                    val latencyResults = testTcpLatency(
                                                        serverAddress,
                                                        serverPort.toInt(),
                                                        packetSize,
                                                    )
                                                    averageLatency =
                                                        (averageLatency * i + latencyResults) / (i + 1)
                                                    withContext(Dispatchers.Main) {
                                                        latencyList = latencyList!! + latencyResults
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text("开始测试")
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Button(onClick = { testJob?.cancel() }) {
                                Text("结束测试")
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Button(onClick = { showResult = false }) {
                                Text("清空结果")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Button(onClick = {
                                launch {
                                    showResult = true
                                    val intent =
                                        Intent(this@MainActivity, HONVpnService::class.java)
                                    intent.action = HONVpnService.ACTION_REQUEST_DATA
                                    startService(intent)
                                }
                            }) {
                                Text("获取客户端测试结果报告")
                            }

                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Button(onClick = {
                                launch {
                                    stopVpn()
                                    showResult = true
                                    serverData.value = requestDataFromServer(config)
                                }
                            }) {
                                Text("获取服务端测试结果报告（会自动断连VPN）")
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        if (showResult) {
//                            latencyList?.let { results ->
//                                Text("平均延迟：${averageLatency}ms\n[${latencyList!!.size}/${numTests * numThreads}]：${results.joinToString()}")
//                            }
                            Column {
                                if (latencyList != null) {
                                    Text("平均延迟：${averageLatency}ms [${latencyList!!.size}/${numTests * numThreads}]")
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                                clientData.value?.let { dataMap ->
                                    Text("客户端接收数据包比: ${"%.2f%%".format((dataMap["rxRate"]?.toDouble() ?: 0.0) * 100)} [${dataMap["rxCount"]}/${dataMap["rxTotal"]}]")
                                    Text("客户端等包超时比: ${"%.2f%%".format((dataMap["timeoutRate"]?.toDouble() ?: 0.0) * 100)}")
                                    Text(
                                        "客户端平均等包时间(最短时间/最长时间): ${
                                            "%.2f(%.2f/%.2f)".format(
                                                dataMap["rxTime"]?.toDoubleOrNull() ?: 0.0,
                                                dataMap["rxMin"]?.toDoubleOrNull() ?: 0.0,
                                                dataMap["rxMax"]?.toDoubleOrNull() ?: 0.0
                                            )
                                        }"
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                                serverData.value?.let { dataMap ->
                                    Text("服务端接收数据包比: ${"%.2f%%".format((dataMap["rx_rate"]?.toDouble() ?: 0.0) * 100)} [${dataMap["rx_count"]}/${dataMap["rx_total"]}]")
                                    Text("服务端等包超时比: ${"%.2f%%".format((dataMap["timeout_rate"]?.toDouble() ?: 0.0) * 100)}")
                                    Text(
                                        "服务端平均等包时间(最短时间/最长时间): ${
                                            "%.2f(%.2f/%.2f)".format(
                                                dataMap["rx_time"]?.toDoubleOrNull() ?: 0.0,
                                                dataMap["rx_min"]?.toDoubleOrNull() ?: 0.0,
                                                dataMap["rx_max"]?.toDoubleOrNull() ?: 0.0
                                            )
                                        }"
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                            }
                        }
                    }

                }
            }
        }
    }


    // 串行测试时延
    private fun testTcpLatency(
        serverAddress: String,
        serverPort: Int,
        packetSize: Int,
    ): Long {
        val socket = Socket(serverAddress, serverPort)
        val outStream = DataOutputStream(socket.getOutputStream())
        val inStream = DataInputStream(socket.getInputStream())
        val data = ByteArray(packetSize)
        val start = System.currentTimeMillis()
        outStream.write(data)
        outStream.flush()
        inStream.readFully(ByteArray(packetSize))
        val end = System.currentTimeMillis()
        socket.close()
        return end - start
    }


    // 选取APP（只转发这个APP的包）
    @RequiresApi(Build.VERSION_CODES.O)
    @Composable
    fun AppPickerDialog(onDismissRequest: () -> Unit, onAppSelected: (AppInfo) -> Unit) {
        val context = LocalContext.current
        val apps = remember { mutableStateOf(listOf<AppInfo>()) }
        LaunchedEffect(Unit) {
            apps.value = getAppList(context)
        }
        Dialog(onDismissRequest = onDismissRequest) {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .background(MaterialTheme.colors.surface)
            ) {
                Column {
                    Text(
                        text = "请选择一个应用",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.h6
                    )
                    Divider()
                    LazyColumn {
                        items(apps.value) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = { onAppSelected(app) })
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(50.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = app.name, style = MaterialTheme.typography.body1
                                )
                            }
                            Divider()
                        }
                    }
                }
            }
        }
    }

    data class AppInfo(val name: String, val packageName: String, val icon: ImageBitmap)

    // 获取APP列表
    @RequiresApi(Build.VERSION_CODES.O)
    private fun getAppList(context: Context): List<AppInfo> {
        val pm = context.packageManager
        return pm.getInstalledPackages(0)
            .filter { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .mapNotNull { packageInfo ->
                val activityInfo = packageInfo.applicationInfo
                val packageName = activityInfo.packageName
                val name = activityInfo.loadLabel(pm).toString()
                val iconDrawable = activityInfo.loadIcon(pm)
                val icon: ImageBitmap? = when (iconDrawable) {
                    is BitmapDrawable -> iconDrawable.bitmap.asImageBitmap()
                    is AdaptiveIconDrawable -> {
                        val bitmap = Bitmap.createBitmap(
                            iconDrawable.intrinsicWidth,
                            iconDrawable.intrinsicHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bitmap)
                        iconDrawable.setBounds(0, 0, canvas.width, canvas.height)
                        iconDrawable.draw(canvas)
                        bitmap.asImageBitmap()
                    }

                    else -> null
                }
                if (icon != null) AppInfo(name, packageName, icon) else null
            }
    }

    // 和服务器同步配置
    private suspend fun syncConfig(
        config: HONConfig,
    ): String {
        delay(1000)
        val inetAddress = config.ipAddress
        val socket = withContext(Dispatchers.IO) {
            Socket(inetAddress, SYNC_PORT)
        }
        val jsonConfig = config.toJson()
        var bytesRead: Int = -1
        val response = ByteArray(1024)

        withContext(Dispatchers.IO) {
            val outStream = socket.getOutputStream()
            outStream.write(byteArrayOf(TYPE_SYNC_CONFIG))
            outStream.write(jsonConfig.toByteArray())
            outStream.flush()

            val inStream = socket.getInputStream()
            bytesRead = inStream.read(response)
            socket.close()
        }

        return if (bytesRead != -1) {
            when (String(response, 0, bytesRead)) {
                "200" -> "同步成功！"
                "500" -> "同步失败！服务器出错！"
                else -> "未知错误！"
            }
        } else {
            "无法从服务器获取响应！"
        }
    }

    private suspend fun requestDataFromServer(
        config: HONConfig,
    ): Map<String, String> {
        delay(1000)
        val inetAddress = config.ipAddress
        val socket = withContext(Dispatchers.IO) {
            Socket(inetAddress, SYNC_PORT)
        }
        var bytesRead: Int = -1
        val response = ByteArray(1024)
        val gson = Gson()

        withContext(Dispatchers.IO) {
            val outStream = socket.getOutputStream()
            outStream.write(byteArrayOf(TYPE_REQUEST_DATA))
            outStream.flush()

            val inStream = socket.getInputStream()
            bytesRead = inStream.read(response)
            socket.close()
        }
        val responseString = if (bytesRead != -1) String(response, 0, bytesRead) else ""
        val type = object : TypeToken<Map<String, String>>() {}.type

        return if (responseString.isNotEmpty()) {
            gson.fromJson(responseString, type)
        } else {
            emptyMap()
        }
    }



// 启动！
private fun startVpn(
    config: HONConfig, primaryChannel: String, appPackageName: String?
) {
    if (config.dataNum <= 0) {
        config.dataNum = 1
    }
    val intent = VpnService.prepare(this)
    if (intent != null) {
        startActivityForResult(intent, VPN_REQUEST_CODE)
    } else {
        launch {
            val vpnIntent = Intent(this@MainActivity, HONVpnService::class.java)
            vpnIntent.putExtra("CONFIG", config)
            vpnIntent.putExtra("PRIMARY_CHANNEL", primaryChannel)
            vpnIntent.putExtra("APP_PACKAGE_NAME", appPackageName)
            startService(vpnIntent)
        }
    }
}

// 关闭
private fun stopVpn() {
    launch {
        val intent = Intent(this@MainActivity, HONVpnService::class.java)
        intent.action = HONVpnService.ACTION_STOP_VPN
        startService(intent)
    }
}
}

