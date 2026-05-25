package com.thirdapp.evacuation.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdapp.evacuation.EvacuationViewModel
import com.thirdapp.evacuation.model.Coord
import com.thirdapp.evacuation.model.Shelter
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.roundToInt

private const val USER_MARKER_ID = "user-marker"
private const val ROUTE_LINE_ID = "route-line"
private const val SHELTER_MARKER_PREFIX = "shelter-"

@Composable
fun EvacuationScreen(vm: EvacuationViewModel = viewModel()) {
    val context = LocalContext.current
    val shelters by vm.shelters.collectAsState()
    val userLocation by vm.userLocation.collectAsState()
    val route by vm.route.collectAsState()
    val isLoadingRoute by vm.isLoadingRoute.collectAsState()
    val error by vm.error.collectAsState()

    val snackbarHost = remember { SnackbarHostState() }

    var hasPermission by remember { mutableStateOf(vm.hasLocationPermission()) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.any { it }
        if (hasPermission) vm.startLocationUpdates()
    }

    LaunchedEffect(Unit) {
        if (hasPermission) {
            vm.startLocationUpdates()
        } else {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        vm.consumeError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OsmMap(
                shelters = shelters,
                userCoord = userLocation?.coord,
                routePoints = route?.points,
                onLongPress = { vm.addShelter(it) },
                onShelterClick = { vm.buildRouteTo(it) }
            )

            StatusCard(
                userCoord = userLocation?.coord,
                accuracy = userLocation?.accuracyMeters,
                shelterCount = shelters.size,
                routeDistance = route?.distanceMeters,
                routeDuration = route?.durationSeconds,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .fillMaxWidth()
            )

            ControlBar(
                isLoading = isLoadingRoute,
                hasPermission = hasPermission,
                onAddHere = { vm.addShelterAtCurrentLocation() },
                onRouteNearest = { vm.buildRouteToNearest() },
                onClear = { vm.clearAll() },
                onRequestPermission = {
                    permLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OsmMap(
    shelters: List<Shelter>,
    userCoord: Coord?,
    routePoints: List<Coord>?,
    onLongPress: (Coord) -> Unit,
    onShelterClick: (Shelter) -> Unit
) {
    val context = LocalContext.current
    val mapView = remember { createMapView(context, onLongPress) }
    var didFirstCenter by remember { mutableStateOf(false) }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    LaunchedEffect(userCoord) {
        val c = userCoord ?: return@LaunchedEffect
        updateUserMarker(mapView, c)
        if (!didFirstCenter) {
            mapView.controller.setZoom(16.5)
            mapView.controller.animateTo(GeoPoint(c.lat, c.lon))
            didFirstCenter = true
        } else {
            mapView.invalidate()
        }
    }

    LaunchedEffect(shelters) {
        updateShelterMarkers(mapView, shelters, onShelterClick)
    }

    LaunchedEffect(routePoints) {
        updateRoutePolyline(mapView, routePoints)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView }
    )
}

private fun createMapView(context: Context, onLongPress: (Coord) -> Unit): MapView {
    val config = Configuration.getInstance()
    config.load(
        context,
        context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
    )
    config.userAgentValue = "Evacuation/1.0 (${context.packageName})"
    val base = File(context.cacheDir, "osmdroid").apply { mkdirs() }
    val tiles = File(base, "tiles").apply { mkdirs() }
    config.osmdroidBasePath = base
    config.osmdroidTileCache = tiles

    val map = MapView(context)
    map.setTileSource(TileSourceFactory.MAPNIK)
    map.setMultiTouchControls(true)
    map.controller.setZoom(5.0)
    map.controller.setCenter(GeoPoint(55.75, 37.62))

    val events = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
        override fun longPressHelper(p: GeoPoint?): Boolean {
            p ?: return false
            onLongPress(Coord(p.latitude, p.longitude))
            return true
        }
    })
    map.overlays.add(events)
    return map
}

private fun updateUserMarker(map: MapView, c: Coord) {
    val existing = map.overlays.firstOrNull { it is Marker && it.id == USER_MARKER_ID } as? Marker
    val marker = existing ?: Marker(map).apply {
        id = USER_MARKER_ID
        title = "Вы здесь"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = makeUserIcon(map.context)
        map.overlays.add(this)
    }
    marker.position = GeoPoint(c.lat, c.lon)
    map.invalidate()
}

private fun updateShelterMarkers(
    map: MapView,
    shelters: List<Shelter>,
    onClick: (Shelter) -> Unit
) {
    map.overlays.removeAll { it is Marker && it.id?.startsWith(SHELTER_MARKER_PREFIX) == true }
    val shelterIcon = makeShelterIcon(map.context)
    shelters.forEach { sh ->
        val m = Marker(map).apply {
            id = "$SHELTER_MARKER_PREFIX${sh.id}"
            title = sh.name
            position = GeoPoint(sh.coord.lat, sh.coord.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = shelterIcon
            setOnMarkerClickListener { _, _ ->
                onClick(sh)
                true
            }
        }
        map.overlays.add(m)
    }
    map.invalidate()
}

private fun updateRoutePolyline(map: MapView, points: List<Coord>?) {
    map.overlays.removeAll { it is Polyline && it.id == ROUTE_LINE_ID }
    if (points.isNullOrEmpty()) {
        map.invalidate()
        return
    }
    val line = Polyline().apply {
        id = ROUTE_LINE_ID
        setPoints(points.map { GeoPoint(it.lat, it.lon) })
        outlinePaint.color = AndroidColor.parseColor("#1976D2")
        outlinePaint.strokeWidth = 12f
    }
    map.overlays.add(0, line)
    map.invalidate()
}

@Composable
private fun StatusCard(
    userCoord: Coord?,
    accuracy: Float?,
    shelterCount: Int,
    routeDistance: Double?,
    routeDuration: Double?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (userCoord != null)
                    "GPS: %.5f, %.5f  ±%.0f м".format(userCoord.lat, userCoord.lon, accuracy ?: 0f)
                else "GPS: ожидание сигнала…",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Укрытий: $shelterCount",
                style = MaterialTheme.typography.bodySmall
            )
            if (routeDistance != null && routeDuration != null) {
                Text(
                    text = "Маршрут: ${formatDistance(routeDistance)}, ~${formatDuration(routeDuration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ControlBar(
    isLoading: Boolean,
    hasPermission: Boolean,
    onAddHere: () -> Unit,
    onRouteNearest: () -> Unit,
    onClear: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!hasPermission) {
                Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Разрешить доступ к локации")
                }
            }
            Text(
                text = "Долгий тап по карте — поставить укрытие. Тап по метке — построить маршрут.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddHere,
                    modifier = Modifier.weight(1f),
                    enabled = hasPermission
                ) { Text("Здесь") }
                Button(
                    onClick = onRouteNearest,
                    modifier = Modifier.weight(1f),
                    enabled = hasPermission && !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("К ближайшему")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f)
                ) { Text("Очистить") }
            }
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} м"
    else "%.2f км".format(meters / 1000.0)

private fun formatDuration(seconds: Double): String {
    val mins = (seconds / 60.0).roundToInt()
    return if (mins < 60) "$mins мин"
    else "${mins / 60} ч ${mins % 60} мин"
}

private fun makeUserIcon(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val outerR = 11f * density
    val innerR = 7f * density
    val size = (outerR * 2 + 4 * density).toInt()
    val cx = size / 2f
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = AndroidColor.parseColor("#552196F3")
    canvas.drawCircle(cx, cx, outerR, paint)
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(cx, cx, innerR + 1.5f * density, paint)
    paint.color = AndroidColor.parseColor("#1976D2")
    canvas.drawCircle(cx, cx, innerR, paint)
    return BitmapDrawable(context.resources, bmp)
}

private fun makeShelterIcon(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val w = (28 * density).toInt()
    val h = (38 * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = w / 2f
    val headR = 11f * density
    val tipY = h - 1.5f * density

    val path = Path().apply {
        moveTo(cx, tipY)
        lineTo(cx - headR * 0.85f, headR * 1.15f)
        lineTo(cx + headR * 0.85f, headR * 1.15f)
        close()
    }
    paint.color = AndroidColor.parseColor("#2E7D32")
    canvas.drawPath(path, paint)
    canvas.drawCircle(cx, headR + 1.5f * density, headR, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.5f * density
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(cx, headR + 1.5f * density, headR, paint)

    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.WHITE
    val crossT = 2f * density
    val crossL = headR * 0.55f
    canvas.drawRect(cx - crossT, headR + 1.5f * density - crossL, cx + crossT, headR + 1.5f * density + crossL, paint)
    canvas.drawRect(cx - crossL, headR + 1.5f * density - crossT, cx + crossL, headR + 1.5f * density + crossT, paint)

    return BitmapDrawable(context.resources, bmp)
}
