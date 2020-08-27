package xjcl.mundraub

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.*
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.squareup.picasso.Picasso
import kotlinx.serialization.json.Json
import java.net.URL
import java.util.*
import kotlin.collections.HashSet
import kotlin.concurrent.thread


class MapsActivity : AppCompatActivity(), OnMapReadyCallback, OnCameraIdleListener, ActivityCompat.OnRequestPermissionsResultCallback {

    // --- Place a single marker on the GoogleMap, and prepare its info window, using parsed JSON class ---
    private fun addMarkerFromFeature(feature: Feature) {
        val latlng = LatLng(feature.pos[0], feature.pos[1])
        val tid = feature.properties?.tid
        val type = when {
            feature.properties == null -> "cluster"       // Cluster of 2+ markers
            treeIdToSeason[tid]?.first == 0.0 -> "other"  // Marker of unknown species with no season info
            else -> "normal"                              // Marker of known species with season info
        }

        val title = getString(resources.getIdentifier("tid$tid", "string", packageName))
        val fruitColor = getFruitColor(resources, tid)

        val icon =
            if (type == "cluster") // isCluster
                BitmapDescriptorFactory.fromBitmap( bitmapWithText(R.drawable._cluster_c, this, feature.count.toString(), 45F) )
            else // isTree
                BitmapDescriptorFactory.fromResource(treeIdToMarkerIcon[tid] ?: R.drawable.otherfruit)

        // *** The following code represents January-start as 1, mid-January as 1.5, February-start as 2, and so on
        val monthCodes = (1..12).joinToString("") { when {
             isSeasonal(tid, it + .25) &&  isSeasonal(tid, it + .75) -> "x"
             isSeasonal(tid, it + .25) && !isSeasonal(tid, it + .75) -> "l"
            !isSeasonal(tid, it + .25) &&  isSeasonal(tid, it + .75) -> "r"
            else -> "_"
        }}

        markersData[latlng] = MarkerData(type, title, monthCodes, getCurMonth(), isSeasonal(tid, getCurMonth()), fruitColor,
            feature.properties?.nid, null, null, null, null)

        runOnUiThread {
            markers[latlng] = mMap.addMarker(MarkerOptions().position(latlng).title(title).icon(icon).anchor(.5F, if (type == "cluster") .5F else 1F))
        }
    }

    // --- Place a list of markers on the GoogleMap ("var markers"), using raw JSON String ---
    private fun addLocationMarkers(jsonStrPre: String) {
        Log.e("addLocationMarkers", markers.size.toString() + " " + jsonStrPre)
        val jsonStr = if (jsonStrPre == "null") "{\"features\":[]}" else jsonStrPre

        // --- parse newly downloaded markers ---
        // API inconsistently either returns String or double/int... -> strip away double quotes
        val jsonStrClean = Regex(""""-?[0-9]+.?[0-9]+"""").replace(jsonStr) { it.value.substring(1, it.value.length - 1) }
        val root = Json.parse(Root.serializer(), jsonStrClean)

        // --- remove old markers not in newly downloaded set (also removes OOB markers) ---
        val featuresSet = HashSet<LatLng>( root.features.map { LatLng(it.pos[0], it.pos[1]) } )
        for (mark in markers.toMap()) {  // copy constructor
            if (!featuresSet.contains(mark.key)) { runOnUiThread { mark.value.remove(); markers.remove(mark.key); markersData.remove(mark.key) } }
        }

        // --- add newly downloaded markers not already in old set ---
        for (feature in root.features) {
            val latlng = LatLng(feature.pos[0], feature.pos[1])
            if (markers.contains(latlng)) continue
            addMarkerFromFeature(feature)
        }
    }

    // --- Update markers when user finished moving the map ---
    private fun updateMarkers(callback : () -> Unit = {}) {
        val zoom = mMap.cameraPosition.zoom
        Log.e("updateMarkers", "zoom $zoom")
        if (zoom == 2F || zoom == 3F) return  // Bugfix, do not remove, see commit message
        val bboxLo = mMap.projection.visibleRegion.latLngBounds.southwest
        val bboxHi = mMap.projection.visibleRegion.latLngBounds.northeast

        // API documented here: https://github.com/niccokunzmann/mundraub-android/blob/master/docs/api.md
        val url = "https://mundraub.org/cluster/plant?bbox=${bboxLo.longitude},${bboxLo.latitude},${bboxHi.longitude},${bboxHi.latitude}" +
             "&zoom=${(zoom + .25F).toInt()}&cat=${selectedSpeciesStr}"

        Log.e("updateMarkers", "GET $url")

        thread {
            val jsonStr = try { URL(url).readText() } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this,  getString(R.string.errMsgNoInternet), Toast.LENGTH_SHORT).show() }
                return@thread
            }
            addLocationMarkers(jsonStr)
            callback()
        }
    }

    override fun onCameraIdle() = updateMarkers()

    fun markerOnClickListener(marker : Marker): Boolean {
        if (markersData[marker.position]?.type ?: "" == "cluster") return false
        marker.showInfoWindow()
        // --- Click on FAB will give directions to Marker in Google Maps app ---
        fab.setOnClickListener {
            fusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
                if (location == null) return@addOnSuccessListener
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "http://maps.google.com/maps?saddr=${location.latitude}, ${location.longitude}&daddr=${marker.position.latitude}, ${marker.position.longitude}"
                )))
            }
        }
        fab.animate().x(fabAnimationFromTo.second)
        val targetPosition = vecAdd(marker.position, vecMul(.25, vecSub(mMap.projection.visibleRegion.farLeft, mMap.projection.visibleRegion.nearLeft)))
        mMap.animateCamera(CameraUpdateFactory.newLatLng(targetPosition), 300, null)
        return true
    }

    // --- OnInternetConnected: Automatically update (download) markers ---
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setUpNetworking() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = runOnUiThread { this@MapsActivity.updateMarkers() }
        })
    }

    // --- On startup: Prepare map and cause onRequestPermissionsResult to be called ---
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.setOnCameraIdleListener(this)
        mMap.setPadding(totalLeftPadding, 0, 0, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) setUpNetworking()

        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        ActivityCompat.requestPermissions(this, permissions, 0)

        // --- Build a vertical layout to provide an info window for a marker ---
        // https://stackoverflow.com/a/31629308/2111778
        mMap.setInfoWindowAdapter(object : InfoWindowAdapter {
            override fun getInfoWindow(arg0: Marker): View? = null

            override fun getInfoContents(marker: Marker): View {
                val md = markersData[marker.position] ?: return TextView(this@MapsActivity)

                // 12 month circles of 13 pixels width -- ugly but WRAP_CONTENT just would not work =(
                val density = resources.displayMetrics.density
                var masterWidth = (12 * 13 * density).toInt()

                val info = LinearLayout(this@MapsActivity)
                info.orientation = LinearLayout.VERTICAL

                val photo = ImageView(this@MapsActivity)
                photo.setImageBitmap(scaleToWidth(md.image, masterWidth))
                if (md.image != null) info.addView(photo)

                val description = TextView(this@MapsActivity)
                description.width = masterWidth
                description.textSize = 12F
                if (md.type != "cluster") info.addView(description)
                if (md.description != null) {
                    description.text = md.description

                    val uploader = TextView(this@MapsActivity)
                    uploader.textSize = 12F
                    uploader.text = md.uploader
                    uploader.gravity = Gravity.RIGHT
                    uploader.maxWidth = masterWidth
                    info.addView(uploader)

                    val uploadDate = TextView(this@MapsActivity)
                    uploadDate.textSize = 12F
                    uploadDate.text = md.uploadDate
                    uploadDate.gravity = Gravity.RIGHT
                    uploadDate.maxWidth = masterWidth
                    info.addView(uploadDate)
                } else {
                    description.text = this@MapsActivity.getString(R.string.tapForInfo)
                }

                val title = TextView(this@MapsActivity)
                title.setTextColor(md.fruitColor)
                title.gravity = Gravity.CENTER
                title.setTypeface(null, Typeface.BOLD)
                title.text = marker.title
                info.addView(title)

                // no month/season information in this case so return early
                if (md.type == "cluster" || md.type == "other")
                    return info

                val seasonText = TextView(this@MapsActivity)
                seasonText.setTextColor(Color.BLACK)
                seasonText.text = this@MapsActivity.getString(if (md.isSeasonal) R.string.inSeason else R.string.notInSeason)
                info.addView(seasonText)

                val months = LinearLayout(this@MapsActivity)
                months.orientation = LinearLayout.HORIZONTAL
                for (i in 1..12) {
                    val circle = LinearLayout(this@MapsActivity)
                    circle.orientation = LinearLayout.HORIZONTAL

                    val circleLeft = ImageView(this@MapsActivity)
                    val circleRight = ImageView(this@MapsActivity)

                    val resLeft = if ("xl".contains(md.monthCodes[i-1])) R.drawable._dot_l1 else R.drawable._dot_l0
                    val resRight = if ("xr".contains(md.monthCodes[i-1])) R.drawable._dot_r1 else R.drawable._dot_r0
                    circleLeft.setImageResource(resLeft)
                    circleRight.setImageResource(resRight)
                    if ("xl".contains(md.monthCodes[i-1])) circleLeft.setColorFilter(md.fruitColor)
                    if ("xr".contains(md.monthCodes[i-1])) circleRight.setColorFilter(md.fruitColor)
                    // add vertical line for current time in year
                    if (md.curMonth.toInt() == i)
                        (if (md.curMonth % 1 < .5) circleLeft else circleRight).setImageBitmap(
                            bitmapWithText( (if (md.curMonth % 1 < .5) resLeft else resRight), this@MapsActivity,
                                "|", 50F, false,  2 * (md.curMonth % .5).toFloat(), if (md.isSeasonal) md.fruitColor else Color.GRAY) )

                    circle.addView(circleLeft)
                    circle.addView(circleRight)

                    val letter = TextView(this@MapsActivity)
                    letter.setTextColor(if (md.monthCodes[i-1] != '_') md.fruitColor else Color.GRAY)
                    letter.gravity = Gravity.CENTER
                    if (md.monthCodes[i-1] != '_') letter.setTypeface(null, Typeface.BOLD)
                    letter.text = "JFMAMJJASOND"[i-1].toString()

                    val month = LinearLayout(this@MapsActivity)
                    month.orientation = LinearLayout.VERTICAL
                    month.addView(circle)
                    month.addView(letter)
                    months.addView(month)
                }
                months.measure(0, 0)
                Log.e("width change", masterWidth.toString() + " -> " + months.measuredWidth)
                masterWidth = months.measuredWidth
                description.width = masterWidth
                photo.setImageBitmap(scaleToWidth(md.image, masterWidth))

                val day = RelativeLayout(this@MapsActivity)
                val tv = TextView(this@MapsActivity)
                tv.text = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString()
                tv.setTextColor(if (md.isSeasonal) md.fruitColor else Color.GRAY)
                tv.setTypeface(null, Typeface.BOLD)
                tv.measure(0, 0)
                val params = RelativeLayout.LayoutParams(tv.measuredWidth, tv.measuredHeight)
                params.leftMargin = ( masterWidth / 12F * (md.curMonth - 1) - tv.measuredWidth / 2F ).toInt().coerceIn(0, masterWidth - tv.measuredWidth)
                day.addView(tv, params)

                info.addView(day)
                info.addView(months)
                return info
            }
        })

        // --- Disappear the navigation button once window closes ---
        mMap.setOnInfoWindowCloseListener {
            fab.animate().x(fabAnimationFromTo.first)
        }

        // --- Download detailed node description when user taps ("clicks") on info window ---
        mMap.setOnInfoWindowClickListener { marker ->
            val md = markersData[marker.position] ?: return@setOnInfoWindowClickListener
            if (md.description != null || md.nid == null) return@setOnInfoWindowClickListener

            thread {
                // --- Download number of finds and description ---
                val htmlStr = try { URL("https://mundraub.org/node/${md.nid}").readText() } catch (ex : Exception) {
                    runOnUiThread { Toast.makeText(this,  getString(R.string.errMsgNoInternet), Toast.LENGTH_SHORT).show() }
                    return@thread
                }

                fun extractUnescaped(htmlStr : String, after : String, before : String) : String {
                    val extractEscaped = htmlStr.substringAfter(after).substringBefore(before, "(no data)")
                    return HtmlCompat.fromHtml(extractEscaped, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()  // unescape "&quot;" etc
                }

                val number = extractUnescaped(htmlStr, "Anzahl: <span class=\"tag\">", "</span>")
                val description = extractUnescaped(htmlStr, "<p>", "</p>")
                md.uploader = extractUnescaped(htmlStr.substringAfter("typeof=\"schema:Person\""), ">", "</span>")
                md.uploadDate = extractUnescaped(htmlStr.substringAfter("am <span>"), ", ", " - ")
                md.description = "[$number] $description"

                runOnUiThread { marker.showInfoWindow() }

                // --- Download image in lowest quality ---
                val imageURL = htmlStr.substringAfter("srcset=\"", "").substringBefore(" ")
                if (imageURL.isBlank() || md.image != null) return@thread

                runOnUiThread {
                    Log.e("onMarkerClickListener", "Started Picasso on UI thread now ($imageURL)")
                    picassoBitmapTarget.md = md
                    picassoBitmapTarget.marker = marker
                    Picasso.with(this@MapsActivity).load("https://mundraub.org/$imageURL").into(picassoBitmapTarget)
                }
            }
        }

        // --- Custom zoom to marker at a *below-center* position to leave more space for its large info window ---
        mMap.setOnMarkerClickListener { marker -> markerOnClickListener(marker) }
    }

    // --- When user rotates phone, re-download markers for the new screen size ---
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // dummy zoom to trigger onCameraIdle with *correct* orientation  https://stackoverflow.com/a/61993030/2111778
        mMap.animateCamera( CameraUpdateFactory.zoomBy(0F) )
    }

    // --- On startup: If GPS enabled, then zoom into user, else zoom into Germany ---
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray) {
        fusedLocationClient.lastLocation.addOnFailureListener(this) {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.17, 10.45), 6F))  // Germany
        }
        fusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
            if (location == null) return@addOnSuccessListener
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 13F))
        }

        if (grantResults.isEmpty() || grantResults[0] == PackageManager.PERMISSION_DENIED) return

        mMap.isMyLocationEnabled = true  // show blue circle on map
    }

    // if we add a marker, resume at its location
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!(requestCode == 33 && resultCode == Activity.RESULT_OK && data != null)) return

        val lat = data.getDoubleExtra("lat", 0.0)
        val lng = data.getDoubleExtra("lng", 0.0)
        val nid = data.getStringExtra("nid") ?: ""

        // TODO undo filtering  -> FilterBar class
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 18F), 1, object : CancelableCallback {
            override fun onFinish() = updateMarkers { runOnUiThread {
                markers.values.filter { markersData[it.position]?.nid.toString() == nid }
                .forEach { markerOnClickListener(it) }
            }}
            override fun onCancel() {}
        })
    }

    // Handle ActionBar option selection
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.title) {
            "Add" -> {
                val intent = Intent(this, AddPlantActivity::class.java)
                startActivityForResult(intent, 33)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Create the ActionBar options menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val icon = ContextCompat.getDrawable(this, R.drawable.material_add_location) ?: return true
        icon.setColorFilter(resources.getColor(R.color.colorPrimary), PorterDuff.Mode.SRC_IN)

        menu.add(0, 0, 0, "Add").setIcon(icon).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    // --- On startup: Prepare classes ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)
        supportActionBar?.apply {
            val navStr = if (resources.displayMetrics.widthPixels  / resources.displayMetrics.density > 500) "Navigator" else "Nav."
            title = HtmlCompat.fromHtml("<font color=\"#94b422\">${navStr} v${BuildConfig.VERSION_NAME}!</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            setHomeAsUpIndicator(R.drawable.mundraub_logo_bar_48dp)  // export with 15px border
            displayOptions = ActionBar.DISPLAY_SHOW_HOME or ActionBar.DISPLAY_SHOW_TITLE or ActionBar.DISPLAY_HOME_AS_UP or ActionBar.DISPLAY_USE_LOGO
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as JanMapFragment
        mapFragment.getMapAsync(this)
        // retains markers if user rotates phone etc. (useful offline)  https://stackoverflow.com/a/22058966/2111778
        mapFragment.retainInstance = true
    }

    override fun onBackPressed() {
        moveTaskToBack(true)  // do not call onCreate after user accidentally hits Back (useful offline)
    }
}

// TODO: * show season information in info window (only a single row)

// TODO testing
//    - Firebase Labs Robo Script
//    - Firebase Labs credentials on site

// TODO publishing
//    - write blog post about it
//    - publish to reddit about it
//    - official email newsletter

// TODO QA
//    - Full automated UI scripts using Espresso
//    - Node test with image: https://mundraub.org/node/75327
//    - Ensure correct season information
//    - Test different pixel densities by setting screen rezo on my phone
//    - Test both supported languages (EN/DE)
//    - Test rotation
//    - Test offline use


// TODO clusters
//    - use Material design cluster icon with shadow
//        - should fix font centering issue too
//    - request max zoom level earlier (clusters are a useless anti-affordance)

// TODO marker filter
//    - apply darkened markers to map too
//    - animation / FloatingActionButton (slides out when tapped, also can be used to reset filtering)
//    - fix phone rotation  -> put 'val linear' into own singleton class that has an updateHeight function
//         -> either remove drawer, shrink it (???) or put it on the x axis (bottom) (?)
//         -> minor priority as the app is not really usable in landscape mode

// TODO latlng boundaries
//    - extend boundaries to go slightly offscreen so less re-loading needed?

// TODO bugs
//    - when tapping a marker, markers reload, so it sometimes deletes the marker in focus
//    - better workaround for initial Maps download issue

// * TODO pokemon
//    * favorite markers
//        * in a cardview list  -> wait no on the main map!!
//            * um we need a cardview of added markers first
//        * store offline
//    - detect when someone "visits" a marker
//    - list of recently visited or starred markers
//    - list of which marker types have ever been visited (including link to most recent one)
//    - list of how common each marker type is

// TODO UI
//    - all markers jump when pressing filter?
//    * immediately download info when tapping marker (1 instead of 2 taps)
//    - "force reload" button

// TODO marker availability
//    - startup: load markers from last time
//    - keep markers near user location always
//    - favorite markers that get permanently stored


// TODO user profiles
//    - allow login
//    - allow adding a node
//    - allow editing a node
//    - allow reporting a problem with a node


// TODO wontfix
//    - rarely used marker types: groups, actions, cider, saplings
//    - draw in sorted order and/or with z-score so front markers are in front -> rarely needed

// TODO show seasonality in infobar

// TODO make img/ and docs/ dir
// TODO v11

/*
Kleine Sachen über die ich noch nachgedacht habe:
- Eventuell direkt auf der Karte die Marker dunkler machen die nicht saisonal sind
- Direkter Download von Marker-Infos beim ersten Berühren. Da ist die Frage wie sehr es das Backend belastet

Größere Features:
- Marker als "Favoriten" makieren. Die werden dann am besten permanent auf dem Gerät gespeichert. Ich dachte zuerst an eine Liste aber ein Filter mit grauem Icon wie die anderen beiden wäre sogar intuitiver denke ich.
- Marker-History. Die am besten automatisch bemerkt an welchen Markern man vorbei gelaufen ist. Daraus kann man dann Gamification machen und schauen ob man alle Sorten besuchen kann. Da braucht man auch ein Feature um fälschlich besuchte Marker wieder zu entfernen.

Error message
*/
