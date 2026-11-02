package ir.mbarani.companycamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
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
    private lateinit var categorySpinner: Spinner
    private lateinit var descriptionInput: EditText
    private lateinit var listBox: GridLayout
    private lateinit var searchInput: EditText
    private lateinit var countLabel: TextView
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var allPhotos: List<Photo> = emptyList()

    private val categories = listOf("ماشین‌آلات", "نفرات")
    private fun categoryFolder(): String = if (categorySpinner.selectedItemPosition == 1) "PERSONNEL" else "MACHINERY"
    private fun cameraRoot(): File = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "CompanyCamera").apply { mkdirs() }
    private fun photosRoot(): File = File(cameraRoot(), "PHOTOS").apply { mkdirs() }
    private fun backupsRoot(): File = File(cameraRoot(), "BACKUPS").apply { mkdirs() }
    private fun categoryDir(category: String): File = File(photosRoot(), category).apply { mkdirs() }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else toast("برای عکاسی باید مجوز دوربین را بدهی.")
    }
    private val exportJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) lifecycleScope.launch { writeJson(uri) } }
    private val exportZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) lifecycleScope.launch { writeZip(uri) } }
    private val importJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) lifecycleScope.launch { restoreJson(uri) } }
    private val importZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) lifecycleScope.launch { restoreZip(uri) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "company-camera.db").build()
        photosRoot(); categoryDir("MACHINERY"); categoryDir("PERSONNEL"); backupsRoot()
        buildUi(); refreshPhotos()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18,18,18,24); setBackgroundColor(0xFF0A1220.toInt()); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(root); setContentView(scroll)
        root.addView(text("📷 COMPANY CAMERA",22,-1,true))
        root.addView(text("ثبت و آرشیو تصاویر کاری · کاملاً آفلاین",13,0xFF9EB0C9.toInt()))
        root.addView(label("دسته‌بندی تصاویر"))
        categorySpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categories) }
        root.addView(categorySpinner, params())
        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; layoutParams = LinearLayout.LayoutParams(-1,dp(240)).apply { topMargin=dp(12) } }
        root.addView(previewView)
        val cameraRow = LinearLayout(this)
        cameraRow.addView(button("فعال‌سازی دوربین") { startCamera() }, weightParams())
        cameraRow.addView(button("تعویض دوربین") { lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; startCamera() }, weightParams())
        root.addView(cameraRow)
        root.addView(button("● گرفتن عکس") { takePhoto() }.apply { textSize=17f }, params())
        descriptionInput = EditText(this).apply { hint="توضیحات عکس (اختیاری)"; setTextColor(-1); setHintTextColor(0xFF9EB0C9.toInt()) }
        root.addView(descriptionInput, params())

        val archiveHeader = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        archiveHeader.addView(text("آرشیو تصاویر",18,-1,true), LinearLayout.LayoutParams(0,-2,1f))
        archiveHeader.addView(button("⌕") { searchInput.visibility = if (searchInput.visibility == View.VISIBLE) View.GONE else View.VISIBLE; if (searchInput.visibility == View.GONE) { searchInput.setText(""); refreshGrid() } })
        root.addView(archiveHeader)
        countLabel = text("",12,0xFF9EB0C9.toInt()); root.addView(countLabel)
        searchInput = EditText(this).apply { hint="جست‌وجوی نام عکس؛ مثلاً 2254"; setTextColor(-1); setHintTextColor(0xFF9EB0C9.toInt()); visibility=View.GONE }
        searchInput.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshGrid() }
            override fun afterTextChanged(s: Editable?) {}
        })
        root.addView(searchInput, params())
        listBox = GridLayout(this).apply { columnCount=3; useDefaultMargins=true }
        root.addView(listBox, params())

        root.addView(text("پشتیبان‌گیری و بازیابی",18,-1,true))
        val row1=LinearLayout(this); row1.addView(button("خروجی JSON") { exportJson.launch("company-camera-backup.json") },weightParams()); row1.addView(button("خروجی ZIP") { exportZip.launch("company-camera-backup.zip") },weightParams()); root.addView(row1)
        val row2=LinearLayout(this); row2.addView(button("بازیابی JSON") { importJson.launch(arrayOf("application/json","text/*")) },weightParams()); row2.addView(button("بازیابی ZIP") { importZip.launch(arrayOf("application/zip","application/octet-stream")) },weightParams()); root.addView(row2)
        root.addView(button("راهنمای برنامه و مسیر فایل‌ها") { showHelp() }, params())
        root.addView(text("عکس‌ها در پوشه اختصاصی CompanyCamera ذخیره می‌شوند. برای جلوگیری از ازدست‌رفتن تصاویر، مرتباً پشتیبان ZIP بگیر.",12,0xFFFFDFA0.toInt()))
    }

    private fun showHelp() {
        val message = "راهنمای استفاده\n\n۱) دسته‌بندی ماشین‌آلات یا نفرات را انتخاب کن.\n۲) دوربین را فعال و عکس بگیر.\n۳) نام فایل را وارد کن؛ پسوند JPG خودکار اضافه می‌شود.\n۴) عکس‌ها در آرشیو به‌صورت بندانگشتی دیده می‌شوند؛ با لمس عکس، تصویر بزرگ باز می‌شود.\n۵) برای یافتن عکس از آیکون جست‌وجو استفاده کن.\n۶) برای انتقال یا نگهداری امن، خروجی ZIP بگیر و فایل را در محل دلخواه ذخیره کن.\n\nمسیر پوشه عکس‌ها:\n${photosRoot().absolutePath}\n\nپوشه ماشین‌آلات:\n${categoryDir("MACHINERY").absolutePath}\n\nپوشه نفرات:\n${categoryDir("PERSONNEL").absolutePath}\n\nمسیر پشتیبان‌های داخلی برنامه:\n${backupsRoot().absolutePath}\n\nنکته: خروجی JSON و ZIP علاوه بر ذخیره نسخه داخلی، در محلی هم ذخیره می‌شود که هنگام گرفتن خروجی در پنجره فایل گوشی انتخاب می‌کنی."
        AlertDialog.Builder(this).setTitle("راهنمای Company Camera").setMessage(message).setPositiveButton("متوجه شدم",null).show()
    }

    private fun startCamera() {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({ cameraProvider=future.get(); val preview=Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }; imageCapture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build(); try { cameraProvider?.unbindAll(); cameraProvider?.bindToLifecycle(this,CameraSelector.Builder().requireLensFacing(lens).build(),preview,imageCapture) } catch(e:Exception){ toast("خطا در راه‌اندازی دوربین: ${e.localizedMessage}") } },ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture=imageCapture ?: run { toast("دوربین هنوز آماده نیست."); return }
        val temp=File(cacheDir,"capture-${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(temp).build(),ContextCompat.getMainExecutor(this),object:ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output:ImageCapture.OutputFileResults) { askFileName(temp) }
            override fun onError(exc:ImageCaptureException) { toast("گرفتن عکس ناموفق بود: ${exc.message}") }
        })
    }

    private fun askFileName(temp:File) {
        val name=EditText(this).apply { hint="مثلاً 2254"; setTextColor(0xFF111111.toInt()) }
        AlertDialog.Builder(this).setTitle("نام عکس را وارد کن").setView(name).setPositiveButton("ذخیره") { _,_ ->
            val safe=name.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"),"-").removeSuffix(".jpg").removeSuffix(".JPG")
            if(safe.isBlank()){ toast("نام فایل نمی‌تواند خالی باشد."); temp.delete(); return@setPositiveButton }
            lifecycleScope.launch {
                val category=categoryFolder(); val dir=categoryDir(category); val dest=File(dir,"$safe.jpg")
                if(dest.exists()){ withContext(Dispatchers.Main){ toast("این نام در این دسته‌بندی قبلاً استفاده شده است."); askFileName(temp) }; return@launch }
                withContext(Dispatchers.IO){ temp.copyTo(dest,false) }
                db.photoDao().insert(Photo(fileName="$safe.jpg",project=category,description=descriptionInput.text.toString(),relativePath=dest.absolutePath,createdAt=System.currentTimeMillis()))
                temp.delete(); withContext(Dispatchers.Main){ descriptionInput.text.clear(); toast("عکس در پوشه $category ذخیره شد."); refreshPhotos() }
            }
        }.setNegativeButton("لغو"){_,_->temp.delete()}.show()
    }

    private fun refreshPhotos() { lifecycleScope.launch { allPhotos=withContext(Dispatchers.IO){db.photoDao().all()}; refreshGrid() } }
    private fun refreshGrid() {
        if (!::listBox.isInitialized || !::searchInput.isInitialized) return
        listBox.removeAllViews()
        val query=searchInput.text.toString().trim()
        val shown=allPhotos.filter { query.isBlank() || it.fileName.contains(query,true) }
        countLabel.text="${shown.size} عکس${if(query.isNotBlank()) " پیدا شد" else " ثبت شده"}"
        shown.forEach { p ->
            val file=File(p.relativePath)
            val tile=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(4),dp(4),dp(4),dp(4)); setBackgroundColor(0xFF192A42.toInt()) }
            val image=ImageView(this).apply { layoutParams=LinearLayout.LayoutParams(dp(96),dp(96)); scaleType=ImageView.ScaleType.CENTER_CROP; if(file.exists()) setImageURI(android.net.Uri.fromFile(file)) else setImageResource(android.R.drawable.ic_menu_report_image); contentDescription=p.fileName }
            tile.addView(image); tile.addView(text(p.fileName,11,-1,true).apply { gravity=Gravity.CENTER; maxLines=1 })
            tile.setOnClickListener { showPhoto(p) }
            val lp=GridLayout.LayoutParams().apply { width=0; columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f); setMargins(dp(3),dp(3),dp(3),dp(3)) }
            listBox.addView(tile,lp)
        }
    }

    private fun showPhoto(p:Photo) {
        val file=File(p.relativePath); if(!file.exists()){toast("فایل عکس پیدا نشد.");return}
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(8)) }
        val image=ImageView(this).apply { adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER; setImageURI(android.net.Uri.fromFile(file)); layoutParams=LinearLayout.LayoutParams(-1,dp(420)) }
        content.addView(image); content.addView(text("${p.fileName} · ${p.project}",13,0xFF222222.toInt(),true))
        AlertDialog.Builder(this).setTitle(p.fileName).setView(content).setPositiveButton("اشتراک‌گذاری"){_,_->sharePhoto(p)}.setNegativeButton("بستن",null).show()
    }

    private fun sharePhoto(p:Photo) {
        val f=File(p.relativePath); if(!f.exists()){toast("فایل عکس پیدا نشد.");return}
        val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.provider",f)
        val intent=android.content.Intent(android.content.Intent.ACTION_SEND).apply { type="image/jpeg"; putExtra(android.content.Intent.EXTRA_STREAM,uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(android.content.Intent.createChooser(intent,"ارسال تصویر"))
    }

    private suspend fun backupObject():JSONObject=withContext(Dispatchers.IO){
        val arr=JSONArray(); db.photoDao().all().forEach { p -> val f=File(p.relativePath); val encoded=if(f.exists()) android.util.Base64.encodeToString(f.readBytes(),android.util.Base64.NO_WRAP) else ""; arr.put(JSONObject().put("fileName",p.fileName).put("project",p.project).put("description",p.description).put("createdAt",p.createdAt).put("imageBase64",encoded)) }
        JSONObject().put("format","company-camera-backup").put("version",2).put("exportedAt",System.currentTimeMillis()).put("photos",arr)
    }
    private suspend fun writeJson(uri:android.net.Uri){ try { val data=backupObject().toString(2).toByteArray(Charsets.UTF_8); withContext(Dispatchers.IO){ File(backupsRoot(),"company-camera-${System.currentTimeMillis()}.json").writeBytes(data); contentResolver.openOutputStream(uri)?.use{it.write(data)} }; toast("پشتیبان JSON ذخیره شد.") } catch(e:Exception){toast("خطا در خروجی JSON: ${e.localizedMessage}")} }
    private suspend fun writeZip(uri:android.net.Uri){
        try {
            val obj=backupObject(); val bytes=withContext(Dispatchers.IO){
                val output=ByteArrayOutputStream(); ZipOutputStream(BufferedOutputStream(output)).use{zip-> zip.putNextEntry(ZipEntry("backup.json"));zip.write(obj.toString(2).toByteArray(Charsets.UTF_8));zip.closeEntry(); db.photoDao().all().forEach{p->val f=File(p.relativePath);if(f.exists()){zip.putNextEntry(ZipEntry("photos/${p.project}/${p.fileName}"));f.inputStream().use{it.copyTo(zip)};zip.closeEntry()}} }; output.toByteArray()
            }
            withContext(Dispatchers.IO){ File(backupsRoot(),"company-camera-${System.currentTimeMillis()}.zip").writeBytes(bytes); contentResolver.openOutputStream(uri)?.use{it.write(bytes)} }
            toast("پشتیبان ZIP ذخیره شد.")
        }catch(e:Exception){toast("خطا در خروجی ZIP: ${e.localizedMessage}")}
    }
    private suspend fun restoreJson(uri:android.net.Uri){try{val raw=withContext(Dispatchers.IO){contentResolver.openInputStream(uri)!!.bufferedReader().readText()};restoreObject(JSONObject(raw));toast("بازیابی JSON انجام شد.");refreshPhotos()}catch(e:Exception){toast("فایل JSON معتبر نیست: ${e.localizedMessage}")}}
    private suspend fun restoreZip(uri:android.net.Uri){try{
        val entries=HashMap<String,ByteArray>();withContext(Dispatchers.IO){ZipInputStream(contentResolver.openInputStream(uri)!!).use{zis->while(true){val entry=zis.nextEntry?:break;if(!entry.isDirectory)entries[entry.name]=zis.readBytes();zis.closeEntry()}}}
        val obj=JSONObject(entries["backup.json"]?.toString(Charsets.UTF_8)?:error("backup.json پیدا نشد"));val arr=obj.getJSONArray("photos")
        for(i in 0 until arr.length()){val item=arr.getJSONObject(i);val project=item.optString("project","MACHINERY");val name=item.getString("fileName");val bytes=entries["photos/$project/$name"]?:item.optString("imageBase64").takeIf{it.isNotBlank()}?.let{android.util.Base64.decode(it,android.util.Base64.DEFAULT)};if(bytes!=null)saveRestored(item,bytes)}
        toast("بازیابی ZIP انجام شد.");refreshPhotos()
    }catch(e:Exception){toast("بازیابی ZIP ناموفق بود: ${e.localizedMessage}")}}
    private suspend fun restoreObject(obj:JSONObject){require(obj.optString("format")=="company-camera-backup"){"فرمت پشتیبان شناخته نشد"};val arr=obj.getJSONArray("photos");for(i in 0 until arr.length()){val item=arr.getJSONObject(i);val encoded=item.optString("imageBase64");if(encoded.isNotBlank())saveRestored(item,android.util.Base64.decode(encoded,android.util.Base64.DEFAULT))}}
    private suspend fun saveRestored(item:JSONObject,bytes:ByteArray)=withContext(Dispatchers.IO){
        val raw=item.optString("project","MACHINERY"); val category=when(raw){"PERSONNEL","PERSONELS","نفرات"->"PERSONNEL";"MACHINERY","ماشین‌آلات"->"MACHINERY";else->"MACHINERY"}
        val name=item.optString("fileName","restored-${System.currentTimeMillis()}.jpg").replace(Regex("[\\\\/:*?\"<>|]+"),"-")
        val dir=categoryDir(category);var file=File(dir,name);if(file.exists())file=File(dir,"${System.currentTimeMillis()}-$name");file.writeBytes(bytes)
        db.photoDao().insert(Photo(fileName=file.name,project=category,description=item.optString("description"),relativePath=file.absolutePath,createdAt=item.optLong("createdAt",System.currentTimeMillis())))
    }

    private fun text(s:String,size:Int,color:Int,bold:Boolean=false)=TextView(this).apply{text=s;textSize=size.toFloat();setTextColor(color);if(bold)setTypeface(typeface,android.graphics.Typeface.BOLD);setPadding(2,dp(7),2,dp(7));gravity=Gravity.START}
    private fun label(s:String)=text(s,13,0xFFCAD7E8.toInt(),true)
    private fun button(s:String,action:()->Unit)=Button(this).apply{text=s;isAllCaps=false;setOnClickListener{action()}}
    private fun params()=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7)}
    private fun weightParams()=LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(3),dp(5),dp(3),dp(5))}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
