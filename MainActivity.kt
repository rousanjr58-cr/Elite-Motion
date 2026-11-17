package com.elitemotion.editor

import android.content.Intent
import android.os.StatFs
import android.util.Xml
import java.io.InputStream
import java.io.OutputStream
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import android.graphics.Matrix
import androidx.media3.effect.MatrixTransformation
import androidx.media3.common.util.Size
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val background = Color(0xFF101018)
private val panel = Color(0xFF20202E)
private val accent = Color(0xFF9C75FF)

data class MotionKeyframe(val timeMs: Long, val x: Float, val y: Float, val scale: Float, val rotation: Float)
data class ExtraTrack(val uri: String, val durationMs: Long, val offsetMs: Long = 0L, val volume: Float = 1f)

/** Animated matrix evaluated at presentation timestamps (microseconds). */
@androidx.annotation.OptIn(UnstableApi::class)
class KeyframeMatrix(private val frames: List<MotionKeyframe>) : MatrixTransformation {
    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val t = presentationTimeUs / 1000L
        val sorted = frames.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return Matrix()
        val a = sorted.lastOrNull { it.timeMs <= t } ?: sorted.first()
        val b = sorted.firstOrNull { it.timeMs >= t } ?: sorted.last()
        val f = if (a.timeMs == b.timeMs) 0f else ((t-a.timeMs).toFloat()/(b.timeMs-a.timeMs)).coerceIn(0f,1f)
        fun lerp(v: Float,w: Float) = v+(w-v)*f
        return Matrix().apply {
            postScale(lerp(a.scale,b.scale),lerp(a.scale,b.scale))
            postRotate(lerp(a.rotation,b.rotation))
            postTranslate(lerp(a.x,b.x),lerp(a.y,b.y))
        }
    }
}

data class Clip(val uri: String, val durationMs: Long, val startMs: Long = 0, val endMs: Long = durationMs)

class MainActivity : ComponentActivity() {
    private val clips = mutableStateListOf<Clip>()
    private var selected by mutableIntStateOf(0)
    private val overlayTracks = mutableStateListOf<ExtraTrack>()
    private val musicTracks = mutableStateListOf<ExtraTrack>()
    private val motionFrames = mutableStateListOf<MotionKeyframe>()
    private var transition by mutableStateOf("Cut")
    private var keyTime by mutableFloatStateOf(0f)
    private var keyX by mutableFloatStateOf(0f)
    private var keyY by mutableFloatStateOf(0f)
    private var keyScale by mutableFloatStateOf(1f)
    private var keyRotation by mutableFloatStateOf(0f)
    private var title by mutableStateOf("")
    private var grayscale by mutableStateOf(false)
    private var muted by mutableStateOf(false)
    private var rotation by mutableFloatStateOf(0f)
    private var exportHeight by mutableIntStateOf(720)
    private var exportFps by mutableIntStateOf(30)
    private var bitrateMbps by mutableFloatStateOf(8f)
    private var exportScreen by mutableStateOf(false)
    private var exportKind by mutableStateOf("Video")
    private var exportMessage by mutableStateOf("")
    private var status by mutableStateOf("Import a video to begin")
    private var exporting by mutableStateOf(false)
    private var transformer: Transformer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadProject()
        setContent { Editor() }
    }

    override fun onPause() { super.onPause(); saveProject() }

    private fun duration(uri: Uri): Long = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, uri)
        val value = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
        r.release()
        value.coerceAtLeast(1000L)
    } catch (_: Exception) { 1000L }

    private fun addVideos(uris: List<Uri>) {
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            clips.add(Clip(uri.toString(), duration(uri)))
        }
        if (clips.isNotEmpty()) selected = clips.lastIndex
        status = "${clips.size} video clip(s) loaded"
        saveProject()
    }

    private fun saveProject() {
        val root = JSONObject()
        val arr = JSONArray()
        clips.forEach { c -> arr.put(JSONObject().put("uri", c.uri).put("duration", c.durationMs).put("start", c.startMs).put("end", c.endMs)) }
        fun trackJson(items: List<ExtraTrack>) = JSONArray().apply { items.forEach { put(JSONObject().put("uri",it.uri).put("duration",it.durationMs).put("offset",it.offsetMs).put("volume",it.volume.toDouble())) } }
        root.put("overlays",trackJson(overlayTracks)).put("music",trackJson(musicTracks))
        root.put("keyframes", JSONArray().apply { motionFrames.forEach { put(JSONObject().put("time",it.timeMs).put("x",it.x.toDouble()).put("y",it.y.toDouble()).put("scale",it.scale.toDouble()).put("rotation",it.rotation.toDouble())) } })
        root.put("fps",exportFps).put("bitrateMbps",bitrateMbps.toDouble())
        root.put("transition",transition)
        root.put("clips", arr).put("text", title).put("grayscale", grayscale).put("muted", muted).put("rotation", rotation).put("height", exportHeight)
        openFileOutput("project.json", MODE_PRIVATE).bufferedWriter().use { it.write(root.toString(2)) }
        status = "Project saved locally"
    }

    private fun loadProject() {
        try {
            val root = JSONObject(openFileInput("project.json").bufferedReader().use { it.readText() })
            val arr = root.getJSONArray("clips")
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                clips.add(Clip(c.getString("uri"), c.getLong("duration"), c.getLong("start"), c.getLong("end")))
            }
            fun restoreTracks(key: String, target: MutableList<ExtraTrack>) {
                val list = root.optJSONArray(key) ?: return
                for (i in 0 until list.length()) { val o = list.getJSONObject(i); target.add(ExtraTrack(o.getString("uri"),o.getLong("duration"),o.optLong("offset"),o.optDouble("volume",1.0).toFloat())) }
            }
            restoreTracks("overlays",overlayTracks); restoreTracks("music",musicTracks)
            root.optJSONArray("keyframes")?.let { a -> for (i in 0 until a.length()) { val o=a.getJSONObject(i); motionFrames.add(MotionKeyframe(o.getLong("time"),o.getDouble("x").toFloat(),o.getDouble("y").toFloat(),o.getDouble("scale").toFloat(),o.getDouble("rotation").toFloat())) } }
            exportFps = root.optInt("fps",30)
            bitrateMbps = root.optDouble("bitrateMbps",8.0).toFloat()
            transition = root.optString("transition","Cut")
            title = root.optString("text")
            grayscale = root.optBoolean("grayscale")
            muted = root.optBoolean("muted")
            rotation = root.optDouble("rotation", 0.0).toFloat()
            exportHeight = root.optInt("height", 720)
            status = "Saved project restored"
        } catch (_: Exception) { }
    }

    private fun exportProjectTo(uri: Uri) {
        try {
            saveProject()
            contentResolver.openOutputStream(uri)?.use { out ->
                openFileInput("project.json").use { it.copyTo(out) }
            } ?: error("Cannot open destination")
            status = "Elite-Motion project exported"
        } catch (e: Exception) { status = "Project export failed: ${e.message}" }
    }

    private fun importProjectFrom(uri: Uri) {
        try {
            val name = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
            require(bytes.size < 10_000_000) { "Project file is too large" }
            val raw = bytes.toString(Charsets.UTF_8)
            val json = if (raw.trimStart().startsWith("{")) raw else if (raw.trimStart().startsWith("<")) {
                // Supports Elite-Motion's documented XML project schema, not arbitrary effect XML or AEPX.
                val parser = Xml.newPullParser()
                parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", false)
                parser.setInput(raw.reader())
                var rootSeen = false
                val clipsJson = JSONArray()
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        if (!rootSeen) {
                            require(parser.name == "elite-motion-project") { "Unsupported XML format. AEPX/effect XML cannot be imported as projects." }
                            rootSeen = true
                        } else if (parser.name == "clip") {
                            val u = parser.getAttributeValue(null,"uri") ?: ""
                            val d = parser.getAttributeValue(null,"duration")?.toLongOrNull() ?: 0L
                            require(u.isNotBlank() && d > 0) { "Invalid clip" }
                            clipsJson.put(JSONObject().put("uri",u).put("duration",d)
                                .put("start",parser.getAttributeValue(null,"start")?.toLongOrNull() ?: 0L)
                                .put("end",parser.getAttributeValue(null,"end")?.toLongOrNull() ?: d))
                        }
                    }
                    event = parser.next()
                }
                JSONObject().put("clips",clipsJson).toString()
            } else error("Unsupported file. Import Elite-Motion JSON or Elite-Motion XML.")
            val obj = JSONObject(json)
            require(obj.has("clips") && obj.getJSONArray("clips").length() < 500) { "Invalid project" }
            openFileOutput("project.json", MODE_PRIVATE).use { it.write(json.toByteArray()) }
            clips.clear(); overlayTracks.clear(); musicTracks.clear(); motionFrames.clear()
            selected = 0
            loadProject()
            status = "Project imported. Reconnect missing media if necessary."
        } catch (e: Exception) { status = "Import failed: ${e.message}" }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun export() {
        if (clips.isEmpty() || exporting) return
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        outputDir.mkdirs()
        val filename = "EliteMotion_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val output = File(outputDir, filename)
        try {
            val videoEffects = mutableListOf<androidx.media3.common.Effect>()
            if (grayscale) videoEffects.add(RgbFilter.createGrayscaleFilter())
            if (rotation != 0f) videoEffects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotation).build())
            if (motionFrames.isNotEmpty()) videoEffects.add(KeyframeMatrix(motionFrames.toList()))
            videoEffects.add(Presentation.createForHeight(exportHeight))
            if (title.isNotBlank()) {
                val text = SpannableString(title)
                text.setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, text.length, 0)
                text.setSpan(RelativeSizeSpan(1.8f), 0, text.length, 0)
                videoEffects.add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(text))))
            }
            val edited = clips.map { c ->
                val media = MediaItem.Builder().setUri(Uri.parse(c.uri))
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()).build()
                EditedMediaItem.Builder(media).setRemoveAudio(muted)
                    .setEffects(Effects(emptyList(), videoEffects)).build()
            }
            // A separate sequence for each track. Media3 mixes audio sequences during export.
            // Video track compositing is device/Media3-version dependent; see README.
            val sequences = mutableListOf(EditedMediaItemSequence(edited))
            val totalMs = clips.sumOf { it.endMs-it.startMs }
            fun paddedTrack(track: ExtraTrack, audioOnly: Boolean): EditedMediaItemSequence {
                val items = mutableListOf<EditedMediaItem>()
                if (track.offsetMs > 0) items.add(EditedMediaItem.Builder(MediaItem.EMPTY).setDurationUs(track.offsetMs*1000).build())
                val media = MediaItem.Builder().setUri(track.uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(minOf(track.durationMs, (totalMs-track.offsetMs).coerceAtLeast(1))).build()).build()
                items.add(EditedMediaItem.Builder(media).setRemoveVideo(audioOnly).setRemoveAudio(!audioOnly).build())
                val remaining = totalMs-track.offsetMs-track.durationMs
                if (remaining > 0) items.add(EditedMediaItem.Builder(MediaItem.EMPTY).setDurationUs(remaining*1000).build())
                return EditedMediaItemSequence(items)
            }
            musicTracks.filter { it.offsetMs < totalMs }.forEach { sequences.add(paddedTrack(it,true)) }
            overlayTracks.filter { it.offsetMs < totalMs }.forEach { sequences.add(paddedTrack(it,false)) }
            val composition = Composition.Builder(sequences).build()
            exporting = true
            status = "Exporting… Keep the app open"
            val encoderFactory = DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                    .setBitrate((bitrateMbps * 1_000_000).toInt()).build())
                .build()
            transformer = Transformer.Builder(this).setEncoderFactory(encoderFactory).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    runOnUiThread { exporting = false; status = "Export complete: ${output.absolutePath}" }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    runOnUiThread { exporting = false; status = "Export error: ${exportException.message}" }
                }
            }).build()
            transformer!!.start(composition, output.absolutePath)
        } catch (e: Exception) { exporting = false; status = "Export failed: ${e.message}" }
    }

    @Composable
    private fun Editor() {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addVideos(it) }
        val layerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { try { contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_:Exception) {} ; overlayTracks.add(ExtraTrack(it.toString(),duration(it))); saveProject() } }
        val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { try { contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_:Exception) {} ; musicTracks.add(ExtraTrack(it.toString(),duration(it))); saveProject() } }
        val projectImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importProjectFrom) }
        val projectExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(::exportProjectTo) }
        if (exportScreen) {
            ExportScreen(onBack = { exportScreen = false }, onExport = { export() },
                onProjectExport = { projectExporter.launch("Elite-Motion-project.json") })
            return
        }
        val current = clips.getOrNull(selected)
        var startText by remember(current) { mutableStateOf(current?.startMs?.div(1000f)?.toString() ?: "0") }
        var endText by remember(current) { mutableStateOf(current?.endMs?.div(1000f)?.toString() ?: "0") }
        MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = background, surface = panel)) {
            Column(Modifier.fillMaxSize().background(background).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("ELITE-MOTION", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { saveProject() }) { Text("Save") }
                    TextButton(onClick = { projectImporter.launch(arrayOf("application/json", "text/xml", "application/xml", "*/*")) }) { Text("Import project") }
                    TextButton(onClick = { projectExporter.launch("Elite-Motion-project.json") }) { Text("Share project") }
                }
                if (current != null) {
                    key(current.uri, current.startMs, current.endMs) { Preview(current) }
                } else {
                    Box(Modifier.fillMaxWidth().height(210.dp).background(panel, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text("Your preview appears here", color = Color.LightGray)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("video/*")) }) { Text("+ Import videos") }
                    Button(onClick = { exportScreen = true }, enabled = clips.isNotEmpty() && !exporting) { Text("Export & Share") }
                }
                Text("TIMELINE · Tap a clip to edit", color = accent, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(clips) { index, clip ->
                        Surface(onClick = { selected = index }, shape = RoundedCornerShape(8.dp), color = if (index == selected) accent else panel) {
                            Column(Modifier.width(116.dp).padding(10.dp)) {
                                Text("Clip ${index + 1}", fontWeight = FontWeight.Bold)
                                Text("${"%.1f".format((clip.endMs - clip.startMs) / 1000f)} sec", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (current != null) {
                    Text("TRIM SELECTED CLIP", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(startText, { startText = it }, Modifier.weight(1f), label = { Text("Start (sec)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(endText, { endText = it }, Modifier.weight(1f), label = { Text("End (sec)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val start = ((startText.toFloatOrNull() ?: 0f) * 1000).toLong().coerceAtLeast(0)
                            val end = ((endText.toFloatOrNull() ?: 0f) * 1000).toLong().coerceAtMost(current.durationMs)
                            if (start < end) { clips[selected] = current.copy(startMs = start, endMs = end); status = "Trim applied" } else status = "Start must be before end"
                        }) { Text("Apply trim") }
                        OutlinedButton(onClick = { clips.removeAt(selected); selected = (selected - 1).coerceAtLeast(0) }) { Text("Delete clip") }
                        OutlinedButton(onClick = { clips.add(selected + 1, current.copy()); selected++ }) { Text("Duplicate") }
                        OutlinedButton(onClick = { if (selected > 0) { val c = clips.removeAt(selected); clips.add(--selected, c) } }) { Text("← Move") }
                        OutlinedButton(onClick = { if (selected < clips.lastIndex) { val c = clips.removeAt(selected); clips.add(++selected, c) } }) { Text("Move →") }
                    }
                }
                HorizontalDivider()
                Text("KEYFRAME ANIMATION · MAIN VIDEO", color = accent, fontWeight = FontWeight.Bold)
                Text("Time: ${keyTime.toInt()} ms", color = Color.White)
                Slider(keyTime, { keyTime=it }, valueRange=0f..((clips.sumOf { it.endMs-it.startMs }).coerceAtLeast(1000L).toFloat()))
                Text("X: ${keyX.toInt()}% · Y: ${keyY.toInt()}%", color=Color.White)
                Slider(keyX,{keyX=it},valueRange=-100f..100f)
                Slider(keyY,{keyY=it},valueRange=-100f..100f)
                Text("Scale: ${"%.2f".format(keyScale)} · Rotation: ${keyRotation.toInt()}°",color=Color.White)
                Slider(keyScale,{keyScale=it},valueRange=0.1f..3f)
                Slider(keyRotation,{keyRotation=it},valueRange=-180f..180f)
                Button(onClick={ motionFrames.removeAll { it.timeMs==keyTime.toLong() }; motionFrames.add(MotionKeyframe(keyTime.toLong(),keyX/100f,keyY/100f,keyScale,keyRotation)); saveProject() }) { Text("Add / replace keyframe") }
                motionFrames.sortedBy { it.timeMs }.forEach { k -> Row(verticalAlignment=Alignment.CenterVertically) { Text("${k.timeMs}ms · scale ${k.scale}",color=Color.White,modifier=Modifier.weight(1f)); TextButton(onClick={motionFrames.remove(k)}) { Text("Remove") } } }
                HorizontalDivider()
                Text("INDEPENDENT TRACKS", color=accent,fontWeight=FontWeight.Bold)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={layerPicker.launch(arrayOf("video/*"))}) { Text("+ Video layer") }
                    Button(onClick={audioPicker.launch(arrayOf("audio/*"))}) { Text("+ Music") }
                }
                overlayTracks.forEachIndexed { i,t -> TrackEditor("Video layer ${i+1}",t, { overlayTracks[i]=it }, { overlayTracks.removeAt(i) }) }
                musicTracks.forEachIndexed { i,t -> TrackEditor("Audio track ${i+1}",t, { musicTracks[i]=it }, { musicTracks.removeAt(i) }) }
                Text("TRANSITIONS",color=accent,fontWeight=FontWeight.Bold)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("Cut","Fade").forEach { option -> FilterChip(selected=transition==option,onClick={transition=option},label={Text(option)}) }
                }
                Text("Cut is supported in export. Fade is a planned rendering mode and currently exports as a cut.",color=Color.LightGray)
                HorizontalDivider()
                Text("TEXT OVERLAY", fontWeight = FontWeight.Bold, color = Color.White)
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Text on exported video") }, singleLine = true)
                Text("EFFECTS & OUTPUT", fontWeight = FontWeight.Bold, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Black & white", color = Color.White); Spacer(Modifier.weight(1f)); Switch(grayscale, { grayscale = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mute original audio", color = Color.White); Spacer(Modifier.weight(1f)); Switch(muted, { muted = it })
                }
                Text("Rotation: ${rotation.toInt()}°", color = Color.White)
                Slider(rotation, { rotation = it }, valueRange = 0f..270f, steps = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Export resolution", color = Color.White)
                    listOf(480, 720, 1080).forEach { h -> FilterChip(selected = exportHeight == h, onClick = { exportHeight = h }, label = { Text("${h}p") }) }
                }
                Text(status, color = accent, style = MaterialTheme.typography.bodySmall)
                Text("Exports are saved to Android/data/com.elitemotion.editor/files/Movies. Use a file manager or Android Studio Device Explorer to retrieve them.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    @Composable
    private fun ExportScreen(onBack: () -> Unit, onExport: () -> Unit, onProjectExport: () -> Unit) {
        val white = Color(0xFFFAFAFB)
        val ink = Color(0xFF1C2239)
        val mint = Color(0xFF00E6A2)
        val freeBytes = remember { try { StatFs(filesDir.absolutePath).availableBytes } catch (_: Exception) { 0L } }
        val seconds = clips.sumOf { it.endMs - it.startMs }.toDouble() / 1000.0
        val estimatedMb = seconds * (bitrateMbps + 0.192) / 8.0
        MaterialTheme {
            Column(Modifier.fillMaxSize().background(white).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("✕", color = ink, style = MaterialTheme.typography.headlineMedium) }
                    Spacer(Modifier.weight(1f))
                    Text("Export & Share", color = ink, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Available Space", color = ink)
                    Text("%.1f GB".format(Locale.US,freeBytes / 1e9), color = ink)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("File size (estimate)", color = ink)
                    Text("%.1f MB".format(Locale.US,estimatedMb), color = ink)
                }
                HorizontalDivider()
                listOf("Video", "Upload to Cloud", "Project Package", "Current Frame as PNG", "Image Sequence").forEach { kind ->
                    Surface(onClick = { exportKind = kind }, shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = exportKind == kind, onClick = { exportKind = kind }, colors = RadioButtonDefaults.colors(selectedColor = mint))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(kind, color = ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text(when(kind) {
                                    "Video" -> "MP4 ${exportHeight}p · ${exportFps}fps · %.1f Mbps".format(Locale.US,bitrateMbps)
                                    "Project Package" -> "Export editable Elite-Motion project JSON"
                                    else -> "Not supported in this build"
                                }, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text("Video resolution", color = ink, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(480,720,1080,1440,2160).forEach { h ->
                        FilterChip(selected = exportHeight == h, onClick = { exportHeight = h }, label = { Text("${h}p") })
                    }
                }
                Text("Frame rate (source-dependent)", color = ink, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24,30,60).forEach { f -> FilterChip(selected = exportFps == f, onClick = { exportFps = f }, label = { Text("${f} fps") }) }
                }
                Text("Video bitrate: %.1f Mbps".format(Locale.US,bitrateMbps), color = ink, fontWeight = FontWeight.Bold)
                Slider(value = bitrateMbps, onValueChange = { bitrateMbps = it }, valueRange = 1f..50f)
                Text("The encoder requests the selected bitrate; actual output depends on device support. FPS selection is saved but not enforced by this exporter.", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { if (exportKind == "Video") onExport() else if (exportKind == "Project Package") onProjectExport() else exportMessage = "This export type is not implemented yet" },
                    modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = ink)) {
                    Text("Export", color = mint, style = MaterialTheme.typography.titleLarge)
                }
                if (exportMessage.isNotBlank()) Text(exportMessage, color = Color.DarkGray)
                Text(status, color = ink, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun TrackEditor(label: String, track: ExtraTrack, update: (ExtraTrack)->Unit, delete: ()->Unit) {
        Column(Modifier.fillMaxWidth().background(panel,RoundedCornerShape(8.dp)).padding(8.dp)) {
            Text(label,color=Color.White,fontWeight=FontWeight.Bold)
            Text("Start offset: ${track.offsetMs/1000f}s",color=Color.White)
            Slider(track.offsetMs.toFloat(),{update(track.copy(offsetMs=it.toLong()))},valueRange=0f..60000f)
            Text("Track gain (metadata): ${"%.1f".format(track.volume)}",color=Color.LightGray)
            Slider(track.volume,{update(track.copy(volume=it))},valueRange=0f..2f)
            TextButton(onClick=delete) { Text("Remove track") }
        }
    }

    @Composable
    private fun Preview(clip: Clip) {
        val player = remember(clip) {
            ExoPlayer.Builder(this).build().apply {
                setMediaItem(MediaItem.Builder().setUri(clip.uri)
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()).build())
                prepare()
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxWidth().height(225.dp).background(Color.Black, RoundedCornerShape(10.dp)))
    }
}
