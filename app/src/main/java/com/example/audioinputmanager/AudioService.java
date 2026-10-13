package com.example.audioinputmanager;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Foreground service that maintains the user's audio-input preference.
 *
 * Fixes relative to the original version:
 *  1. Null-Intent safe: START_STICKY restarts no longer crash; the last
 *     preference is persisted in SharedPreferences and restored instead.
 *  2. Runtime permission checks before touching mic/Bluetooth APIs
 *     (prevents SecurityException on Android 12+/14+).
 *  3. Sets the audio mode (MODE_IN_COMMUNICATION) before routing so the
 *     Bluetooth SCO path actually takes effect on real devices.
 *  4. Uses AudioManager.setCommunicationDevice() on API 31+ so a SPECIFIC
 *     Bluetooth device can be targeted (legacy startBluetoothSco() is kept
 *     only as a pre-31 fallback).
 *  5. Registers an AudioDeviceCallback so the routing is RE-APPLIED whenever
 *     devices are added/removed — the preference now genuinely stays active.
 *  6. Restores the system default (MODE_NORMAL, SCO off, comm device cleared)
 *     in onDestroy().
 */
public class AudioService extends Service {

    private static final String TAG = "AudioService";
    private static final String CHANNEL_ID = "AudioServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private static final String PREFS_NAME = "audio_prefs";
    private static final String KEY_DEVICE_TYPE = "device_type";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";

    /** True while the service is alive; lets MainActivity give honest UI feedback. */
    public static volatile boolean isRunning = false;

    private AudioManager audioManager;
    private SharedPreferences prefs;

    private int preferredDeviceType = -1;
    private int preferredDeviceId = -1;
    private String preferredDeviceName = "Unknown Device";

    /** Re-applies the routing whenever the audio-device landscape changes. */
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            Log.d(TAG, "Audio devices added; re-applying routing.");
            applyRouting();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            Log.d(TAG, "Audio devices removed; re-applying routing.");
            applyRouting();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        createNotificationChannel();
        if (audioManager != null) {
            audioManager.registerAudioDeviceCallback(deviceCallback, null);
        }
        Log.d(TAG, "Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isRunning = true;

        // FIX #2: intent is NULL when the system restarts a STICKY service.
        // Restore the last preference from storage instead of crashing.
        if (intent == null) {
            Log.w(TAG, "Restarted with null Intent; restoring last preference.");
            restorePreference();
        } else {
            preferredDeviceType = intent.getIntExtra("DEVICE_TYPE", -1);
            preferredDeviceId = intent.getIntExtra("DEVICE_ID", -1);
            preferredDeviceName = intent.getStringExtra("DEVICE_NAME");
            if (preferredDeviceName == null) preferredDeviceName = "Unknown Device";
            persistPreference();
        }

        // No valid preference to apply (never set, or already reverted).
        if (preferredDeviceType == -1) {
            Log.w(TAG, "No preference to apply; stopping.");
            stopSelf();
            return START_NOT_STICKY;
        }

        // FIX #1/#3: a microphone-type foreground service on Android 14+
        // requires RECORD_AUDIO to be granted BEFORE startForeground().
        if (!hasRecordAudioPermission()) {
            Log.e(TAG, "RECORD_AUDIO not granted; cannot run foreground service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        showNotification(preferredDeviceName);

        if (audioManager == null) {
            // FIX (round 5, #17): do not linger as a foreground zombie with a
            // notification but no functionality — stop and clean up instead.
            Log.e(TAG, "AudioManager not available; stopping.");
            stopSelf();
            return START_NOT_STICKY;
        }

        applyRouting();
        return START_STICKY;
    }

    // ------------------------------------------------------------------ routing

    private void applyRouting() {
        if (audioManager == null || preferredDeviceType == -1) return;

        try {
            // LE Audio headsets (TYPE_BLE_HEADSET, API 31+) route through the
            // same setCommunicationDevice() path as classic SCO devices —
            // critical on Android 15, where most earbuds use LE Audio.
            if (preferredDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    preferredDeviceType == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                routeToBluetooth();
            } else {
                routeToNonBluetooth(preferredDeviceType, preferredDeviceId);
            }
        } catch (SecurityException se) {
            // FIX #3: BT calls can throw SecurityException if BLUETOOTH_CONNECT
            // was revoked while the service is running.
            Log.e(TAG, "Missing permission while routing audio", se);
            stopSelf();
        }
    }

    private void routeToBluetooth() {
        // FIX #4: SCO/communication routing only takes effect reliably when
        // the audio mode is MODE_IN_COMMUNICATION.
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        // FIX #5: on API 31+ target the SPECIFIC device the user picked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBluetoothConnectPermission()) {
            AudioDeviceInfo target = null;
            List<AudioDeviceInfo> commDevices = audioManager.getAvailableCommunicationDevices();
            for (AudioDeviceInfo d : commDevices) {
                if (d.getId() == preferredDeviceId) { target = d; break; }
            }
            // Fallback: any currently available Bluetooth device (SCO or LE Audio).
            if (target == null) {
                for (AudioDeviceInfo d : commDevices) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) { target = d; break; }
                }
            }
            if (target != null) {
                boolean ok = audioManager.setCommunicationDevice(target);
                Log.d(TAG, "setCommunicationDevice(" + target.getProductName() + ") -> " + ok);
                if (ok) return;
                Log.w(TAG, "setCommunicationDevice failed; trying legacy SCO path.");
            } else {
                Log.w(TAG, "No BT SCO communication device available; trying legacy SCO path.");
            }
        }

        // Legacy path (pre-API 31, or as a fallback.)
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);
        Log.d(TAG, "Legacy Bluetooth SCO activated.");
    }

    private void routeToNonBluetooth(int deviceType, int deviceId) {
        // On API 31+, try to target the exact wired/USB device; otherwise clear
        // any communication device so the system falls back to its default
        // priority (wired > USB > internal).
        boolean handled = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<AudioDeviceInfo> commDevices = audioManager.getAvailableCommunicationDevices();
            for (AudioDeviceInfo d : commDevices) {
                if (d.getId() == deviceId && d.getType() == deviceType) {
                    handled = audioManager.setCommunicationDevice(d);
                    Log.d(TAG, "setCommunicationDevice(" + d.getProductName() + ") -> " + handled);
                    break;
                }
            }
            if (!handled) {
                audioManager.clearCommunicationDevice();
            }
        }
        // Make sure Bluetooth SCO is OFF so the system uses wired/USB/internal.
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "No BLUETOOTH_CONNECT; skipping SCO teardown.");
        } else if (audioManager.isBluetoothScoOn()) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
        }
        audioManager.setMode(AudioManager.MODE_NORMAL);
        Log.d(TAG, "Non-Bluetooth routing applied (communication device cleared/set, SCO off).");
    }

    // ------------------------------------------------------------- notification

    private void showNotification(String deviceName) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Audio Manager Active")
                .setContentText("Managing audio preference for: " + deviceName)
                .setSmallIcon(R.drawable.ic_mic_logo)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build();

        // FIX #1: declare the microphone FGS type (manifest type is honored too,
        // but being explicit works across all API levels).
        // NOTE (round 4): FOREGROUND_SERVICE_TYPE_MICROPHONE (128) was only
        // added in API 30 — the guard must be R, not Q (passing an unknown type
        // bit to startForeground(id, n, type) on API 29 would be rejected).
        // minSdk is 30 anyway, so this branch in fact always executes.
        int fgsType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                : 0;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType);
        } catch (Exception e) {
            // e.g. ForegroundServiceStartNotAllowedException on API 31+
            Log.e(TAG, "Unable to start foreground service", e);
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Audio Service Channel",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    // ----------------------------------------------------------- prefs & perms

    private void persistPreference() {
        prefs.edit()
                .putInt(KEY_DEVICE_TYPE, preferredDeviceType)
                .putInt(KEY_DEVICE_ID, preferredDeviceId)
                .putString(KEY_DEVICE_NAME, preferredDeviceName)
                .apply();
    }

    private void restorePreference() {
        preferredDeviceType = prefs.getInt(KEY_DEVICE_TYPE, -1);
        preferredDeviceId = prefs.getInt(KEY_DEVICE_ID, -1);
        preferredDeviceName = prefs.getString(KEY_DEVICE_NAME, "Unknown Device");
    }

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        Log.d(TAG, "Service destroyed. Reverting audio routing to system default.");

        if (audioManager != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback);
                // FIX: clearCommunicationDevice() only needs MODIFY_AUDIO_SETTINGS
                // (install-time), NOT BLUETOOTH_CONNECT — gating it on the BT
                // permission would leak the wired/USB routing when the user
                // denied Bluetooth access. Clear unconditionally on API 31+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice();
                }
                // BT-specific teardown genuinely does need the BT permission.
                if (hasBluetoothConnectPermission() && audioManager.isBluetoothScoOn()) {
                    audioManager.setBluetoothScoOn(false);
                    audioManager.stopBluetoothSco();
                }
            } catch (SecurityException se) {
                Log.e(TAG, "Permission lost during cleanup", se);
            }
            // FIX #4-b: restore the audio mode we changed.
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }

        // Forget the stored preference so a system restart doesn't resurrect it.
        prefs.edit().remove(KEY_DEVICE_TYPE).remove(KEY_DEVICE_ID).remove(KEY_DEVICE_NAME).apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
