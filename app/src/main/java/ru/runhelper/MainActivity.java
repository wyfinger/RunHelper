package ru.runhelper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 21;
    private EditText intervalInput;
    private Button actionButton;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("run", MODE_PRIVATE);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(44), dp(28), dp(24));
        root.setBackgroundColor(Color.rgb(247, 249, 246));

        TextView title = new TextView(this);
        title.setText("RunHelper");
        title.setTextSize(32);
        title.setTextColor(Color.rgb(30, 75, 52));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(60)));

        TextView caption = new TextView(this);
        caption.setText("Голосовые подсказки во время пробежки");
        caption.setTextSize(16);
        caption.setTextColor(Color.DKGRAY);
        caption.setGravity(Gravity.CENTER);
        root.addView(caption, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView label = new TextView(this);
        label.setText("Интервал подсказок, минут");
        label.setTextSize(16);
        label.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, dp(44));
        labelParams.topMargin = dp(48);
        root.addView(label, labelParams);

        intervalInput = new EditText(this);
        intervalInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        intervalInput.setSingleLine(true);
        intervalInput.setText(String.valueOf(prefs.getInt("interval", 1)));
        intervalInput.setTextSize(22);
        intervalInput.setSelectAllOnFocus(true);
        root.addView(intervalInput, new LinearLayout.LayoutParams(-1, dp(56)));

        actionButton = new Button(this);
        actionButton.setTextSize(18);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(58));
        buttonParams.topMargin = dp(38);
        root.addView(actionButton, buttonParams);
        actionButton.setOnClickListener(v -> onAction());

        TextView hint = new TextView(this);
        hint.setText("Во время бега приложение использует GPS и продолжает работу с выключенным экраном.");
        hint.setTextSize(14);
        hint.setTextColor(Color.GRAY);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(22);
        root.addView(hint, hintParams);
        setContentView(root);
        syncButton();
    }

    @Override protected void onResume() { super.onResume(); if (actionButton != null) syncButton(); }

    private void onAction() {
        if (prefs.getBoolean("running", false)) {
            startService(new Intent(this, RunService.class).setAction(RunService.ACTION_STOP));
            prefs.edit().putBoolean("running", false).apply();
            syncButton();
            return;
        }
        int minutes;
        try { minutes = Integer.parseInt(intervalInput.getText().toString().trim()); }
        catch (Exception e) { minutes = 1; }
        if (minutes < 1 || minutes > 120) {
            intervalInput.setError("Введите число от 1 до 120");
            return;
        }
        prefs.edit().putInt("interval", minutes).apply();
        if (!hasLocationPermission() || (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            requestNeededPermissions();
            return;
        }
        startRun(minutes);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(permissions.toArray(new String[0]), PERMISSIONS_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSIONS_REQUEST) {
            if (hasLocationPermission()) startRun(prefs.getInt("interval", 1));
            else android.widget.Toast.makeText(this, "Для отслеживания пробежки нужен доступ к геолокации", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void startRun(int minutes) {
        Intent intent = new Intent(this, RunService.class).setAction(RunService.ACTION_START).putExtra("interval", minutes);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        prefs.edit().putBoolean("running", true).apply();
        syncButton();
    }

    private void syncButton() {
        boolean running = prefs.getBoolean("running", false);
        actionButton.setText(running ? "Закончить" : "Начать");
        intervalInput.setEnabled(!running);
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
}

