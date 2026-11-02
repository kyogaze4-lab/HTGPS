package mx.tavizon.replanteo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 10;
    private static final int REQ_OPEN_FILE = 11;
    private static final int REQ_SAVE_FILE = 12;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingSaveText;

    // GPS nativo: lecturas directas del receptor, sin pasar por el navegador
    private LocationManager lm;
    private boolean gpsWanted = false;
    private boolean gpsRunning = false;
    private long lastGpsFix = 0;

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            boolean isGps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
            if (isGps) lastGpsFix = System.currentTimeMillis();
            else if (System.currentTimeMillis() - lastGpsFix < 5000) return; // con GPS activo se ignora la red
            String js = String.format(Locale.US,
                    "window.__nativeFix&&__nativeFix(%.8f,%.8f,%.2f,%.1f,%.2f,%d)",
                    l.getLatitude(), l.getLongitude(),
                    l.hasAccuracy() ? l.getAccuracy() : 99.0,
                    l.hasBearing() ? l.getBearing() : -1.0,
                    l.hasSpeed() ? l.getSpeed() : -1.0,
                    l.getTime());
            if (web != null) web.evaluateJavascript(js, null);
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) {
            if (LocationManager.GPS_PROVIDER.equals(p)) jsError("La ubicación del teléfono está apagada. Actívala en Ajustes.");
        }
    };

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            int used = 0, total = status.getSatelliteCount();
            for (int i = 0; i < total; i++) if (status.usedInFix(i)) used++;
            if (web != null) web.evaluateJavascript("window.__nativeSats&&__nativeSats(" + used + "," + total + ")", null);
        }
    };

    private void jsError(String msg) {
        if (web != null) web.evaluateJavascript("window.__nativeErr&&__nativeErr(" + org.json.JSONObject.quote(msg) + ")", null);
    }

    private void startNativeGps() {
        gpsWanted = true;
        if (gpsRunning) return;
        if (!hasLocation()) { requestLocation(); return; }
        if (lm == null) lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                jsError("La ubicación del teléfono está apagada. Actívala en Ajustes.");
            // 0 ms y 0 m: cada lectura que entregue el receptor (normalmente 1 por segundo o más)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsListener, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0, gpsListener, Looper.getMainLooper());
            lm.registerGnssStatusCallback(gnssCallback);
            gpsRunning = true;
        } catch (SecurityException e) {
            jsError("Permite la ubicación para usar el GPS");
        }
    }

    private void stopNativeGps(boolean keepWanted) {
        if (!keepWanted) gpsWanted = false;
        if (!gpsRunning || lm == null) return;
        lm.removeUpdates(gpsListener);
        lm.unregisterGnssStatusCallback(gnssCallback);
        gpsRunning = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF1D2A33);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocation()) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestLocation();
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Elegir archivo de vértices"), REQ_OPEN_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
        if (!hasLocation()) requestLocation();
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION && gpsWanted) {
            if (hasLocation()) startNativeGps();
            else { gpsWanted = false; jsError("Permite la ubicación para usar el GPS"); }
        }
        if (requestCode == REQ_LOCATION && pendingGeoCallback != null) {
            pendingGeoCallback.invoke(pendingGeoOrigin, hasLocation(), false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        } else if (requestCode == REQ_SAVE_FILE) {
            String text = pendingSaveText;
            pendingSaveText = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || text == null) return;
            try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Archivo guardado", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo guardar el archivo", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Puente entre la página y Android: archivos exportados y GPS nativo. */
    private class Bridge {
        @JavascriptInterface
        public void startGps() {
            runOnUiThread(new Runnable() { @Override public void run() { startNativeGps(); } });
        }

        @JavascriptInterface
        public void stopGps() {
            runOnUiThread(new Runnable() { @Override public void run() { stopNativeGps(false); } });
        }

        @JavascriptInterface
        public void saveFile(final String name, final String text, final String mime) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingSaveText = text;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(mime == null || mime.isEmpty() ? "text/plain" : mime);
                    i.putExtra(Intent.EXTRA_TITLE, name);
                    try {
                        startActivityForResult(i, REQ_SAVE_FILE);
                    } catch (Exception e) {
                        pendingSaveText = null;
                        Toast.makeText(MainActivity.this, "No hay app para guardar archivos", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopNativeGps(true); // ahorra batería en segundo plano; se reanuda al volver
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gpsWanted) startNativeGps();
    }

    @Override
    protected void onDestroy() {
        stopNativeGps(false);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
