package me.rkfg.pfe;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.frostwire.jlibtorrent.AddTorrentParams;
import com.frostwire.jlibtorrent.AlertListener;
import com.frostwire.jlibtorrent.AnnounceEntry;
import com.frostwire.jlibtorrent.Entry;
import com.frostwire.jlibtorrent.ErrorCode;
import com.frostwire.jlibtorrent.LibTorrent;
import com.frostwire.jlibtorrent.Session;
import com.frostwire.jlibtorrent.SettingsPack;
import com.frostwire.jlibtorrent.Sha1Hash;
import com.frostwire.jlibtorrent.TorrentAlertAdapter;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.alerts.AlertType;
import com.frostwire.jlibtorrent.alerts.BlockFinishedAlert;
import com.frostwire.jlibtorrent.alerts.FileErrorAlert;
import com.frostwire.jlibtorrent.alerts.StatsAlert;
import com.frostwire.jlibtorrent.alerts.TorrentErrorAlert;
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert;
import com.frostwire.jlibtorrent.swig.create_torrent;
import com.frostwire.jlibtorrent.swig.error_code;
import com.frostwire.jlibtorrent.swig.file_storage;
import com.frostwire.jlibtorrent.swig.libtorrent;
import com.frostwire.jlibtorrent.swig.set_piece_hashes_listener;
import com.frostwire.jlibtorrent.swig.settings_pack.bool_types;
import com.frostwire.jlibtorrent.swig.settings_pack.int_types;

public enum PFECore {

    INSTANCE;

    private Logger log = LoggerFactory.getLogger(getClass());

    private Session session;

    private SettingsStorage settingsStorage;

    private PFECore() {
        loadLibrary();
    }

    public void init(SettingsStorage settingsStorage) {
        this.settingsStorage = settingsStorage;
        initSession();
    }

    private void loadLibrary() {
        String arch = System.getProperty("os.arch");
        if (arch.equals("x86") || arch.equals("i386")) {
            arch = "x86";
        }
        if (arch.equals("amd64")) {
            arch = "x86_64";
        }
        String os = System.getProperty("os.name").toLowerCase();
        String ext = "so"; // Linux/Android
        if (os.contains("mac")) {
            ext = "dylib";
        }
        if (os.contains("windows")) {
            ext = "dll";
        }

        String path = "/lib/" + arch + "/libjlibtorrent." + ext;

        Path inputPath = Paths.get(path);

        if (!inputPath.isAbsolute()) {
            throw new IllegalArgumentException("The path has to be absolute, but found: " + inputPath);
        }

        String fileNameFull = inputPath.getFileName().toString();
        int dotIndex = fileNameFull.indexOf('.');
        if (dotIndex < 0 || dotIndex >= fileNameFull.length() - 1) {
            throw new IllegalArgumentException("The path has to end with a file name and extension, but found: " + fileNameFull);
        }

        String fileName = fileNameFull.substring(0, dotIndex);
        String extension = fileNameFull.substring(dotIndex);

        Path target;
        try {
            target = Files.createTempFile(fileName, extension);
        } catch (IOException e) {
            throw new PFEException("Can't create temporary file for library: " + fileName + "." + extension);
        }
        File targetFile = target.toFile();
        targetFile.deleteOnExit();

        try (InputStream source = Main.class.getResourceAsStream(inputPath.toString())) {
            if (source == null) {
                throw new FileNotFoundException("File " + inputPath + " was not found in classpath.");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new PFEException("Can't open library resource " + inputPath);
        }
        System.setProperty("jlibtorrent.jni.path", target.toString());
        log.debug("Using libtorrent version: {}", LibTorrent.version());
    }

    private class LastTorrentActivity {
        public long upload = 0;
        public long timestamp = System.nanoTime();
    }

    private void initSession() {
        SettingsPack settingsPack = new SettingsPack();
        settingsPack.setBoolean(bool_types.enable_dht.swigValue(), settingsStorage.isDht());
        log.debug("Trackers: {}", settingsStorage.getTrackers());
        session = new Session(settingsPack, true);
        settingsPack.setInteger(int_types.alert_queue_size.swigValue(), 1000000);
        // stats don't get sent after a minute of torrent inactivity or so, we should manually check them to handle timeouts
        Timer seedingTimeoutTimer = new Timer("Stats checker", true);
        seedingTimeoutTimer.schedule(new TimerTask() {

            // torrent_handle.id => last activity data
            Map<Long, LastTorrentActivity> activities = new HashMap<>();

            @Override
            public void run() {
                for (TorrentHandle torrentHandle : session.getTorrents()) {
                    if (torrentHandle.getStatus().isFinished()) {
                        long transferred = torrentHandle.getStatus().getTotalPayloadUpload();
                        long id = torrentHandle.getSwig().id();
                        LastTorrentActivity lastTorrentActivity = activities.get(id);
                        if (lastTorrentActivity == null) {
                            lastTorrentActivity = new LastTorrentActivity();
                            activities.put(id, lastTorrentActivity);
                        }
                        if (transferred > lastTorrentActivity.upload) {
                            lastTorrentActivity.upload = transferred;
                            lastTorrentActivity.timestamp = System.nanoTime();
                        } else {
                            log.debug("Last timestamp: {} now: {} timeout: {}", lastTorrentActivity.timestamp, System.nanoTime(),
                                    settingsStorage.getSeedingTimeout());
                            if (System.nanoTime() - lastTorrentActivity.timestamp > settingsStorage.getSeedingTimeout()) {
                                log.warn("Seeding '{}' timeout.", torrentHandle.getTorrentInfo().getName());
                                torrentHandle.pause();
                            }
                        }
                    }
                }
            }
        }, 1000, 1000);
    }

    public TorrentHandle addTorrent(String base32hash, String saveToPath) {
        AddTorrentParams torrentParams = AddTorrentParams.createInstance();
        char[] hash = Hex.encodeHex(new Base32().decode(base32hash));
        torrentParams.infoHash(new Sha1Hash(String.valueOf(hash)));
        torrentParams.savePath(saveToPath);
        final TorrentHandle handle = session.addTorrent(torrentParams, new ErrorCode(new error_code()));
        setTrackers(handle);
        TorrentAlertAdapter listener = new TorrentAlertAdapter(handle) {

            Map<Long, Integer> progresses = new HashMap<>();

            /*
             * should not block for too long so only log percentage when it changes. Else the alert manager queue gets overflown and we lose
             * the torrent_finished alert
             */

            @Override
            public void blockFinished(BlockFinishedAlert alert) {
                int p = (int) (alert.getHandle().getStatus().getProgress() * 100);
                long id = alert.getHandle().getSwig().id();
                Integer progress = progresses.get(id);
                if (progress == null) {
                    progress = 0;
                }
                if (p > progress) {
                    log.debug("Progress: {} for torrent {}", p, alert.torrentName());
                    progresses.put(id, p);
                }
            }

            @Override
            public void torrentError(TorrentErrorAlert alert) {
                log.error("Torrent {} failed", alert.torrentName());
            }

            @Override
            public void torrentFinished(TorrentFinishedAlert alert) {
                log.info("Torrent {} finished.", alert.torrentName());
            }

            @Override
            public void fileError(FileErrorAlert alert) {
                log.error("File error when working on torrent '{}' {}: {}", alert.torrentName(), alert.filename(), alert.error().message());
            }

            @Override
            public int[] types() {
                return new int[] { AlertType.BLOCK_FINISHED.getSwig(), AlertType.TORRENT_ERROR.getSwig(),
                        AlertType.TORRENT_FINISHED.getSwig(), AlertType.FILE_ERROR.getSwig() };
            }

        };
        addListener(listener);
        addSeedListener(handle);
        return handle;
    }

    public TorrentHandle share(String path) {
        file_storage fs = new file_storage();
        File file = new File(path).getAbsoluteFile();
        libtorrent.add_files(fs, file.getAbsolutePath());
        final create_torrent ct = new create_torrent(fs);
        set_piece_hashes_listener hashListener = new set_piece_hashes_listener() {

            int lastPercent = 0;

            @Override
            public void progress(int i) {
                int p = i * 100 / ct.num_pieces();
                if (p > lastPercent) {
                    log.info("Hashed {}%", p);
                    lastPercent = p;
                }
            }
        };
        error_code ec = new error_code();
        libtorrent.set_piece_hashes_ex(UUID.randomUUID().toString(), ct, file.getParent(), ec, hashListener);
        if (ec.value() != 0) {
            throw new IllegalStateException(ec.message());
        }
        Entry e = new Entry(ct.generate());
        TorrentInfo torrentInfo = TorrentInfo.bdecode(e.bencode());
        final TorrentHandle handle = session.addTorrent(torrentInfo, file.getParentFile(), null, null);
        setTrackers(handle);
        log.info("Seeding {}", handle.getTorrentInfo().getName());
        try {
            log.info("Link: {}", new Base32().encodeAsString(Hex.decodeHex(torrentInfo.getInfoHash().toHex().toCharArray())));
        } catch (DecoderException e1) {
            e1.printStackTrace();
        }
        addSeedListener(handle);
        return handle;
    }

    private void setTrackers(TorrentHandle th) {
        for (String tracker : settingsStorage.getTrackers()) {
            th.addTracker(new AnnounceEntry(tracker));
        }
    }

    public void addListener(AlertListener listener) {
        session.addListener(listener);
    }

    private void addSeedListener(TorrentHandle th) {
        session.addListener(new TorrentAlertAdapter(th) {

            @Override
            public void stats(StatsAlert alert) {
                TorrentHandle torrentHandle = alert.getHandle();
                if (torrentHandle.getStatus().isFinished()) {
                    long transferred = torrentHandle.getStatus().getTotalPayloadUpload();
                    long totalSize = torrentHandle.getTorrentInfo().getTotalSize();
                    if (transferred > totalSize * settingsStorage.getSeedRatio()) {
                        log.info("Seeding '{}' complete after reaching {} ratio.", torrentHandle.getTorrentInfo().getName(),
                                Math.round(transferred * 100 / totalSize) / 100.0);
                        torrentHandle.pause();
                    }
                }
            }

            @Override
            public int[] types() {
                return new int[] { AlertType.STATS.getSwig() };
            }
        });
    }

    public void removeTorrent(TorrentHandle th) {
        session.removeTorrent(th);
    }

}
