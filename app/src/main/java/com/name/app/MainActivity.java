package com.example.ussdwebview;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private BroadcastReceiver smsReceiver;

    private static final int REQUEST_ALL_PERMISSIONS = 1;
    private static final String CHANNEL_ID = "foreground_channel";
    private static final int NOTIFICATION_ID = 101;

    private String pendingUSSDCode;

    /* =========================
       ACTIVITY
       ========================= */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                v.loadUrl(r.getUrl().toString());
                return true;
            }
        });

        webView.addJavascriptInterface(new JSBridge(), "AndroidUSSD");
        webView.loadUrl("file:///android_asset/index.html");

        setupSmsReceiver();
        requestAllPermissions();
    }

    /* =========================
       JS BRIDGE
       ========================= */
    private class JSBridge {

        @JavascriptInterface
        public void runUssd(String code) {
            runOnUiThread(() -> executeUSSD(code));
        }

        @JavascriptInterface
        public void sendSms(String phone, String msg) {
            runOnUiThread(() -> executeSendSMS(phone, msg));
        }

        @JavascriptInterface
        public void readSms() {
            runOnUiThread(MainActivity.this::executeReadSMS);
        }
    }

    /* =========================
       USSD
       ========================= */
    private void executeUSSD(String code) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sendResultToWeb("USSD Android 8+ only");
            return;
        }

        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return;

        tm.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {
            @Override
            public void onReceiveUssdResponse(
                    TelephonyManager t, String r, CharSequence res) {
                sendResultToWeb(res.toString());
            }

            @Override
            public void onReceiveUssdResponseFailed(
                    TelephonyManager t, String r, int f) {
                sendResultToWeb("USSD failed: " + f);
            }
        }, new Handler(Looper.getMainLooper()));
    }

    /* =========================
       SEND SMS
       ========================= */
    private void executeSendSMS(String phone, String msg) {
        try {
            SmsManager.getDefault().sendTextMessage(phone, null, msg, null, null);
            sendResultToWeb("SMS sent");
        } catch (Exception e) {
            sendResultToWeb(e.getMessage());
        }
    }

    /* =========================
       READ SMS
       ========================= */
    private void executeReadSMS() {

        Cursor c = getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                null, null, null,
                "date DESC LIMIT 10"
        );

        if (c == null) return;

        StringBuilder sb = new StringBuilder();
        while (c.moveToNext()) {
            sb.append("From: ")
              .append(c.getString(c.getColumnIndexOrThrow("address")))
              .append("\n")
              .append(c.getString(c.getColumnIndexOrThrow("body")))
              .append("\n\n");
        }
        c.close();

        sendResultToWeb(sb.toString());
    }

    /* =========================
       INCOMING SMS (FIXED)
       ========================= */
    private void setupSmsReceiver() {

        smsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {

                Object[] pdus = (Object[]) i.getExtras().get("pdus");
                if (pdus == null) return;

                for (Object p : pdus) {
                    SmsMessage sms = SmsMessage.createFromPdu((byte[]) p);
                    String from = sms.getOriginatingAddress();
                    String msg = sms.getMessageBody();

                    if (from == null) from = "Unknown";
                    if (msg == null) msg = "";

                    sendIncomingSmsToWeb(from, msg);
                }
            }
        };
    }

    private void sendIncomingSmsToWeb(String from, String msg) {

        from = from.replace("'", "\\'");
        msg = msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                        "if(window.onIncomingSms) onIncomingSms('" + from + "','" + msg + "');",
                        null
                )
        );
    }

    /* =========================
       FOREGROUND NOTIFICATION
       ========================= */
    private void showForegroundNotification() {

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(ch);
        }

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Ready To Serve You")
                .setContentText("Erandix Monitoring")
                .setSmallIcon(R.drawable.app_icon)
                .setOngoing(true)
                .build();

        nm.notify(NOTIFICATION_ID, n);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showForegroundNotification();
        registerReceiver(smsReceiver,
                new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(smsReceiver);
    }

    /* =========================
       PERMISSIONS
       ========================= */
    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.RECEIVE_SMS,
                        Manifest.permission.READ_SMS,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE
                },
                REQUEST_ALL_PERMISSIONS
        );
    }

    /* =========================
       WEB CALLBACK
       ========================= */
    private void sendResultToWeb(String msg) {
        msg = msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        webView.post(() ->
                webView.evaluateJavascript(
                        "if(window.showResult) showResult('" + msg + "');",
                        null
                )
        );
    }

    /* =========================
       BOOT RECEIVER (AUTO START)
       ========================= */
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
                Intent intent = new Intent(c, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(intent);
            }
        }
    }
}
