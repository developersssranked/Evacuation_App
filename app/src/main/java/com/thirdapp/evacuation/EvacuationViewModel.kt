package com.thirdapp.evacuation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thirdapp.evacuation.location.LocationProvider
import com.thirdapp.evacuation.model.Coord
import com.thirdapp.evacuation.model.Shelter
import com.thirdapp.evacuation.model.UserLocation
import com.thirdapp.evacuation.model.haversineMeters
import com.thirdapp.evacuation.network.OsrmClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class RouteState(
    val points: List<Coord>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val targetShelterId: String
)

class EvacuationViewModel(app: Application) : AndroidViewModel(app) {

    private val locationProvider = LocationProvider(app)
    private val osrm = OsrmClient()

    private val _shelters = MutableStateFlow<List<Shelter>>(emptyList())
    val shelters: StateFlow<List<Shelter>> = _shelters.asStateFlow()

    private val _userLocation = MutableStateFlow<UserLocation?>(null)
    val userLocation: StateFlow<UserLocation?> = _userLocation.asStateFlow()

    private val _route = MutableStateFlow<RouteState?>(null)
    val route: StateFlow<RouteState?> = _route.asStateFlow()

    private val _isLoadingRoute = MutableStateFlow(false)
    val isLoadingRoute: StateFlow<Boolean> = _isLoadingRoute.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var locationJob: Job? = null

    fun hasLocationPermission(): Boolean = locationProvider.hasPermission()

    fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        if (!locationProvider.hasPermission()) return
        locationJob = viewModelScope.launch {
            locationProvider.updates().collectLatest { _userLocation.value = it }
        }
    }

    fun addShelter(coord: Coord) {
        val n = _shelters.value.size + 1
        _shelters.value = _shelters.value + Shelter(name = "Укрытие #$n", coord = coord)
    }

    fun addShelterAtCurrentLocation() {
        val loc = _userLocation.value ?: run {
            _error.value = "Нет данных о местоположении"
            return
        }
        addShelter(loc.coord)
    }

    fun clearAll() {
        _shelters.value = emptyList()
        _route.value = null
    }

    fun clearRoute() {
        _route.value = null
    }

    fun nearestShelter(): Shelter? {
        val from = _userLocation.value?.coord ?: return null
        return _shelters.value.minByOrNull { haversineMeters(from, it.coord) }
    }

    fun buildRouteTo(shelter: Shelter) {
        val from = _userLocation.value?.coord ?: run {
            _error.value = "Нет данных о местоположении"
            return
        }
        viewModelScope.launch {
            _isLoadingRoute.value = true
            osrm.route(from, shelter.coord)
                .onSuccess {
                    _route.value = RouteState(
                        points = it.points,
                        distanceMeters = it.distanceMeters,
                        durationSeconds = it.durationSeconds,
                        targetShelterId = shelter.id
                    )
                }
                .onFailure { _error.value = "Маршрут не построен: ${it.message}" }
            _isLoadingRoute.value = false
        }
    }

    fun buildRouteToNearest() {
        val target = nearestShelter() ?: run {
            _error.value = "Нет добавленных укрытий"
            return
        }
        buildRouteTo(target)
    }

    fun consumeError() { _error.value = null }
}
