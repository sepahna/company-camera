package ir.mbarani.companycamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var db: AppDatabase
    private lateinit var previewView: PreviewView
    private lateinit var projectInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var listBox: LinearLayout
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var pendingFile: File? = null
    private var currentPhotoPath = ""

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else toast("برای عکاسی باید مجوز دوربین را بدهی.")
    }
    private val exportJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch { writeJson(uri) }
    }
    private val exportZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch { writeZip(uri) }
    }
    private val importJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { restoreJson(uri) }
    }
    private val importZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { restoreZip(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "company-camera.db").build()
        buildUi()
        refreshPhotos()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 24)
            setBackgroundColor(0xFF0A1220.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(text("📷 COMPANY CAMERA", 22, 0xFFFFFFFF.toInt(), true))
        root.addView(text("ثبت و آرشیو تصاویر کاری · کاملاً آفلاین", 13, 0xFF9EB0C9.toInt()))
        projectInput = EditText(this).apply { hint = "محل پروژه؛ مثلاً انبار مرکزی"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF9EB0C9.toInt()) }
        root.addView(label("محل / بخش پروژه"))
        root.addView(projectInput, params())
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            layoutParams = LinearLayout.LayoutParams(-1, dp(250)).apply { topMargin = dp(12) }
        }
        root.addView(previewView)
        val cameraRow = LinearLayout(this)
        cameraRow.addView(button("فعال‌سازی دوربین") { startCamera() }, weightParams())
        cameraRow.addView(button("تعویض دوربین") { lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; startCamera() }, weightParams())
        root.addView(cameraRow)
        root.addView(button("● گرفتن عکس") { takePhoto() }.apply { textSize = 17f }, params())
        descriptionInput = EditText(this).apply { hint = "توضیحات عکس (اختیاری)"; setTextColor(-1); setHintTextColor(0xFF9EB0C9.toInt()) }
        root.addView(descriptionInput, params())
        root.addView(text("آرشیو تصاویر", 18, -1, true))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        root.addView(text("پشتیبان‌گیری و بازیابی", 18, -1, true))
        val row1 = LinearLayout(this)
        row1.addView(button("خروجی JSON") { exportJson.launch("company-camera-backup.json") }, weightParams())
        row1.addView(button("خروجی ZIP") { exportZip.launch("company-camera-backup.zip") }, weightParams())
        root.addView(row1)
        val row2 = LinearLayout(this)
        row2.addView(button("بازیابی JSON") { importJson.launch(arrayOf("application/json", "text/*")) }, weightParams())
        row2.addView(button("بازیابی ZIP") { importZip.launch(arrayOf("application/zip", "application/octet-stream")) }, weightParams())
        root.addView(row2)
        root.addView(text("اطلاعات در حافظه خصوصی برنامه نگهداری می‌شود. برای جلوگیری از ازدست‌رفتن تصاویر، مرتباً فایل پشتیبان بگیر.", 12, 0xFFFFDFA0.toInt()))
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(lens).build(), preview, imageCapture)
            } catch (e: Exception) { toast("خطا در راه‌اندازی دوربین: ${e.localizedMessage}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run { toast("دوربین هنوز آماده نیست."); return }
        val tempDir = File(cacheDir, "pending").apply { mkdirs() }
        val temp = File(tempDir, "capture-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(temp).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                pendingFile = temp
                askFileName(temp)
            }
            override fun onError(exc: ImageCaptureException) { toast("گرفتن عکس ناموفق بود: ${exc.message}") }
        })
    }

    private fun askFileName(temp: File) {
        val name = EditText(this).apply { hint = "مثلاً 2215"; setTextColor(0xFF111111.toInt()) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        box.addView(name)
        AlertDialog.Builder(this).setTitle("نام عکس را وارد کن").setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                val safe = name.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").removeSuffix(".jpg")
                if (safe.isBlank()) { toast("نام فایل نمی‌تواند خالی باشد."); temp.delete(); return@setPositiveButton }
                lifecycleScope.launch {
                    val project = projectInput.text.toString().trim().ifBlank { "بدون-محل" }
                    val folder = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CompanyCamera/$project").apply { mkdirs() }
                    val dest = File(folder, "$safe.jpg")
                    if (dest.exists()) { withContext(Dispatchers.Main) { toast("این نام در این محل قبلاً استفاده شده است."); askFileName(temp) }; return@launch }
                    withContext(Dispatchers.IO) { temp.copyTo(dest, overwrite = false) }
                    db.photoDao().insert(Photo(fileName = "$safe.jpg", project = project, description = descriptionInput.text.toString(), relativePath = dest.absolutePath, createdAt = System.currentTimeMillis()))
                    temp.delete()
                    withContext(Dispatchers.Main) { descriptionInput.text.clear(); toast("عکس ذخیره شد."); refreshPhotos() }
                }
            }.setNegativeButton("لغو") { _, _ -> temp.delete() }.show()
    }

    private fun refreshPhotos() {
        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) { db.photoDao().all() }
            listBox.removeAllViews()
            listBox.addView(text("${photos.size} عکس ثبت شده", 13, 0xFF9EB0C9.toInt()))
            photos.forEach { p ->
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 10); setBackgroundColor(0xFF192A42.toInt()) }
                row.addView(text("${p.fileName}  ·  ${p.project}", 14, -1, true))
                row.addView(text("${p.description}  ·  ${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(p.createdAt))}", 11, 0xFFB5C5DA.toInt()))
                row.addView(button("اشتراک‌گذاری / ارسال عکس") { sharePhoto(p) })
                listBox.addView(row, params().apply { bottomMargin = dp(7) })
            }
        }
    }

    private fun sharePhoto(p: Photo) {
        val f = File(p.relativePath)
        if (!f.exists()) { toast("فایل عکس پیدا نشد."); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "image/jpeg"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(android.content.Intent.createChooser(intent, "ارسال تصویر"))
    }

    private suspend fun backupObject(): JSONObject = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        db.photoDao().all().forEach { p ->
            val f = File(p.relativePath)
            val encoded = if (f.exists()) android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP) else ""
            arr.put(JSONObject().put("fileName", p.fileName).put("project", p.project).put("description", p.description).put("createdAt", p.createdAt).put("imageBase64", encoded))
        }
        JSONObject().put("format", "company-camera-backup").put("version", 1).put("exportedAt", System.currentTimeMillis()).put("photos", arr)
    }

    private suspend fun writeJson(uri: android.net.Uri) {
        try { val obj = backupObject(); withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(obj.toString(2).toByteArray(Charsets.UTF_8)) } }; toast("پشتیبان JSON ساخته شد.") }
        catch (e: Exception) { toast("خطا در خروجی JSON: ${e.localizedMessage}") }
    }

    private suspend fun writeZip(uri: android.net.Uri) {
        try {
            val obj = backupObject()
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                        zip.putNextEntry(ZipEntry("backup.json")); zip.write(obj.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                        val photos = db.photoDao().all()
                        photos.forEach { p ->
                            val f = File(p.relativePath)
                            if (f.exists()) { zip.putNextEntry(ZipEntry("photos/${p.project}/${p.fileName}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                        }
                    }
                }
            }
            toast("پشتیبان ZIP ساخته شد.")
        } catch (e: Exception) { toast("خطا در خروجی ZIP: ${e.localizedMessage}") }
    }

    private suspend fun restoreJson(uri: android.net.Uri) {
        try {
            val raw = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.bufferedReader().readText() }
            restoreObject(JSONObject(raw)); toast("بازیابی JSON انجام شد."); refreshPhotos()
        } catch (e: Exception) { toast("فایل JSON معتبر نیست: ${e.localizedMessage}") }
    }

    private suspend fun restoreZip(uri: android.net.Uri) {
        try {
            val entries = HashMap<String, ByteArray>()
            withContext(Dispatchers.IO) {
                ZipInputStream(contentResolver.openInputStream(uri)!!).use { zis ->
                    while (true) { val entry = zis.nextEntry ?: break; if (!entry.isDirectory) entries[entry.name] = zis.readBytes(); zis.closeEntry() }
                }
            }
            val json = entries["backup.json"]?.toString(Charsets.UTF_8) ?: error("backup.json پیدا نشد")
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("photos")
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val project = item.optString("project", "بدون-محل")
                val name = item.getString("fileName")
                val pathInZip = "photos/$project/$name"
                val bytes = entries[pathInZip] ?: item.optString("imageBase64").takeIf { it.isNotBlank() }?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
                if (bytes != null) saveRestored(item, bytes)
            }
            toast("بازیابی ZIP انجام شد."); refreshPhotos()
        } catch (e: Exception) { toast("بازیابی ZIP ناموفق بود: ${e.localizedMessage}") }
    }

    private suspend fun restoreObject(obj: JSONObject) {
        require(obj.optString("format") == "company-camera-backup") { "فرمت پشتیبان شناخته نشد" }
        val arr = obj.getJSONArray("photos")
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val encoded = item.optString("imageBase64")
            if (encoded.isNotBlank()) saveRestored(item, android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        }
    }

    private suspend fun saveRestored(item: JSONObject, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val project = item.optString("project", "بدون-محل").replace(Regex("[\\\\/:*?\"<>|]+"), "-")
        val name = item.optString("fileName", "restored-${System.currentTimeMillis()}.jpg").replace(Regex("[\\\\/:*?\"<>|]+"), "-")
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CompanyCamera/$project").apply { mkdirs() }
        var file = File(dir, name)
        if (file.exists()) file = File(dir, "${System.currentTimeMillis()}-$name")
        file.writeBytes(bytes)
        db.photoDao().insert(Photo(fileName = file.name, project = project, description = item.optString("description"), relativePath = file.absolutePath, createdAt = item.optLong("createdAt", System.currentTimeMillis())))
    }

    private fun text(s: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(2, dp(7), 2, dp(7)); gravity = Gravity.START
    }
    private fun label(s: String) = text(s, 13, 0xFFCAD7E8.toInt(), true)
    private fun button(s: String, action: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { action() } }
    private fun params() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }
    private fun weightParams() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(5), dp(3), dp(5)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
