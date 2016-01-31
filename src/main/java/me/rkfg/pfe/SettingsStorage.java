package me.rkfg.pfe;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

public class SettingsStorage extends AbstractSettingsStorage {

    private Collection<String> trackers;
    private boolean dht;
    private int seedRatio;
    private long seedingTimeout;
    private boolean seedAfterDownload;

    public SettingsStorage(Class<?> jarClass) {
        super(jarClass, System.getProperty("pfe.settings", "pfe_settings.ini"));
        dht = Boolean.valueOf(properties.getProperty("enable_dht", "false"));
        seedRatio = Integer.valueOf(properties.getProperty("seeding_ratio", "3"));
        trackers = Arrays.asList(properties.getProperty("trackers", "").split("\\|"));
        if (trackers == null) {
        }
        seedingTimeout = TimeUnit.SECONDS.toNanos(Integer.valueOf(properties.getProperty("seeding_timeout", "3600")));
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
