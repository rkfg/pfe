package me.rkfg.pfe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class SettingsStorage {

    private Properties properties = new Properties();
    private Collection<String> trackers;
    private boolean dht;
    private int seedRatio;
    private long seedingTimeout;
    private boolean seedAfterDownload;

    public SettingsStorage() {
        File settingsFile = null;
        String settingsFilename = System.getProperty("pfe.settings");
        if (settingsFilename == null) {
            settingsFilename = "pfe_settings.ini";
        }
        settingsFile = new File(settingsFilename);
        try {
            properties.load(new FileInputStream(settingsFile));
        } catch (IOException e) {
            e.printStackTrace();
        }
        dht = Boolean.valueOf(properties.getProperty("enable_dht", "false"));
        seedRatio = Integer.valueOf(properties.getProperty("seeding_ratio", "3"));
        trackers = Arrays.asList(properties.getProperty("trackers", "").split("\\|"));
        if (trackers == null) {
        }
        seedingTimeout = TimeUnit.SECONDS.toNanos(Integer.valueOf(properties.getProperty("seeding_timeout", "60")));
        seedAfterDownload = Boolean.valueOf(properties.getProperty("seed_after_download", "false"));
    }

    public Collection<String> getTrackers() {
        return trackers;
    }

    public boolean isDht() {
        return dht;
    }

    public int getSeedRatio() {
        return seedRatio;
    }

    public long getSeedingTimeout() {
        return seedingTimeout;
    }

    public boolean isSeedAfterDownload() {
        return seedAfterDownload;
    }

}
