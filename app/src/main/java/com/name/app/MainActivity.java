package com.example.ussdwebview;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    private static final int REQUEST_ALL_PERMISSIONS = 1;
    private String pendingUSSDCode;

    private BroadcastReceiver smsReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        webView.addJavascriptInterface(new JSBridge(), "AndroidUSSD");
        webView.loadUrl("file:///android_asset/index.html");

        setupSmsReceiver();
    }

    private class JSBridge {

        @JavascriptInterface
        public void runUssd(String code) {
            runOnUiThread(() -> executeUSSD(code));
        }

        @JavascriptInterface
        public void sendSms(String phone, String message) {
            runOnUiThread(() -> executeSendSMS(phone, message));
        }

        @JavascriptInterface
        public void readSms() {
            runOnUiThread(() -> executeReadSMS());
        }
    }

    private void executeUSSD(String code) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sendResultToWeb("USSD supported on Android 8.0+ only");
            return;
        }

        if (!hasAllPermissions()) {
            pendingUSSDCode = code;
            requestAllPermissions();
            return;
        }

        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) {
            sendResultToWeb("Telephony unavailable");
            return;
        }

        tm.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {
            @Override
            public void onReceiveUssdResponse(
                    TelephonyManager telephonyManager,
                    String request,
                    CharSequence response) {

                sendResultToWeb(response.toString());
            }

            @Override
            public void onReceiveUssdResponseFailed(
                    TelephonyManager telephonyManager,
                    String request,
                    int failureCode) {

                sendResultToWeb("USSD failed: " + failureCode);
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private void executeSendSMS(String phone, String message) {

        if (!hasSmsPermissions()) {
            requestAllPermissions();
            return;
        }

        try {
            SmsManager.getDefault().sendTextMessage(
                    phone, null, message, null, null
            );
            sendResultToWeb("SMS sent");
        } catch (Exception e) {
            sendResultToWeb("SMS failed: " + e.getMessage());
        }
    }

    private void executeReadSMS() {

        if (!hasSmsPermissions()) {
            requestAllPermissions();
            return;
        }

        Cursor cursor = getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                null, null, null,
                "date DESC LIMIT 10"
        );

        if (cursor == null) {
            sendResultToWeb("Failed to read SMS");
            return;
        }

        StringBuilder result = new StringBuilder();

        while (cursor.moveToNext()) {
            result.append("From: ")
                  .append(cursor.getString(cursor.getColumnIndexOrThrow("address")))
                  .append("\n")
                  .append(cursor.getString(cursor.getColumnIndexOrThrow("body")))
                  .append("\n\n");
        }

        cursor.close();
        sendResultToWeb(result.toString());
    }

    private void setupSmsReceiver() {

        smsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                if (!hasSmsPermissions()) return;

                Bundle bundle = intent.getExtras();
                if (bundle == null) return;

                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus == null) return;

                for (Object pdu : pdus) {
                    SmsMessage sms;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        String format = bundle.getString("format");
                        sms = SmsMessage.createFromPdu((byte[]) pdu, format);
                    } else {
                        sms = SmsMessage.createFromPdu((byte[]) pdu);
                    }

                    String from = sms.getOriginatingAddress();
                    String body = sms.getMessageBody();

                    sendIncomingSmsToWeb(from, body);
                }
            }
        };
    }

    private void sendIncomingSmsToWeb(String from, String message) {

        String safeFrom = from.replace("'", "\\'");
        String safeMsg = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                        "onIncomingSms('" + safeFrom + "','" + safeMsg + "')",
                        null
                )
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(
                smsReceiver,
                new IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(smsReceiver);
    }

    private boolean hasSmsPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAllPermissions() {
        return hasSmsPermissions() &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_SMS
                },
                REQUEST_ALL_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_ALL_PERMISSIONS) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    sendResultToWeb("Permission denied");
                    return;
                }
            }

            if (pendingUSSDCode != null) {
                executeUSSD(pendingUSSDCode);
                pendingUSSDCode = null;
            }
        }
    }

    private void sendResultToWeb(String message) {

        String safeMessage = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                        "showResult('" + safeMessage + "')",
                        null
                )
        );
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
