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
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.io.path.Path
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas


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
                    dataNum = 8,
                    parityNum = 0,
                    rxNum = 100,
                    encodeTimeout = 1000000,
                    decodeTimeout = 1000000,
                    rxTimeout = 100000,
                    ackTimeout = 50000,
                    primaryProbability = 80,
                    ipAddress = "106.75.223.143",
                    port = "54345",
                    mode = 0,
                )
            )
        }

        // 模式选项
        val modeOptions = listOf("自研FEC", "开源RS", "多倍发包", "重传冗余")

        // ip地址选取
        val ipAddresses = listOf("106.75.223.143")
        var menuExpanded by remember { mutableStateOf(false) }

        // 选择那一条路是主路
        val primaryChannel = remember { mutableStateOf("Wifi") }

        val dropModeList = listOf("主路随机丢包", "双路随机丢包")
        var dropMode by remember { mutableStateOf("主路随机丢包") }

        // 显示同步状态
        val syncResult = remember { mutableStateOf("") }

        var updateVpnResult by remember { mutableStateOf(false) }

        // 时延测试的配置
        var serverAddress by remember { mutableStateOf("106.75.251.187") }
        var serverPort by remember { mutableStateOf("33333") }
        var packetSize by remember { mutableStateOf(1024) }
        var numTests by remember { mutableStateOf(200) }
        var numThreads by remember { mutableStateOf(10) }

        // 时延测试的输出
        var showLatencyResult by remember { mutableStateOf(false) }
        var averageLatency by remember { mutableStateOf(0L) }
        var latencyList by remember { mutableStateOf<List<Long>?>(null) }
        var testJob: Job? = null

        val serverData = remember { mutableStateOf<Map<String, String>?>(null) }
        val clientData = remember { mutableStateOf<Map<String, String>?>(null) }
        val lineData = remember { mutableStateOf<Map<String, List<Float>>?>(null) }

        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var latencyEnergyBefore =
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        var showEnergyResult by remember { mutableStateOf(false) }
        var energyStartTime by remember { mutableStateOf(0L) }
        var energyBefore =
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        var energyConsumed by remember { mutableStateOf(0L) }
        var startTimeFormatted by remember { mutableStateOf("") }
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        var powerConsumption by remember { mutableStateOf(0.0) }

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
                        dataMap?.forEach { (key, value) ->
                            val floatValue = value.toFloatOrNull() ?: 0f
                            val existingList = lineData.value?.get(key) ?: emptyList()
                            val updatedList = existingList + floatValue
                            lineData.value = lineData.value.orEmpty() + (key to updatedList)
                        }
                    }
                }
            }

            val filter = IntentFilter("com.example.ACTION_DATA_AVAILABLE")
            context.registerReceiver(vpnDataReceiver, filter)

            onDispose {
                context.unregisterReceiver(vpnDataReceiver)
            }
        }

        // 更新包信息
        val handler = Handler(Looper.getMainLooper())
        val showLog = object : Runnable {
            override fun run() {
                val intent = Intent(this@MainActivity, HONVpnService::class.java)
                intent.action = HONVpnService.ACTION_REQUEST_DATA
                startService(intent)
                handler.postDelayed(this, 1000) // 1秒后再次执行
            }
        }

        @Composable
        fun LineChart(
            wifiData: List<Float>,
            cellularData: List<Float>,
            modifier: Modifier = Modifier,
            pointsToShow: Int = 30 // 新增参数，控制展示的最后若干个数据点
        ) {
            // 定义折线图的尺寸
            val chartHeight = 200.dp
            val chartWidth = Modifier.fillMaxWidth()

            // 只取最后 pointsToShow 个点
            val displayedWifiData = wifiData.takeLast(pointsToShow)
            val displayedCellularData = cellularData.takeLast(pointsToShow)

            Canvas(modifier = modifier
                .height(chartHeight)
                .then(chartWidth)) {
                val pathWifi = Path()
                val pathCellular = Path()

                // 找到 WiFi 和 Cellular 的全局最大值，确保 Y 轴比例一致
                val globalMax = maxOf(
                    displayedWifiData.maxOrNull() ?: 0f,
                    displayedCellularData.maxOrNull() ?: 0f
                )
                val maxPoints = maxOf(displayedWifiData.size, displayedCellularData.size)
                val widthPerPoint = size.width / (maxPoints - 1)
                val maxHeight = size.height

                // 底色绘制（浅灰色）
                drawRect(color = Color.White, size = size)

                // 绘制纵坐标刻度
                val yAxisStep = globalMax / 5 // 将 Y 轴分成5部分
                for (i in 0..5) {
                    val y = maxHeight - (i * yAxisStep / globalMax * maxHeight)
                    drawLine(
                        color = Color.Gray,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    // 绘制纵坐标标注
                    drawContext.canvas.nativeCanvas.drawText(
                        "%.1f".format(i * yAxisStep),
                        10f,
                        y,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.BLACK
                            textSize = 30f
                        }
                    )
                }

                drawContext.canvas.nativeCanvas.drawText(
                    "流量 (B)", // 标注文本
                    10f, // X 坐标
                    -50f, // Y 坐标，放在上方
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 30f // 字体大小
                        isFakeBoldText = true // 加粗
                    }
                )

                // 创建WiFi的折线
                displayedWifiData.forEachIndexed { index, point ->
                    val x = index * widthPerPoint
                    val y = maxHeight - (point / globalMax) * maxHeight // 使用全局最大值
                    if (index == 0) {
                        pathWifi.moveTo(x, y)
                    } else {
                        pathWifi.lineTo(x, y)
                    }
                }

                // 创建蜂窝网络的折线
                displayedCellularData.forEachIndexed { index, point ->
                    val x = index * widthPerPoint
                    val y = maxHeight - (point / globalMax) * maxHeight // 使用全局最大值
                    if (index == 0) {
                        pathCellular.moveTo(x, y)
                    } else {
                        pathCellular.lineTo(x, y)
                    }
                }

                // 绘制WiFi线
                drawPath(
                    pathWifi,
                    Color.Blue,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(5f)
                )

                // 绘制蜂窝线
                drawPath(
                    pathCellular,
                    Color.Red,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(5f)
                )

                // 绘制颜色标注 (图例) - 放置在右上角
                val legendBoxSize = 30f
                val legendTextOffset = 40f
                val rightPadding = 20f
                val topPadding = 20f

                // 右上角 X、Y 位置
                val legendXOffset = size.width - legendBoxSize - rightPadding
                val legendYOffset = topPadding

                // WiFi 标注
                drawRect(
                    color = Color.Blue,
                    topLeft = Offset(legendXOffset, legendYOffset),
                    size = Size(legendBoxSize, legendBoxSize)
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "WiFi",
                    legendXOffset + legendTextOffset,
                    legendYOffset + legendBoxSize,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 30f
                    }
                )

                // Cellular 标注
                val cellularLegendYOffset = legendYOffset + legendBoxSize + 10f
                drawRect(
                    color = Color.Red,
                    topLeft = Offset(legendXOffset, cellularLegendYOffset),
                    size = Size(legendBoxSize, legendBoxSize)
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "Cellular",
                    legendXOffset + legendTextOffset,
                    cellularLegendYOffset + legendBoxSize,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 30f
                    }
                )
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

                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // VPN IP地址和端口短输入
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

                        // FEC 数据包和冗余包配置
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

                        // 编解码和保序时间上限
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

                        }

                        Row {
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
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = config.ackTimeout.toString(),
                                onValueChange = { newValue ->
                                    val value = newValue.toLongOrNull()
                                    config = if (value != null) {
                                        config.copy(rxTimeout = value)
                                    } else {
                                        config.copy(rxTimeout = 0)
                                    }
                                },
                                label = { Text("ACK超时 (微秒)") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                        // 保守发包率（暂时没用）和模拟丢包率
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

                        // 模式选择
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Text("模式选择：", modifier = Modifier.align(Alignment.CenterVertically))
                            Spacer(modifier = Modifier.width(10.dp))

                            var expanded by remember { mutableStateOf(false) }
                            Text(modeOptions[config.mode],
                                modifier = Modifier
                                    .clickable { expanded = true }
                                    .align(Alignment.CenterVertically)
                                    .padding(start = 8.dp))

                            DropdownMenu(expanded = expanded,
                                onDismissRequest = { expanded = false }) {
                                modeOptions.forEachIndexed { index, option ->
                                    DropdownMenuItem(onClick = {
                                        config = config.copy(mode = index)
                                        expanded = false
                                    }) {
                                        Text(option)
                                    }
                                }
                            }
                        }

                        // 丢包模式
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Text("丢包模式：", modifier = Modifier.align(Alignment.CenterVertically))
                            var expanded by remember { mutableStateOf(false) }
                            Text(dropMode,
                                modifier = Modifier
                                    .clickable { expanded = true }
                                    .align(Alignment.CenterVertically)
                                    .padding(start = 8.dp))
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                dropModeList.forEach { mode ->
                                    DropdownMenuItem(onClick = {
                                        dropMode = mode
                                        expanded = false
                                    }) {
                                        Text(mode)
                                    }
                                }
                            }
                        }

                        // 主路选择
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Text("主路选择：", modifier = Modifier.align(Alignment.CenterVertically))
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

                        // VPN 开始和结束按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Button(onClick = {
                                launch {
                                    updateVpnResult = true
                                    // 先结束vpn，然后再同步配置，最后开始vpn
                                    stopVpn()
                                    lineData.value = null
                                    syncResult.value = "Loading"
                                    syncResult.value = syncConfig(config)
                                    syncResult.value = ""
                                    startVpn(
                                        config,
                                        primaryChannel.value,
                                        dropMode,
                                        selectedApp?.packageName
                                    )
                                    handler.post(showLog)
                                }
                            }) {
                                Text(text = "开始")
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Button(onClick = {
                                launch {
                                    updateVpnResult = false
                                    val intent =
                                        Intent(this@MainActivity, HONVpnService::class.java)
                                    intent.action = HONVpnService.ACTION_REQUEST_DATA
                                    startService(intent)
                                    stopVpn()
                                    handler.removeCallbacks(showLog)
                                }
                            }) {
                                Text(text = "结束")
                            }
                        }

                        // 选择哪个应用的包被VPN拦截
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

                        // 展示被VPN拦截的应用
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

                        lineData.value?.let { lineMap ->

                            val wifiReceivePacketNum =
                                lineMap["wifiReceivePacketNum"] ?: emptyList<Float>()
                            val wifiReceivePacketSize =
                                lineMap["wifiReceivePacketSize"] ?: emptyList<Float>()
                            val cellularReceivePacketNum =
                                lineMap["cellularReceivePacketNum"] ?: emptyList<Float>()
                            val cellularReceivePacketSize =
                                lineMap["cellularReceivePacketSize"] ?: emptyList<Float>()

                            val wifiData = mutableListOf<Float>()
                            for (i in 1 until wifiReceivePacketNum.size) {
                                val current = wifiReceivePacketNum[i] * wifiReceivePacketSize[i]
                                val previous =
                                    wifiReceivePacketNum[i - 1] * wifiReceivePacketSize[i - 1]
                                wifiData.add(current - previous)
                            }

                            val cellularData = mutableListOf<Float>()
                            for (i in 1 until cellularReceivePacketNum.size) {
                                val current =
                                    cellularReceivePacketNum[i] * cellularReceivePacketSize[i]
                                val previous =
                                    cellularReceivePacketNum[i - 1] * cellularReceivePacketSize[i - 1]
                                cellularData.add(current - previous)
                            }

                            LineChart(
                                wifiData = wifiData,
                                cellularData = cellularData,
                                modifier = Modifier.padding(16.dp)
                            )

                        }
                        clientData.value?.let { dataMap ->
//                                Text("vpn发送/接收包个数：${dataMap["vpnSendPacketNum"]}/${dataMap["vpnReceivePacketNum"]}")
//                                Text(
//                                    "vpn发送/接收包平均大小：${"%.2f".format(dataMap["vpnSendPacketSize"]?.toDouble() ?: 0.0)}/${
//                                        "%.2f".format(
//                                            dataMap["vpnReceivePacketSize"]?.toDouble() ?: 0.0
//                                        )
//                                    }"
//                                )

                            Text("wifi发送/接收包个数：${dataMap["wifiSendPacketNum"]}/${dataMap["wifiReceivePacketNum"]}")
                            Text(
                                "wifi发送/接收包平均大小：${"%.2f".format(dataMap["wifiSendPacketSize"]?.toDouble() ?: 0.0)}/${
                                    "%.2f".format(
                                        dataMap["wifiReceivePacketSize"]?.toDouble() ?: 0.0
                                    )
                                }"
                            )
                            Text("蜂窝发送/接收包个数：${dataMap["cellularSendPacketNum"]}/${dataMap["cellularReceivePacketNum"]}")
                            Text(
                                "蜂窝发送/接收包平均大小：${"%.2f".format(dataMap["cellularSendPacketSize"]?.toDouble() ?: 0.0)}/${
                                    "%.2f".format(
                                        dataMap["cellularReceivePacketSize"]?.toDouble() ?: 0.0
                                    )
                                }"
                            )
                        }



                        Spacer(modifier = Modifier.height(100.dp))
                    }

                }

                // 测试页面
                composable("测试") {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
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
                                    showLatencyResult = true
                                    averageLatency = 0
                                    latencyList = mutableListOf()
                                    latencyEnergyBefore =
                                        batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                                    testJob = CoroutineScope(Dispatchers.IO).launch {
                                        for (thread in 0 until numThreads) {
                                            launch {
                                                val socket =
                                                    createSocket(serverAddress, serverPort.toInt())
                                                for (i in 0 until numTests) {
//                                                    Log.d(TAG, "test thread=$thread, i=$i")
                                                    val latencyResults = testTcpLatency(
                                                        socket,
                                                        packetSize,
                                                    )
                                                    withContext(Dispatchers.Main) {
                                                        latencyList = latencyList!! + latencyResults
                                                    }
                                                    averageLatency =
                                                        (latencyList as MutableList<Long>).average()
                                                            .toLong()
                                                }
                                                socket.close()
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text("时延测试")
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Button(onClick = { testJob?.cancel() }) {
                                Text("结束测试")
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Button(onClick = { showLatencyResult = false }) {
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
                                    showLatencyResult = true
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
                                    showLatencyResult = true
                                    serverData.value = requestDataFromServer(config)
                                }
                            }) {
                                Text("获取服务端测试结果报告（会自动断连VPN）")
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Button(
                                onClick = {
                                    showEnergyResult = true
                                    energyStartTime = System.currentTimeMillis()
                                    startTimeFormatted = timeFormatter.format(Date(energyStartTime))
                                    energyBefore =
                                        batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                                    energyConsumed =
                                        energyBefore - batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                                    val energyNowTime = System.currentTimeMillis()
                                    val timeElapsedInHours =
                                        (energyNowTime - energyStartTime) / (1000.0 * 60 * 60)
                                    powerConsumption = if (timeElapsedInHours > 0) {
                                        energyConsumed.toDouble() / timeElapsedInHours
                                    } else {
                                        0.0
                                    }

                                },
                            ) {
                                Text("功耗测试")
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Button(onClick = {
                                showEnergyResult = true
                                energyConsumed =
                                    energyBefore - batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                                val energyNowTime = System.currentTimeMillis()
                                val timeElapsedInHours =
                                    (energyNowTime - energyStartTime) / (1000.0 * 60 * 60)
                                powerConsumption = if (timeElapsedInHours > 0) {
                                    energyConsumed.toDouble() / timeElapsedInHours
                                } else {
                                    0.0
                                }
                            }) {
                                Text("刷新功耗")
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Button(onClick = { showEnergyResult = false }) {
                                Text("清空结果")
                            }
                        }

                        if (showLatencyResult) {
//                            latencyList?.let { results ->
//                                Text("平均延迟：${averageLatency}ms\n[${latencyList!!.size}/${numTests * numThreads}]：${results.joinToString()}")
//                            }
                            Column {

                                if (latencyList != null) {
                                    Text("平均延迟：${averageLatency}ms [${latencyList!!.size}/${numTests * numThreads}]")
                                    Text(
                                        "功耗：${
                                            (latencyEnergyBefore - batteryManager.getLongProperty(
                                                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
                                            ))
                                        } 微安时（μAh）"
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                                clientData.value?.let { dataMap ->
                                    Text("客户端接收数据包比: ${"%.2f%%".format((dataMap["rxRate"]?.toDouble() ?: 0.0) * 100)} [${dataMap["rxCount"]}/${dataMap["rxTotal"]}]")
                                    Text("客户端等包超时比: ${"%.2f%%".format((dataMap["timeoutRate"]?.toDouble() ?: 0.0) * 100)}")
                                    Text(
                                        "客户端平均等包时间(最短时间/最长时间): ${
                                            "%.2fms(%.2fms/%.2fms)".format(
                                                dataMap["rxTime"]?.toDoubleOrNull()
                                                    ?.div(1000000) ?: 0.0,
                                                dataMap["rxMin"]?.toDoubleOrNull()
                                                    ?.div(1000000) ?: 0.0,
                                                dataMap["rxMax"]?.toDoubleOrNull()
                                                    ?.div(1000000) ?: 0.0
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
                                            "%.2fms(%.2fms/%.2fms)".format(
                                                dataMap["rx_time"]?.toDoubleOrNull()
                                                    ?.div(1000000) ?: 0.0,
                                                dataMap["rx_min"]?.toDoubleOrNull()
                                                    ?.div(1000000) ?: 0.0,
                                                dataMap["rx_max"]?.toDoubleOrNull()
                                                    ?.div(1000000) ?: 0.0
                                            )
                                        }"
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                            }
                        }

                        if (showEnergyResult) {
                            Text("测试开始时间： $startTimeFormatted")
                            Text("测试持续时间： ${(System.currentTimeMillis() - energyStartTime) / 1000} 秒")

                            // Display power consumption in microamperes
                            Text("功耗： ${powerConsumption} µA") // Assuming energyConsumed is in nanoamperes (nA)

                        }

                        Spacer(modifier = Modifier.height(100.dp))
                    }

                }
            }
        }
    }


    // 串行测试时延
    private fun createSocket(serverAddress: String, serverPort: Int): Socket {
        val socket = Socket(serverAddress, serverPort)
        return socket
    }

    // 串行测试时延
    private fun testTcpLatency(
        socket: Socket,
        packetSize: Int,
    ): Long {
        val start = System.currentTimeMillis()
//        val socket = Socket(serverAddress, serverPort)
        val outStream = DataOutputStream(socket.getOutputStream())
        val inStream = DataInputStream(socket.getInputStream())
        val data = ByteArray(packetSize)
        outStream.write(data)
        outStream.flush()
        inStream.readFully(ByteArray(packetSize))
//        outStream.close()
//        inStream.close()
//        socket.close()
        val end = System.currentTimeMillis()
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

        Log.d(TAG, "HONConfig=${config}")
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

    // 获取服务器的统计信息
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
        config: HONConfig, primaryChannel: String, dropMode: String?, appPackageName: String?
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
                vpnIntent.putExtra("DROP_MODE", dropMode)
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

