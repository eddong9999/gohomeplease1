package com.example.myapplication1

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.myapplication1.ui.theme.MyApplicationTheme
import com.google.android.gms.location.*
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

// 🔥 Google Maps Compose Imports 추가
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

// 화면 상태를 정의하는 Enum
enum class ScreenState {
    MAIN_SCREEN,
    MAP_SCREEN
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 권한 요청 (위치 정보 권한 추가)
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { }

        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION, // 정밀 위치
                Manifest.permission.ACCESS_COARSE_LOCATION // 대략적인 위치
            )
        )

        setContent {
            MyApplicationTheme {
                // 상위 컴포저블에서 화면 상태 관리
                var currentScreen by remember { mutableStateOf(ScreenState.MAIN_SCREEN) }

                when (currentScreen) {
                    // 메인 화면 (BLE/GPS 센서 데이터 표시 및 Firestore 저장)
                    ScreenState.MAIN_SCREEN -> BleSensorScreen { currentScreen = ScreenState.MAP_SCREEN }
                    // 🔥 지도 화면을 실제 MapScreen으로 변경
                    ScreenState.MAP_SCREEN -> MapScreen { currentScreen = ScreenState.MAIN_SCREEN }
                }
            }
        }
    }
}

// 2. BLE UUID (아두이노 코드와 동일)
val SERVICE_UUID = UUID.fromString("0000180C-0000-1000-8000-00805F9B34FB")
val CHAR_UUID    = UUID.fromString("00002A56-0000-1000-8000-00805F9B34FB")


// 🔥 Firestore 저장 함수: GPS 정보를 추가하여 KST로 저장
fun saveSensorDataKst(gas: String, shock: String, dist: String, lat: Double, lng: Double) {
    val db = FirebaseFirestore.getInstance()

    // 1. 현재 한국 시간(KST) 포맷 생성 (필드에 저장할 시간 문자열)
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
    val currentTimeString = sdf.format(Date())

    // 2. 문서 ID로 사용할 시간 포맷 (밀리초까지 포함하여 고유성 확보)
    val idFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.KOREA)
    idFormat.timeZone = TimeZone.getTimeZone("Asia/Seoul")
    val documentId = idFormat.format(Date())

    // 3. 필드에 저장할 데이터 구성 (GPS 정보 추가)
    val data = hashMapOf(
        "gas" to gas,
        "shock" to shock,
        "distance" to dist,
        "latitude" to lat,   // GPS 위도 추가
        "longitude" to lng,  // GPS 경도 추가
        "timestamp_kst" to currentTimeString // KST 문자열 시간 저장
    )

    // 4. set()을 사용하여 지정된 문서 ID로 저장
    db.collection("sensorData")
        .document(documentId)
        .set(data)
        .addOnSuccessListener {
            Log.d("Firestore", "✅ 저장 성공 - ID: $documentId, Lat/Lng: $lat/$lng")
        }
        .addOnFailureListener { e ->
            Log.e("Firestore", "❌ 저장 실패: ${e.localizedMessage}")
        }
}

@SuppressLint("MissingPermission")
@Composable
fun BleSensorScreen(onNavigateToMap: () -> Unit) { // 지도 이동 람다 함수를 인수로 받음
    val context = LocalContext.current
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter

    var connectionStatus by remember { mutableStateOf("연결 안됨") }
    var gasValue by remember { mutableStateOf("0") }
    var shockValue by remember { mutableStateOf("0") }
    var distValue by remember { mutableStateOf("0") }

    // 🔥 GPS 상태 변수 추가
    var latitude by remember { mutableStateOf(0.0) }
    var longitude by remember { mutableStateOf(0.0) }
    var locationStatus by remember { mutableStateOf("위치 정보 대기 중...") }

    // 🔥 Fused Location Provider Client 초기화
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // 🔥 Location Callback 정의: 위치가 업데이트될 때마다 호출됨
    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    latitude = location.latitude
                    longitude = location.longitude
                    locationStatus = "위치 업데이트 완료"
                } ?: run {
                    locationStatus = "위치 정보를 가져올 수 없습니다"
                }
            }
        }
    }

    // 🔥 위치 업데이트 요청 (앱 로드 시 5초마다 위치 업데이트 요청)
    LaunchedEffect(Unit) {
        val locationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (locationPermission) {
            val locationRequest = LocationRequest.create().apply {
                interval = 5000 // 5초마다
                fastestInterval = 3000 // 가장 빠른 간격
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } else {
            locationStatus = "GPS 권한이 없습니다."
        }
    }


    // BLE 연결 관리자
    val gattCallback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                    connectionStatus = "기기 연결됨! 데이터 찾는 중..."
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionStatus = "연결 끊김"
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHAR_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"))
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                    connectionStatus = "데이터 수신 시작!"
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.getStringValue(0)
                val parts = data.split(",")
                if (parts.size == 3) {
                    val newGas = parts[0]
                    val newShock = parts[1]
                    val newDist = parts[2]

                    // Compose 상태 업데이트
                    gasValue = newGas
                    shockValue = newShock
                    distValue = newDist

                    // 🔥 Firestore에 실시간 데이터 저장 (GPS 좌표 포함)
                    saveSensorDataKst(newGas, newShock, newDist, latitude, longitude) // GPS 좌표 전달
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // 타이틀 및 상태 정보
        Text("작업자 안전 (BLE/GPS 버전)", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 16.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Divider()

        // GPS 상태 표시 추가
        Text("BLE 상태: $connectionStatus", color = Color.DarkGray, fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
        Text("GPS 상태: $locationStatus", color = Color.DarkGray, fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
        Text("위치: Lat ${String.format("%.4f", latitude)}, Lng ${String.format("%.4f", longitude)}",
            color = Color(0xFF0D47A1), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))

        Spacer(modifier = Modifier.height(20.dp))

        // [BLE 연결 버튼]
        Button(
            onClick = {
                connectionStatus = "장치 검색 중..."
                val scanner = bluetoothAdapter.bluetoothLeScanner
                scanner.startScan(object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (result.device.name == "MySafetyWorker_BLE") {
                            connectionStatus = "장치 발견! 연결 시도..."
                            scanner.stopScan(this)
                            result.device.connectGatt(context, false, gattCallback)
                        }
                    }
                })
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03A9F4)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("BLE 장치 연결 및 데이터 수신 시작", color = Color.White)
        }

        Spacer(modifier = Modifier.height(30.dp))

        // 🎨 [센서 데이터 카드] ---------------------------
        val GAS_DANGER_THRESHOLD = 1300
        val gasInt = gasValue.toIntOrNull() ?: 0
        val gasIsDanger = gasInt > GAS_DANGER_THRESHOLD

        GasDataCard(
            gasValue = gasValue,
            gasIsDanger = gasIsDanger,
            dangerThreshold = GAS_DANGER_THRESHOLD
        )

        val shockIsDanger = shockValue == "1"
        val shockColor = if (shockIsDanger) Color.Red else Color(0xFF0D47A1)
        val shockText = if (shockValue == "1") "충격 감지!" else "정상"
        DataCard("충격 감지", shockText, "", shockColor)

        val distColor = Color(0XFF00897B)
        DataCard("안전고리", distValue, "cm", distColor)

        // -------------------------------------------------
        Spacer(modifier = Modifier.height(30.dp))

        // 🔥 구글 지도 보기 버튼 추가
        Button(
            onClick = onNavigateToMap, // 화면 전환 함수 호출
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7)), // 보라색
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("저장된 위치 구글 지도에서 보기", color = Color.White)
        }
    }
}


// ------------------------------------------------------------------
// GasDataCard (가스 농도 전용 - 4분할 레이아웃)
// ------------------------------------------------------------------

@Composable
fun GasDataCard(gasValue: String, gasIsDanger: Boolean, dangerThreshold: Int) {
    val cardColor = if (gasIsDanger) Color.Red else Color(0xFF00897B)
    val statusText = if (gasIsDanger) "평균 초과!!" else "정상"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [왼쪽 영역: 센서 이름 및 상태 텍스트]
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "가스 농도",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // [오른쪽 영역: 평균 농도 및 현재 농도]
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "평균 농도: $dangerThreshold",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "현재 농도: $gasValue",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

// DataCard (충격 및 거리 센서용)
@Composable
fun DataCard(title: String, value: String, unit: String, color: Color) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.Start) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(value + unit, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// 🔥 실제 구글 지도 화면 구현
@Composable
fun MapScreen(onNavigateBack: () -> Unit) {

    // 🔥 Firestore에서 가장 최근 GPS 좌표를 저장할 상태 변수
    var latestLocation by remember { mutableStateOf<LatLng?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("최신 위치 정보 불러오는 중...") }

    // 🔥 Firestore에서 가장 최근 데이터 가져오기 (문서 ID 기반)
    LaunchedEffect(Unit) {
        val db = FirebaseFirestore.getInstance()
        try {
            // KST 시간 문자열을 기준으로 내림차순 정렬하여 가장 최근 문서 1개를 가져옵니다.
            val querySnapshot = db.collection("sensorData")
                .orderBy("timestamp_kst", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .await()

            if (querySnapshot.documents.isNotEmpty()) {
                val doc = querySnapshot.documents.first()
                // Firestore는 기본적으로 Double 타입을 사용합니다.
                val lat = doc.getDouble("latitude")
                val lng = doc.getDouble("longitude")

                if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) { // 0.0, 0.0 (그리니치) 제외 필터링
                    latestLocation = LatLng(lat, lng)
                    statusText = "최신 위치: ${String.format("%.4f", lat)}, ${String.format("%.4f", lng)}"
                } else {
                    statusText = "유효한 GPS 데이터가 Firestore에 없습니다. (위도/경도 값이 0이거나 누락됨)"
                }
            } else {
                statusText = "Firestore 'sensorData' 컬렉션에 데이터가 없습니다."
            }
        } catch (e: Exception) {
            statusText = "데이터 로드 오류: ${e.localizedMessage}"
            Log.e("MapScreen", "Error fetching latest location: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 상단 정보 패널
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("작업자 위치 추적", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF673AB7))
            Spacer(modifier = Modifier.height(8.dp))
            Text(statusText, color = if (isLoading) Color.Gray else Color.Black)
        }

        // 구글 지도 영역
        if (latestLocation != null) {
            val cameraPositionState = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom(latestLocation!!, 15f) // 줌 레벨 15
            }

            GoogleMap(
                modifier = Modifier
                    .weight(1f) // 남은 공간 모두 사용
                    .fillMaxWidth(),
                cameraPositionState = cameraPositionState
            ) {
                Marker(
                    state = MarkerState(position = latestLocation!!),
                    title = "최신 센서 데이터 위치",
                    snippet = "Lat: ${String.format("%.4f", latestLocation!!.latitude)}, Lng: ${String.format("%.4f", latestLocation!!.longitude)}"
                )
            }
        } else if (!isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("지도를 표시할 수 없습니다. 유효한 GPS 데이터가 필요합니다.", color = Color.Red, fontSize = 18.sp)
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        // 하단 버튼
        Button(
            onClick = onNavigateBack,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("메인 화면으로 돌아가기", color = Color.White)
        }
    }
}