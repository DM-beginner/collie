package dev.dmbeginner.colliepocket.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in test against an already paired emulator. Never targets a real pane. */
public final class DeviceSmoke extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent launch = new Intent().setClassName("dev.dmbeginner.colliepocket", "dev.dmbeginner.colliepocket.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(launch);
            AtomicReference<WebView> reference = new AtomicReference<>();
            runOnMainSync(() -> reference.set(findWeb(activity.getWindow().getDecorView())));
            WebView web = reference.get();
            if (web == null) throw new AssertionError("WebView missing");
            long deadline = System.currentTimeMillis() + 30000;
            while (!"true".equals(evaluate(web, "!!(location.protocol.indexOf('http')===0 && document.readyState==='complete')"))) {
                if (System.currentTimeMillis() > deadline) throw new AssertionError("Page load timeout");
                Thread.sleep(250);
            }
            // The synthetic ID cannot name a herdr pane. No real agent receives input.
            evaluate(web, "window.__colliePocketSmoke=null;(async function(){try{"
                    + "var t=localStorage.getItem('collie:device-token');"
                    + "var p='/api/pane/__collie_android_smoke_nonexistent__/keys';"
                    + "var body=JSON.stringify({keys:['Escape']});"
                    + "var a=await fetch(p,{method:'POST',headers:{'Content-Type':'application/json'},body:body});"
                    + "var b=await fetch(p,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+t},body:body});"
                    + "var text=await b.text();window.__colliePocketSmoke={hasToken:!!t,plain:a.status,paired:b.status,missingPane:text.indexOf('pane_not_found')>=0};"
                    + "}catch(e){window.__colliePocketSmoke={error:'network failed'};}})();true;");
            JSONObject facts = null;
            deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                String value = evaluate(web, "JSON.stringify(window.__colliePocketSmoke)");
                Object decoded = new JSONTokener(value).nextValue();
                if (decoded instanceof String && !"null".equals(decoded)) {
                    facts = new JSONObject((String) decoded); break;
                }
                Thread.sleep(250);
            }
            if (facts == null || !facts.optBoolean("hasToken") || facts.optInt("plain") != 403
                    || facts.optInt("paired") != 200 || !facts.optBoolean("missingPane")) {
                throw new AssertionError("Pairing gate failed: " + facts);
            }
            AtomicReference<View> settings = new AtomicReference<>();
            runOnMainSync(() -> {
                View decor = activity.getWindow().getDecorView();
                settings.set(findSettings(decor));
                if (settings.get() == null || !settings.get().isShown()) throw new AssertionError("Home settings missing");
                if (hasTitle(decor)) throw new AssertionError("Persistent app toolbar remains");
            });
            // Agent rows are accessible buttons, rather than links; wait for the router's first render.
            String paneButton = "Array.from(document.querySelectorAll('button')).find(function(b){return b.querySelector('[aria-label$=\" logo\"]');})";
            waitFor(web, "!!(" + paneButton + ")");
            String navigation = evaluate(web, "(function(){var b=" + paneButton + ";if(!b)return false;b.click();return true;})()");
            if (!"true".equals(navigation)) throw new AssertionError("No session button on home");
            waitFor(web, "location.pathname.indexOf('/pane/')===0");
            runOnMainSync(() -> {
                if (settings.get().isShown()) throw new AssertionError("Home controls occupy session space");
                View root = activity.findViewById(android.R.id.content);
                int[] rootPosition = new int[2], webPosition = new int[2];
                root.getLocationOnScreen(rootPosition); web.getLocationOnScreen(webPosition);
                int safeTop = ((ViewGroup)root).getChildAt(0).getPaddingTop();
                if (webPosition[1] != rootPosition[1] + safeTop) throw new AssertionError("A top toolbar gap remains");
                activity.onBackPressed();
            });
            waitFor(web, "location.pathname==='/'");
            runOnMainSync(() -> {
                if (!settings.get().isShown()) throw new AssertionError("Back does not restore home settings");
            });
            result.putString("result", "PASS: pairing survives update and restart; auth gate enforced; home settings visible; session uses full height; Back restores home settings.");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private String evaluate(WebView web, String javascript) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        runOnMainSync(() -> web.evaluateJavascript(javascript, value -> { result.set(value); done.countDown(); }));
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("JavaScript evaluation timed out");
        return result.get();
    }
    private WebView findWeb(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            WebView found = findWeb(((ViewGroup)view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private void waitFor(WebView web, String condition) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (!"true".equals(evaluate(web, condition))) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Navigation timed out");
            Thread.sleep(100);
        }
        waitForIdleSync();
    }
    private View findSettings(View view) {
        if ("App 设置".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            View found = findSettings(((ViewGroup)view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private boolean hasTitle(View view) {
        if (view instanceof TextView && "Collie Pocket".contentEquals(((TextView)view).getText())) return true;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            if (hasTitle(((ViewGroup)view).getChildAt(i))) return true;
        }
        return false;
    }
}
