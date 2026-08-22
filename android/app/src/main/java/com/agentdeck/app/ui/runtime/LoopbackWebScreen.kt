package com.agentdeck.app.ui.runtime

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.agentdeck.app.domain.runtime.LoopbackWebPolicy

/**
 * Full-bleed local Web UI tuned for phone memory/jank.
 *
 * Extreme rules:
 * - destroy WebView on leave (otherwise Chromium keeps ~100MB+)
 * - pause timers when Activity stops
 * - minimal JS inject (no full-DOM walks)
 * - no software layer / no textZoom thrash
 */
@Composable
fun LoopbackWebScreen(
    title: String,
    url: String,
    onBack: () -> Unit,
    onCloseSession: (() -> Unit)? = null,
    onNewSession: ((reload: () -> Unit) -> Unit)? = null,
) {
    val allowed = remember(url) { LoopbackWebPolicy.isAllowedUrl(url) }
    var loading by remember { mutableStateOf(true) }
    var blocked by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Never let the SPA eat the system back — always leave + stop dsh.
    BackHandler(onBack = onBack)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val wv = webViewRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    wv.onResume()
                    wv.resumeTimers()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    wv.onPause()
                    wv.pauseTimers()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroyWebView(webViewRef)
            webViewRef = null
            onCloseSession?.invoke()
        }
    }

    fun reloadClean() {
        webViewRef?.let { wv ->
            val target = url.takeIf { LoopbackWebPolicy.isAllowedUrl(it) } ?: wv.url
            if (!target.isNullOrBlank() && LoopbackWebPolicy.isAllowedUrl(target)) {
                loading = true
                // Bypass HTTP cache for a true new shell after session wipe.
                wv.clearHistory()
                wv.loadUrl(target)
            } else {
                wv.reload()
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (!allowed) {
                Text(
                    "地址不被允许。AgentDeck 只打开本机 127.0.0.1 / localhost 上的 Web UI。",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                return@Box
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(Color.WHITE)
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = android.view.View.OVER_SCROLL_NEVER
                        isNestedScrollingEnabled = true
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        configureForLocalSpa(settings)
                        @SuppressLint("SetJavaScriptEnabled")
                        webViewClient = object : WebViewClient() {
                            private var lastInjectAt = 0L

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val target = request?.url?.toString().orEmpty()
                                if (!LoopbackWebPolicy.isAllowedUrl(target)) {
                                    blocked = "已拦截离开本机的跳转"
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                val now = SystemClock.uptimeMillis()
                                // SPA may fire finished multiple times; inject at most once / 2s.
                                if (now - lastInjectAt < 2_000L) return
                                lastInjectAt = now
                                view?.post { view.evaluateJavascript(MOBILE_ADAPT_JS, null) }
                            }
                        }
                        webViewRef = this
                        loadUrl(url)
                    }
                },
                update = { webView ->
                    webViewRef = webView
                    val current = webView.url
                    if (current.isNullOrBlank()) {
                        if (LoopbackWebPolicy.isAllowedUrl(url)) webView.loadUrl(url)
                    } else if (current != url && LoopbackWebPolicy.isAllowedUrl(url)) {
                        webView.loadUrl(url)
                    }
                },
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 8.dp, top = 6.dp, end = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shadowElevation = 2.dp,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (onNewSession != null) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
                        shadowElevation = 2.dp,
                    ) {
                        TextButton(
                            onClick = { onNewSession { reloadClean() } },
                            modifier = Modifier.padding(horizontal = 2.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(18.dp),
                            )
                            Text("新会话")
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shadowElevation = 2.dp,
                ) {
                    IconButton(onClick = { reloadClean() }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            blocked?.let { message ->
                Text(
                    message,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 56.dp, vertical = 56.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars),
                )
            }
            @Suppress("UNUSED_VARIABLE")
            val unusedTitle = title
        }
    }
}

private fun destroyWebView(webView: WebView?) {
    if (webView == null) return
    runCatching {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.pauseTimers()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.clearHistory()
        webView.clearCache(true)
        webView.removeAllViews()
        webView.destroy()
    }
}

@SuppressLint("RequiresFeature", "SetJavaScriptEnabled")
private fun configureForLocalSpa(settings: WebSettings) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    // Zoom is expensive on high-DPI; keep off unless user needs it.
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.setSupportZoom(false)
    settings.mediaPlaybackRequiresUserGesture = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    // Local SPA: keep decoded resources warm across soft reloads.
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.textZoom = 100
    settings.blockNetworkImage = false
    settings.loadsImagesAutomatically = true
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setGeolocationEnabled(false)
    settings.mediaPlaybackRequiresUserGesture = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = false
    }
    // Prefer fewer offscreen tiles → lower Graphics PSS on 1260×2800.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
        WebSettingsCompat.setOffscreenPreRaster(settings, false)
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
    } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
        @Suppress("DEPRECATION")
        WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        runCatching { settings.forceDark = WebSettings.FORCE_DARK_OFF }
    }
    val defaultUa = settings.userAgentString.orEmpty()
    if (!defaultUa.contains("Mobile", ignoreCase = true)) {
        settings.userAgentString = "$defaultUa Mobile"
    }
}

/**
 * Minimal phone polish. Geometry probe is bounded; no TreeWalker; few retries.
 */
private val MOBILE_ADAPT_JS: String = """
(function(){
  try {
    if (window.__agentdeckMobileAdapt) return;
    window.__agentdeckMobileAdapt = true;
    var head = document.head || document.getElementsByTagName('head')[0];
    if (!head) return;
    var meta = document.querySelector('meta[name="viewport"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.name = 'viewport';
      head.appendChild(meta);
    }
    meta.setAttribute('content','width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover');
    if (!document.getElementById('agentdeck-mobile-adapt')) {
      var style = document.createElement('style');
      style.id = 'agentdeck-mobile-adapt';
      style.textContent = [
        'html,body{background:#fff!important;min-height:100%!important;overflow-x:hidden!important;',
        '  -webkit-text-size-adjust:100%;touch-action:manipulation;}',
        'body{padding-top:56px!important;padding-bottom:env(safe-area-inset-bottom,0);box-sizing:border-box;}',
        '#root,#root>*{max-width:100vw;min-width:0;}',
        '#agentdeck-rail-toggle{position:fixed;z-index:2147483000;right:12px;bottom:96px;',
        '  min-width:48px;min-height:48px;padding:0 12px;border:0;border-radius:999px;',
        '  background:rgba(32,32,36,.9);color:#fff;font:600 13px/1 system-ui,sans-serif;}'
      ].join('');
      head.appendChild(style);
    }
    var rail=null;
    function narrow(){return window.innerWidth<900}
    function findRail(){
      if(rail&&document.contains(rail))return rail;
      var root=document.getElementById('root'); if(!root)return null;
      var q=[root], n=0;
      while(q.length&&n<28){
        var el=q.shift(); n++;
        if(!el||!el.getBoundingClientRect)continue;
        var r=el.getBoundingClientRect();
        if(r.left<=12&&r.width>=160&&r.width<window.innerWidth*0.72&&r.height>=window.innerHeight*0.45&&r.top<140){
          rail=el; return el;
        }
        var kids=el.children||[];
        for(var i=0;i<kids.length&&q.length<18;i++) q.push(kids[i]);
      }
      return null;
    }
    function toggleBtn(r){
      var b=document.getElementById('agentdeck-rail-toggle');
      if(!b){
        b=document.createElement('button'); b.id='agentdeck-rail-toggle'; b.type='button';
        b.onclick=function(){
          if(r.getAttribute('data-h')==='1'){ r.style.removeProperty('display'); r.setAttribute('data-h','0'); b.textContent='聊天'; }
          else { r.style.setProperty('display','none','important'); r.setAttribute('data-h','1'); b.textContent='会话'; }
        };
        document.body.appendChild(b);
      }
      b.style.display=narrow()?'block':'none';
      b.textContent=r.getAttribute('data-h')==='1'?'会话':'聊天';
    }
    function adapt(){
      if(!narrow()){
        var t=document.getElementById('agentdeck-rail-toggle'); if(t) t.style.display='none';
        if(rail&&rail.getAttribute('data-h')==='1'){ rail.style.removeProperty('display'); rail.setAttribute('data-h','0'); }
        return;
      }
      var r=findRail(); if(!r) return;
      if(r.getAttribute('data-h')!=='0'){
        r.style.setProperty('display','none','important'); r.setAttribute('data-h','1');
      }
      toggleBtn(r);
    }
    adapt();
    var tries=0, id=setInterval(function(){ tries++; adapt(); if(tries>=3||rail) clearInterval(id); }, 900);
  } catch(e) {}
})();
""".trimIndent()
