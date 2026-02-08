package com.example.ussdwebview;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import androidx.core.app.NotificationCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    private static final int REQUEST_ALL_PERMISSIONS = 1;
    private static final String CHANNEL_ID = "foreground_channel";
    private static final int NOTIFICATION_ID = 101;

    private String pendingUSSDCode;

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

        // Request permissions and start foreground notification
        requestAllPermissions();
        startForegroundNotification();

        // Listen for SMS received
        registerReceiver(smsReceiver, new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));

        // Listen for general notifications (optional)
        registerReceiver(notificationReceiver, new IntentFilter("com.example.ussdwebview.NOTIFICATION_LISTENER"));
    }

    /* =======================
       JavaScript Bridge
       ======================= */
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

    /* =======================
       USSD
       ======================= */
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
            sendResultToWeb("Telephony service unavailable");
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

    /* =======================
       SEND SMS
       ======================= */
    private void executeSendSMS(String phone, String message) {
        if (!hasSmsPermissions()) {
            requestAllPermissions();
            return;
        }

        try {
            SmsManager.getDefault().sendTextMessage(phone, null, message, null, null);
            sendResultToWeb("SMS sent successfully");
        } catch (Exception e) {
            sendResultToWeb("SMS failed: " + e.getMessage());
        }
    }

    /* =======================
       READ SMS
       ======================= */
    private void executeReadSMS() {
        if (!hasSmsPermissions()) {
            requestAllPermissions();
            return;
        }

        Uri inboxUri = Uri.parse("content://sms/inbox");
        Cursor cursor = getContentResolver().query(
                inboxUri,
                null,
                null,
                null,
                "date DESC"
        );

        if (cursor == null) {
            sendResultToWeb("Failed to read SMS");
            return;
        }

        StringBuilder smsJson = new StringBuilder("[");
        boolean first = true;

        while (cursor.moveToNext()) {
            String id = cursor.getString(cursor.getColumnIndexOrThrow("_id"));
            String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
            String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));

            if (!first) smsJson.append(",");
            first = false;

            smsJson.append("{")
                    .append("\"id\":\"").append(id).append("\",")
                    .append("\"from\":\"").append(address).append("\",")
                    .append("\"body\":\"").append(body).append("\"")
                    .append("}");
        }

        cursor.close();
        smsJson.append("]");

        webView.post(() -> webView.evaluateJavascript(
                "if(window.onReadSms) onReadSms(" + smsJson + ");",
                null
        ));
    }

    /* =======================
       Permissions
       ======================= */
    private boolean hasSmsPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAllPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                hasSmsPermissions();
    }

    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_BOOT_COMPLETED
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
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
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

    /* =======================
       WebView callback
       ======================= */
    private void sendResultToWeb(String message) {
        String safeMessage = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n");

        webView.post(() -> webView.evaluateJavascript(
                "showResult('" + safeMessage + "')",
                null
        ));
    }

    /* =======================
       Foreground Notification
       ======================= */
    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Real Time Data")
                .setContentText("Erandix Monitoring")
                .setSmallIcon(R.drawable.app_icon)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

        nm.notify(NOTIFICATION_ID, notification);
    }

    /* =======================
       SMS Receiver
       ======================= */
    private final BroadcastReceiver smsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            // On SMS receive, reload all SMS in WebView
            executeReadSMS();

            // Optional: bring app to foreground
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
        }
    };

    /* =======================
       Notification Listener Receiver
       ======================= */
    private final BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Example: Reload WebView on notification
            webView.post(() -> webView.reload());
        }
    };

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /* =======================
       Boot Receiver (Auto Start)
       ======================= */
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                Intent i = new Intent(context, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(smsReceiver);
            unregisterReceiver(notificationReceiver);
        } catch (Exception ignored) {}
    }
}
