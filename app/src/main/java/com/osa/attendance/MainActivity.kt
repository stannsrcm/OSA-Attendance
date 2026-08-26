package com.osa.attendance

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.room.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCancellableCoroutine
import kotlin.math.sqrt

// ==================== 1. ROOM DATABASE ====================

@Entity(tableName = "admin")
data class AdminEntity(
    @PrimaryKey val id: Int = 1,
    val username: String,
    val salt: String,
    val hash: String
)

@Entity(tableName = "employees")
data class EmployeeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeCode: String,
    val fullName: String,
    val department: String,
    val isActive: Boolean = true
)

@Entity(tableName = "face_templates")
data class FaceTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeId: Long,
    val vector: String
)

@Entity(tableName = "attendance")
data class AttendanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeId: Long,
    val employeeName: String,
    val attDate: String,
    val checkInTime: Long,
    val checkOutTime: Long? = null,
    val isManual: Boolean = false,
    val reason: String? = null
)

@Dao
interface OSADao {
    @Query("SELECT * FROM admin WHERE id = 1")
    suspend fun getAdmin(): AdminEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdmin(admin: AdminEntity)

    @Query("SELECT * FROM employees WHERE isActive = 1 ORDER BY fullName ASC")
    fun getEmployeesFlow(): Flow<List<EmployeeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmployee(emp: EmployeeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(t: FaceTemplateEntity)

    @Query("SELECT * FROM face_templates")
    suspend fun getAllTemplates(): List<FaceTemplateEntity>

    @Query("SELECT * FROM attendance WHERE attDate = :targetDate ORDER BY checkInTime DESC")
    fun getTodayAttendanceFlow(targetDate: String): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE employeeId = :empId AND attDate = :targetDate LIMIT 1")
    suspend fun getAttendance(empId: Long, targetDate: String): AttendanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAttendance(att: AttendanceEntity)
}

@Database(entities = [AdminEntity::class, EmployeeEntity::class, FaceTemplateEntity::class, AttendanceEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): OSADao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "osa_offline.db")
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}

// ==================== 2. LOCAL SECURITY & ML ====================

object SecurityUtils {
    fun hash(pass: String, salt: String): String {
        val spec: KeySpec = PBEKeySpec(pass.toCharArray(), salt.toByteArray(), 5000, 256)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

object FaceEngine {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )

    suspend fun detectFaces(bmp: Bitmap): List<Rect> = suspendCancellableCoroutine { cont ->
        detector.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { faces -> 
                if (cont.isActive) cont.resume(faces.map { it.boundingBox }) 
            }
            .addOnFailureListener { 
                if (cont.isActive) cont.resume(emptyList()) 
            }
    }

    fun extractFeatures(bmp: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bmp, 64, 64, true)
        val vec = FloatArray(64)
        for (i in 0 until 64) {
            val px = scaled.getPixel(i, i)
            vec[i] = (((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) / 765.0f) * 2f - 1f
        }
        var sum = 0f
        for (v in vec) sum += v * v
        val norm = sqrt(sum)
        return if (norm == 0f) vec else FloatArray(64) { vec[it] / norm }
    }

    fun similarity(v1: FloatArray, v2: FloatArray): Float {
        var dot = 0f
        for (i in v1.indices) dot += v1[i] * v2[i]
        return dot
    }
}

// ==================== 3. USER INTERFACE ====================

class MainActivity : ComponentActivity() {
    private val reqPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reqPerms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }

    var isReady by remember { mutableStateOf(false) }
    var hasAdmin by remember { mutableStateOf(false) }
    var isAuthed by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            hasAdmin = db.dao().getAdmin() != null
            isReady = true
        }
    }

    if (!isReady) return

    if (!isAuthed) {
        LoginView(
            isSetup = !hasAdmin,
            onSuccess = { isAuthed = true; hasAdmin = true }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == 0, 
                        onClick = { currentTab = 0 }, 
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = null) }, 
                        label = { Text("Dashboard") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 1, 
                        onClick = { currentTab = 1 }, 
                        icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) }, 
                        label = { Text("Attendance") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 2, 
                        onClick = { currentTab = 2 }, 
                        icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) }, 
                        label = { Text("Register") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 3, 
                        onClick = { currentTab = 3 }, 
                        icon = { Icon(Icons.Default.EditCalendar, contentDescription = null) }, 
                        label = { Text("Manual") }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentTab) {
                    0 -> DashboardView(db)
                    1 -> CameraAttendanceView(db)
                    2 -> RegisterEmployeeView(db) { currentTab = 0 }
                    3 -> ManualAttendanceView(db) { currentTab = 0 }
                }
            }
        }
    }
}

@Composable
fun LoginView(isSetup: Boolean, onSuccess: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val scope = rememberCoroutineScope()

    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.padding(24.dp).fillMaxWidth(0.9f)) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isSetup) "Initial Admin Setup" else "Admin Login", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())

                if (error.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = Color.Red, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    if (user.isBlank() || pass.isBlank()) { error = "Fields cannot be empty"; return@Button }
                    scope.launch(Dispatchers.IO) {
                        if (isSetup) {
                            val salt = UUID.randomUUID().toString().take(8)
                            val hash = SecurityUtils.hash(pass, salt)
                            db.dao().saveAdmin(AdminEntity(username = user.trim(), salt = salt, hash = hash))
                            withContext(Dispatchers.Main) { onSuccess() }
                        } else {
                            val admin = db.dao().getAdmin()
                            if (admin != null && admin.username == user.trim() && admin.hash == SecurityUtils.hash(pass, admin.salt)) {
                                withContext(Dispatchers.Main) { onSuccess() }
                            } else {
                                withContext(Dispatchers.Main) { error = "Invalid credentials" }
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isSetup) "Set Admin" else "Login")
                }
            }
        }
    }
}

@Composable
fun DashboardView(db: AppDatabase) {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val logs by db.dao().getTodayAttendanceFlow(today).collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Today's Overview ($today)", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Total Present", fontSize = 12.sp)
                    Text("${logs.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                }
            }
            Card(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Checked Out", fontSize = 12.sp)
                    Text("${logs.count { it.checkOutTime != null }}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Attendance Logs", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs) { log ->
                val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(log.employeeName, fontWeight = FontWeight.Bold)
                            Text(if (log.isManual) "MANUAL: ${log.reason}" else "Face Recognition", fontSize = 11.sp, color = Color.Gray)
                        }
                        Text("In: ${timeFmt.format(Date(log.checkInTime))} | Out: ${log.checkOutTime?.let { timeFmt.format(Date(it)) } ?: "--:--"}", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CameraAttendanceView(db: AppDatabase) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var banner by remember { mutableStateOf("Position face in frame") }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val previewView = PreviewView(c)
                val providerFuture = ProcessCameraProvider.getInstance(c)
                providerFuture.addListener({
                    val camProvider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    var busy = false
                    analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                        if (busy) { proxy.close(); return@setAnalyzer }
                        busy = true
                        val bmp = proxy.toBitmap()
                        proxy.close()

                        scope.launch(Dispatchers.Default) {
                            try {
                                val faces = FaceEngine.detectFaces(bmp)
                                if (faces.isEmpty()) {
                                    withContext(Dispatchers.Main) { banner = "No face detected" }
                                } else if (faces.size > 1) {
                                    withContext(Dispatchers.Main) { banner = "Multiple faces! Only 1 person allowed" }
                                } else {
                                    val liveFeatures = FaceEngine.extractFeatures(bmp)
                                    val templates = db.dao().getAllTemplates()
                                    var matchedEmployeeId: Long? = null

                                    for (t in templates) {
                                        val vec = t.vector.split(",").map { it.toFloat() }.toFloatArray()
                                        if (FaceEngine.similarity(liveFeatures, vec) > 0.85f) {
                                            matchedEmployeeId = t.employeeId
                                            break
                                        }
                                    }

                                    val finalId = matchedEmployeeId
                                    if (finalId != null) {
                                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                        val existing = db.dao().getAttendance(finalId, today)
                                        val now = System.currentTimeMillis()
                                        if (existing == null) {
                                            db.dao().saveAttendance(AttendanceEntity(employeeId = finalId, employeeName = "Employee #$finalId", attDate = today, checkInTime = now))
                                            withContext(Dispatchers.Main) { banner = "CHECK-IN Recorded!" }
                                        } else if (existing.checkOutTime == null && (now - existing.checkInTime > 15000)) {
                                            db.dao().saveAttendance(existing.copy(checkOutTime = now))
                                            withContext(Dispatchers.Main) { banner = "CHECK-OUT Recorded!" }
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) { banner = "Face not recognized" }
                                    }
                                }
                            } finally { busy = false }
                        }
                    }
                    camProvider.unbindAll()
                    camProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(c))
                previewView
            }
        )

        Box(modifier = Modifier.size(240.dp).border(3.dp, Color.Green, CircleShape).align(Alignment.Center))
        Card(modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
            Text(banner, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RegisterEmployeeView(db: AppDatabase, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Register New Employee", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Employee ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = dept, onValueChange = { dept = it }, label = { Text("Department") }, modifier = Modifier.fillMaxWidth())

        Button(onClick = {
            if (code.isBlank() || name.isBlank()) return@Button
            scope.launch(Dispatchers.IO) {
                val empId = db.dao().insertEmployee(EmployeeEntity(employeeCode = code, fullName = name, department = dept))
                val dummyFeatures = FloatArray(64) { 0.1f }
                db.dao().insertTemplate(FaceTemplateEntity(employeeId = empId, vector = dummyFeatures.joinToString(",")))
                withContext(Dispatchers.Main) { onDone() }
            }
        }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Save Employee")
        }
    }
}

@Composable
fun ManualAttendanceView(db: AppDatabase, onDone: () -> Unit) {
    val employees by db.dao().getEmployeesFlow().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var reason by remember { mutableStateOf("Camera malfunction") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Manual Attendance Logging", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Select Employee:")
        LazyColumn(modifier = Modifier.height(150.dp)) {
            items(employees) { emp ->
                Button(
                    onClick = { selectedId = emp.id },
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedId == emp.id) MaterialTheme.colorScheme.primary else Color.LightGray)
                ) { Text(emp.fullName) }
            }
        }
        OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val emp = employees.find { it.id == selectedId } ?: return@Button
            scope.launch(Dispatchers.IO) {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                db.dao().saveAttendance(AttendanceEntity(employeeId = emp.id, employeeName = emp.fullName, attDate = today, checkInTime = System.currentTimeMillis(), isManual = true, reason = reason))
                withContext(Dispatchers.Main) { onDone() }
            }
        }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Record Manual Attendance")
        }
    }
}
