package com.manga.reader;

import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  private WebView web;

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getBridge().getWebView().addJavascriptInterface(new LockApi(), "Lock");
    getBridge().getWebView().addJavascriptInterface(new GemApi(), "Gem");
  }

  public class LockApi {
    @JavascriptInterface
    public void lock() {
      runOnUiThread(new Runnable() {
        public void run() { try { startLockTask(); } catch (Exception e) { } }
      });
    }
    @JavascriptInterface
    public void unlock() {
      runOnUiThread(new Runnable() {
        public void run() { try { stopLockTask(); } catch (Exception e) { } }
      });
    }
  }

  public class GemApi {
    @JavascriptInterface
    public void open(final int top, final int bottom, final String url) {
      runOnUiThread(new Runnable() {
        public void run() { showWeb(top, bottom, url); }
      });
    }
    @JavascriptInterface
    public void load(final String url) {
      runOnUiThread(new Runnable() {
        public void run() { if (web != null) web.loadUrl(url); }
      });
    }
    @JavascriptInterface
    public void close() {
      runOnUiThread(new Runnable() {
        public void run() { closeWeb(); }
      });
    }
  }

  private void showWeb(int top, int bottom, String url) {
    float d = getResources().getDisplayMetrics().density;
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    lp.topMargin = Math.round(top * d);
    lp.bottomMargin = Math.round(bottom * d);
    if (web == null) {
      web = new WebView(this);
      WebSettings s = web.getSettings();
      s.setJavaScriptEnabled(true);
      s.setDomStorageEnabled(true);
      s.setCacheMode(WebSettings.LOAD_NO_CACHE);
      s.setSaveFormData(false);
      s.setSupportMultipleWindows(true);
      s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
      web.setWebViewClient(new WebViewClient());
      web.setWebChromeClient(new WebChromeClient() {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
          WebView tmp = new WebView(MainActivity.this);
          tmp.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
              web.loadUrl(u);
              return true;
            }
          });
          WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
          t.setWebView(tmp);
          resultMsg.sendToTarget();
          return true;
        }
      });
      CookieManager.getInstance().setAcceptCookie(true);
      CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
      addContentView(web, lp);
    } else {
      web.setLayoutParams(lp);
    }
    web.setVisibility(View.VISIBLE);
    web.loadUrl(url);
  }

  private void closeWeb() {
    if (web == null) return;
    web.loadUrl("about:blank");
    web.clearHistory();
    web.clearCache(true);
    web.clearFormData();
    web.setVisibility(View.GONE);
    CookieManager cm = CookieManager.getInstance();
    cm.removeAllCookies(null);
    cm.flush();
  }
}
