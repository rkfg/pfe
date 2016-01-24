package me.rkfg.pfe;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.alerts.AlertType;
import com.frostwire.jlibtorrent.alerts.FileErrorAlert;
import com.frostwire.jlibtorrent.alerts.TorrentErrorAlert;
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert;
import com.frostwire.jlibtorrent.swig.add_torrent_params;
import com.frostwire.jlibtorrent.swig.create_torrent;
import com.frostwire.jlibtorrent.swig.error_code;
import com.frostwire.jlibtorrent.swig.file_storage;
import com.frostwire.jlibtorrent.swig.libtorrent;
import com.frostwire.jlibtorrent.swig.set_piece_hashes_listener;
import com.frostwire.jlibtorrent.swig.settings_pack.bool_types;

public enum PFECore {

    INSTANCE;

    private Logger log = LoggerFactory.getLogger(getClass());

    private Session session;

    private SettingsStorage settingsStorage;

    private Set<PFEListener> listeners = new HashSet<PFEListener>();

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

        try (InputStream source = PFECore.class.getResourceAsStream(inputPath.toString())) {
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

    private void initSession() {
        log.info("Trackers: {}", settingsStorage.getTrackers());
        SettingsPack settingsPack = new SettingsPack();
        settingsPack.setBoolean(bool_types.enable_dht.swigValue(), settingsStorage.isDht());
        session = new Session(settingsPack, true);
        // stats don't get sent after a minute of torrent inactivity or so, we should manually check them to handle timeouts
        Timer torrentProgressTimer = new Timer("Progress checker", true);
        torrentProgressTimer.schedule(new TimerTask() {

            // torrent_handle.id => activity data
            Map<Long, TorrentActivity> activities = new HashMap<>();

            @Override
            public void run() {
                List<TorrentActivity> changed = new ArrayList<>();
                List<TorrentActivity> stopped = new ArrayList<>();
                for (TorrentHandle torrentHandle : session.getTorrents()) {
                    TorrentStatus status = torrentHandle.getStatus();
                    if (status.isPaused()) {
                        // paused torrents are skipped entirely
                        continue;
                    }
                    TorrentActivity activity = getActivity(torrentHandle);
                    int p = (int) (status.getProgress() * 100);
                    TorrentInfo torrentInfo = torrentHandle.getTorrentInfo();
                    if (p > activity.progress) {
                        // percentage changed
                        activity.name = torrentHandle.getName();
                        activity.progress = p;
                        if (torrentInfo != null) {
                            activity.size = torrentInfo.getTotalSize();
                        }
                        log.info("Progress: {} for torrent {}", p, activity.name);
                        changed.add(activity);
                    }
                    // handle seeding torrents
                    if (status.isFinished()) {
                        long transferred = status.getTotalPayloadUpload();
                        if (transferred > activity.upload) {
                            // any useful data was uploaded
                            activity.upload = transferred;
                            activity.timestamp = System.nanoTime();
                            long totalSize = torrentInfo.getTotalSize();
                            int seedRatio = settingsStorage.getSeedRatio();
                            if (seedRatio > 0 && transferred > totalSize * seedRatio) {
                                log.info("Seeding '{}' complete after reaching {} ratio.", torrentInfo.getName(),
                                        Math.round(transferred * 100 / totalSize) / 100.0);
                                stopped.add(activity);
                                torrentHandle.pause();
                            }
                        } else {
                            // nothing changed, check timeout
                            long seedingTimeout = settingsStorage.getSeedingTimeout();
                            log.debug("Last timestamp: {} now: {} timeout: {}", activity.timestamp, System.nanoTime(), seedingTimeout);
                            if (seedingTimeout > 0 && System.nanoTime() - activity.timestamp > seedingTimeout) {
                                log.warn("Seeding '{}' timeout.", torrentInfo.getName());
                                stopped.add(activity);
                                torrentHandle.pause();
                            }
                        }
                    } else {
                        activity.timestamp = System.nanoTime();
                    }
                    if (changed.size() > 0) {
                        for (PFEListener pfeListener : listeners) {
                            pfeListener.torrentProgress(changed);
                        }
                    }
                    if (stopped.size() > 0) {
                        for (PFEListener pfeListener : listeners) {
                            pfeListener.torrentStopped(stopped);
                        }
                    }
                }
            }

            private TorrentActivity getActivity(TorrentHandle torrentHandle) {
                long id = torrentHandle.getSwig().id();
                TorrentActivity activity = activities.get(id);
                if (activity == null) {
                    try {
                        activity = new TorrentActivity(getHash(torrentHandle));
                        activities.put(id, activity);
                    } catch (DecoderException e) {
                        e.printStackTrace();
                    }
                }
                return activity;
            }
        }, 1000, 1000);
    }

    public static Sha1Hash base32ToSha1(String base32hash) {
        char[] hash = Hex.encodeHex(new Base32().decode(base32hash));
        return new Sha1Hash(String.valueOf(hash));
    }

    public TorrentHandle addTorrent(String base32hash, String saveToPath) {
        AddTorrentParams torrentParams = AddTorrentParams.createInstance();
        torrentParams.infoHash(base32ToSha1(base32hash));
        torrentParams.savePath(saveToPath);
        final TorrentHandle handle = session.addTorrent(torrentParams, new ErrorCode(new error_code()));
        setTrackers(handle);
        TorrentAlertAdapter listener = new TorrentAlertAdapter(handle) {

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
                return new int[] { AlertType.TORRENT_ERROR.getSwig(), AlertType.TORRENT_FINISHED.getSwig(), AlertType.FILE_ERROR.getSwig() };
            }

        };
        addListener(listener);
        return handle;
    }

    private enum TorrentType {
        MULTIFILE, ONEDIR
    }

    /**
     * Share files and/or directories
     * 
     * @param listener
     *            the listener will be called with hash progress in percents
     * @param paths
     *            paths to add to the torrent
     * @return handle of the created torrent, call {@link TorrentHandle#resume()} to start it.
     */
    public TorrentHandle share(final set_piece_hashes_listener listener, String... paths) {
        file_storage fs = new file_storage();
        String rootPath = null;
        TorrentType type = null;
        for (String path : paths) {
            File file = new File(path).getAbsoluteFile();
            if (!file.exists()) {
                throw new PFEException("file " + file.getAbsolutePath() + " doesn't exist.");
            }
            if (file.isDirectory() && type == TorrentType.MULTIFILE || file.isFile() && type == TorrentType.ONEDIR) {
                throw new PFEException("Only create torrent from directories or files, do not mix both.");

            }
            if (file.isDirectory()) {
                type = TorrentType.ONEDIR;
                if (paths.length > 1) {
                    throw new PFEException("Only select one directory, you've selected " + paths.length);
                }
                rootPath = file.getParent();
                libtorrent.add_files(fs, file.getAbsolutePath());
            } else {
                type = TorrentType.MULTIFILE;
                String newRootPath = file.getParentFile().getParent();
                if (rootPath != null && !rootPath.equals(newRootPath)) {
                    throw new PFEException("Files should have the same root directory.");
                }
                rootPath = newRootPath;
                String multiTorrentPath = new File(file.getParentFile().getName(), file.getName()).getPath();
                log.debug("Mapping: {} => {}", file.getAbsolutePath(), multiTorrentPath);
                fs.add_file(multiTorrentPath, file.length());
            }
        }
        final create_torrent ct = new create_torrent(fs);
        set_piece_hashes_listener hashListener = new set_piece_hashes_listener() {

            int lastPercent = 0;

            @Override
            public void progress(int i) {
                int p = i * 100 / ct.num_pieces();
                if (p > lastPercent) {
                    log.info("Hashed {}%", p);
                    lastPercent = p;
                    if (listener != null) {
                        listener.progress(p);
                    }
                }
            }
        };
        error_code ec = new error_code();
        log.debug("Root path: {}", rootPath);
        libtorrent.set_piece_hashes_ex(UUID.randomUUID().toString(), ct, rootPath, ec, hashListener);
        if (ec.value() != 0) {
            throw new IllegalStateException(ec.message());
        }
        Entry e = new Entry(ct.generate());
        TorrentInfo torrentInfo = TorrentInfo.bdecode(e.bencode());
        AddTorrentParams addTorrentParams = AddTorrentParams.createInstance();
        addTorrentParams.getSwig().setFlags(add_torrent_params.flags_t.flag_seed_mode.swigValue());
        addTorrentParams.torrentInfo(torrentInfo);
        addTorrentParams.savePath(rootPath);
        final TorrentHandle handle = session.addTorrent(addTorrentParams, new ErrorCode(new error_code()));
        setTrackers(handle);
        log.info("Seeding {}", handle.getTorrentInfo().getName());
        try {
            log.info("Hash: {}", getHash(handle));
        } catch (DecoderException e1) {
            e1.printStackTrace();
        }
        return handle;
    }

    public String getHash(TorrentHandle handle) throws DecoderException {
        return new Base32().encodeAsString(Hex.decodeHex(handle.getInfoHash().toHex().toCharArray()));
    }

    private void setTrackers(TorrentHandle th) {
        for (String tracker : settingsStorage.getTrackers()) {
            th.addTracker(new AnnounceEntry(tracker));
        }
    }

    public void addListener(AlertListener listener) {
        session.addListener(listener);
    }

    public void removeTorrent(TorrentHandle th) {
        session.removeTorrent(th);
    }

    public void addPFEListener(PFEListener listener) {
        listeners.add(listener);
    }

    public void removePFEListener(PFEListener listener) {
        listeners.remove(listener);
    }

    public void stop() {
        session.abort();
    }

    public TorrentHandle findTorrent(String hash) {
        return session.findTorrent(base32ToSha1(hash));
    }
}
