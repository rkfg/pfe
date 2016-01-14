package me.rkfg.pfe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.frostwire.jlibtorrent.AddTorrentParams;
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
import com.frostwire.jlibtorrent.alerts.BlockFinishedAlert;
import com.frostwire.jlibtorrent.alerts.StatsAlert;
import com.frostwire.jlibtorrent.alerts.StatsAlert.StatsChannel;
import com.frostwire.jlibtorrent.alerts.TorrentErrorAlert;
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert;
import com.frostwire.jlibtorrent.swig.create_torrent;
import com.frostwire.jlibtorrent.swig.error_code;
import com.frostwire.jlibtorrent.swig.file_storage;
import com.frostwire.jlibtorrent.swig.libtorrent;
import com.frostwire.jlibtorrent.swig.set_piece_hashes_listener;
import com.frostwire.jlibtorrent.swig.settings_pack.bool_types;
import com.frostwire.jlibtorrent.swig.settings_pack.int_types;

public class Main {

    private Properties properties;
    private String[] trackers;
    private Boolean dht;
    private Logger log = LoggerFactory.getLogger(getClass());
    private Session session;

    public static void main(String[] args) throws InterruptedException, IOException {
        new Main().run(args);
    }

    private void run(String[] args) throws InterruptedException, IOException {
        if (args.length < 2) {
            showUsage();
            return;
        }
        extractLibrary();
        log.info("Using libtorrent version: {}", LibTorrent.version());
        loadSettings();
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
        initSession();

        // final TorrentHandle th = s.addTorrent(torrentFile, torrentFile.getParentFile());
        AddTorrentParams torrentParams = AddTorrentParams.createInstance();
        char[] hash = Hex.encodeHex(new Base32().decode(params[0]));
        torrentParams.infoHash(new Sha1Hash(String.valueOf(hash)));
        torrentParams.savePath(params[1]);
        TorrentHandle th = session.addTorrent(torrentParams, new ErrorCode(new error_code()));
        for (String tracker : trackers) {
            th.addTracker(new AnnounceEntry(tracker));
        }

        final CountDownLatch signal = new CountDownLatch(1);

        TorrentAlertAdapter listener = new TorrentAlertAdapter(th) {
            @Override
            public void blockFinished(BlockFinishedAlert alert) {
                int p = (int) (alert.getHandle().getStatus().getProgress() * 100);
                log.debug("Progress: {} for torrent {}", p, alert.torrentName());
            }

            @Override
            public void torrentFinished(TorrentFinishedAlert alert) {
                log.info("Torrent {} finished", alert.torrentName());
                signal.countDown();
            }

            @Override
            public void torrentError(TorrentErrorAlert alert) {
                log.error("Torrent {} failed", alert.torrentName());
            }

        };
        session.addListener(listener);

        th.resume();

        signal.await();
    }

    private void initSession() {
        SettingsPack settingsPack = new SettingsPack();
        dht = Boolean.valueOf(properties.getProperty("enable_dht", "false"));
        settingsPack.setBoolean(bool_types.enable_dht.swigValue(), dht);
        trackers = properties.getProperty("trackers", "").split("\\|");
        log.debug("Trackers: {}", (Object[]) trackers);
        settingsPack.setInteger(int_types.alert_queue_size.swigValue(), 10000);
        session = new Session(settingsPack, true);
    }

    private void share(String[] params) throws InterruptedException {
        file_storage fs = new file_storage();
        File file = new File(params[0]).getAbsoluteFile();
        libtorrent.add_files(fs, file.getAbsolutePath());
        final create_torrent ct = new create_torrent(fs);
        set_piece_hashes_listener hashListener = new set_piece_hashes_listener() {
            @Override
            public void progress(int i) {
                log.info("Hashed {}%", i * 100 / ct.num_pieces());
            }
        };
        error_code ec = new error_code();
        libtorrent.set_piece_hashes_ex(UUID.randomUUID().toString(), ct, file.getParent(), ec, hashListener);
        if (ec.value() != 0) {
            throw new IllegalStateException(ec.message());
        }
        Entry e = new Entry(ct.generate());
        initSession();
        TorrentInfo torrentInfo = TorrentInfo.bdecode(e.bencode());
        TorrentHandle handle = session.addTorrent(torrentInfo, file.getParentFile(), null, null);
        final CountDownLatch signal = new CountDownLatch(1);
        final int timeout = Integer.valueOf(properties.getProperty("seeding_timeout", "60"));
        session.addListener(new TorrentAlertAdapter(handle) {

            int lastUpload = 0;
            long lastTimestamp = System.nanoTime();

            @Override
            public void stats(StatsAlert alert) {
                int transferred = alert.transferred(StatsChannel.UPLOAD_PAYLOAD.getIndex());
                if (transferred > lastUpload) {
                    lastUpload = transferred;
                    lastTimestamp = System.nanoTime();
                } else {
                    if (System.nanoTime() - lastTimestamp > TimeUnit.SECONDS.toNanos(timeout)) {
                        log.warn("Seeding timeout.");
                        signal.countDown();
                    }
                }
                long totalSize = alert.getHandle().getTorrentInfo().getTotalSize();
                if (transferred > totalSize * 2) {
                    log.info("Seeding complete.");
                    signal.countDown();
                }
            }
        });
        log.info("Seeding {}", handle.getTorrentInfo().getName());
        try {
            log.info("Link: {}", new Base32().encodeAsString(Hex.decodeHex(torrentInfo.getInfoHash().toHex().toCharArray())));
        } catch (DecoderException e1) {
            e1.printStackTrace();
        }
        handle.resume();
        signal.await();
    }

    private void loadSettings() {
        properties = new Properties();
        try {
            properties.load(new FileInputStream(new File("settings.ini")));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void extractLibrary() throws IOException {
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

        Path target = Files.createTempFile(fileName, extension);
        File targetFile = target.toFile();
        targetFile.deleteOnExit();

        try (InputStream source = Main.class.getResourceAsStream(inputPath.toString())) {
            if (source == null) {
                throw new FileNotFoundException("File " + inputPath + " was not found in classpath.");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }

        System.setProperty("jlibtorrent.jni.path", target.toString());

    }

}
