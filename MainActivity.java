package com.manga.reader;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.ParcelFileDescriptor;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends BridgeActivity {
  private WebView web;
  private String currentUrl;
  private AudioServer audioServer;

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    WebView main = getBridge().getWebView();
    main.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    main.addJavascriptInterface(new LockApi(), "Lock");
    main.addJavascriptInterface(new GemApi(), "Gem");
    main.addJavascriptInterface(new FilesApi(), "Files");
    try { audioServer = new AudioServer(getContentResolver()); } catch (Exception e) { }
  }

  @Override
  public void onBackPressed() {
    WebView wv = getBridge().getWebView();
    if (wv == null) { super.onBackPressed(); return; }
    wv.evaluateJavascript(
      "(function(){try{return (window.__back && window.__back())?'1':'0'}catch(e){return '0'}})()",
      value -> { if (!"1".equals(value)) MainActivity.super.onBackPressed(); });
  }

  public class LockApi {
    @JavascriptInterface public void lock() { runOnUiThread(() -> { try { startLockTask(); } catch (Exception e) { } }); }
    @JavascriptInterface public void unlock() { runOnUiThread(() -> { try { stopLockTask(); } catch (Exception e) { } }); }
  }

  public class FilesApi {
    @JavascriptInterface public int getAudioPort() { return audioServer != null ? audioServer.port() : 0; }
    @JavascriptInterface public String addAudio(String contentUri) {
      return audioServer != null ? audioServer.add(Uri.parse(contentUri)) : "";
    }

    @JavascriptInterface
    public void pickAudio() {
      runOnUiThread(() -> {
        try {
          Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          i.addCategory(Intent.CATEGORY_OPENABLE);
          i.setType("audio/*");
          i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
          i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
          startActivityForResult(i, 1001);
        } catch (Exception e) { }
      });
    }

    @JavascriptInterface
    public void saveNotes(String name, String json) {
      try {
        File dir = new File(getExternalFilesDir(null), "notes");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, name + ".json");
        try (FileWriter w = new FileWriter(f)) { w.write(json); }
      } catch (Exception e) { }
    }

    @JavascriptInterface
    public String loadNotes(String name) {
      try {
        File dir = new File(getExternalFilesDir(null), "notes");
        File f = new File(dir, name + ".json");
        if (!f.exists()) return "";
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      } catch (Exception e) { return ""; }
    }
  }

  public class GemApi {
    @JavascriptInterface public void open(final int top, final int bottom, final String url) {
      runOnUiThread(() -> showWeb(top, bottom, url));
    }
    @JavascriptInterface public void load(final String url) {
      runOnUiThread(() -> { if (web != null) { web.loadUrl(url); currentUrl = url; } });
    }
    @JavascriptInterface public void close() {
      runOnUiThread(() -> closeWeb());
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
      s.setCacheMode(WebSettings.LOAD_DEFAULT);
      s.setSaveFormData(true);
      s.setSupportMultipleWindows(true);
      s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
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
      web.loadUrl(url);
      currentUrl = url;
    } else {
      web.setLayoutParams(lp);
    }
    web.setVisibility(View.VISIBLE);
  }

  private void closeWeb() {
    if (web == null) return;
    web.setVisibility(View.GONE);
    // Intentionally do NOT clear cookies, cache, history or reload.
    // Session survives until the app process dies.
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req != 1001 || res != RESULT_OK || data == null) return;
    JSONArray arr = new JSONArray();
    try {
      int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      if (data.getClipData() != null) {
        ClipData cd = data.getClipData();
        for (int i = 0; i < cd.getItemCount(); i++) {
          Uri u = cd.getItemAt(i).getUri();
          try { getContentResolver().takePersistableUriPermission(u, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { }
          JSONObject o = new JSONObject();
          o.put("name", nameOf(u));
          o.put("uri", u.toString());
          arr.put(o);
        }
      } else if (data.getData() != null) {
        Uri u = data.getData();
        try { getContentResolver().takePersistableUriPermission(u, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { }
        JSONObject o = new JSONObject();
        o.put("name", nameOf(u));
        o.put("uri", u.toString());
        arr.put(o);
      }
    } catch (Exception e) { }
    final String json = arr.toString();
    getBridge().getWebView().evaluateJavascript(
      "window.__onAudioPicked && window.__onAudioPicked(" + JSONObject.quote(json) + ")", null);
  }

  private String nameOf(Uri u) {
    String n = u.getLastPathSegment();
    if (n == null) n = "audio";
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  // --- Tiny localhost HTTP server so <audio> can stream content:// URIs ---
  static class AudioServer {
    private final ServerSocket ss;
    private final int port;
    private final Map<String, Uri> routes = new ConcurrentHashMap<>();
    private final ContentResolver cr;

    AudioServer(ContentResolver cr) throws Exception {
      this.cr = cr;
      ss = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
      port = ss.getLocalPort();
      Thread t = new Thread(this::serve, "AudioSrv");
      t.setDaemon(true);
      t.start();
    }

    int port() { return port; }

    String add(Uri uri) {
      String id = UUID.randomUUID().toString().replace("-", "");
      routes.put(id, uri);
      return id;
    }

    private void serve() {
      while (!ss.isClosed()) {
        try {
          Socket s = ss.accept();
          new Thread(() -> handle(s), "AudioReq").start();
        } catch (Exception e) { break; }
      }
    }

    private void handle(Socket s) {
      try {
        InputStream in = s.getInputStream();
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
          sb.append((char) c);
          if (sb.length() > 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) break;
        }
        String raw = sb.toString();
        String firstLine = raw.split("\r\n")[0];
        String path = firstLine.split(" ")[1];
        String id = path.startsWith("/") ? path.substring(1) : path;

        String rangeH = null;
        for (String h : raw.split("\r\n")) {
          if (h.toLowerCase().startsWith("range:")) { rangeH = h.substring(6).trim(); break; }
        }

        Uri uri = routes.get(id);
        OutputStream out = s.getOutputStream();
        if (uri == null) {
          out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes());
          s.close();
          return;
        }

        String mime = cr.getType(uri);
        if (mime == null) mime = "audio/mpeg";

        long total = -1;
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
          if (pfd != null) total = pfd.getStatSize();
        } catch (Exception e) { }

        long start = 0, end = total > 0 ? total - 1 : -1;
        int status = 200;
        if (rangeH != null && rangeH.startsWith("bytes=") && total > 0) {
          try {
            String r = rangeH.substring(6);
            int dash = r.indexOf('-');
            String a = dash > 0 ? r.substring(0, dash) : "";
            String b2 = dash + 1 < r.length() ? r.substring(dash + 1) : "";
            if (!a.isEmpty()) start = Long.parseLong(a);
            if (!b2.isEmpty()) end = Long.parseLong(b2);
            status = 206;
          } catch (Exception e) { start = 0; end = total - 1; status = 200; }
        }

        long len = end >= 0 ? (end - start + 1) : -1;
        StringBuilder hdr = new StringBuilder();
        hdr.append("HTTP/1.1 ").append(status).append(status == 206 ? " Partial Content" : " OK").append("\r\n");
        hdr.append("Content-Type: ").append(mime).append("\r\n");
        if (len >= 0) hdr.append("Content-Length: ").append(len).append("\r\n");
        hdr.append("Accept-Ranges: bytes\r\n");
        hdr.append("Access-Control-Allow-Origin: *\r\n");
        if (status == 206 && total > 0)
          hdr.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(total).append("\r\n");
        hdr.append("\r\n");
        out.write(hdr.toString().getBytes("UTF-8"));

        InputStream is = cr.openInputStream(uri);
        if (is == null) { s.close(); return; }
        long skip = start;
        while (skip > 0) { long n = is.skip(skip); if (n <= 0) break; skip -= n; }

        byte[] buf = new byte[16384];
        long remain = len;
        while (remain != 0) {
          int n = is.read(buf, 0, remain > 0 ? (int) Math.min(buf.length, remain) : buf.length);
          if (n <= 0) break;
          out.write(buf, 0, n);
          if (remain > 0) remain -= n;
        }
        is.close();
        out.flush();
        s.close();
      } catch (Exception e) {
        try { s.close(); } catch (Exception ignored) { }
      }
    }
  }
}
