package me.rkfg.pfe;

import java.io.IOException;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.frostwire.jlibtorrent.TorrentAlertAdapter;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.alerts.AlertType;
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert;
import com.frostwire.jlibtorrent.alerts.TorrentPausedAlert;

public class Main {

    private Logger log = LoggerFactory.getLogger(getClass());
    private PFECore pfeCore = PFECore.INSTANCE;
    private SettingsStorage settingsStorage;

    public static void main(String[] args) throws InterruptedException, IOException {
        new Main().run(args);
    }

    private void run(String[] args) throws InterruptedException, IOException {
        if (args.length < 2) {
            showUsage();
            return;
        }
        settingsStorage = new SettingsStorage();
        pfeCore.init(settingsStorage);
        String[] params = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0].toLowerCase()) {
        case "share":
            share(params);
            break;
        case "get":
            if (args.length < 3) {
                showUsage();
                return;
            }
            get(params);
            break;
        default:
            showUsage();
            return;
        }
    }

    private void showUsage() {
        System.out.println("Usage: share file.ext\nOR\nget somehashedvalue target_dir");
    }

    private void get(String[] params) throws InterruptedException {
        TorrentHandle handle = pfeCore.addTorrent(params[0], params[1]);
        final CountDownLatch signal = new CountDownLatch(1);
        pfeCore.addListener(new TorrentAlertAdapter(handle) {
            @Override
            public void torrentFinished(TorrentFinishedAlert alert) {
                boolean seed = settingsStorage.isSeedAfterDownload();
                log.info("Torrent {} is complete, {}.", alert.torrentName(), seed ? "seeding" : "stopping");
                if (!seed) {
                    stopTorrent(alert.getHandle());
                }
            }

            @Override
            public void torrentPaused(TorrentPausedAlert alert) {
                if (alert.getHandle().getStatus().isFinished()) {
                    log.info("Torrent {} is seeded enough, stopping.", alert.torrentName());
                    if (settingsStorage.isSeedAfterDownload()) {
                        stopTorrent(alert.getHandle());
                    }
                }
            }

            private void stopTorrent(TorrentHandle th) {
                pfeCore.removeTorrent(th);
                signal.countDown();
            }

            @Override
            public int[] types() {
                return new int[] { AlertType.TORRENT_FINISHED.getSwig(), AlertType.TORRENT_PAUSED.getSwig() };
            }
        });
        handle.resume();
        signal.await();
    }

    private void share(String[] params) throws InterruptedException {
        final CountDownLatch signal = new CountDownLatch(1);
        final TorrentHandle handle = pfeCore.share(params[0]);
        Timer timerFinish = new Timer("Stats checker", true);
        timerFinish.schedule(new TimerTask() {

            @Override
            public void run() {
                if (handle.getStatus().isPaused()) {
                    log.info("Torrent {} fully shared", handle.getName());
                    signal.countDown();
                }
            }
        }, 1000, 1000);
        handle.resume();
        signal.await();
    }

}
