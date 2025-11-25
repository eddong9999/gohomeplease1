package com.example.myapplication1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication1.ui.theme.MyApplicationTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone // TimeZone import 추가


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RealtimeDataScreen()
                }
            }
        }
    }
}

// 🔥 Firestore 저장 함수
fun saveSensorData(gas: String, shock: String, dist: String) {
    val db = FirebaseFirestore.getInstance()

    // 1. 현재 한국 시간(KST) 포맷 생성
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    // 타임존을 'Asia/Seoul'로 명시적으로 설정하여 KST를 보장합니다.
    sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")

    val currentTimeString = sdf.format(Date())

    // 2. 문서 ID로 사용할 시간 포맷 (예: yyyyMMdd_HHmmss_SSS)
    // SSS는 밀리초를 의미하며, 중복 방지에 유용합니다.
    val idFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.KOREA)
    idFormat.timeZone = TimeZone.getTimeZone("Asia/Seoul")
    val documentId = idFormat.format(Date())

    // 3. 필드에 저장할 데이터 구성
    val data = hashMapOf(
        "gas" to gas,
        "shock" to shock,
        "distance" to dist,
        "timestamp_kst" to currentTimeString // KST 문자열 시간 저장
    )

    // 4. add() 대신 set()을 사용하여 문서 ID를 지정합니다.
    db.collection("sensorData")
        .document(documentId) // << 무작위 ID 대신 KST 시간 기반 ID 사용
        .set(data)
        .addOnSuccessListener {
            println("✅ Firestore 저장 성공 - ID: $documentId")
        }
        .addOnFailureListener { e ->
            println("❌ Firestore 저장 실패: ${e.localizedMessage}")
        }
}

@Composable
fun RealtimeDataScreen() {
    var gasValue by remember { mutableStateOf("0") }
    var shockValue by remember { mutableStateOf("0") }
    var distValue by remember { mutableStateOf("0") }

    // 🔥 앱 실행 후 10초마다 자동 저장되는 코드
    LaunchedEffect(Unit) {
        while (true) {
            // 랜덤값(센서 시뮬레이션)
            gasValue = (100..2000).random().toString()
            shockValue = (0..1).random().toString()
            distValue = (5..50).random().toString()

            // Firestore 저장
            saveSensorData(gasValue, shockValue, distValue)

            // 10초 대기 = 몇초후에 저장할지 설정 가능기능
            delay(10_000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("실시간 센서 데이터", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(40.dp))

        DataCard(title = "가스 농도 (MQ-2)", value = gasValue, unit = "", color = Color(0xFF4CAF50))
        DataCard(title = "충격 감지 (SW-420)", value = shockValue, unit = "(0=없음, 1=충격)", color = Color(0xFFFF9800))
        DataCard(title = "안전고리 거리 (HC-SR04)", value = distValue, unit = "cm", color = Color(0xFF2196F3))

        Spacer(modifier = Modifier.height(50.dp))

        // ✔ 수동 저장 버튼도 유지하고 싶으면 그대로 둬도 됨
        Button(onClick = {
            gasValue = (100..2000).random().toString()
            shockValue = (0..1).random().toString()
            distValue = (5..50).random().toString()
            saveSensorData(gasValue, shockValue, distValue)
        }) {
            Text("데이터 수동 저장 (랜덤)")
        }
    }
}

@Composable
fun DataCard(title: String, value: String, unit: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, color = Color.White, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = value, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = unit,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MyApplicationTheme {
        RealtimeDataScreen()
    }
}
