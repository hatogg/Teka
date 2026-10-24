package ua.teka.files;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Доступ з комп’ютера: HTTP-сервер у локальній мережі, поки ввімкнено. Працює як служба
 * переднього плану зі сповіщенням (адреса й код), тож не зупиняється, коли екран вимкнено.
 */
public class ServerService extends Service {
    static final String ACTION_STOP = "ua.teka.files.PC_STOP";
    static final String CHANNEL = "pc";
    static final int NOTE_ID = 7;

    interface Listener { void onChange(String json); }

    static volatile Listener listener;
    static volatile PcServer server;
    static volatile String pin, ip;
    static volatile int port;
    static volatile String lastEvent = "";

    PowerManager.WakeLock wake;
    WifiManager.WifiLock wifi;

    static String status() {
        try {
            JSONObject o = new JSONObject().put("on", server != null);
            if (server != null) {
                o.put("pin", pin).put("port", port).put("ip", ip == null ? "" : ip)
                    .put("url", ip == null ? "" : "http://" + ip + ":" + port).put("event", lastEvent);
            } else {
                o.put("ip", localIp() == null ? "" : localIp());
            }
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    static void changed() {
        Listener l = listener;
        if (l != null) l.onChange(status());
    }

    /** IPv4-адреса в локальній мережі: спершу Wi-Fi, потім точка доступу, потім інші. */
    static String localIp() {
        String best = null;
        int bestRank = 99;
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifs) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                int rank = n.startsWith("wlan") ? 0 : (n.startsWith("ap") || n.startsWith("swlan")) ? 1 : (n.startsWith("eth") || n.startsWith("usb") || n.startsWith("rndis")) ? 2 : (n.startsWith("rmnet") || n.startsWith("ccmni")) ? 9 : 5;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (!a.isSiteLocalAddress() && rank >= 9) continue;
                    if (rank < bestRank) { best = a.getHostAddress(); bestRank = rank; }
                }
            }
        } catch (Exception ignored) {}
        return best;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server == null) {
            try {
                List<Fs.Vol> vols = Fs.vols(this);
                File[] roots = new File[vols.size()];
                String[] names = new String[vols.size()];
                for (int k = 0; k < vols.size(); k++) { roots[k] = vols.get(k).dir; names[k] = vols.get(k).name; }
                String p = intent != null ? intent.getStringExtra("pin") : null;
                pin = p != null && p.matches("\\d{4,8}") ? p : PcServer.newPin();
                PcServer s = new PcServer(roots, names, pin);
                s.listener = text -> { lastEvent = text; changed(); };
                int want = intent != null ? intent.getIntExtra("port", 8080) : 8080;
                try {
                    port = s.start(want);
                } catch (Exception e) {
                    port = s.start(0);
                }
                server = s;
                ip = localIp();
            } catch (Exception e) {
                lastEvent = "Не вдалося запустити: " + e.getMessage();
                server = null;
                changed();
                stopSelf();
                return START_NOT_STICKY;
            }
            acquireLocks();
        }
        Notification n = note();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTE_ID, n);
        changed();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "teka:pc");
            wake.acquire(4 * 3600_000L);
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "teka:pc");
            wifi.acquire();
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    Notification note() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Доступ з ПК", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Показується, поки файли доступні з комп’ютера");
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        int imm = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class).setAction("ua.teka.files.PC").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), imm);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ServerService.class).setAction(ACTION_STOP), imm);
        String url = ip == null ? "немає Wi-Fi" : "http://" + ip + ":" + port;
        return b.setSmallIcon(R.drawable.ic_note)
            .setContentTitle("Доступ з ПК увімкнено")
            .setContentText(url + " · код " + pin)
            .setStyle(new Notification.BigTextStyle().bigText("Відкрийте в браузері на комп’ютері:\n" + url + "\nКод входу: " + pin))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Вимкнути", stop).build())
            .build();
    }

    @Override
    public void onDestroy() {
        PcServer s = server;
        server = null;
        if (s != null) s.stop();
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        lastEvent = "";
        changed();
        super.onDestroy();
    }
}
