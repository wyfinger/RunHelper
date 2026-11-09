package ru.runhelper;

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

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;

public class RunService extends Service implements LocationListener, TextToSpeech.OnInitListener {
    public static final String ACTION_START = "ru.runhelper.START";
    public static final String ACTION_STOP = "ru.runhelper.STOP";
    private static final String CHANNEL = "run_status";
    private static final int NOTIFICATION_ID = 7;
    private static final long MAX_SPEED_SAMPLE_GAP_MS = 5_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<SpeedSample> speedSamples = new ArrayDeque<>();
    private LocationManager locationManager;
    private TextToSpeech tts;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private long startedAt;
    private long nextAnnouncement;
    private long intervalMs = 60_000L;
    private double speedMps;
    private boolean hasSpeed;
    private boolean running;
    private boolean ttsReady;

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
        startForeground(NOTIFICATION_ID, buildNotification("Ожидание GPS…"));
        if (!running) beginRun();
        return START_NOT_STICKY;
    }

    private void beginRun() {
        running = true;
        startedAt = SystemClock.elapsedRealtime();
        nextAnnouncement = startedAt + intervalMs;
        speedSamples.clear();
        speedMps = 0;
        hasSpeed = false;
        getSharedPreferences("run", MODE_PRIVATE).edit().putBoolean("running", true).apply();
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
                speedSamples.addLast(new SpeedSample(sampleTime, measured));
                trimSpeedSamples(sampleTime - intervalMs);
            }
        }
        updateNotification();
    }

    private void announce() {
        if (!ttsReady || tts == null) return;
        long minutes = Math.max(1, (SystemClock.elapsedRealtime() - startedAt) / 60_000L);
        String message = numberWords(minutes) + " " + minuteForm(minutes) + ". Темп — ";
        int averagePace = averagePaceSecondsPerKm();
        message += averagePace >= 0 ? paceSpeech(averagePace) : "пока не рассчитан";
        requestDuck();
        if (Build.VERSION.SDK_INT >= 21) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "runhelper_" + SystemClock.elapsedRealtime());
        else tts.speak(message, TextToSpeech.QUEUE_FLUSH, null);
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
        return numberWords(paceMinutes) + " " + minuteForm(paceMinutes) + ", " +
                numberWords(paceSeconds) + " " + secondForm(paceSeconds);
    }

    private void requestDuck() {
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setWillPauseWhenDucked(false).build();
            audioManager.requestAudioFocus(focusRequest);
        } else audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        handler.postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
            else audioManager.abandonAudioFocus(null);
        }, 12_000L);
    }

    private String paceText() {
        if (!hasSpeed || speedMps <= 0) return "Ожидание GPS";
        int sec = (int)Math.round(1000.0 / speedMps);
        return String.format(Locale.getDefault(), "%d:%02d мин/км", sec / 60, sec % 60);
    }

    private void updateNotification() {
        if (!running) return;
        long elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000L;
        String content = String.format(Locale.getDefault(), "%d:%02d · %s", elapsed / 60, elapsed % 60, paceText());
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification(content));
    }

    private Notification buildNotification(String content) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, RunService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("RunHelper · пробежка")
                .setContentText(content).setContentIntent(contentIntent).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "Закончить", stopIntent);
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Пробежка", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void stopRun() {
        running = false;
        handler.removeCallbacks(ticker);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) { }
        getSharedPreferences("run", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int result = tts.setLanguage(new Locale("ru", "RU"));
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
        }
    }

    private static String minuteForm(long n) {
        long a = Math.abs(n) % 100;
        if (a >= 11 && a <= 14) return "минут";
        switch ((int)(Math.abs(n) % 10)) { case 1: return "минута"; case 2: case 3: case 4: return "минуты"; default: return "минут"; }
    }
    private static String secondForm(long n) {
        long a = Math.abs(n) % 100;
        if (a >= 11 && a <= 14) return "секунд";
        switch ((int)(Math.abs(n) % 10)) { case 1: return "секунда"; case 2: case 3: case 4: return "секунды"; default: return "секунд"; }
    }
    private static String numberWords(long n) {
        String[] ones = {"ноль", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
        String[] teens = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
        String[] tens = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят"};
        String[] hundreds = {"", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"};
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
    @Override public void onProviderDisabled(String provider) { hasSpeed = false; speedSamples.clear(); updateNotification(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { running = false; handler.removeCallbacks(ticker); try { locationManager.removeUpdates(this); } catch (Exception ignored) { } if (tts != null) tts.shutdown(); super.onDestroy(); }
}

