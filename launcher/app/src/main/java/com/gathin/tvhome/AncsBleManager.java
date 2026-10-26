package com.gathin.tvhome;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Handles Bluetooth LE connection with iPhone via Apple Notification Center Service (ANCS).
 * Supports Android 14 onCharacteristicChanged callback and displays a Liquid Glass call HUD.
 */
public final class AncsBleManager {
    private static final String TAG = "AncsBleManager";

    public static final UUID ANCS_SERVICE_UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0");
    public static final UUID CLIENT_CONFIG_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static volatile AncsBleManager instance;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGatt connectedGatt;
    private boolean isAdvertising;

    private BluetoothGattCharacteristic notifSourceChar;
    private BluetoothGattCharacteristic controlPointChar;
    private BluetoothGattCharacteristic dataSourceChar;

    private final Queue<BluetoothGattDescriptor> descriptorQueue = new ArrayDeque<>();
    private boolean isWritingDescriptor = false;

    private int lastCallUid = -1;
    private int lastMediaUid = -1;
    private String lastMediaTitle = "";

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                Log.i(TAG, "Bond state changed: " + (device != null ? device.getAddress() : "null") + " -> " + bondState);
                if (device != null && bondState == BluetoothDevice.BOND_BONDED) {
                    connectToDevice(device);
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    Log.i(TAG, "ACL Connected: " + device.getAddress() + " (" + device.getName() + ")");
                    connectToDevice(device);
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
                if (state == BluetoothAdapter.STATE_ON) {
                    startAdvertising();
                    connectBondedDevices();
                }
            }
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isAdvertising = true;
            Log.i(TAG, "ANCS BLE advertising started successfully");
        }

        @Override
        public void onStartFailure(int errorCode) {
            isAdvertising = false;
            Log.w(TAG, "ANCS BLE advertising failed: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice dev = gatt.getDevice();
            Log.i(TAG, "GATT state change for " + (dev != null ? dev.getAddress() : "device")
                    + ": status=" + status + ", newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedGatt = gatt;
                handler.postDelayed(() -> {
                    try {
                        Log.i(TAG, "Triggering discoverServices on " + dev.getAddress());
                        gatt.discoverServices();
                    } catch (SecurityException e) {
                        Log.e(TAG, "SecurityException discovering services", e);
                    }
                }, 800);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedGatt == gatt) {
                    connectedGatt = null;
                }
                notifSourceChar = null;
                controlPointChar = null;
                dataSourceChar = null;
                descriptorQueue.clear();
                isWritingDescriptor = false;
                CallBannerManager.getInstance(context).dismiss();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.i(TAG, "onServicesDiscovered: status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return;
            }

            BluetoothGattService ancsService = gatt.getService(ANCS_SERVICE_UUID);
            if (ancsService == null) {
                for (BluetoothGattService s : gatt.getServices()) {
                    if (s.getUuid().toString().equalsIgnoreCase(ANCS_SERVICE_UUID.toString())) {
                        ancsService = s;
                        break;
                    }
                }
            }

            if (ancsService == null) {
                Log.w(TAG, "ANCS service not found on device " + gatt.getDevice().getAddress());
                return;
            }

            for (BluetoothGattCharacteristic c : ancsService.getCharacteristics()) {
                String u = c.getUuid().toString().toLowerCase();
                Log.i(TAG, "ANCS Characteristic: " + u + " (props=" + c.getProperties() + ")");
                if (u.startsWith("9fbf")) {
                    notifSourceChar = c;
                } else if (u.startsWith("69d1")) {
                    controlPointChar = c;
                } else if (u.startsWith("22ea")) {
                    dataSourceChar = c;
                }
            }

            Log.i(TAG, "Resolved ANCS characteristics: NotifSource=" + (notifSourceChar != null)
                    + ", ControlPoint=" + (controlPointChar != null)
                    + ", DataSource=" + (dataSourceChar != null));

            descriptorQueue.clear();
            if (notifSourceChar != null) queueSubscription(gatt, notifSourceChar);
            if (dataSourceChar != null) queueSubscription(gatt, dataSourceChar);
            processDescriptorQueue(gatt);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "onDescriptorWrite: char=" + descriptor.getCharacteristic().getUuid() + ", status=" + status);
            isWritingDescriptor = false;
            processDescriptorQueue(gatt);
        }

        // Android 13+ (API 33+) overload
        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            handleCharacteristicPacket(gatt, characteristic, value);
        }

        // Legacy overload
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            handleCharacteristicPacket(gatt, characteristic, value);
        }
    };

    private void handleCharacteristicPacket(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {
        if (data == null || data.length == 0) {
            Log.w(TAG, "Characteristic changed with empty data: " + characteristic.getUuid());
            return;
        }

        String uuidStr = characteristic.getUuid().toString().toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X ", b));
        Log.i(TAG, "PACKET from " + uuidStr + " len=" + data.length + " hex=" + sb.toString().trim());

        if (uuidStr.startsWith("9fbf")) {
            handleNotificationSource(gatt, data);
        } else if (uuidStr.startsWith("22ea")) {
            handleDataSource(data);
        }
    }

    private AncsBleManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }
    }

    public static AncsBleManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AncsBleManager.class) {
                if (instance == null) {
                    instance = new AncsBleManager(context);
                }
            }
        }
        return instance;
    }

    public void start() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not available or disabled");
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(bluetoothReceiver, filter);
        }

        startAdvertising();
        connectBondedDevices();
    }

    private void startAdvertising() {
        if (bluetoothAdapter == null || isAdvertising) return;
        try {
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return;

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(ANCS_SERVICE_UUID))
                    .build();

            AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();

            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        } catch (Exception e) {
            Log.w(TAG, "Error starting advertising", e);
        }
    }

    public void connectBondedDevices() {
        if (bluetoothAdapter == null) return;
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) {
                    String name = device.getName();
                    Log.i(TAG, "Found bonded device: " + name + " (" + device.getAddress() + ")");
                    if (name != null && (name.toLowerCase().contains("iphone") || name.toLowerCase().contains("apple"))) {
                        connectToDevice(device);
                    }
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException listing bonded devices", e);
        }
    }

    public void connectToDevice(BluetoothDevice device) {
        if (device == null) return;
        try {
            if (connectedGatt != null && connectedGatt.getDevice().getAddress().equals(device.getAddress())) {
                Log.i(TAG, "Already connected to " + device.getAddress());
                return;
            }

            Log.i(TAG, "Connecting GATT to " + device.getAddress() + " (" + device.getName() + ")");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                device.connectGatt(context, true, gattCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error connecting to device", e);
        }
    }

    private void queueSubscription(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                descriptorQueue.add(descriptor);
                Log.i(TAG, "Queued CCCD write for " + characteristic.getUuid());
            } else {
                Log.w(TAG, "CCCD descriptor not found on " + characteristic.getUuid());
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception queueing subscription", e);
        }
    }

    private synchronized void processDescriptorQueue(BluetoothGatt gatt) {
        if (isWritingDescriptor || descriptorQueue.isEmpty()) return;
        BluetoothGattDescriptor descriptor = descriptorQueue.poll();
        if (descriptor != null) {
            try {
                isWritingDescriptor = true;
                Log.i(TAG, "Writing CCCD descriptor for " + descriptor.getCharacteristic().getUuid());
                gatt.writeDescriptor(descriptor);
            } catch (Exception e) {
                Log.e(TAG, "Exception writing descriptor", e);
                isWritingDescriptor = false;
            }
        }
    }

    private void handleNotificationSource(BluetoothGatt gatt, byte[] data) {
        if (data.length < 8) return;

        int eventId = data[0] & 0xFF;         // 0 = Added, 1 = Modified, 2 = Removed
        int categoryId = data[2] & 0xFF;      // 1 = Incoming Call
        int uid = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8) | ((data[6] & 0xFF) << 16) | ((data[7] & 0xFF) << 24);

        Log.i(TAG, "ANCS Event: eventId=" + eventId + ", categoryId=" + categoryId + ", uid=" + uid);

        // Check if this is an Incoming Call (CategoryID == 1)
        if (categoryId == 1) {
            lastCallUid = uid;
            if (eventId == 0 || eventId == 1) { // Call Ringing / Started
                Log.i(TAG, "INCOMING CALL DETECTED! UID=" + uid);
                CallBannerManager.getInstance(context).showCall("Incoming Call", "iPhone • Ringing...");
                fetchNotificationAttributes(gatt, uid);
            } else if (eventId == 2) { // Call Ended / Answered / Rejected
                Log.i(TAG, "CALL ENDED / DISMISSED: UID=" + uid);
                CallBannerManager.getInstance(context).dismiss();
            }
        } else if (categoryId == 11) { // CategoryIDEntertainment (Music/Audio playback from iPhone)
            lastMediaUid = uid;
            if (eventId == 0 || eventId == 1) {
                Log.i(TAG, "ENTERTAINMENT/MUSIC NOTIFICATION DETECTED! UID=" + uid);
                fetchNotificationAttributes(gatt, uid);
            } else if (eventId == 2) {
                Log.i(TAG, "ENTERTAINMENT NOTIFICATION REMOVED: UID=" + uid);
                NowPlayingManager.getInstance().clearRemoteTrack();
            }
        }
    }

    private void fetchNotificationAttributes(BluetoothGatt gatt, int notificationUid) {
        try {
            if (controlPointChar == null) {
                Log.w(TAG, "Cannot fetch attributes: controlPointChar is null");
                return;
            }

            byte[] command = new byte[] {
                    0x00,
                    (byte) (notificationUid & 0xFF),
                    (byte) ((notificationUid >> 8) & 0xFF),
                    (byte) ((notificationUid >> 16) & 0xFF),
                    (byte) ((notificationUid >> 24) & 0xFF),
                    0x01,       // Attribute ID: 1 (Title / Caller Name)
                    0x40, 0x00, // Max length (64 bytes)
                    0x03,       // Attribute ID: 3 (Message / Number)
                    0x40, 0x00
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(controlPointChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                controlPointChar.setValue(command);
                gatt.writeCharacteristic(controlPointChar);
            }
            Log.i(TAG, "Requested Title attribute for UID " + notificationUid);
        } catch (Exception e) {
            Log.e(TAG, "Exception fetching attributes", e);
        }
    }

    private void handleDataSource(byte[] data) {
        if (data.length < 8) return;

        int commandId = data[0] & 0xFF;
        int uid = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8) | ((data[3] & 0xFF) << 16) | ((data[4] & 0xFF) << 24);
        int attributeId = data[5] & 0xFF;
        int length = (data[6] & 0xFF) | ((data[7] & 0xFF) << 8);

        if (commandId == 0 && length > 0 && data.length >= 8 + length) {
            String text = new String(data, 8, length, StandardCharsets.UTF_8).trim();
            if (!TextUtils.isEmpty(text)) {
                if (uid == lastCallUid && attributeId == 1) {
                    Log.i(TAG, "Parsed caller name: " + text);
                    CallBannerManager.getInstance(context).showCall(text, "iPhone • Ringing...");
                } else if (uid == lastMediaUid) {
                    if (attributeId == 1) {
                        lastMediaTitle = text;
                        Log.i(TAG, "Parsed media title: " + text);
                        NowPlayingManager.getInstance().updateRemoteTrack(new MediaTrack(
                                text, "iPhone", "Apple Music", true, false, null, null
                        ));
                    } else if (attributeId == 3) {
                        Log.i(TAG, "Parsed media artist: " + text);
                        String currentTitle = !TextUtils.isEmpty(lastMediaTitle) ? lastMediaTitle : "Now Playing";
                        NowPlayingManager.getInstance().updateRemoteTrack(new MediaTrack(
                                currentTitle, text, "Apple Music", true, false, null, null
                        ));
                    }
                }
            }
        }
    }

    public void stop() {
        try {
            if (advertiser != null && isAdvertising) {
                advertiser.stopAdvertising(advertiseCallback);
                isAdvertising = false;
            }
            if (connectedGatt != null) {
                connectedGatt.close();
                connectedGatt = null;
            }
            context.unregisterReceiver(bluetoothReceiver);
        } catch (Exception ignored) {}
    }
}
