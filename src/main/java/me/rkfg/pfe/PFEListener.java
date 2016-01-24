package me.rkfg.pfe;

import java.util.Collection;

public interface PFEListener {
    public void torrentProgress(Collection<TorrentActivity> torrentActivities);

    public void torrentStopped(Collection<TorrentActivity> stopped);
}
