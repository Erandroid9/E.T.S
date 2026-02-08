package com.example.ussdwebview;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.os.IBinder;
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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private BroadcastReceiver smsReceiver;
    private static final int REQUEST_ALL_PERMISSIONS = 1;
    private static final String FOREGROUND_CHANNEL_ID = "foreground_channel";
    private static final int FOREGROUND_NOTIFICATION_ID = 101;
    private String pendingUSSDCode;

    /* =========================
       ACTIVITY ONCREATE
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

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                v.loadUrl(url);
                return true;
            }
        });

        webView.addJavascriptInterface(new JSBridge(), "AndroidUSSD");
        webView.loadUrl("file:///android_asset/index.html");

        requestAllPermissions();
        startForegroundServiceIfNeeded();
        setupSmsReceiver();
    }

    /* =========================
       JS Bridge
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
            runOnUiThread(() -> executeReadSMS());
        }
    }

    /* =========================
       USSD
       ========================= */
    private void executeUSSD(String code) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sendResultToWeb("USSD requires Android 8.0+");
            return;
        }

        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingUSSDCode = code;
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE
            }, REQUEST_ALL_PERMISSIONS);
            return;
        }

        tm.sendUssdRequest(code, new TelephonyManager.UssdResponseCallback() {
            @Override
            public void onReceiveUssdResponse(TelephonyManager t, String r, CharSequence res) {
                sendResultToWeb(res.toString());
            }

            @Override
            public void onReceiveUssdResponseFailed(TelephonyManager t, String r, int f) {
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
            sendResultToWeb("SMS sent to " + phone);
        } catch (Exception e) {
            sendResultToWeb("SMS send error: " + e.getMessage());
        }
    }

    /* =========================
       READ SMS WITH IDS (JSON)
       ========================= */
    private void executeReadSMS() {
        Cursor c = getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                null, null, null,
                "date DESC"
        );
        if (c == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("["); // Start JSON array
        boolean first = true;

        while (c.moveToNext()) {
            if (!first) sb.append(",");
            first = false;

            String id = c.getString(c.getColumnIndexOrThrow("_id"));
            String from = c.getString(c.getColumnIndexOrThrow("address"));
            String body = c.getString(c.getColumnIndexOrThrow("body"));

            sb.append("{")
              .append("\"id\":\"").append(id).append("\",")
              .append("\"from\":\"").append(from.replace("\"","\\\"")).append("\",")
              .append("\"body\":\"").append(body.replace("\"","\\\"")).append("\"")
              .append("}");
        }
        c.close();
        sb.append("]"); // End JSON array

        final String safeMsg = sb.toString()
                                 .replace("\\", "\\\\")
                                 .replace("'", "\\'")
                                 .replace("\n", "\\n");

        webView.post(() -> webView.evaluateJavascript(
                "if(window.onReadSms) onReadSms(" + safeMsg + ");",
                null
        ));
    }

    /* =========================
       SEND DATA TO WEB
       ========================= */
    private void sendResultToWeb(String msg) {
        final String safeMsg = msg.replace("\\", "\\\\")
                                  .replace("'", "\\'")
                                  .replace("\n", "\\n");
        webView.post(() -> webView.evaluateJavascript(
                "if(window.showResult) showResult('" + safeMsg + "');",
                null
        ));
    }

    /* =========================
       SMS RECEIVER (foreground & notifications)
       ========================= */
    private void setupSmsReceiver() {
        smsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction() == null || !intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED"))
                    return;

                Intent serviceIntent = new Intent(context, SmsForegroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(serviceIntent);
                else
                    context.startService(serviceIntent);

                Object[] pdus = (Object[]) intent.getExtras().get("pdus");
                if (pdus == null) return;

                for (Object p : pdus) {
                    SmsMessage sms = SmsMessage.createFromPdu((byte[]) p);
                    String from = sms.getOriginatingAddress();
                    String msg = sms.getMessageBody();
                    showSmsNotification(context, from, msg);
                }
            }
        };
    }

    private void showSmsNotification(Context context, String from, String msg) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("sms_channel", "SMS Alert", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "sms_channel")
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("New SMS from " + from)
                .setContentText(msg)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    /* =========================
       FOREGROUND SERVICE
       ========================= */
    public static class SmsForegroundService extends Service {

        @Override
        public void onCreate() {
            super.onCreate();
            startForegroundServiceNotification();
        }

        private void startForegroundServiceNotification() {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(FOREGROUND_CHANNEL_ID, "Foreground Service", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }

            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
                    .setContentTitle("Real Time Data")
                    .setContentText("Erandix Monitoring")
                    .setSmallIcon(R.drawable.app_icon)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();

            startForeground(FOREGROUND_NOTIFICATION_ID, n);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

        @Nullable
        @Override
        public IBinder onBind(Intent intent) { return null; }
    }

    /* =========================
       BOOT RECEIVER
       ========================= */
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                Intent serviceIntent = new Intent(context, SmsForegroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(serviceIntent);
                else
                    context.startService(serviceIntent);
            }
        }
    }

    /* =========================
       ACTIVITY LIFECYCLE
       ========================= */
    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(smsReceiver, new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(smsReceiver);
    }

    private void startForegroundServiceIfNeeded() {
        Intent serviceIntent = new Intent(this, SmsForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ContextCompat.startForegroundService(this, serviceIntent);
        else
            startService(serviceIntent);
    }

    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.FOREGROUND_SERVICE
        }, REQUEST_ALL_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ALL_PERMISSIONS && pendingUSSDCode != null &&
            grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            executeUSSD(pendingUSSDCode);
            pendingUSSDCode = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack())
            webView.goBack();
        else
            super.onBackPressed();
    }
}
