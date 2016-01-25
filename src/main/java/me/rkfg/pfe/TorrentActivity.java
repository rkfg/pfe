package me.rkfg.pfe;

import org.apache.commons.codec.DecoderException;

import com.frostwire.jlibtorrent.TorrentHandle;

public class TorrentActivity {
    public long upload = 0;
    public long timestamp = System.nanoTime();
    public int progress;
    public String name;
    public String hash;
    public long size;
    public boolean complete;
    public boolean uploading;
    public int seedPercent;
    public int peers;

    public TorrentActivity(TorrentHandle handle) throws DecoderException {
        this.hash = PFECore.getHash(handle);
    }

}