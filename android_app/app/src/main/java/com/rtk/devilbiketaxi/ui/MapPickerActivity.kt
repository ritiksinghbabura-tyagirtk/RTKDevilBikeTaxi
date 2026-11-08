package com.rtk.devilbiketaxi.ui

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import android.graphics.Color

class MapPickerActivity : Activity() {

    private var selectedLat = 25.1460
    private var selectedLng = 82.5700
    private lateinit var coordinates: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedLat = intent.getDoubleExtra("lat", 25.1460)
        selectedLng = intent.getDoubleExtra("lng", 82.5700)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        coordinates = TextView(this).apply {
            text = "Map par pickup/drop point select karein"
            textSize = 15f
            setPadding(16, 14, 16, 14)
        }

        val map = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(MapBridge(), "AndroidBridge")
        }

        val confirm = Button(this).apply {
            text = "Confirm Selected Location"
            setOnClickListener {
                setResult(
                    Activity.RESULT_OK,
                    intent.apply {
                        putExtra("lat", selectedLat)
                        putExtra("lng", selectedLng)
                    }
                )
                finish()
            }
        }

        root.addView(coordinates, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(map, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(confirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        val html = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
html,body,#map {height:100%;margin:0;padding:0}
.leaflet-container {font-family:sans-serif}
</style>
</head>
<body>
<div id="map"></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
var map = L.map('map').setView([$selectedLat,$selectedLng], 14);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
 maxZoom:19,
 attribution:'&copy; OpenStreetMap contributors'
}).addTo(map);
var marker = L.marker([$selectedLat,$selectedLng]).addTo(map);
function choose(lat,lng) {
 marker.setLatLng([lat,lng]);
 AndroidBridge.selected(lat,lng);
}
map.on('click', function(e) {
 choose(e.latlng.lat,e.latlng.lng);
});
</script>
</body>
</html>
        """.trimIndent()

        map.loadDataWithBaseURL(
            "https://www.openstreetmap.org/",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    inner class MapBridge {
        @JavascriptInterface
        fun selected(lat: Double, lng: Double) {
            runOnUiThread {
                if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                    selectedLat = lat
                    selectedLng = lng
                    coordinates.text = "Selected: %.6f, %.6f".format(lat, lng)
                }
            }
        }
    }
}
