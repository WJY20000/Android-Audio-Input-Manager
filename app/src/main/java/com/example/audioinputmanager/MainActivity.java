package com.example.audioinputmanager;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Button setPreferenceButton, revertButton, refreshButton;
    private Spinner deviceSpinner;

    private AudioManager audioManager;
    private List<AudioDeviceInfo> audioDevices = new ArrayList<>();
    private ArrayAdapter<String> spinnerAdapter;

    // FIX (round 5, #15): remember that we already asked for notifications so
    // a denied user is NOT re-prompted on every cold start (nag prompts are
    // platform-banned UX on Android 13+).
    private static final String PREFS_UI = "ui_prefs";
    private static final String KEY_NOTIF_PERM_ASKED = "notif_perm_asked";

    // Modern way to handle permission requests
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean recordAudioGranted = result.getOrDefault(Manifest.permission.RECORD_AUDIO, false);
                Boolean bluetoothConnectGranted = true; // Assume true for older APIs

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothConnectGranted = result.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, false);
                }

                // NOTE: POST_NOTIFICATIONS is optional and intentionally NOT gated on.
                if (recordAudioGranted && bluetoothConnectGranted) {
                    Toast.makeText(this, "Permissions granted. Ready.", Toast.LENGTH_SHORT).show();
                    updateDeviceList();
                } else {
                    Toast.makeText(this, "Permissions are required to list devices and run the service.", Toast.LENGTH_LONG).show();
                }
            });

    // FIX (round 4): POST_NOTIFICATIONS was bundled into requestPermissions(),
    // which ONLY runs when a core permission is missing. If RECORD_AUDIO and
    // BLUETOOTH_CONNECT were already granted (previous session / system
    // settings), the flow took the "checkPermissions() == true" branch and the
    // notification permission was NEVER requested — leaving the foreground
    // service notification silently hidden on Android 13+. Use a dedicated
    // launcher so it can be asked independently.
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                String msg = granted ? "Notifications enabled."
                        : "Notifications hidden; service still runs normally.";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Install the splash screen. MUST be before super.onCreate() and setContentView()
        SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Find views by their new IDs
        setPreferenceButton = findViewById(R.id.set_preference_button);
        revertButton = findViewById(R.id.revert_button);
        refreshButton = findViewById(R.id.refresh_button);
        deviceSpinner = findViewById(R.id.device_spinner);

        // Setup the adapter for our spinner
        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(spinnerAdapter);

        // Check for permissions on startup
        if (checkPermissions()) {
            updateDeviceList();
            // Core flow is satisfied; now optionally ask for notifications —
            // this is the branch where the permission used to be skipped.
            requestNotificationPermissionIfNeeded();
        } else {
            requestPermissions();
        }

        refreshButton.setOnClickListener(v -> updateDeviceList());

        setPreferenceButton.setOnClickListener(v -> {
            // FIX: re-check permissions here — the user may have revoked them
            // in system settings after the initial grant.
            if (!checkPermissions()) {
                Toast.makeText(this, "Permissions required before setting a preference.", Toast.LENGTH_SHORT).show();
                requestPermissions();
                return;
            }
            int selectedPosition = deviceSpinner.getSelectedItemPosition();
            if (selectedPosition < 0 || selectedPosition >= audioDevices.size()) {
                Toast.makeText(this, "No device selected or available.", Toast.LENGTH_SHORT).show();
                return;
            }
            AudioDeviceInfo selectedDevice = audioDevices.get(selectedPosition);
            startAudioService(selectedDevice);
        });

        // The "Revert to Default" button stops the service, whose onDestroy()
        // restores system defaults. FIX: only claim success if it was running.
        revertButton.setOnClickListener(v -> {
            if (AudioService.isRunning) {
                stopAudioService();
            } else {
                Toast.makeText(this, "No active preference to revert.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateDeviceList() {
        if (!checkPermissions()) {
            Toast.makeText(this, "Permissions needed to scan for devices.", Toast.LENGTH_SHORT).show();
            // FIX: don't leave a stale list on screen when we cannot scan.
            audioDevices.clear();
            spinnerAdapter.clear();
            spinnerAdapter.notifyDataSetChanged();
            return;
        }

        audioDevices.clear();
        List<String> deviceNames = new ArrayList<>();

        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (devices.length == 0) {
            deviceNames.add("No input devices found");
        } else {
            for (AudioDeviceInfo device : devices) {
                // NOTE: TYPE_BLE_HEADSET (LE Audio, added in API 31, value 26) is
                // how modern earbuds appear on Android 14/15 — WITHOUT it they
                // would be invisible in this list. The constant is a static final
                // int inlined at compile time, so it is safe to reference on API 30.
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                        device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    audioDevices.add(device);
                    String deviceName = device.getProductName().toString() + " (" + getDeviceTypeName(device.getType()) + ")";
                    deviceNames.add(deviceName);
                }
            }
            // FIX: all devices were filtered out — still give visible feedback.
            if (deviceNames.isEmpty()) {
                deviceNames.add("No input devices found");
            }
        }
        spinnerAdapter.clear();
        spinnerAdapter.addAll(deviceNames);
        spinnerAdapter.notifyDataSetChanged();
        Toast.makeText(this, "Device list updated.", Toast.LENGTH_SHORT).show();
    }

    private String getDeviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Internal Mic";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "Bluetooth LE";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired Headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB Audio";
            default:
                return "Other";
        }
    }

    private void startAudioService(AudioDeviceInfo device) {
        Intent serviceIntent = new Intent(this, AudioService.class);
        serviceIntent.putExtra("DEVICE_TYPE", device.getType());
        // FIX: pass the device ID so the service can target THIS specific
        // device via setCommunicationDevice() on Android 12+.
        serviceIntent.putExtra("DEVICE_ID", device.getId());
        serviceIntent.putExtra("DEVICE_NAME", device.getProductName().toString());

        ContextCompat.startForegroundService(this, serviceIntent);
        Toast.makeText(this, "Preference set to: " + getDeviceTypeName(device.getType()), Toast.LENGTH_SHORT).show();
    }

    private void stopAudioService() {
        Intent serviceIntent = new Intent(this, AudioService.class);
        stopService(serviceIntent);
        Toast.makeText(this, "Audio preference reverted to default", Toast.LENGTH_SHORT).show();
    }

    private boolean checkPermissions() {
        boolean hasRecordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean hasBluetoothConnect = true; // No permission needed before Android 12
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasBluetoothConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return hasRecordAudio && hasBluetoothConnect;
    }

    private void requestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        // FIX: needed so the foreground-service notification is visible on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
    }

    /** Optional one-shot request: must NOT run at the same time as requestPermissions().
     *  Asked at most ONCE per install; after that the user controls it via settings. */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences uiPrefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
        if (uiPrefs.getBoolean(KEY_NOTIF_PERM_ASKED, false)) return;
        uiPrefs.edit().putBoolean(KEY_NOTIF_PERM_ASKED, true).apply();
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }
}
