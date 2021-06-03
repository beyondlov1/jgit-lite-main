package com.beyond.jgit.storage;

import com.beyond.jgit.util.PathUtils;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;
import com.thegrizzlylabs.sardineandroid.impl.SardineException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class SardineStorage extends AbstractStorage {

    private final String basePath;

    private Sardine sardine;

    private final Set<String> existDirs = new HashSet<>();

    public SardineStorage(String basePath, String username, String password) {
        this.basePath = basePath;
        sardine = new OkHttpSardine();
        sardine.setCredentials(username, password);
    }

    @Override
    public boolean exists(String path) {
        return false;
    }

    @Override
    public void upload(File file, String targetPath) throws IOException {
        String absPath = getAbsPath(targetPath);
        forceMkdirParent(absPath);
        sardine.put(absPath, file, null);
    }

    private void forceMkdirParent(String absPath) throws IOException {
        String parent;
        if (existDirs.contains(PathUtils.parent(absPath))){
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
        if (existDirs.contains(absDir)){
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
        }catch (SardineException e){
            if (e.getStatusCode() != 404){
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
}
