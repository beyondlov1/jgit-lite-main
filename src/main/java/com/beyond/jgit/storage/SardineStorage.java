package com.beyond.jgit.storage;

import com.beyond.jgit.util.PathUtils;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;
import com.thegrizzlylabs.sardineandroid.impl.SardineException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SardineStorage extends AbstractStorage {

    private final String basePath;

    private Sardine sardine;

    private final ExistDirCacheManager existDirs;

    public SardineStorage(String basePath, String username, String password) {
        this(basePath, username, password, null);
    }

    public SardineStorage(String basePath, String username, String password, String existDirCachePath) {
        this.basePath = basePath;
        sardine = new LoggedSardine(new OkHttpSardine());
        sardine.setCredentials(username, password);
        existDirs = new ExistDirCacheManager(existDirCachePath);
    }

    @Override
    public boolean exists(String path) throws IOException {
        String absPath = getAbsPath(path);
        if (existDirs.contains(PathUtils.parent(absPath))) {
            return true;
        }
        return sardine.exists(absPath);
    }

    @Override
    public void upload(File file, String targetPath) throws IOException {
        String absPath = getAbsPath(targetPath);
        forceMkdirParent(absPath);
        sardine.put(absPath, file, null);
    }

    private void forceMkdirParent(String absPath) throws IOException {
        String parent;
        if (existDirs.contains(PathUtils.parent(absPath))) {
            return;
        }
        if (!sardine.exists(parent = PathUtils.parent(absPath))) {
            forceMkdirParent(parent);
            sardine.createDirectory(parent);
            existDirs.add(parent);
        }
        existDirs.add(parent);
    }

    @Override
    public void download(String path, File targetFile) throws IOException {
        String absPath = getAbsPath(path);
        InputStream inputStream = sardine.get(absPath);
        FileUtils.copyInputStreamToFile(inputStream, targetFile);
    }

    @Override
    public void mkdir(Collection<String> dirPaths) throws IOException {
        for (String dirPath : dirPaths) {
            mkdir(dirPath);
        }
    }

    @Override
    public void mkdir(String dir) throws IOException {
        String absDir = getAbsPath(dir);
        if (existDirs.contains(absDir)) {
            return;
        }
        if (!sardine.exists(absDir)) {
            sardine.createDirectory(absDir);
            existDirs.add(absDir);
        }
        existDirs.add(absDir);
    }

    @Override
    public void delete(String path) throws IOException {
        try {
            String absPath = getAbsPath(path);
            sardine.delete(absPath);
            existDirs.delete(absPath);
        } catch (SardineException e) {
            if (e.getStatusCode() != 404) {
                throw e;
            }
        }
    }

    @Override
    public byte[] readFullyToByteArray(String path) throws IOException {
        String absPath = getAbsPath(path);
        InputStream inputStream = sardine.get(absPath);
        return IOUtils.toByteArray(inputStream);
    }

    @Override
    public String getBasePath() {
        return basePath;
    }

    private String getAbsPath(String path) {
        return PathUtils.concat(getBasePath(), path.replace(File.separator, "/"));
    }

    private static class ExistDirCacheManager {

        private String cachePath;

        private final Set<String> existDirs = new HashSet<>();

        public ExistDirCacheManager(String cachePath) {
            if (StringUtils.isNotBlank(cachePath)) {
                this.cachePath = cachePath;
                String s = null;
                try {
                    s = FileUtils.readFileToString(new File(cachePath), StandardCharsets.UTF_8);
                } catch (IOException ignore) {
                }
                if (StringUtils.isNotBlank(s)) {
                    String[] split = StringUtils.split(s, "\n");
                    existDirs.addAll(Arrays.asList(split));
                }
            }
        }

        public void add(String relativePath) throws IOException {
            if (StringUtils.isNotBlank(cachePath)) {
                FileUtils.writeLines(new File(cachePath), Collections.singletonList(relativePath), true);
            }
            existDirs.add(relativePath);
        }

        public boolean contains(String path) {
            return existDirs.contains(path);
        }

        public void delete(String path) throws IOException {
            if (contains(path)){
                existDirs.remove(path);
                FileUtils.writeLines(new File(cachePath), existDirs);
            }
        }
    }
}
