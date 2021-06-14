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
import com.beyond.jgit.storage.TransportMapping;
import com.beyond.jgit.util.FileUtil;
import com.beyond.jgit.util.JsonUtils;
import com.beyond.jgit.util.ObjectUtils;
import com.beyond.jgit.util.PathUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

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


    private final Map<String, LogManager> remoteLogManagerMap;
    private final Map<String, Storage> remoteStorageMap;

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
                if (StringUtils.isNotBlank(remoteConfig.getRemoteTmpDir())) {
                    remoteStorageMap.put(remoteConfig.getRemoteName(),
                            new SardineStorage(PathUtils.concat(remoteConfig.getRemoteUrl(), ".git"),
                                    remoteConfig.getRemoteUserName(),
                                    remoteConfig.getRemotePassword(),
                                    PathUtils.concat(remoteConfig.getRemoteTmpDir(), remoteConfig.getRemoteName(), "master.ed"),
                                    PathUtils.concat(remoteConfig.getRemoteTmpDir(), remoteConfig.getRemoteName(), "session")));
                } else {
                    remoteStorageMap.put(remoteConfig.getRemoteName(),
                            new SardineStorage(PathUtils.concat(remoteConfig.getRemoteUrl(), ".git"),
                                    remoteConfig.getRemoteUserName(),
                                    remoteConfig.getRemotePassword()));
                }
            } else {
                remoteStorageMap.put(remoteConfig.getRemoteName(), new FileStorage(PathUtils.concat(remoteConfig.getRemoteUrl(), ".git")));
            }
        }
    }

    public void init() throws IOException {
        mkdirIfNotExists(config.getLocalDir());
        mkdirIfNotExists(config.getGitDir());
        mkdirIfNotExists(config.getObjectsDir());
        mkdirIfNotExists(config.getLogsDir());
        mkdirIfNotExists(config.getRefsDir());
        String headPath = config.getHeadPath();
        File file = new File(headPath);
        if (!file.exists()) {
            FileUtils.write(file, "ref: refs/heads/master", StandardCharsets.UTF_8);
        }
    }

    private void mkdirIfNotExists(String path) {
        File file = new File(path);
        if (file.exists()) {
            return;
        }
        boolean mkdirs = file.mkdirs();
        if (!mkdirs) {
            throw new RuntimeException("mkdir fail");
        }
    }

    public void add(String... paths) throws IOException {
        List<File> files = new ArrayList<>();
        if (paths.length == 0) {
            Collection<File> listFiles = FileUtil.listFilesAndDirsWithoutNameOf(config.getLocalDir(), ".git");
            files.addAll(listFiles);
        } else {
            for (String path : paths) {
                Collection<File> listFiles = FileUtil.listFilesAndDirsWithoutNameOf(PathUtils.concat(config.getLocalDir(), path), ".git");
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

    public String commit(Index index, String message) throws IOException {

        Index committedIndex = Index.generateFromCommit(findLocalCommitObjectId(), objectManager);
        IndexDiffResult committedDiff = IndexDiffer.diff(index, committedIndex);
        if (!committedDiff.isChanged()) {
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

    private ObjectEntity addCommitObject(ObjectEntity tree, String message) throws IOException {
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
        // todo: only fetch latest commit
        Storage remoteStorage = remoteStorageMap.get(remoteName);
        if (remoteStorage == null) {
            throw new RuntimeException("remoteStorage is not exist");
        }
        LogManager remoteLogManager = remoteLogManagerMap.get(remoteName);
        if (remoteLogManager == null) {
            throw new RuntimeException("remoteLogManager is not exist");
        }
        if (!remoteStorage.exists(PathUtils.concat("refs", "remotes", remoteName, "master"))) {
            log.warn("remote is empty");
            return;
        }

        initRemoteDirs(remoteName);

        // fetch remote head to remote head lock
        // locked?
        File remoteHeadFile = new File(PathUtils.concat(config.getRefsRemotesDir(), remoteName, "master"));
        File remoteHeadLockFile = new File(PathUtils.concat(config.getRefsRemotesDir(), remoteName, "master.lock"));
        if (remoteHeadLockFile.exists()) {
            log.error("remote master is locked, path:{}", remoteHeadLockFile.getAbsolutePath());
            throw new RuntimeException("remote master is locked");
        }
        remoteStorage.download(PathUtils.concat("refs", "remotes", remoteName, "master"), remoteHeadLockFile);
        // fetch remote logs to remote logs lock
        remoteStorage.download(PathUtils.concat("logs", "remotes", remoteName, "master.json"), new File(PathUtils.concat(config.getLogsRemotesDir(), remoteName, "master.json.lock")));

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
        if (!remoteHeadFile.exists() || logs == null) {
            log.warn("local/remote log is empty, no fetch");
            return;
        }
        // 去remoteLog, remoteLog只在本地存,不上传. 改用commitObject中的parent获取提交链
        List<CommitChainItem> chain = getRemoteCommitChain(remoteLockCommitObjectId, remoteCommitObjectId, remoteStorage);
        // chain 顺序: 由新到旧
        for (CommitChainItem commitChainItem : chain) {
            downloadByObjectIdRecursive(commitChainItem.getCommitObjectId(), remoteStorage);
        }

        // update logs
        try {
            String currRemoteLogsDir = PathUtils.concat(config.getLogsRemotesDir(), remoteName);
            remoteStorage.download(PathUtils.concat("logs", "remotes", remoteName, "master.json"), new File(PathUtils.concat(currRemoteLogsDir, "master.json.lock")));
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

    // 包新不包旧
    private List<CommitChainItem> getCommitChain(String newerCommitObjectId, String olderCommitObjectId) throws IOException {
        CommitChainItem firstItem = getCommitChainHead(newerCommitObjectId, olderCommitObjectId);
        List<CommitChainItem> chain = new ArrayList<>();

        CommitChainItem tmp = firstItem;
        while (true) {
            if (tmp == null){
                break;
            }
            CommitChainItem parent = tmp.getParent();
            if (parent == null){
                break;
            }
            chain.add(tmp);
            tmp = parent;
        }
        return chain;
    }

    private CommitChainItem getCommitChainHead(String newerCommitObjectId, String olderCommitObjectId) throws IOException {
        if (Objects.equals(newerCommitObjectId, olderCommitObjectId)) {
            CommitChainItem commitChainItem = new CommitChainItem();
            commitChainItem.setCommitObjectId(newerCommitObjectId);
            return commitChainItem;
        }
        if (Objects.equals(newerCommitObjectId, EMPTY_OBJECT_ID)) {
            return null;
        }
        ObjectEntity commitObjectEntity = objectManager.read(newerCommitObjectId);
        CommitChainItem commitChainItem = new CommitChainItem();
        commitChainItem.setCommitObjectId(newerCommitObjectId);
        List<String> parents = CommitObjectData.parseFrom(commitObjectEntity.getData()).getParents();
        // fixme parents暂时只有一个值
        for (String parent : parents) {
            commitChainItem.setParent(getCommitChainHead(parent, olderCommitObjectId));
        }
        return commitChainItem;
    }

    // 包新不包旧
    private List<CommitChainItem> getRemoteCommitChain(String newerCommitObjectId, String olderCommitObjectId, Storage remoteStorage) throws IOException {
        downloadCommitObjectsBetween(newerCommitObjectId, olderCommitObjectId, remoteStorage);
        return getCommitChain(newerCommitObjectId, olderCommitObjectId);
    }

    private void downloadCommitObjectsBetween(String newerCommitObjectId, String olderCommitObjectId, Storage remoteStorage) throws IOException {
        if (Objects.equals(newerCommitObjectId, olderCommitObjectId)) {
            return;
        }
        if (Objects.equals(newerCommitObjectId, EMPTY_OBJECT_ID)) {
            return;
        }
        File objectFile = ObjectUtils.getObjectFile(config.getObjectsDir(), newerCommitObjectId);
        if (!objectFile.exists()) {
            FileUtils.forceMkdirParent(objectFile);
            remoteStorage.download(PathUtils.concat("objects", ObjectUtils.path(newerCommitObjectId)), objectFile);
        }
        ObjectEntity commitObjectEntity = objectManager.read(newerCommitObjectId);
        List<String> parents = CommitObjectData.parseFrom(commitObjectEntity.getData()).getParents();
        // fixme parents暂时只有一个值
        for (String parent : parents) {
            downloadCommitObjectsBetween(parent, olderCommitObjectId, remoteStorage);
        }
    }

    private void downloadByObjectIdRecursive(String objectId, Storage remoteStorage) throws IOException {
        File objectFile = ObjectUtils.getObjectFile(config.getObjectsDir(), objectId);
        if (!objectFile.exists()) {
            FileUtils.forceMkdirParent(objectFile);
            remoteStorage.download(PathUtils.concat("objects", ObjectUtils.path(objectId)), objectFile);
        }
        ObjectEntity objectEntity = objectManager.read(objectId);
        switch (objectEntity.getType()) {
            case commit:
                CommitObjectData commitObjectData = CommitObjectData.parseFrom(objectEntity.getData());
                String tree = commitObjectData.getTree();
                downloadByObjectIdRecursive(tree, remoteStorage);
                break;
            case tree:
                TreeObjectData treeObjectData = TreeObjectData.parseFrom(objectEntity.getData());
                List<TreeObjectData.TreeEntry> entries = treeObjectData.getEntries();
                for (TreeObjectData.TreeEntry entry : entries) {
                    downloadByObjectIdRecursive(entry.getObjectId(), remoteStorage);
                }
                break;
            case blob:
                // do nothing
                break;
            default:
                throw new RuntimeException("type error");
        }

    }

    public void checkout(String commitObjectId) throws IOException {
        Index targetIndex = Index.generateFromCommit(commitObjectId, objectManager);
        Index localIndex = Index.generateFromLocalDir(config.getLocalDir());

        IndexDiffResult diff = IndexDiffer.diff(targetIndex, localIndex);
        Set<Index.Entry> removed = diff.getRemoved();
        for (Index.Entry entry : removed) {
            FileUtils.forceDelete(new File(PathUtils.concat(config.getLocalDir(), entry.getPath())));
        }

        List<Index.Entry> changedEntries = new ArrayList<>();
        changedEntries.addAll(diff.getAdded());
        changedEntries.addAll(diff.getUpdated());
        for (Index.Entry entry : changedEntries) {
            String absPath = PathUtils.concat(config.getLocalDir(), entry.getPath());
            ObjectEntity objectEntity = objectManager.read(entry.getObjectId());
            if (objectEntity.getType() == ObjectEntity.Type.blob) {
                BlobObjectData blobObjectData = BlobObjectData.parseFrom(objectEntity.getData());
                FileUtils.writeByteArrayToFile(new File(absPath), blobObjectData.getData());
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
        if (remoteLogManager == null) {
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
            log.warn("no intersectionCommitObjectId, remote log is empty, cover.");
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

        if (!committedDiff.isChanged() && !remoteDiff.isChanged()) {
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


    public String findLocalCommitObjectId() throws IOException {
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

    public String findRemoteCommitObjectId(String remoteName) throws IOException {
        String headPath = config.getHeadPath();
        String ref = FileUtils.readFileToString(new File(headPath), StandardCharsets.UTF_8);
        String relativePath = StringUtils.trim(StringUtils.substringAfter(ref, "ref: "));
        relativePath = relativePath.replace(File.separator + "heads" + File.separator, File.separator + "remotes" + File.separator + remoteName + File.separator);
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
        relativePath = relativePath.replace(File.separator + "heads" + File.separator, File.separator + "remotes" + File.separator + remoteName + File.separator);
        File file = new File(config.getGitDir(), relativePath + ".lock");
        if (!file.exists()) {
            return null;
        }
        return StringUtils.trim(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
    }

    public void push(String remoteName) throws IOException {
        Storage remoteStorage = remoteStorageMap.get(remoteName);
        if (remoteStorage == null) {
            throw new RuntimeException("remoteStorage is not exist");
        }
        LogManager remoteLogManager = remoteLogManagerMap.get(remoteName);
        if (remoteLogManager == null) {
            throw new RuntimeException("remoteLogManager is not exist");
        }

        initRemoteDirs(remoteName);

        // fetch哪些就push哪些
        // 1. 根据commit链, 将所有提交导致的变化objectId全部上传
        String localCommitObjectId = findLocalCommitObjectId();
        String remoteCommitObjectId = findRemoteCommitObjectId(remoteName);

        // 检查本地的remote和远程的remote是否有异常, 比如远程的文件被修改了导致本地的晚于远程
//        checkWebRemoteStatus(remoteName, remoteStorage, remoteLogManager);

        List<CommitChainItem> chain = getCommitChain(localCommitObjectId, remoteCommitObjectId);

        IndexDiffResult combinedDiff = new IndexDiffResult();
        for (CommitChainItem commitChainItem : chain) {
            Index thisIndex = Index.generateFromCommit(commitChainItem.getCommitObjectId(), objectManager);
            Index parentIndex;
            if (commitChainItem.getParent() == null) {
                parentIndex = null;
            } else {
                parentIndex = Index.generateFromCommit(commitChainItem.getParent().getCommitObjectId(), objectManager);
            }
            IndexDiffResult committedDiff = IndexDiffer.diff(thisIndex, parentIndex);
            log.debug("committedDiff to push: {}", JsonUtils.writeValueAsString(committedDiff));

            // 2. 上传objects
            combinedDiff.getAdded().addAll(committedDiff.getAdded());
            combinedDiff.getUpdated().addAll(committedDiff.getUpdated());
            combinedDiff.getRemoved().addAll(committedDiff.getRemoved());
        }

        if (!combinedDiff.isChanged()) {
            log.info("nothing changed, no push");
            return;
        }

        Set<Index.Entry> changedEntries = new HashSet<>();
        changedEntries.addAll(combinedDiff.getAdded());
        changedEntries.addAll(combinedDiff.getUpdated());

        //  upload
        List<String> objectIdsToUpload = new ArrayList<>();
        Set<String> dirs = changedEntries.stream().map(x -> PathUtils.parent(ObjectUtils.path(x.getObjectId()))).map(x -> PathUtils.concat("objects", x)).collect(Collectors.toSet());
        remoteStorage.mkdir(dirs);
        for (Index.Entry changedEntry : changedEntries) {
            objectIdsToUpload.add(changedEntry.getObjectId());
        }

        // 2. 上传commitObject，上传treeObject
        for (CommitChainItem commitChainItem : chain) {
            // 没有用uploadCommitObjectAndTreeObjectRecursive是为了减少不必要的上传
            // 查找变化的treeObjectId
            Map<String, String> parentPath2TreeObjectIdMap = new HashMap<>();
            getChangedTreeObjectRecursive(commitChainItem.getParent().getCommitObjectId(), "", parentPath2TreeObjectIdMap);
            Map<String, String> thisPath2TreeObjectIdMap = new HashMap<>();
            getChangedTreeObjectRecursive(commitChainItem.getCommitObjectId(), "", thisPath2TreeObjectIdMap);
            Set<String> changedTreeObjectIds = new HashSet<>();
            for (String path : thisPath2TreeObjectIdMap.keySet()) {
                if (parentPath2TreeObjectIdMap.get(path) != null && Objects.equals(parentPath2TreeObjectIdMap.get(path), thisPath2TreeObjectIdMap.get(path))) {
                    continue;
                }
                changedTreeObjectIds.add(thisPath2TreeObjectIdMap.get(path));
            }
            objectIdsToUpload.addAll(changedTreeObjectIds);

            // 上传commitObjectId
            objectIdsToUpload.add(commitChainItem.getCommitObjectId());
        }

        // upload with session, dont resort
        remoteStorage.uploadBatch(objectIdsToUpload.stream().map(x -> TransportMapping.of(ObjectUtils.getObjectPath(config.getObjectsDir(), x), PathUtils.concat("objects", ObjectUtils.path(x)))).collect(Collectors.toList()));

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
            remoteLogItem.setParentCommitObjectId(remoteCommitObjectId);
        }
        remoteLogItem.setCommitObjectId(localCommitLogItem.getCommitObjectId());
        remoteLogItem.setCommitterName(localCommitLogItem.getCommitterName());
        remoteLogItem.setCommitterEmail(localCommitLogItem.getCommitterEmail());
        remoteLogItem.setMessage("push");
        remoteLogItem.setMtime(System.currentTimeMillis());

        // todo: 并发push问题，开分支 or 加远程锁?
        String currRemoteLogsDir = PathUtils.concat(config.getLogsRemotesDir(), remoteName);
//        remoteStorage.download(PathUtils.concat("logs", "remotes", remoteName, "master.json"), new File(PathUtils.concat(currRemoteLogsDir, "master.json.lock")));
//        if (!Objects.equals(remoteLogManager.getLastLogItem().getCommitObjectId(), remoteCommitObjectId)) {
//            throw new RuntimeException("web remote head is changed, concurrent problem?");
//        }
        remoteLogManager.lock();
        remoteLogManager.appendToLock(remoteLogItem);

        // 4  上传日志
        try {
            //  upload log lock file to log file
            remoteStorage.upload(new File(PathUtils.concat(currRemoteLogsDir, "master.json.lock")),
                    PathUtils.concat("logs", "remotes", remoteName, "master.json"));
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

    private void checkWebRemoteStatus(String remoteName, Storage remoteStorage, LogManager remoteLogManager) throws IOException {
        String currRemoteLogsDir = PathUtils.concat(config.getLogsRemotesDir(), remoteName);
        File remoteLogFile = new File(PathUtils.concat(currRemoteLogsDir, "master.json"));
        File remoteLogLockFile = new File(PathUtils.concat(currRemoteLogsDir, "master.json.lock"));
        boolean webRemoteLogExists = remoteStorage.exists(PathUtils.concat("logs", "remotes", remoteName, "master.json"));
        if (!webRemoteLogExists && remoteLogFile.exists()) {
            throw new RuntimeException("web remote logs is empty, but file remote logs is not empty");
        }
        if (!webRemoteLogExists && !remoteLogFile.exists()) {
            return;
        }
        try {
            remoteStorage.download(PathUtils.concat("logs", "remotes", remoteName, "master.json"), remoteLogLockFile);
            List<LogItem> webRemoteLogItems = LogManager.getLogsFromFile(remoteLogLockFile);
            List<LogItem> fileRemoteLogItems = LogManager.getLogsFromFile(remoteLogFile);
            if (CollectionUtils.isEmpty(webRemoteLogItems) && CollectionUtils.isEmpty(fileRemoteLogItems)) {
                log.warn("web remote logs is empty, but file remote logs is not empty");
            }
            if (CollectionUtils.isEmpty(webRemoteLogItems) && CollectionUtils.isNotEmpty(fileRemoteLogItems)) {
                throw new RuntimeException("web remote logs is empty, but file remote logs is not empty");
            }
            if (CollectionUtils.isNotEmpty(webRemoteLogItems) && CollectionUtils.isEmpty(fileRemoteLogItems)) {
                throw new RuntimeException("file remote logs is empty/null");
            }
            assert webRemoteLogItems != null;
            assert fileRemoteLogItems != null;
            if (!webRemoteLogItems.containsAll(fileRemoteLogItems)) {
                throw new RuntimeException("file remote has some commit not push.");
            }
        } finally {
            if (remoteLogLockFile.exists()) {
                FileUtils.forceDelete(remoteLogLockFile);
            }
        }
    }

    // 包新不包旧
    @NotNull
    private List<CommitChainItem> getCommitChainByLog(List<LogItem> logs, String newerCommitObjectId, String olderCommitObjectId) throws IOException {
        List<CommitChainItem> chain = new LinkedList<>();
        boolean started = false;
        CommitChainItem startCommitChainItem = null;
        for (LogItem committedLog : logs) {
            if (!started && olderCommitObjectId == null) {
                CommitChainItem commitChainItem = new CommitChainItem();
                commitChainItem.setCommitObjectId(committedLog.getCommitObjectId());
                commitChainItem.setParent(null);
                startCommitChainItem = commitChainItem;
                chain.add(0, commitChainItem);
                started = true;
            } else if (!started && Objects.equals(committedLog.getCommitObjectId(), olderCommitObjectId)) {
                started = true;
                CommitChainItem commitChainItem = new CommitChainItem();
                commitChainItem.setCommitObjectId(committedLog.getCommitObjectId());
                commitChainItem.setParent(null);
                startCommitChainItem = commitChainItem;
            } else if (started && CollectionUtils.isEmpty(chain)) {
                CommitChainItem commitChainItem = new CommitChainItem();
                commitChainItem.setCommitObjectId(committedLog.getCommitObjectId());
                commitChainItem.setParent(startCommitChainItem);
                chain.add(0, commitChainItem);
            } else if (started && CollectionUtils.isNotEmpty(chain)) {
                CommitChainItem commitChainItem = new CommitChainItem();
                commitChainItem.setCommitObjectId(committedLog.getCommitObjectId());
                commitChainItem.setParent(chain.get(0));
                chain.add(0, commitChainItem);
            }
            if (Objects.equals(committedLog.getCommitObjectId(), newerCommitObjectId)) {
                break;
            }
        }
        return chain;
    }

    @Deprecated
    private void uploadCommitObjectAndTreeObjectRecursive(String objectId, Storage remoteStorage) throws IOException {
        File objectFile = ObjectUtils.getObjectFile(config.getObjectsDir(), objectId);
        ObjectEntity objectEntity = objectManager.read(objectId);
        switch (objectEntity.getType()) {
            case commit:
                remoteStorage.upload(objectFile, PathUtils.concat("objects", ObjectUtils.path(objectId)));
                CommitObjectData commitObjectData = CommitObjectData.parseFrom(objectEntity.getData());
                String tree = commitObjectData.getTree();
                uploadCommitObjectAndTreeObjectRecursive(tree, remoteStorage);
                break;
            case tree:
                remoteStorage.upload(objectFile, PathUtils.concat("objects", ObjectUtils.path(objectId)));
                TreeObjectData treeObjectData = TreeObjectData.parseFrom(objectEntity.getData());
                List<TreeObjectData.TreeEntry> entries = treeObjectData.getEntries();
                for (TreeObjectData.TreeEntry entry : entries) {
                    uploadCommitObjectAndTreeObjectRecursive(entry.getObjectId(), remoteStorage);
                }
                break;
            case blob:
                // do nothing
                break;
            default:
                throw new RuntimeException("type error");
        }
    }

    private void getChangedTreeObjectRecursive(String objectId, String path, Map<String, String> path2TreeObjectIdMap) throws IOException {
        ObjectEntity objectEntity = objectManager.read(objectId);
        switch (objectEntity.getType()) {
            case commit:
                CommitObjectData commitObjectData = CommitObjectData.parseFrom(objectEntity.getData());
                String tree = commitObjectData.getTree();
                path2TreeObjectIdMap.put("", tree);
                getChangedTreeObjectRecursive(tree, "", path2TreeObjectIdMap);
                break;
            case tree:
                TreeObjectData treeObjectData = TreeObjectData.parseFrom(objectEntity.getData());
                List<TreeObjectData.TreeEntry> entries = treeObjectData.getEntries();
                for (TreeObjectData.TreeEntry entry : entries) {
                    if (entry.getType() == ObjectEntity.Type.tree) {
                        String treePath = PathUtils.concat(path, entry.getName());
                        path2TreeObjectIdMap.put(treePath, entry.getObjectId());
                        getChangedTreeObjectRecursive(entry.getObjectId(), treePath, path2TreeObjectIdMap);
                    }
                }
                break;
            case blob:
                // do nothing
                break;
            default:
                throw new RuntimeException("type error");
        }
    }


    private void initRemoteDirs(String remoteName) throws IOException {
        Storage remoteStorage = remoteStorageMap.get(remoteName);
        if (remoteStorage == null) {
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

    @Data
    private static class CommitChainItem {
        private String commitObjectId;
        private CommitChainItem parent;
    }

}
