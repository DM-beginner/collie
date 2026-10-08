package dev.dmbeginner.colliepocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Standalone Android client: the chosen Collie origin owns its isolated WebView storage. */
public final class MainActivity extends Activity {
    private static final int PICK_FILE = 70;
    private static final int BACKGROUND = Color.rgb(16, 24, 23);
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private LinearLayout root;
    private FrameLayout content;
    private ProgressBar progress;
    private WebView web;
    private View homeControls;
    private TextView serverCaption;
    private View errorPanel;
    private ValueCallback<Uri[]> fileCallback;
    private String server = "";
    private String failedUrl = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        preferences = getSharedPreferences("connection", MODE_PRIVATE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                root.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        } else { root.setFitsSystemWindows(true); }
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        buildWebView();
        content.addView(progress, new FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP));
        buildHomeControls();
        String saved = preferences.getString("server", getString(R.string.default_server));
        try { if (saved != null && !saved.isEmpty()) server = ServerAddress.normalize(saved); }
        catch (IllegalArgumentException ignored) { server = ""; }
        if (server.isEmpty()) showError("连接你的电脑", "填写电脑上的 Collie 地址，然后配对这台设备。");
        else if (state == null || web.restoreState(state) == null
                || !ServerAddress.sameOrigin(server, web.getUrl())) web.loadUrl(server);
        updateHomeControls(web.getUrl());
    }

    private void buildHomeControls() {
        LinearLayout section = new LinearLayout(this);
        section.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(6), dp(4), dp(6));
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(Color.rgb(24, 35, 32));
        surface.setCornerRadius(dp(8));
        surface.setStroke(dp(1), Color.rgb(55, 72, 64));
        card.setBackground(surface);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.connection_title);
        title.setTextColor(Color.rgb(159, 230, 184));
        title.setTextSize(13);
        labels.addView(title);
        serverCaption = new TextView(this);
        serverCaption.setTextColor(Color.LTGRAY);
        serverCaption.setTextSize(12);
        serverCaption.setSingleLine(true);
        serverCaption.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        labels.addView(serverCaption);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = new Button(this, null, android.R.attr.borderlessButtonStyle);
        menu.setText(R.string.connection_settings);
        menu.setTextSize(14);
        menu.setTextColor(Color.WHITE);
        menu.setContentDescription(getString(R.string.app_settings));
        card.addView(menu, new LinearLayout.LayoutParams(dp(72), dp(48)));
        menu.setOnClickListener(view -> {
            PopupMenu popup = new PopupMenu(this, menu);
            String[] items = {"连接设置", "配对这台设备", "刷新主页", "打开 Tailscale", "关于"};
            for (int i = 0; i < items.length; i++) popup.getMenu().add(0, i, i, items[i]);
            popup.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 0: showConnection(); break;
                    case 1: showPairing(); break;
                    case 2: if (!server.isEmpty()) web.reload(); break;
                    case 3: openTailscale(); break;
                    case 4: new AlertDialog.Builder(this).setTitle("Collie Pocket 0.1.1")
                        .setMessage("基于开源 Collie 的非官方安卓客户端（MIT）。\n\n电脑继续运行 Collie 与 herdr，手机使用 Tailscale 连接。配对凭据保存在此 App 中。\n\n源码：github.com/DM-beginner/collie，android-apk 分支。\n\n此版本提供前台查看和操作；系统通知、麦克风录音尚未接入。")
                        .setNeutralButton("开源许可", (dialog, which) -> showLicense())
                        .setPositiveButton("知道了", null).show(); break;
                    default: return false;
                }
                return true;
            });
            popup.show();
        });
        section.addView(card, new LinearLayout.LayoutParams(-1, -2));
        homeControls = section;
        homeControls.setVisibility(View.GONE);
        root.addView(homeControls, new LinearLayout.LayoutParams(-1, -2));
    }

    private boolean isHome(String url) {
        if (!ServerAddress.sameOrigin(server, url)) return false;
        String path = Uri.parse(url).getPath();
        return path == null || path.isEmpty() || "/".equals(path);
    }

    private void updateHomeControls(String url) {
        if (homeControls == null) return;
        serverCaption.setText(server.isEmpty() ? getString(R.string.connection_unset)
                : server.replaceFirst("^https?://", "").replaceFirst("/$", ""));
        homeControls.setVisibility(server.isEmpty() || isHome(url) ? View.VISIBLE : View.GONE);
    }

    private void buildWebView() {
        web = new WebView(this);
        web.setBackgroundColor(BACKGROUND);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Only picker-granted content URIs are handed to the page.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setUserAgentString(settings.getUserAgentString() + " ColliePocket/0.1.1");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String destination = request.getUrl().toString();
                if (ServerAddress.sameOrigin(server, destination)) return false;
                if (request.isForMainFrame() && request.hasGesture()) openExternal(request.getUrl());
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                failedUrl = "";
                progress.setVisibility(View.VISIBLE);
                updateHomeControls(url);
                clearError();
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                updateHomeControls(url); // Includes React Router's pushState/replaceState navigations.
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                updateHomeControls(url);
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    failedUrl = request.getUrl().toString();
                    showError("暂时连不上电脑", "请确认 Tailscale 已连接、电脑开机联网，并检查连接地址。再点重试。");
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    failedUrl = request.getUrl().toString();
                    showError("服务器返回错误", "HTTP " + response.getStatusCode() + "。请检查电脑上的 Collie 服务。");
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showError("证书验证未通过", "请检查电脑时间和服务器 HTTPS 证书，或在 Tailscale 私网中使用原来的 HTTP 地址。");
            }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                content.removeView(web);
                web.destroy();
                buildWebView();
                showError("页面需要重新载入", "配对记录仍保留，点重试继续。");
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!ServerAddress.sameOrigin(server, view.getUrl())) return false;
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                String[] types = params.getAcceptTypes();
                ArrayList<String> mimes = new ArrayList<>();
                if (types != null) for (String type : types) if (type != null && type.contains("/")) mimes.add(type);
                if (!mimes.isEmpty()) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(pick, PICK_FILE); }
                catch (ActivityNotFoundException e) { fileCallback.onReceiveValue(null); fileCallback = null; }
                return true;
            }
        });
        content.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showConnection() {
        LinearLayout fields = dialogFields();
        TextView info = new TextView(this);
        info.setText("填写电脑上的 Collie 首页地址。外出使用时，请保持 Tailscale 已连接。");
        fields.addView(info);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("http://电脑名.tailxxxx.ts.net:8787");
        address.setText(server);
        address.setContentDescription("服务器地址");
        fields.addView(address);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接设置").setView(fields)
                .setNegativeButton("取消", null).setPositiveButton("保存并连接", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String chosen = ServerAddress.normalize(address.getText().toString());
                if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
                boolean changed = !chosen.equals(server);
                server = chosen;
                preferences.edit().putString("server", server).apply();
                if (changed) { web.stopLoading(); web.clearHistory(); }
                clearError();
                web.loadUrl(server);
                dialog.dismiss();
            } catch (IllegalArgumentException error) { address.setError(error.getMessage()); }
        }));
        dialog.show();
    }

    private void showPairing() {
        if (server.isEmpty()) { showConnection(); return; }
        if (!ServerAddress.sameOrigin(server, web.getUrl()) || errorPanel != null || web.getProgress() < 100) {
            Toast.makeText(this, "请先连接电脑并等待页面载入", Toast.LENGTH_LONG).show(); return;
        }
        LinearLayout fields = dialogFields();
        TextView info = new TextView(this);
        info.setText("输入电脑生成的一次性配对码。此 App 的配对记录与浏览器分开保存。");
        fields.addView(info);
        EditText code = new EditText(this);
        code.setSingleLine(true);
        code.setHint("8 位配对码");
        code.setContentDescription("配对码");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fields.addView(code);
        EditText label = new EditText(this);
        label.setSingleLine(true);
        label.setHint("设备名称");
        label.setContentDescription("设备名称");
        label.setText(preferences.getString("label", Build.MODEL + "-app"));
        fields.addView(label);
        TextView message = new TextView(this);
        fields.addView(message);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("配对这台设备").setView(fields)
                .setNegativeButton("取消", null).setPositiveButton("配对并启用操作", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String entered = code.getText().toString().trim().toUpperCase(Locale.ROOT);
            String name = label.getText().toString().trim();
            if (!entered.matches("[A-Z0-9]{8}")) { code.setError("请输入 8 位配对码"); return; }
            if (name.isEmpty() || name.length() > 64) { label.setError("名称需要 1 到 64 个字符"); return; }
            String chosenServer = server;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            message.setText("正在配对…");
            network.execute(() -> {
                try {
                    String token = PairingClient.claim(chosenServer, entered, name);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (!chosenServer.equals(server) || !ServerAddress.sameOrigin(server, web.getUrl())) {
                            message.setText("连接地址已切换，请重新生成配对码后重试");
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); return;
                        }
                        // Only the explicitly configured origin receives its own freshly minted token.
                        web.evaluateJavascript("(function(){try{localStorage.setItem('collie:device-token',"
                                + JSONObject.quote(token) + ");return true;}catch(e){return false;}})()", result -> {
                            if (!"true".equals(result)) {
                                message.setText("无法保存配对记录，请重启 App 后使用新配对码重试");
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); return;
                            }
                            preferences.edit().putString("label", name).apply();
                            dialog.dismiss();
                            web.reload();
                            Toast.makeText(this, "配对成功，可以操作电脑了", Toast.LENGTH_LONG).show();
                        });
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        String detail = error instanceof java.net.SocketTimeoutException ? "连接超时，请检查 Tailscale；若配对码已使用，请生成新码"
                                : error instanceof java.io.IOException ? "连接失败，请检查 Tailscale 和电脑服务" : error.getMessage();
                        message.setText(detail == null ? "配对失败，请重新生成配对码后重试" : detail);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    });
                }
            });
        }));
        dialog.show();
    }

    private LinearLayout dialogFields() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(12), dp(24), dp(8));
        return fields;
    }
    private void showLicense() {
        StringBuilder license = new StringBuilder("Collie — https://github.com/AltanS/collie\n\n");
        try (java.io.Reader reader = new java.io.InputStreamReader(getAssets().open("LICENSE-Collie.txt"), java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024]; int count;
            while ((count = reader.read(buffer)) != -1) license.append(buffer, 0, count);
        } catch (java.io.IOException error) { license.append("See the LICENSE file in the source repository."); }
        TextView text = new TextView(this); text.setText(license.toString()); text.setTextSize(14);
        text.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("开源许可").setView(scroll).setPositiveButton("关闭", null).show();
    }
    private void showError(String title, String description) {
        clearError();
        progress.setVisibility(View.GONE);
        LinearLayout panel = dialogFields();
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(BACKGROUND);
        TextView heading = new TextView(this); heading.setText(title); heading.setTextSize(22); heading.setTextColor(Color.WHITE);
        TextView body = new TextView(this); body.setText(description); body.setTextSize(16); body.setTextColor(Color.LTGRAY);
        body.setPadding(0, dp(16), 0, dp(20));
        panel.addView(heading); panel.addView(body);
        if (!server.isEmpty()) {
            Button retry = new Button(this); retry.setText("重试");
            retry.setOnClickListener(view -> {
                clearError();
                web.loadUrl(ServerAddress.sameOrigin(server, failedUrl) ? failedUrl : server);
            });
            panel.addView(retry);
        }
        Button settings = new Button(this); settings.setText("连接设置");
        settings.setOnClickListener(view -> showConnection()); panel.addView(settings);
        errorPanel = panel;
        content.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    }
    private void clearError() { if (errorPanel != null) { content.removeView(errorPanel); errorPanel = null; } }
    private void openTailscale() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, "请先安装并登录 Tailscale", Toast.LENGTH_LONG).show();
    }
    private void openExternal(Uri uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return;
        Intent chrome = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome");
        try { startActivity(chrome); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "外部链接需要 Google Chrome", Toast.LENGTH_SHORT).show(); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || fileCallback == null) return;
        ArrayList<Uri> selected = new ArrayList<>();
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null && "content".equals(uri.getScheme())) selected.add(uri);
            } else if (data.getData() != null && "content".equals(data.getData().getScheme())) selected.add(data.getData());
        }
        fileCallback.onReceiveValue(selected.isEmpty() ? null : selected.toArray(new Uri[0])); fileCallback = null;
    }
    private void navigateBack() {
        if (isHome(web.getUrl())) { moveTaskToBack(true); return; }
        if (errorPanel == null && web.canGoBack()) web.goBack();
        else if (!server.isEmpty() && ServerAddress.sameOrigin(server, web.getUrl())) web.loadUrl(server);
        else moveTaskToBack(true);
    }
    @Override public void onBackPressed() { navigateBack(); }
    @Override protected void onPause() { web.onPause(); web.pauseTimers(); CookieManager.getInstance().flush(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) { web.onResume(); web.resumeTimers(); } }
    @Override protected void onSaveInstanceState(Bundle state) { web.saveState(state); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        network.shutdownNow(); web.stopLoading(); content.removeView(web); web.destroy(); super.onDestroy();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
