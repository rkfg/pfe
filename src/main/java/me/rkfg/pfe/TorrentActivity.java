package me.rkfg.pfe;

public class TorrentActivity {
    public long upload = 0;
    public long timestamp = System.nanoTime();
    public int progress;
    public String name;
    public String hash;
    public long size;

    public TorrentActivity(String hash) {
        this.hash = hash;
    }

}