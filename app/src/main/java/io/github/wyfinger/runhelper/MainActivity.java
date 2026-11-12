package io.github.wyfinger.runhelper;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 21;
    private EditText intervalInput;
    private Button actionButton;
    private LinearLayout infoContainer;
    private ScrollView logScroll;
    private TextView logText;
    private SharedPreferences prefs;
    private boolean receiverRegistered;

    private final BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            syncScreen(true);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("run", MODE_PRIVATE);
        boolean serviceActive = isRunServiceActive();
        if (!prefs.getBoolean("running", false) || !serviceActive) {
            if (serviceActive) stopService(new Intent(this, RunService.class));
            prefs.edit().putBoolean("running", false).remove(RunService.PREF_SPEECH_LOG).apply();
        }
        buildScreen();
    }

    @SuppressWarnings("deprecation")
    private boolean isRunServiceActive() {
        ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
        for (ActivityManager.RunningServiceInfo service : services) {
            if (RunService.class.getName().equals(service.service.getClassName())) return true;
        }
        return false;
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(44), dp(28), dp(24));
        root.setBackgroundColor(Color.rgb(247, 249, 246));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(32);
        title.setTextColor(Color.rgb(30, 75, 52));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(60)));

        TextView caption = new TextView(this);
        caption.setText(R.string.run_caption);
        caption.setTextSize(16);
        caption.setTextColor(Color.DKGRAY);
        caption.setGravity(Gravity.CENTER);
        root.addView(caption, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView label = new TextView(this);
        label.setText(R.string.interval_label);
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

        infoContainer = new LinearLayout(this);
        infoContainer.setOrientation(LinearLayout.VERTICAL);
        infoContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        contentParams.topMargin = dp(18);
        root.addView(infoContainer, contentParams);

        TextView gpsHint = new TextView(this);
        gpsHint.setText(R.string.gps_hint);
        gpsHint.setTextSize(14);
        gpsHint.setTextColor(Color.GRAY);
        gpsHint.setGravity(Gravity.CENTER);
        infoContainer.addView(gpsHint, new LinearLayout.LayoutParams(-1, -2));

        TextView privacyNote = new TextView(this);
        privacyNote.setText(R.string.privacy_note);
        privacyNote.setTextSize(14);
        privacyNote.setTextColor(Color.rgb(65, 75, 68));
        privacyNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(-1, -2);
        privacyParams.topMargin = dp(18);
        infoContainer.addView(privacyNote, privacyParams);

        TextView repositoryLink = new TextView(this);
        String repositoryUrl = getString(R.string.repository_url);
        SpannableString linkedUrl = new SpannableString(repositoryUrl);
        linkedUrl.setSpan(new URLSpan(repositoryUrl), 0, linkedUrl.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        repositoryLink.setText(linkedUrl);
        repositoryLink.setTextSize(14);
        repositoryLink.setGravity(Gravity.CENTER);
        repositoryLink.setMovementMethod(LinkMovementMethod.getInstance());
        repositoryLink.setLinkTextColor(Color.rgb(30, 95, 64));
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(-1, -2);
        linkParams.topMargin = dp(8);
        infoContainer.addView(repositoryLink, linkParams);

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackgroundColor(Color.WHITE);
        logScroll.setVisibility(View.GONE);
        logText = new TextView(this);
        logText.setTextSize(16);
        logText.setTextColor(Color.rgb(35, 45, 38));
        logText.setPadding(dp(16), dp(14), dp(16), dp(14));
        logText.setLineSpacing(0f, 1f);
        logText.setTextIsSelectable(true);
        logScroll.addView(logText, new ScrollView.LayoutParams(-1, -2));
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        syncScreen(false);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(RunService.ACTION_UI_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(uiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(uiReceiver, filter);
        receiverRegistered = true;
        syncScreen(true);
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(uiReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (actionButton != null) syncScreen(true);
    }

    private void onAction() {
        if (prefs.getBoolean("running", false)) {
            actionButton.setEnabled(false);
            startService(new Intent(this, RunService.class).setAction(RunService.ACTION_STOP));
            return;
        }
        int minutes;
        try { minutes = Integer.parseInt(intervalInput.getText().toString().trim()); }
        catch (Exception e) { minutes = 1; }
        if (minutes < 1 || minutes > 120) {
            intervalInput.setError(getString(R.string.interval_error));
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
            else android.widget.Toast.makeText(this, R.string.location_permission_required, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void startRun(int minutes) {
        prefs.edit().putBoolean("running", true).putString(RunService.PREF_SPEECH_LOG, "").apply();
        Intent intent = new Intent(this, RunService.class).setAction(RunService.ACTION_START).putExtra("interval", minutes);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        syncScreen(false);
    }

    private void syncScreen(boolean scrollToBottom) {
        if (actionButton == null) return;
        boolean running = prefs.getBoolean("running", false);
        String log = prefs.getString(RunService.PREF_SPEECH_LOG, "");
        boolean showLog = running || !log.isEmpty();
        actionButton.setText(running ? R.string.button_finish : R.string.button_start);
        actionButton.setEnabled(true);
        intervalInput.setEnabled(!running);
        infoContainer.setVisibility(showLog ? View.GONE : View.VISIBLE);
        logScroll.setVisibility(showLog ? View.VISIBLE : View.GONE);
        if (showLog) {
            logText.setText(log);
            if (scrollToBottom) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
}

