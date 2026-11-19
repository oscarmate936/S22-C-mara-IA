package com.iacamara.app.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Portrait
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iacamara.app.ai.PhotoScore
import com.iacamara.app.processing.Editor
import com.iacamara.app.processing.PortraitProcessor
import com.iacamara.app.processing.PortraitStore
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Una foto de la carpeta de la app. */
data class GalleryPhoto(val uri: Uri, val name: String, val dateMs: Long)

/** Fotos de "Pictures/IA Camara", de la más nueva a la más vieja. */
object Gallery {
    fun load(context: Context): List<GalleryPhoto> {
        val out = ArrayList<GalleryPhoto>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
                arrayOf("Pictures/IA Camara%", "image/jpeg"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val ms = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000)
                    out += GalleryPhoto(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id)), c.getString(name) ?: "", ms)
                }
            }
        }
        return out
    }

    /** Qué hizo la IA con esta foto (según el nombre del archivo). */
    fun badge(name: String): String? = when {
        "_RETRATO" in name -> "RETRATO"
        "_NOCHE" in name -> "NOCHE"
        "_HDR" in name -> "HDR"
        "_MACRO" in name -> "MACRO+"
        "_MOMENTO" in name -> "MEJOR MOMENTO"
        "_SUPERRES" in name -> "SÚPER RES"
        "_LARGA" in name -> "EXPOSICIÓN LARGA"
        "_ASTRO" in name -> "ASTRO"
        else -> null
    }

    fun bitmap(context: Context, uri: Uri, side: Int): Bitmap? =
        Editor.load(context, uri, side)
}

/**
 * Visor de fotos dentro de la app: deslizar entre fotos, doble toque para ampliar, cuadrícula,
 * compartir, editar, borrar y, en los retratos, cambiar la intensidad del desenfoque.
 *
 * @param photosOverride / bitmapOverride solo para las pruebas (sin galería real).
 */
@Composable
fun ViewerScreen(
    onClose: () -> Unit,
    onChanged: (Uri) -> Unit = {},
    photosOverride: List<GalleryPhoto>? = null,
    bitmapOverride: ((Uri) -> Bitmap?)? = null,
    portraitOverride: ((Uri) -> Float?)? = null,
    scoreOverride: ((Uri) -> PhotoScore.Result?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val photos by produceState<List<GalleryPhoto>?>(photosOverride, reload) {
        value = photosOverride ?: withContext(Dispatchers.IO) { Gallery.load(context) }
    }
    // Versión de cada foto (cambia al re-generar un retrato, para recargar la imagen).
    val versions = remember { mutableStateMapOf<Uri, Int>() }
    var grid by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    // Calificación de cada foto (se calcula al verla) y foto en edición.
    val scores = remember { mutableStateMapOf<Uri, PhotoScore.Result>() }
    var showScore by remember { mutableStateOf<PhotoScore.Result?>(null) }
    var editing by remember { mutableStateOf<Uri?>(null) }
    BackHandler { if (showScore != null) showScore = null else if (grid) grid = false else onClose() }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) reload++
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = photos
        when {
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.Cyan)
            list.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Aún no hay fotos", style = Type.Title, color = Palette.Paper)
                Spacer(Modifier.height(6.dp))
                Text("Las fotos que tomes con IA Cámara aparecerán aquí.", style = Type.Body, color = Palette.Mist)
            }
            else -> {
                val pager = rememberPagerState { list.size }
                if (grid) {
                    LazyVerticalGrid(
                        GridCells.Fixed(3),
                        Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(list, key = { _, p -> p.uri }) { i, p ->
                            val bmp by produceState<Bitmap?>(null, p.uri, versions[p.uri]) {
                                value = bitmapOverride?.invoke(p.uri) ?: withContext(Dispatchers.IO) { Gallery.bitmap(context, p.uri, 360) }
                            }
                            Box(Modifier.aspectRatio(1f).background(Palette.Graphite).clickable {
                                scope.launch { pager.scrollToPage(i) }; grid = false
                            }) {
                                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                Gallery.badge(p.name)?.let { b ->
                                    Text(b, style = Type.Label, color = Palette.Ink, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                                        .clip(RoundedCornerShape(4.dp)).background(Palette.Amber).padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                            }
                        }
                    }
                } else {
                    HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].uri }) { page ->
                        ZoomablePhoto(list[page], versions[list[page].uri] ?: 0, bitmapOverride) { chrome = !chrome }
                    }
                }
                val current = list.getOrNull(pager.currentPage.coerceIn(0, list.size - 1))
                LaunchedEffect(current?.uri, current?.let { versions[it.uri] }) {
                    val u = current?.uri ?: return@LaunchedEffect
                    if (scores[u] != null && versions[u] == null) return@LaunchedEffect
                    val r = scoreOverride?.invoke(u) ?: withContext(Dispatchers.Default) {
                        runCatching {
                            val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@runCatching null
                            val b = Editor.decodeUpright(bytes, 1024) ?: return@runCatching null
                            val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
                            PhotoScore.score(PhotoScore.measure(px, b.width, b.height, Editor.faces(b)), com.iacamara.app.ai.TasteStore(context).sceneOf(u.lastPathSegment ?: ""))
                        }.getOrNull()
                    }
                    if (r != null) scores[u] = r
                }
                // Barra superior
                AnimatedVisibility(chrome || grid, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                    Row(
                        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ViewerButton(Icons.Rounded.Close, "Cerrar") { if (grid) grid = false else onClose() }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            if (grid) Text("${list.size} fotos", style = Type.BodyStrong, color = Palette.Paper)
                            else if (current != null) {
                                Text(SimpleDateFormat("d MMM yyyy · HH:mm", Locale("es")).format(Date(current.dateMs)), style = Type.BodyStrong, color = Palette.Paper)
                                Text("${pager.currentPage + 1} de ${list.size}", style = Type.ReadoutSmall, color = Palette.Mist)
                            }
                        }
                        if (!grid) current?.let { scores[it.uri] }?.let { sc ->
                            ScoreChip(sc) { showScore = sc }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (!grid) current?.let { Gallery.badge(it.name) }?.let { b ->
                            Text(b, style = Type.Label, color = Palette.Amber, modifier = Modifier.clip(RoundedCornerShape(50))
                                .border(1.dp, Palette.Amber.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        ViewerButton(Icons.Rounded.GridView, "Cuadrícula", selected = grid) { grid = !grid }
                    }
                }
                // Barra inferior
                if (!grid && current != null) AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).navigationBarsPadding().padding(16.dp)) {
                        val portraitStrength = remember(current.uri) {
                            portraitOverride?.invoke(current.uri) ?: PortraitStore(context).get(current.uri)?.strength
                        }
                        if (portraitStrength != null) PortraitPanel(current.uri, portraitStrength) {
                            versions[current.uri] = (versions[current.uri] ?: 0) + 1
                            onChanged(current.uri)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ViewerAction(Icons.Rounded.Share, "Compartir") {
                                val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, current.uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(Intent.createChooser(send, "Compartir foto")) }
                            }
                            ViewerAction(Icons.Rounded.AutoFixHigh, "Mejorar", tint = Palette.Amber) { editing = current.uri }
                            ViewerAction(Icons.Rounded.Edit, "Editar") {
                                val edit = Intent(Intent.ACTION_EDIT).setDataAndType(current.uri, "image/jpeg")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                runCatching { context.startActivity(Intent.createChooser(edit, "Editar con")) }
                            }
                            ViewerAction(Icons.Rounded.DeleteOutline, "Borrar", tint = Palette.Signal) {
                                val uri = current.uri
                                try {
                                    if (context.contentResolver.delete(uri, null, null) > 0) { PortraitStore(context).remove(uri); scores.remove(uri); reload++ }
                                } catch (e: SecurityException) {
                                    // Foto que ya no es "nuestra" (p. ej. tras reinstalar): Android pide confirmación.
                                    val sender = when {
                                        Build.VERSION.SDK_INT >= 30 -> MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
                                        e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                                        else -> null
                                    }
                                    sender?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Detalle de la calificación
        showScore?.let { sc -> ScoreSheet(sc) { showScore = null } }
        // Editor «Mejorar con IA»
        editing?.let { u ->
            EditorScreen(u, onClose = { saved ->
                editing = null
                if (saved) { versions[u] = (versions[u] ?: 0) + 1; onChanged(u) }
            })
        }
    }
}

/** Foto con doble toque para ampliar (y arrastrar mientras está ampliada). */
@Composable
private fun ZoomablePhoto(photo: GalleryPhoto, version: Int, bitmapOverride: ((Uri) -> Bitmap?)?, onTap: () -> Unit) {
    val context = LocalContext.current
    var scale by remember(photo.uri) { mutableFloatStateOf(1f) }
    // Al ampliar, se carga con más resolución para ver el detalle real.
    val hiRes = scale > 1.6f
    var bmp by remember(photo.uri, version) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photo.uri, version, hiRes) {
        if (hiRes && (bmp?.let { maxOf(it.width, it.height) } ?: 0) >= 4096) return@LaunchedEffect
        val b = bitmapOverride?.invoke(photo.uri) ?: withContext(Dispatchers.IO) { Gallery.bitmap(context, photo.uri, if (hiRes) 4096 else 2048) }
        if (b != null) bmp = b
    }
    var ox by remember(photo.uri) { mutableFloatStateOf(0f) }
    var oy by remember(photo.uri) { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(photo.uri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; ox = 0f; oy = 0f } else scale = 2.5f },
                )
            }
            .then(
                if (scale > 1f) Modifier.pointerInput("zoom") {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        val lx = size.width * (scale - 1) / 2; val ly = size.height * (scale - 1) / 2
                        ox = (ox + pan.x).coerceIn(-lx, lx); oy = (oy + pan.y).coerceIn(-ly, ly)
                        if (scale <= 1f) { ox = 0f; oy = 0f }
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b == null) CircularProgressIndicator(color = Palette.Fog, strokeWidth = 2.dp)
        else Image(
            b.asImageBitmap(), photo.name,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = ox; translationY = oy },
            contentScale = ContentScale.Fit,
        )
    }
}

/** Intensidad del desenfoque de un retrato (se puede quitar del todo). */
@Composable
private fun PortraitPanel(uri: Uri, initial: Float, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember(uri) { mutableFloatStateOf(initial) }
    var applied by remember(uri) { mutableFloatStateOf(initial) }
    var busy by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    fun apply(v: Float) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            val ok = withContext(Dispatchers.Default) { runCatching { PortraitStore.rerender(context, uri, v) }.getOrDefault(false) }
            busy = false
            if (ok) {
                applied = v; onDone()
                if (v >= 0.02f) com.iacamara.app.ai.TasteStore(context).update { recordPortrait(v) } // tu estilo
            } else failed = true
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Portrait, null, tint = Palette.Amber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("DESENFOQUE DEL FONDO", style = Type.Label, color = Palette.Mist, modifier = Modifier.weight(1f))
            Text(fNumber(value), style = Type.Readout, color = Palette.Amber)
        }
        Slider(
            value, { value = it }, Modifier.fillMaxWidth(), enabled = !busy,
            colors = SliderDefaults.colors(thumbColor = Palette.Amber, activeTrackColor = Palette.Amber, inactiveTrackColor = Palette.Slate),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                busy -> { CircularProgressIndicator(Modifier.size(14.dp), color = Palette.Amber, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Aplicando…", style = Type.Body, color = Palette.Mist) }
                failed -> Text("No se pudo aplicar", style = Type.Body, color = Palette.Signal)
                else -> Text(if (applied < 0.02f) "Sin efecto (foto original)" else "Intensidad actual ${fNumber(applied)}", style = Type.Body, color = Palette.Fog)
            }
            Spacer(Modifier.weight(1f))
            PanelButton("Quitar", enabled = !busy && applied >= 0.02f) { value = 0f; apply(0f) }
            Spacer(Modifier.width(8.dp))
            PanelButton("Aplicar", primary = true, enabled = !busy && kotlin.math.abs(value - applied) > 0.01f) { apply(value) }
        }
    }
}

/** La intensidad, expresada como la apertura de un lente equivalente (más bajo = más desenfoque). */
internal fun fNumber(strength: Float): String =
    if (strength < 0.02f) "—" else "f/%.1f".format(Locale.US, (1.2f / strength.coerceIn(0.05f, 1f)).coerceAtMost(16f))

@Composable
private fun PanelButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (primary) Palette.Amber else Color.Transparent
    val fg = if (primary) Palette.Ink else Palette.Paper
    Text(
        text, style = Type.LabelLarge, color = fg.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg.copy(alpha = if (enabled) bg.alpha else bg.alpha * 0.4f))
            .border(1.dp, if (primary) Color.Transparent else Palette.Hairline, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ViewerButton(icon: ImageVector, desc: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (selected) Palette.Paper.copy(alpha = 0.18f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Palette.Paper, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, tint: Color = Palette.Paper, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = Type.Label, color = Palette.Mist)
    }
}

/** Nota de la foto (0–100) con su color. */
@Composable
private fun ScoreChip(r: PhotoScore.Result, onClick: () -> Unit) {
    val c = scoreColor(r.score)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Star, null, tint = c, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("${r.score}", style = Type.Readout, color = c)
    }
}

private fun scoreColor(s: Int) = when {
    s >= 85 -> Palette.Level
    s >= 70 -> Palette.Cyan
    s >= 50 -> Palette.Amber
    else -> Palette.Signal
}

/** Por qué esa nota: cada aspecto con un consejo. */
@Composable
private fun ScoreSheet(r: PhotoScore.Result, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Palette.Graphite)
                .clickable(enabled = false) {}.navigationBarsPadding().padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.score}", style = Type.Display, color = scoreColor(r.score))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(r.label, style = Type.Title, color = Palette.Paper)
                    Text("Calificación de la IA", style = Type.Body, color = Palette.Mist)
                }
            }
            Spacer(Modifier.height(14.dp))
            for (f in r.factors) {
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        if (f.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
                        tint = if (f.ok) Palette.Level else Palette.Amber, modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name.uppercase(), style = Type.Label, color = Palette.Mist)
                        Text(f.detail, style = Type.Body, color = Palette.Paper)
                    }
                    if (f.penalty > 0) Text("−${f.penalty}", style = Type.ReadoutSmall, color = Palette.Fog)
                }
            }
        }
    }
}
