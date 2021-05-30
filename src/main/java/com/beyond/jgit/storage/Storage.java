package com.beyond.jgit.storage;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

public interface Storage {
    boolean exists(String path);
    void upload(File file, String targetPath) throws IOException;
    void download(String path, File targetFile) throws IOException;
    void mkdir(Collection<String> dirPaths) throws IOException;
    void mkdir(String dir) throws IOException;
    void delete(String path) throws IOException;
    byte[] readFullyToByteArray(String path) throws IOException;
    String readFullToString(String path) throws IOException;
    String getBasePath();
}
