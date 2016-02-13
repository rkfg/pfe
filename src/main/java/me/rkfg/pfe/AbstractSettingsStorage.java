package me.rkfg.pfe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractSettingsStorage {
    protected Properties properties = new Properties();
    protected Logger log = LoggerFactory.getLogger(getClass());
    private String iniFile;

    protected AbstractSettingsStorage(Class<?> clazz, String filename) {
        iniFile = new File(getJarDirectory(clazz), filename).getAbsolutePath();
        try {
            properties.load(new InputStreamReader(new FileInputStream(new File(iniFile)), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.info("{} not found, will be created later.", iniFile);
        }
    }

    protected void storeProperties() {
        try {
            properties.store(new OutputStreamWriter(new FileOutputStream(new File(iniFile)), StandardCharsets.UTF_8), "");
        } catch (IOException e) {
            log.info("{} can't be saved, path info will always be default.", iniFile);
        }
    }

    public static String getJarDirectory(Class<?> clazz) {
        try {
            return new File(URLDecoder.decode(clazz.getProtectionDomain().getCodeSource().getLocation().getPath(),
                    "utf-8"))
                    .getParent();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return ".";
        }
    }

}
