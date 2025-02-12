package com.example.webviewtest

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.webview)
        setupWebView()

        val url = "https://demo.e-dealertire.com/?mode=productCatalogByVehicle&dealer=TOY04563&vin=1G1BE5SM3H7278622&year=&make=&model=&trim=&language=en-us&version=2.0&theme=PRODUCT_LIST_VIEW&canvas=400x800&style=&options=NO_CONTENT&return=https://gc8xs63d1f.execute-api.us-west-1.amazonaws.com/stage/&handle=3E058571-03FB-47E0-BFCF-D62DE8C3E8BF&nonce=2025-02-12T07:01:14&signature=gddiScB3dBc8po2EnHrFRD4oFe8="

        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun postMessage(message: String) {
                Log.d("WebView", "JS Interface Message: $message")
                runOnUiThread {
                    handleMessage(message)
                }
            }

            @JavascriptInterface
            fun log(message: String) {
                Log.d("WebView", "JS log: $message")
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("WebView", "Page Loaded: $url")
                injectMessageHandlers()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.url?.toString()?.let { url ->
                    if (url.contains("gc8xs63d1f.execute-api.us-west-1.amazonaws.com")) {
                        Log.d("WebView", "Intercepted API call: $url")
                        // Force a postMessage event when we see the callback URL
                        view?.post {
                            injectCallbackTrigger(url)
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val message = it.message()
                    Log.d("WebView", "Console: $message")
                    if (message.contains("postMessage") ||
                        message.contains("RESPONSE") ||
                        message.contains("callback") ||
                        message.contains("data:")) {
                        try {
                            handleMessage(message)
                        } catch (e: Exception) {
                            Log.e("WebView", "Error handling console message", e)
                        }
                    }
                }
                return true
            }
        }
    }

    private fun injectCallbackTrigger(url: String) {
        val script = """
            (function() {
                var responseData = {
                    type: 'RESPONSE',
                    payload: {
                        value: {
                            url: '${url}'
                        }
                    }
                };
                
                // Try multiple methods to send the data
                try {
                    window.postMessage(responseData, '*');
                } catch(e) {
                    console.log('postMessage failed:', e);
                }
                
                try {
                    if (window.Android) {
                        window.Android.postMessage(JSON.stringify(responseData));
                    }
                } catch(e) {
                    console.log('Android interface failed:', e);
                }
                
                console.log('Response data:', JSON.stringify(responseData));
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun injectMessageHandlers() {
        val script = """
            (function() {
                // Intercept postMessage
                var originalPostMessage = window.postMessage;
                window.postMessage = function(message, targetOrigin, transfer) {
                    console.log('postMessage called:', JSON.stringify(message));
                    if (window.Android) {
                        window.Android.log('postMessage intercepted: ' + JSON.stringify(message));
                        window.Android.postMessage(JSON.stringify(message));
                    }
                    return originalPostMessage.call(this, message, targetOrigin, transfer);
                };

                // Listen for postMessage events
                window.addEventListener('message', function(event) {
                    console.log('message event:', JSON.stringify(event.data));
                    if (window.Android) {
                        window.Android.log('message event: ' + JSON.stringify(event.data));
                        window.Android.postMessage(JSON.stringify(event.data));
                    }
                }, false);

                // Monitor window.parent.postMessage
                if (window.parent && window.parent !== window) {
                    var originalParentPostMessage = window.parent.postMessage;
                    window.parent.postMessage = function(message, targetOrigin, transfer) {
                        console.log('parent.postMessage called:', JSON.stringify(message));
                        if (window.Android) {
                            window.Android.log('parent.postMessage: ' + JSON.stringify(message));
                            window.Android.postMessage(JSON.stringify(message));
                        }
                        return originalParentPostMessage.call(this, message, targetOrigin, transfer);
                    };
                }

                // Inject a global callback handler
                window.handleCallback = function(data) {
                    console.log('handleCallback called:', JSON.stringify(data));
                    if (window.Android) {
                        window.Android.log('handleCallback: ' + JSON.stringify(data));
                        window.Android.postMessage(JSON.stringify(data));
                    }
                };

                console.log('Enhanced message handlers injected');
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun handleMessage(message: String) {
        Log.d("WebView", "Handling message: $message")
        try {
            val trimmedMessage = message.trim()
            if (trimmedMessage.startsWith("{") || trimmedMessage.startsWith("[")) {
                val jsonMessage = JSONObject(trimmedMessage)
                Log.d("WebView", "Parsed JSON message: $jsonMessage")

                if (jsonMessage.has("type") && jsonMessage.getString("type") == "RESPONSE") {
                    val summary = "Received callback response"
                    runOnUiThread {
                        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebView", "Error parsing message", e)
        }
    }
}