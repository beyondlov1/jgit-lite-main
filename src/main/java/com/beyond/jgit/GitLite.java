package com.beyond.jgit;

import com.beyond.jgit.index.Index;
import com.beyond.jgit.index.IndexDiffResult;
import com.beyond.jgit.index.IndexDiffer;
import com.beyond.jgit.index.IndexManager;
import com.beyond.jgit.log.LogItem;
import com.beyond.jgit.log.LogManager;
import com.beyond.jgit.object.ObjectEntity;
import com.beyond.jgit.object.ObjectManager;
import com.beyond.jgit.object.data.BlobObjectData;
import com.beyond.jgit.object.data.CommitObjectData;
import com.beyond.jgit.object.data.TreeObjectData;
import com.beyond.jgit.storage.FileStorage;
import com.beyond.jgit.storage.SardineStorage;
import com.beyond.jgit.storage.Storage;
import com.beyond.jgit.util.JsonUtils;
import com.beyond.jgit.util.ObjectUtils;
import com.beyond.jgit.util.PathUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("DuplicatedCode")
@Slf4j
public class GitLite {

    public static final String EMPTY_OBJECT_ID = "0000000000000000000000000000000000000000";

    private final ObjectManager objectManager;
    private final IndexManager indexManager;
    private final LogManager localLogManager;


    private final Map<String,LogManager> remoteLogManagerMap;
    private final Map<String,Storage>  remoteStorageMap;

    private final GitLiteConfig config;

    public GitLite(GitLiteConfig config) {
        this.config = config;
        this.objectManager = new ObjectManager(config.getObjectsDir());
        this.indexManager = new IndexManager(config.getIndexPath());
        this.localLogManager = new LogManager(PathUtils.concat(config.getLogsHeadsDir(), "master.json"));

        this.remoteLogManagerMap = new HashMap<>();
        this.remoteStorageMap = new HashMap<>();

        for (GitLiteConfig.RemoteConfig remoteConfig : config.getRemoteConfigs()) {
            remoteLogManagerMap.put(remoteConfig.getRemoteName(), new LogManager(PathUtils.concat(config.getLogsRemotesDir(), remoteConfig.getRemoteName(), "master.json")));
            if (remoteConfig.getRemoteUrl().startsWith("http://") || remoteConfig.getRemoteUrl().startsWith("https://")) {
                if (StringUtils.isNotBlank(remoteConfig.getRemoteTmpDir())){
                    remoteStorageMap.put(remoteConfig.getRemoteName(),
                            new SardineStorage(PathUtils.concat(remoteConfig.getRemoteUrl(), ".git"),
                                    remoteConfig.getRemoteUserName(),
                                    remoteConfig.getRemotePassword(),
                                    PathUtils.concat(remoteConfig.getRemoteTmpDir(),remoteConfig.getRemoteName()+".ed")));
                }else{
                    remoteStorageMap.put(remoteConfig.getRemoteName(),
                            new SardineStorage(PathUtils.concat(remoteConfig.getRemoteUrl(), ".git"),
                                    remoteConfig.getRemoteUserName(),
                                    remoteConfig.getRemotePassword()));
                }
            } else {
                remoteStorageMap.put(remoteConfig.getRemoteName() ,new FileStorage(PathUtils.concat(remoteConfig.getRemoteUrl(), ".git")));
            }
        }
    }

    public void init() throws IOException {
        String headPath = config.getHeadPath();
        File file = new File(headPath);
        if (!file.exists()) {
            FileUtils.write(file, "ref: refs/heads/master", StandardCharsets.UTF_8);
        }
    }

    public void add(String... paths) throws IOException {
        List<File> files = new ArrayList<>();
        if (paths.length == 0) {
            Collection<File> listFiles = FileUtils.listFilesAndDirs(new File(config.getLocalDir()), TrueFileFilter.INSTANCE, new IOFileFilter() {
                @Override
                public boolean accept(File file) {
                    return !".git".equals(file.getName());
                }

                @Override
                public boolean accept(File dir, String name) {
                    return false;
                }
            });
            files.addAll(listFiles);
        } else {
            for (String path : paths) {
                Collection<File> listFiles = FileUtils.listFilesAndDirs(new File(config.getLocalDir(), path), TrueFileFilter.INSTANCE, new IOFileFilter() {
                    @Override
                    public boolean accept(File file) {
                        return !".git".equals(file.getName());
                    }

                    @Override
                    public boolean accept(File dir, String name) {
                        return false;
                    }
                });
                files.addAll(listFiles);
            }
        }

        Index index = new Index();
        for (File file : Objects.requireNonNull(files)) {
            if (file.isFile()) {
                ObjectEntity objectEntity = addBlobObject(file);
                String objectId = ObjectUtils.sha1hash(objectEntity);
                Index.Entry entry = new Index.Entry();
                entry.setPath(PathUtils.getRelativePath(config.getLocalDir(), file.getAbsolutePath()));
                entry.setObjectId(objectId);
                index.getEntries().add(entry);
            }
        }
        indexManager.save(index);
    }

    public String commit(String message) throws IOException {
        return commit(IndexManager.parseIndex(config.getIndexPath()), message);
    }

    public String commit(Index index,String message) throws IOException {

        Index committedIndex = Index.generateFromCommit(findLocalCommitObjectId(), objectManager);
        IndexDiffResult committedDiff = IndexDiffer.diff(index, committedIndex);
        if (!committedDiff.isChanged()){
            log.info("nothing changed, no commit");
            return "nothing changed";
        }

        ObjectEntity tree = addTreeFromIndex(index);
        ObjectEntity commit = addCommitObject(tree, message);
        File headRefFile = getHeadRefFile();
        FileUtils.writeStringToFile(headRefFile, ObjectUtils.sha1hash(commit), StandardCharsets.UTF_8);

        // log
        CommitObjectData commitObjectData = CommitObjectData.parseFrom(commit.getData());
        localLogManager.lock();
        localLogManager.appendToLock(ObjectUtils.sha1hash(commit), config.getCommitterName(), config.getCommitterEmail(), "auto commit", commitObjectData.getCommitTime());
        localLogManager.commit();
        return new String(commit.getData());
    }

    private ObjectEntity addBlobObject(File file) throws IOException {
        BlobObjectData blobObjectData = new BlobObjectData();
        blobObjectData.setData(FileUtils.readFileToByteArray(file));

        ObjectEntity objectEntity = new ObjectEntity();
        objectEntity.setType(ObjectEntity.Type.blob);
        objectEntity.setData(blobObjectData.toBytes());
        String objectId = objectManager.write(objectEntity);
        log.debug(file.getName() + " " + objectId);
        return objectEntity;
    }

    private ObjectEntity addTreeFromIndex(Index index) throws IOException {

        // collect nodes
        Map<File, FileNode> nodes = new HashMap<>();
        File rootFile = new File(config.getLocalDir());
        FileNode root = new FileNode(rootFile);
        nodes.put(rootFile, root);
        for (Index.Entry entry : index.getEntries()) {
            File file = new File(config.getLocalDir(), entry.getPath());
            FileNode fileNode = new FileNode(file);
            fileNode.setObjectId(entry.getObjectId());
            walkUp(fileNode, root, nodes);
        }

        // create tree
        for (FileNode node : nodes.values()) {
            File parentFile = node.getFile().getParentFile();
            FileNode parentNode = nodes.get(parentFile);
            if (parentNode != null) {
                parentNode.addChild(node);
            }
        }

        return addTreeObject(root);
    }

    private ObjectEntity addTreeObject(FileNode fileNode) throws IOException {
        List<FileNode> children = fileNode.getChildren();
        TreeObjectData treeObjectData = new TreeObjectData();
        List<TreeObjectData.TreeEntry> entries = treeObjectData.getEntries();
        for (FileNode child : children) {
            if (child.getType() == ObjectEntity.Type.tree) {
                addTreeObject(child);
            }
            TreeObjectData.TreeEntry treeEntry = new TreeObjectData.TreeEntry();
            treeEntry.setType(child.getType());
            treeEntry.setName(child.getFileName());
            treeEntry.setMode(ObjectUtils.getModeByType(child.getType()));
            treeEntry.setObjectId(child.getObjectId());
            entries.add(treeEntry);
        }
        ObjectEntity objectEntity = new ObjectEntity();
        objectEntity.setType(ObjectEntity.Type.tree);
        entries.sort(Comparator.comparing(TreeObjectData.TreeEntry::getName));
        objectEntity.setData(treeObjectData.toBytes());
        String objectId = objectManager.write(objectEntity);
        fileNode.setObjectId(objectId);
        log.debug(fileNode.getFileName() + ":{}", objectId);
        return objectEntity;
    }

    private void walkUp(FileNode fileNode, FileNode rootNode, Map<File, FileNode> nodes) {
        File file = fileNode.getFile();
        File root = rootNode.getFile();
        if (Objects.equals(file, root)) {
            return;
        }
        if (nodes.containsKey(file)) {
            return;
        }
        File parentFile = file.getParentFile();
        nodes.put(file, fileNode);
        walkUp(new FileNode(parentFile), rootNode, nodes);
    }

    private ObjectEntity addCommitObject(ObjectEntity tree,String message) throws IOException {
        CommitObjectData commitObjectData = new CommitObjectData();
        commitObjectData.setTree(ObjectUtils.sha1hash(tree));
        commitObjectData.setCommitTime(System.currentTimeMillis());
        CommitObjectData.User user = new CommitObjectData.User();
        user.setName(config.getCommitterName());
        user.setEmail(config.getCommitterEmail());
        commitObjectData.setCommitter(user);
        commitObjectData.setAuthor(user);
        commitObjectData.setMessage(message);
        commitObjectData.addParent(localLogManager.getLastLogItem().getCommitObjectId());

        ObjectEntity commitObjectEntity = new ObjectEntity();
        commitObjectEntity.setType(ObjectEntity.Type.commit);
        commitObjectEntity.setData(commitObjectData.toBytes());

        String commitObjectId = objectManager.write(commitObjectEntity);
        log.debug("commitObjectId: {}", commitObjectId);

        return commitObjectEntity;
    }


    public void fetch(String remoteName) throws IOException {
        Storage remoteStorage = remoteStorageMap.get(remoteName);
        if (remoteStorage == null){
            throw new RuntimeException("remoteStorage is not exist");
        }
        LogManager remoteLogManager = remoteLogManagerMap.get(remoteName);
        if (remoteLogManager == null){
            throw new RuntimeException("remoteLogManager is not exist");
        }
        if (!remoteStorage.exists(PathUtils.concat("refs", "remotes",remoteName, "master"))) {
            log.warn("remote is empty");
            return;
        }

        initRemoteDirs(remoteName);

        // fetch remote head to remote head lock
        // locked?
        File remoteHeadFile = new File(PathUtils.concat(config.getRefsRemotesDir(), remoteName,"master"));
        File remoteHeadLockFile = new File(PathUtils.concat(config.getRefsRemotesDir(), remoteName,"master.lock"));
        if (remoteHeadLockFile.exists()) {
            log.error("remote master is locked, path:{}", remoteHeadLockFile.getAbsolutePath());
            throw new RuntimeException("remote master is locked");
        }
        remoteStorage.download(PathUtils.concat("refs", "remotes",remoteName, "master"), remoteHeadLockFile);
        // fetch remote logs to remote logs lock
        remoteStorage.download(PathUtils.concat("logs", "remotes", remoteName, "master.json"), new File(PathUtils.concat(config.getLogsRemotesDir(), remoteName,"master.json.lock")));

        List<LogItem> logs = remoteLogManager.getLogs();
        if (remoteHeadFile.exists() && logs != null) {
            String remoteHeadObjectId = FileUtils.readFileToString(remoteHeadFile, StandardCharsets.UTF_8);
            String remoteHeadLockObjectId = FileUtils.readFileToString(remoteHeadLockFile, StandardCharsets.UTF_8);
            int remoteLockIndex = ListUtils.indexOf(logs, x -> StringUtils.equals(x.getCommitObjectId(), remoteHeadLockObjectId));
            int remoteIndex = ListUtils.indexOf(logs, x -> StringUtils.equals(x.getCommitObjectId(), remoteHeadObjectId));
            if (remoteLockIndex < remoteIndex) {
                log.warn("remote in file is after remote in web");
                remoteLogManager.commit();
                Files.move(remoteHeadLockFile.toPath(), remoteHeadFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            }
            if (remoteLockIndex == remoteIndex) {
                log.warn("Already up to date.");
                remoteLogManager.commit();
                Files.move(remoteHeadLockFile.toPath(), remoteHeadFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            }
        }

        // 根据 remote head 判断需要下载那些objects
        String remoteCommitObjectId = findRemoteCommitObjectId(remoteName);
        String remoteLockCommitObjectId = findRemoteLockCommitObjectId(remoteName);

        Index remoteHeadIndex = Index.generateFromCommit(remoteCommitObjectId, objectManager);
        Index remoteLockHeadIndex = Index.generateFromCommit(remoteLockCommitObjectId, objectManager);

        IndexDiffResult diffToFetch = IndexDiffer.diff(remoteLockHeadIndex, remoteHeadIndex);
        log.debug("diffToFetch: {}", JsonUtils.writeValueAsString(diffToFetch));

        Set<Index.Entry> changedEntries = new HashSet<>();
        changedEntries.addAll(diffToFetch.getAdded());
        changedEntries.addAll(diffToFetch.getUpdated());

        // fetch objects
        for (Index.Entry changedEntry : changedEntries) {
            remoteStorage.download(PathUtils.concat("objects", ObjectUtils.path(changedEntry.getObjectId())), ObjectUtils.getObjectFile(config.getObjectsDir(), changedEntry.getObjectId()));
        }

        // update logs
        try {
            String currRemoteLogsDir = PathUtils.concat(config.getLogsRemotesDir(), remoteName);
            remoteStorage.download(PathUtils.concat("logs", "remotes", remoteName, "master.json"), new File(PathUtils.concat(currRemoteLogsDir,"master.json.lock")));
        } catch (Exception e) {
            log.error("download remote logs fail", e);
            remoteLogManager.rollback();
            throw e;
        }

        // update head
        remoteStorage.download(PathUtils.concat("refs", "remotes", remoteName, "master"), remoteHeadLockFile);

        remoteLogManager.commit();
        Files.move(remoteHeadLockFile.toPath(), remoteHeadFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

    }

    public void checkout(String commitObjectId) throws IOException {
        Index index = Index.generateFromCommit(commitObjectId, objectManager);
        List<Index.Entry> entries = index.getEntries();
        for (Index.Entry entry : entries) {
            String absPath = PathUtils.concat(config.getLocalDir(), entry.getPath());
            ObjectEntity objectEntity = objectManager.read(entry.getObjectId());
            if (objectEntity.getType() == ObjectEntity.Type.blob){
                BlobObjectData blobObjectData = BlobObjectData.parseFrom(objectEntity.getData());
                FileUtils.writeByteArrayToFile(new File(absPath),blobObjectData.getData());
            }
        }
    }


    public void merge(String remoteName) throws IOException {
        // 找不同
        // 1. 创建目录结构列表
        // 2. 对比基础commit和新commit之间的文件路径差异(列出那些文件是添加， 那些是删除， 那些是更新)
        // 3. local和remote找到共同的提交历史， 对比变化
        // 4. 对比变化的差异（以文件路径为key)
        // 根据log查询local和remote共同经历的最后一个commit

        LogManager remoteLogManager = remoteLogManagerMap.get(remoteName);
        if (remoteLogManager == null){
            throw new RuntimeException("remoteLogManager is not exist");
        }

        List<LogItem> committedLogs = localLogManager.getLogs();
        List<LogItem> remoteLogs = remoteLogManager.getLogs();

        if (committedLogs == null) {
            log.warn("local logs is empty");
            return;
        }
        if (remoteLogs == null) {
            log.warn("remote logs is empty");
            remoteLogs = new ArrayList<>();
        }

        String intersectionCommitObjectId = null;
        Set<String> remoteObjectIdSet = remoteLogs.stream().map(LogItem::getCommitObjectId).collect(Collectors.toSet());
        for (int i = committedLogs.size() - 1; i >= 0; i--) {
            if (remoteObjectIdSet.contains(committedLogs.get(i).getCommitObjectId())) {
                intersectionCommitObjectId = committedLogs.get(i).getCommitObjectId();
                break;
            }
        }
        if (intersectionCommitObjectId == null) {
            log.warn("无相同的commitObjectId, remote log is empty, cover.");
        }

        String localCommitObjectId = findLocalCommitObjectId();
        String remoteCommitObjectId = findRemoteCommitObjectId(remoteName);

        Index intersectionIndex = Index.generateFromCommit(intersectionCommitObjectId, objectManager);

        Index committedHeadIndex = Index.generateFromCommit(localCommitObjectId, objectManager);
        IndexDiffResult committedDiff = IndexDiffer.diff(committedHeadIndex, intersectionIndex);
        log.debug("committedDiff: {}", JsonUtils.writeValueAsString(committedDiff));

        Index remoteHeadIndex = Index.generateFromCommit(remoteCommitObjectId, objectManager);
        IndexDiffResult remoteDiff = IndexDiffer.diff(remoteHeadIndex, intersectionIndex);
        log.debug("remoteDiff: {}", JsonUtils.writeValueAsString(remoteDiff));

        // - 没变化直接用变化的commit - 两边都有变化, 更新index, 根据 index 新建commit

        Set<Index.Entry> committedChanged = new HashSet<>();
        committedChanged.addAll(committedDiff.getAdded());
        committedChanged.addAll(committedDiff.getUpdated());
        committedChanged.addAll(committedDiff.getRemoved());

        Set<Index.Entry> remoteChanged = new HashSet<>();
        remoteChanged.addAll(remoteDiff.getAdded());
        remoteChanged.addAll(remoteDiff.getUpdated());
        remoteChanged.addAll(remoteDiff.getRemoved());

        if (!committedDiff.isChanged() && !remoteDiff.isChanged() ){
            log.info("nothing changed, no merge");
            return;
        }

        Set<String> committedChangedPaths = committedChanged.stream().map(Index.Entry::getPath).collect(Collectors.toSet());
        Set<String> remoteChangedPaths = remoteChanged.stream().map(Index.Entry::getPath).collect(Collectors.toSet());

        Collection<String> intersection = CollectionUtils.intersection(committedChangedPaths, remoteChangedPaths);

        if (CollectionUtils.isNotEmpty(intersection)) {
            // todo: merge conflicted
            throw new RuntimeException("not supported yet");
        }

        if (committedHeadIndex == null) {
            committedHeadIndex = new Index();
        }
        committedHeadIndex.upsert(remoteDiff.getAdded());
        committedHeadIndex.upsert(remoteDiff.getUpdated());
        committedHeadIndex.remove(remoteDiff.getRemoved());

        if (remoteHeadIndex == null) {
            remoteHeadIndex = new Index();
        }
        remoteHeadIndex.upsert(committedDiff.getAdded());
        remoteHeadIndex.upsert(committedDiff.getUpdated());
        remoteHeadIndex.remove(committedDiff.getRemoved());

        log.debug(JsonUtils.writeValueAsString(committedHeadIndex));
        log.debug(JsonUtils.writeValueAsString(remoteHeadIndex));

        indexManager.save(committedHeadIndex);

        commit("merge");

    }


    private String findLocalCommitObjectId() throws IOException {
        File file = getHeadRefFile();
        if (!file.exists()) {
            return null;
        }
        return StringUtils.trim(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
    }

    private File getHeadRefFile() throws IOException {
        String headPath = config.getHeadPath();
        String ref = FileUtils.readFileToString(new File(headPath), StandardCharsets.UTF_8);
        String relativePath = StringUtils.trim(StringUtils.substringAfter(ref, "ref: "));
        return new File(config.getGitDir(), relativePath);
    }

    private String findRemoteCommitObjectId(String remoteName) throws IOException {
        String headPath = config.getHeadPath();
        String ref = FileUtils.readFileToString(new File(headPath), StandardCharsets.UTF_8);
        String relativePath = StringUtils.trim(StringUtils.substringAfter(ref, "ref: "));
        relativePath = relativePath.replace(File.separator+"heads"+File.separator, File.separator+"remotes"+File.separator+remoteName+File.separator);
        File file = new File(config.getGitDir(), relativePath);
        if (!file.exists()) {
            return null;
        }
        return StringUtils.trim(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
    }

    private String findRemoteLockCommitObjectId(String remoteName) throws IOException {
        String headPath = config.getHeadPath();
        String ref = FileUtils.readFileToString(new File(headPath), StandardCharsets.UTF_8);
        String relativePath = StringUtils.trim(StringUtils.substringAfter(ref, "ref: "));
        relativePath = relativePath.replace(File.separator+"heads"+File.separator, File.separator+"remotes"+File.separator+remoteName+File.separator);
        File file = new File(config.getGitDir(), relativePath + ".lock");
        if (!file.exists()) {
            return null;
        }
        return StringUtils.trim(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
    }

    public void push(String remoteName) throws IOException {
        Storage remoteStorage = remoteStorageMap.get(remoteName);
        if (remoteStorage == null){
            throw new RuntimeException("remoteStorage is not exist");
        }
        LogManager remoteLogManager = remoteLogManagerMap.get(remoteName);
        if (remoteLogManager == null){
            throw new RuntimeException("remoteLogManager is not exist");
        }

        // fetch哪些就push哪些
        // 1. 根据最新commitObjectId获取更新了那些文件
        String localCommitObjectId = findLocalCommitObjectId();
        String remoteCommitObjectId = findRemoteCommitObjectId(remoteName);

        Index committedHeadIndex = Index.generateFromCommit(localCommitObjectId, objectManager);
        Index remoteHeadIndex = Index.generateFromCommit(remoteCommitObjectId, objectManager);

        IndexDiffResult committedDiff = IndexDiffer.diff(committedHeadIndex, remoteHeadIndex);
        log.debug("committedDiff to push: {}", JsonUtils.writeValueAsString(committedDiff));

        if (!committedDiff.isChanged()){
            log.info("nothing changed, no push");
            return;
        }

        initRemoteDirs(remoteName);

        // 2. 上传objects
        Set<Index.Entry> changedEntries = new HashSet<>();
        changedEntries.addAll(committedDiff.getAdded());
        changedEntries.addAll(committedDiff.getUpdated());
        //  upload
        Set<String> dirs = changedEntries.stream().map(x -> PathUtils.parent(ObjectUtils.path(x.getObjectId()))).map(x -> PathUtils.concat("objects", x)).collect(Collectors.toSet());
        remoteStorage.mkdir(dirs);
        for (Index.Entry changedEntry : changedEntries) {
            File objectFile = ObjectUtils.getObjectFile(config.getObjectsDir(), changedEntry.getObjectId());
            remoteStorage.upload(objectFile, PathUtils.concat("objects", ObjectUtils.path(changedEntry.getObjectId())));
        }

        // 3. 写remote日志(异常回退)
        LogItem localCommitLogItem = localLogManager.getLogs().stream().filter(x -> Objects.equals(x.getCommitObjectId(), localCommitObjectId)).findFirst().orElse(null);
        if (localCommitLogItem == null) {
            throw new RuntimeException("log file error, maybe missing some commit");
        }
        List<LogItem> remoteLogs = remoteLogManager.getLogs();
        LogItem remoteLogItem = new LogItem();
        if (remoteLogs == null) {
            remoteLogItem.setParentCommitObjectId(EMPTY_OBJECT_ID);
        } else {
            remoteLogItem.setParentCommitObjectId(remoteLogs.get(remoteLogs.size() - 1).getCommitObjectId());
        }
        remoteLogItem.setCommitObjectId(localCommitLogItem.getCommitObjectId());
        remoteLogItem.setCommitterName(localCommitLogItem.getCommitterName());
        remoteLogItem.setCommitterEmail(localCommitLogItem.getCommitterEmail());
        remoteLogItem.setMessage("push");
        remoteLogItem.setMtime(System.currentTimeMillis());
        remoteLogManager.lock();
        remoteLogManager.appendToLock(remoteLogItem);

        // 4  上传日志
        try {
            String currRemoteLogsDir = PathUtils.concat(config.getLogsRemotesDir(), remoteName);
            //  upload log lock file to log file
            remoteStorage.upload(new File(PathUtils.concat(currRemoteLogsDir,"master.json.lock")),
                    PathUtils.concat("logs", "remotes",remoteName, "master.json"));
            remoteLogManager.commit();
        } catch (Exception e) {
            log.error("上传日志失败", e);
            remoteLogManager.rollback();
            throw e;
        }

        // 5. 修改本地remote的head(异常回退)
        String currRemoteRefsDir = PathUtils.concat(config.getRefsRemotesDir(), remoteName);
        File remoteHeadFile = new File(currRemoteRefsDir, "master");
        File remoteHeadLockFile = new File(remoteHeadFile.getAbsolutePath() + ".lock");
        FileUtils.copyFile(new File(PathUtils.concat(config.getRefsHeadsDir(), "master")), remoteHeadLockFile);
        FileUtils.writeStringToFile(remoteHeadLockFile, localCommitObjectId, StandardCharsets.UTF_8);

        // 6. 上传remote的head
        try {
            //  upload remote head lock to remote head
            remoteStorage.upload(new File(PathUtils.concat(config.getRefsHeadsDir(), "master")),
                    PathUtils.concat("refs", "remotes", remoteName, "master"));
            Files.move(remoteHeadLockFile.toPath(), remoteHeadFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.error("上传日志失败", e);
            FileUtils.forceDelete(remoteHeadLockFile);
            throw e;
        }

    }

    private void initRemoteDirs(String remoteName) throws IOException {
        Storage remoteStorage = remoteStorageMap.get(remoteName);
        if (remoteStorage == null){
            throw new RuntimeException("remoteStorage is not exist");
        }
        if (!remoteStorage.exists(PathUtils.concat("refs", "remotes", remoteName))) {
            remoteStorage.mkdir("");
            remoteStorage.mkdir("objects");
            remoteStorage.mkdir("logs");
            remoteStorage.mkdir(PathUtils.concat("logs", "remotes"));
            remoteStorage.mkdir(PathUtils.concat("logs", "remotes", remoteName));
            remoteStorage.mkdir("refs");
            remoteStorage.mkdir(PathUtils.concat("refs", "remotes"));
            remoteStorage.mkdir(PathUtils.concat("refs", "remotes", remoteName));
        }
    }

    @Data
    private static class FileNode {
        private File file;
        private List<FileNode> children = new ArrayList<>();
        private String objectId;
        private ObjectEntity.Type type;

        public FileNode(File file) {
            this.file = file;
            if (file.isDirectory()) {
                type = ObjectEntity.Type.tree;
            }
            if (file.isFile()) {
                type = ObjectEntity.Type.blob;
            }
        }

        void addChild(FileNode fileNode) {
            children.add(fileNode);
        }

        String getFileName() {
            return file.getName();
        }
    }

}
