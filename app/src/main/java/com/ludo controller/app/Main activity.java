package com.ludocontroller.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_BLUETOOTH_PERMS = 101;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;

    private final List<BluetoothDevice> pairedDeviceList = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Spinner spinnerDevices;
    private TextView tvConnectionStatus;
    private TextView tvSelectedPlayer;
    private TextView tvGameStatus;

    private String selectedColor = "RED";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinnerDevices = findViewById(R.id.spinnerDevices);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvSelectedPlayer = findViewById(R.id.tvSelectedPlayer);
        tvGameStatus = findViewById(R.id.tvGameStatus);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            tvGameStatus.setText("ℹ️ Bluetooth is not supported on this device.");
            return;
        }

        setupColorButtons();
        setupDiceButtons();

        findViewById(R.id.btnRefresh).setOnClickListener(v -> checkPermissionsAndLoadDevices());
        findViewById(R.id.btnConnect).setOnClickListener(v -> connectToSelectedDevice());
        findViewById(R.id.btnDisconnect).setOnClickListener(v -> disconnectBluetooth());

        checkPermissionsAndLoadDevices();
    }

    private void setupColorButtons() {
        findViewById(R.id.btnRed).setOnClickListener(v -> selectPlayer("RED"));
        findViewById(R.id.btnGreen).setOnClickListener(v -> selectPlayer("GREEN"));
        findViewById(R.id.btnBlue).setOnClickListener(v -> selectPlayer("BLUE"));
        findViewById(R.id.btnYellow).setOnClickListener(v -> selectPlayer("YELLOW"));
    }

    private void selectPlayer(String color) {
        selectedColor = color;
        tvSelectedPlayer.setText("Selected Player: " + selectedColor);
        tvGameStatus.setText("ℹ️ Player set to " + selectedColor + ". Pick a dice value.");
    }

    private void setupDiceButtons() {
        int[] buttonIds = {
                R.id.btnDice1, R.id.btnDice2, R.id.btnDice3,
                R.id.btnDice4, R.id.btnDice5, R.id.btnDice6
        };

        for (int i = 0; i < buttonIds.length; i++) {
            final int diceValue = i + 1;
            Button btn = findViewById(buttonIds[i]);
            btn.setOnClickListener(v -> sendDiceCommand(selectedColor, diceValue));
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void checkPermissionsAndLoadDevices() {
        if (!hasBluetoothPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN},
                        REQ_BLUETOOTH_PERMS
                );
            }
            return;
        }
        loadPairedDevices();
    }

    @SuppressLint("MissingPermission")
    private void loadPairedDevices() {
        if (!bluetoothAdapter.isEnabled()) {
            tvGameStatus.setText("ℹ️ Please turn ON Bluetooth first.");
            return;
        }

        pairedDeviceList.clear();
        List<String> deviceNames = new ArrayList<>();

        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        if (bondedDevices != null && !bondedDevices.isEmpty()) {
            for (BluetoothDevice device : bondedDevices) {
                pairedDeviceList.add(device);
                deviceNames.add(device.getName() + " (" + device.getAddress() + ")");
            }
        } else {
            deviceNames.add("No paired devices found");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, deviceNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(adapter);
    }

    @SuppressLint("MissingPermission")
    private void connectToSelectedDevice() {
        if (!hasBluetoothPermissions()) {
            checkPermissionsAndLoadDevices();
            return;
        }
        if (pairedDeviceList.isEmpty()) {
            Toast.makeText(this, "Pair the main Ludo device in Bluetooth settings first!", Toast.LENGTH_SHORT).show();
            return;
        }

        int pos = spinnerDevices.getSelectedItemPosition();
        if (pos < 0 || pos >= pairedDeviceList.size()) return;

        BluetoothDevice targetDevice = pairedDeviceList.get(pos);
        tvConnectionStatus.setText("📶 Status: Connecting...");
        tvConnectionStatus.setTextColor(Color.parseColor("#FFD54F"));

        executor.execute(() -> {
            try {
                closeSocketQuietly();
                bluetoothAdapter.cancelDiscovery();
                bluetoothSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();

                runOnUiThread(() -> {
                    tvConnectionStatus.setText("📶 Status: Connected to " + targetDevice.getName());
                    tvConnectionStatus.setTextColor(Color.parseColor("#69F0AE"));
                    tvGameStatus.setText("ℹ️ Connected! Select colour and tap a dice number.");
                });
            } catch (IOException e) {
                closeSocketQuietly();
                runOnUiThread(() -> {
                    tvConnectionStatus.setText("📶 Status: Connection Failed");
                    tvConnectionStatus.setTextColor(Color.parseColor("#FF5252"));
                    tvGameStatus.setText("ℹ️ Error: Make sure the compatible host app is listening.");
                });
            }
        });
    }

    private void sendDiceCommand(String color, int value) {
        String command = "DICE|" + color + "|" + value;

        if (bluetoothSocket == null || !bluetoothSocket.isConnected() || outputStream == null) {
            tvGameStatus.setText("⚠️ Not connected! (Command preview: " + command + ")");
            return;
        }

        executor.execute(() -> {
            try {
                String payload = command + "\n";
                outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                runOnUiThread(() ->
                        tvGameStatus.setText("✅ Sent command: " + command)
                );
            } catch (IOException e) {
                closeSocketQuietly();
                runOnUiThread(() -> {
                    tvConnectionStatus.setText("📶 Status: Disconnected");
                    tvConnectionStatus.setTextColor(Color.parseColor("#FF5252"));
                    tvGameStatus.setText("❌ Transmission failed. Connection lost.");
                });
            }
        });
    }

    private void disconnectBluetooth() {
        closeSocketQuietly();
        tvConnectionStatus.setText("📶 Status: Disconnected");
        tvConnectionStatus.setTextColor(Color.parseColor("#FF5252"));
        tvGameStatus.setText("ℹ️ Disconnected from host device.");
    }

    private void closeSocketQuietly() {
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
                bluetoothSocket = null;
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLUETOOTH_PERMS && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadPairedDevices();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeSocketQuietly();
        executor.shutdown();
    }
}
