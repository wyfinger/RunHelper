package io.github.wyfinger.runhelper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;

public class RunService extends Service implements LocationListener, TextToSpeech.OnInitListener {
    public static final String ACTION_START = "io.github.wyfinger.runhelper.START";
    public static final String ACTION_STOP = "io.github.wyfinger.runhelper.STOP";
    public static final String ACTION_UI_UPDATE = "io.github.wyfinger.runhelper.UI_UPDATE";
    public static final String PREF_SPEECH_LOG = "speech_log";
    private static final String CHANNEL = "run_status";
    private static final int NOTIFICATION_ID = 7;
    private static final long MAX_SPEED_SAMPLE_GAP_MS = 5_000L;
    private static final long DUCK_TAIL_MS = 1_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<SpeedSample> speedSamples = new ArrayDeque<>();
    private LocationManager locationManager;
    private TextToSpeech tts;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private String activeUtteranceId;
    private long startedAt;
    private long nextAnnouncement;
    private long intervalMs = 60_000L;
    private double speedMps;
    private double totalDistanceMeters;
    private SpeedSample lastSessionSample;
    private boolean hasSpeed;
    private boolean running;
    private boolean ttsReady;
    private final Runnable duckFailsafe = new Runnable() {
        @Override public void run() { releaseDuck(activeUtteranceId); }
    };

    private static final class SpeedSample {
        final long timeMs;
        final double speedMps;
        SpeedSample(long timeMs, double speedMps) { this.timeMs = timeMs; this.speedMps = speedMps; }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (now >= nextAnnouncement) {
                announce();
                nextAnnouncement += intervalMs;
                while (nextAnnouncement <= now) nextAnnouncement += intervalMs;
            }
            updateNotification();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        tts = new TextToSpeech(this, this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopRun(); return START_NOT_STICKY; }
        if (intent != null && ACTION_START.equals(intent.getAction())) intervalMs = Math.max(1, intent.getIntExtra("interval", 1)) * 60_000L;
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.gps_waiting_ellipsis)));
        if (!running) beginRun();
        return START_NOT_STICKY;
    }

    private void beginRun() {
        running = true;
        startedAt = SystemClock.elapsedRealtime();
        nextAnnouncement = startedAt + intervalMs;
        speedSamples.clear();
        lastSessionSample = null;
        totalDistanceMeters = 0;
        speedMps = 0;
        hasSpeed = false;
        getSharedPreferences("run", MODE_PRIVATE).edit()
                .putBoolean("running", true).putString(PREF_SPEECH_LOG, "").apply();
        publishUiUpdate();
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            else if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this, Looper.getMainLooper());
        } catch (SecurityException ignored) { }
        handler.post(ticker);
    }

    @Override public void onLocationChanged(Location location) {
        if (location.hasSpeed() && location.getAccuracy() < 50f) {
            double measured = location.getSpeed(); // m/s
            if (measured >= 0.3 && measured < 12.0) {
                speedMps = hasSpeed ? speedMps * 0.65 + measured * 0.35 : measured;
                hasSpeed = true;
                long sampleTime = SystemClock.elapsedRealtime();
                SpeedSample sample = new SpeedSample(sampleTime, measured);
                if (lastSessionSample != null) {
                    long gapMs = sample.timeMs - lastSessionSample.timeMs;
                    if (gapMs > 0 && gapMs <= MAX_SPEED_SAMPLE_GAP_MS)
                        totalDistanceMeters += (lastSessionSample.speedMps + sample.speedMps) * 0.5 * (gapMs / 1000.0);
                }
                lastSessionSample = sample;
                speedSamples.addLast(sample);
                trimSpeedSamples(sampleTime - intervalMs);
            }
        }
        updateNotification();
    }

    private void announce() {
        if (!ttsReady || tts == null) return;
        long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
        long minutes = Math.max(1, elapsedMs / 60_000L);
        int averagePace = averagePaceSecondsPerKm();
        String pace = averagePace >= 0 ? paceSpeech(averagePace) : getString(R.string.pace_not_available_yet);
        String elapsedTime = elapsedTimeSpeech(minutes);
        String message = getString(R.string.speech_announcement, elapsedTime, pace);
        // The ticker checks once per second, so snap the logged time to the completed minute.
        long loggedElapsedMs = (elapsedMs / 60_000L) * 60_000L;
        String logEntry = getString(R.string.log_pace, formatClock(loggedElapsedMs / 1000L),
                averagePace >= 0 ? formatClock(averagePace) : getString(R.string.pace_not_available));
        String utteranceId = "runhelper_" + SystemClock.elapsedRealtime();
        appendSpeechLog(logEntry);
        requestDuck(utteranceId);
        if (tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR)
            releaseDuck(utteranceId);
    }

    private void trimSpeedSamples(long cutoffMs) {
        while (speedSamples.size() > 2) {
            Iterator<SpeedSample> iterator = speedSamples.iterator();
            iterator.next();
            SpeedSample second = iterator.next();
            if (second.timeMs < cutoffMs) speedSamples.removeFirst();
            else break;
        }
    }

    private int averagePaceSecondsPerKm() {
        if (speedSamples.size() < 2) return -1;
        long now = SystemClock.elapsedRealtime();
        long windowStart = now - intervalMs;
        double distanceMeters = 0;
        long coveredMs = 0;
        SpeedSample previous = null;
        for (SpeedSample sample : speedSamples) {
            if (previous != null) {
                long gapMs = sample.timeMs - previous.timeMs;
                long segmentStart = Math.max(windowStart, previous.timeMs);
                long segmentEnd = Math.min(now, sample.timeMs);
                long segmentMs = segmentEnd - segmentStart;
                if (gapMs > 0 && gapMs <= MAX_SPEED_SAMPLE_GAP_MS && segmentMs > 0) {
                    double startFraction = (segmentStart - previous.timeMs) / (double)gapMs;
                    double endFraction = (segmentEnd - previous.timeMs) / (double)gapMs;
                    double startSpeed = previous.speedMps + (sample.speedMps - previous.speedMps) * startFraction;
                    double endSpeed = previous.speedMps + (sample.speedMps - previous.speedMps) * endFraction;
                    distanceMeters += (startSpeed + endSpeed) * 0.5 * (segmentMs / 1000.0);
                    coveredMs += segmentMs;
                }
            }
            previous = sample;
        }
        if (coveredMs < intervalMs * 0.7 || distanceMeters <= 0) return -1;
        return (int)Math.round(coveredMs / distanceMeters);
    }

    private String paceSpeech(int totalSeconds) {
        int paceMinutes = totalSeconds / 60;
        int paceSeconds = totalSeconds % 60;
        return getString(R.string.speech_pace, numberWords(paceMinutes), minuteForm(paceMinutes),
                numberWords(paceSeconds), secondForm(paceSeconds));
    }

    private String elapsedTimeSpeech(long totalMinutes) {
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours == 0)
            return getString(R.string.speech_duration_minutes, numberWords(minutes), minuteForm(minutes));
        String hourCount = numberWords(hours, true);
        String hourUnit = getResources().getQuantityString(R.plurals.hour_unit, (int)hours);
        if (minutes == 0)
            return getString(R.string.speech_duration_hours, hourCount, hourUnit);
        return getString(R.string.speech_duration_hours_minutes, hourCount, hourUnit,
                numberWords(minutes), minuteForm(minutes));
    }

    private void requestDuck(String utteranceId) {
        activeUtteranceId = utteranceId;
        handler.removeCallbacks(duckFailsafe);
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setWillPauseWhenDucked(false).build();
            audioManager.requestAudioFocus(focusRequest);
        } else audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        handler.postDelayed(duckFailsafe, 6_000L);
    }

    private void releaseDuck(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(activeUtteranceId)) return;
        handler.removeCallbacks(duckFailsafe);
        activeUtteranceId = null;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        } else audioManager.abandonAudioFocus(null);
    }

    private void releaseDuckAfterSpeech(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(activeUtteranceId)) return;
        handler.removeCallbacks(duckFailsafe);
        handler.postDelayed(() -> releaseDuck(utteranceId), DUCK_TAIL_MS);
    }

    private void appendSpeechLog(String message) {
        if (!running) return;
        android.content.SharedPreferences prefs = getSharedPreferences("run", MODE_PRIVATE);
        String currentLog = prefs.getString(PREF_SPEECH_LOG, "");
        String updatedLog = currentLog.isEmpty() ? message : currentLog + "\n" + message;
        prefs.edit().putString(PREF_SPEECH_LOG, updatedLog).apply();
        publishUiUpdate();
    }

    private void publishUiUpdate() {
        sendBroadcast(new Intent(ACTION_UI_UPDATE).setPackage(getPackageName()));
    }

    private String buildRunSummary(long elapsedMs) {
        long totalSeconds = Math.max(0, Math.round(elapsedMs / 1000.0));
        String duration = formatClock(totalSeconds);
        String pace = totalDistanceMeters > 0
                ? formatClock(Math.round(elapsedMs / totalDistanceMeters))
                : getString(R.string.pace_not_available);
        return getString(R.string.run_summary, duration, pace);
    }

    private static String formatClock(long totalSeconds) {
        long totalMinutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (totalMinutes >= 60) {
            return String.format(Locale.US, "%02d:%02d:%02d", totalMinutes / 60, totalMinutes % 60, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", totalMinutes, seconds);
    }

    private String paceText() {
        if (!hasSpeed || speedMps <= 0) return getString(R.string.gps_waiting);
        int sec = (int)Math.round(1000.0 / speedMps);
        return String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
    }

    private void updateNotification() {
        if (!running) return;
        long elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000L;
        String content = String.format(Locale.getDefault(), getString(R.string.notification_content), elapsed / 60, elapsed % 60, paceText());
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification(content));
    }

    private Notification buildNotification(String content) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, RunService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(getString(R.string.notification_title))
                .setContentText(content).setContentIntent(contentIntent).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, getString(R.string.button_finish), stopIntent);
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void stopRun() {
        long elapsedMs = Math.max(0, SystemClock.elapsedRealtime() - startedAt);
        String summary = buildRunSummary(elapsedMs);
        running = false;
        handler.removeCallbacks(ticker);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) { }
        getSharedPreferences("run", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        releaseDuck(activeUtteranceId);
        android.content.SharedPreferences prefs = getSharedPreferences("run", MODE_PRIVATE);
        String currentLog = prefs.getString(PREF_SPEECH_LOG, "");
        String finalLog = currentLog.isEmpty() ? summary : currentLog + "\n" + summary;
        prefs.edit().putBoolean("running", false).putString(PREF_SPEECH_LOG, finalLog).apply();
        publishUiUpdate();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int result = tts.setLanguage(new Locale(getString(R.string.tts_language)));
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) { handler.post(() -> releaseDuckAfterSpeech(utteranceId)); }
                @Override public void onError(String utteranceId) { handler.post(() -> releaseDuckAfterSpeech(utteranceId)); }
            });
        }
    }

    private String minuteForm(long n) {
        return getResources().getQuantityString(R.plurals.minute_unit, (int)Math.abs(n));
    }
    private String secondForm(long n) {
        return getResources().getQuantityString(R.plurals.second_unit, (int)Math.abs(n));
    }
    private String numberWords(long n) {
        return numberWords(n, false);
    }
    private String numberWords(long n, boolean masculine) {
        String[] ones = getResources().getStringArray(masculine ? R.array.number_ones_masculine : R.array.number_ones);
        String[] teens = getResources().getStringArray(R.array.number_teens);
        String[] tens = getResources().getStringArray(R.array.number_tens);
        String[] hundreds = getResources().getStringArray(R.array.number_hundreds);
        if (n < 0 || n > 999) return Long.toString(n);
        StringBuilder words = new StringBuilder();
        if (n >= 100) {
            words.append(hundreds[(int)n / 100]);
            n %= 100;
        }
        if (n >= 10 && n < 20) {
            if (words.length() > 0) words.append(' ');
            words.append(teens[(int)n - 10]);
        } else {
            if (n >= 20) {
                if (words.length() > 0) words.append(' ');
                words.append(tens[(int)n / 10]);
                n %= 10;
            }
            if (n > 0 || words.length() == 0) {
                if (words.length() > 0) words.append(' ');
                words.append(ones[(int)n]);
            }
        }
        return words.toString();
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { hasSpeed = false; speedSamples.clear(); lastSessionSample = null; updateNotification(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { running = false; handler.removeCallbacks(ticker); try { locationManager.removeUpdates(this); } catch (Exception ignored) { } if (tts != null) tts.shutdown(); releaseDuck(activeUtteranceId); stopForeground(true); super.onDestroy(); }
}

