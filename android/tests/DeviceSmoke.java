package dev.dmbeginner.colliepocket.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
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
            result.putString("result", "PASS: stored pairing token survives launch; unpaired writes rejected; paired write reaches nonexistent-pane validation.");
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
}
