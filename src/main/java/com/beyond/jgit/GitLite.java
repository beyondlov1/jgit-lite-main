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
    private final LogManager remoteLogManager;
    private final LogManager localLogManager;
    private final Storage remoteStorage;

    private final GitLiteConfig config;

    public GitLite(GitLiteConfig config) {
        this.config = config;
        this.objectManager = new ObjectManager(config.getObjectsDir());
        this.indexManager = new IndexManager(config.getIndexPath());
        this.localLogManager = new LogManager(PathUtils.concat(config.getLogsHeadsDir(), "master.json"));
        this.remoteLogManager = new LogManager(PathUtils.concat(config.getLogsRemotesDir(), "master.json"));
        this.remoteStorage = new FileStorage(PathUtils.concat(config.getRemoteUrl(),".git"));
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

    public String commit() throws IOException {
        return commit(IndexManager.parseIndex(config.getIndexPath()));
    }

    public String commit(Index index) throws IOException {
        ObjectEntity tree = addTreeFromIndex(index);
        ObjectEntity commit = addCommitObject(tree);
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

    private ObjectEntity addCommitObject(ObjectEntity tree) throws IOException {
        CommitObjectData commitObjectData = new CommitObjectData();
        commitObjectData.setTree(ObjectUtils.sha1hash(tree));
        commitObjectData.setCommitTime(1620313388000L);
        CommitObjectData.User user = new CommitObjectData.User();
        user.setName(config.getCommitterName());
        user.setEmail(config.getCommitterEmail());
        commitObjectData.setCommitter(user);
        commitObjectData.setAuthor(user);
        commitObjectData.setMessage("a");
        commitObjectData.addParent("463783ca68ec49b4630ffbf5c35264461a3b2174"); // fixme: 找上一个提交

        ObjectEntity commitObjectEntity = new ObjectEntity();
        commitObjectEntity.setType(ObjectEntity.Type.commit);
        commitObjectEntity.setData(commitObjectData.toBytes());

        String commitObjectId = objectManager.write(commitObjectEntity);
        log.debug("commitObjectId: {}", commitObjectId);

        return commitObjectEntity;
    }


    public void fetch() throws IOException {
        // fetch remote head to remote head lock
        // locked?
        File remoteHeadFile = new File(PathUtils.concat(config.getRefsRemotesDir(), "master"));
        File remoteHeadLockFile = new File(PathUtils.concat(config.getRefsRemotesDir(), "master.lock"));
        remoteStorage.download(PathUtils.concat("refs","remotes","master"), remoteHeadLockFile);
        // fetch remote logs to remote logs lock
        remoteStorage.download(PathUtils.concat("logs","remotes","master.json"), new File(PathUtils.concat(config.getLogsRemotesDir(),"master.json.lock")));

        List<LogItem> logs = remoteLogManager.getLogs();
        String remoteHeadObjectId = FileUtils.readFileToString(remoteHeadFile, StandardCharsets.UTF_8);
        String remoteHeadLockObjectId = FileUtils.readFileToString(remoteHeadLockFile, StandardCharsets.UTF_8);
        int remoteLockIndex = ListUtils.indexOf(logs, x -> StringUtils.equals(x.getCommitObjectId(), remoteHeadLockObjectId));
        int remoteIndex = ListUtils.indexOf(logs, x -> StringUtils.equals(x.getCommitObjectId(), remoteHeadObjectId));
        if (remoteLockIndex < remoteIndex){
            log.warn("remote in file is after remote in web");
            return;
        }
        if (remoteLockIndex == remoteIndex){
            log.warn("Already up to date.");
            return;
        }

        // 根据 remote head 判断需要下载那些objects
        String remoteCommitObjectId = findRemoteCommitObjectId();
        String remoteLockCommitObjectId = findRemoteLockCommitObjectId();

        Index remoteHeadIndex = Index.generateFromCommit(remoteCommitObjectId, objectManager);
        Index remoteLockHeadIndex = Index.generateFromCommit(remoteLockCommitObjectId, objectManager);

        IndexDiffResult diffToFetch = IndexDiffer.diff(remoteLockHeadIndex, remoteHeadIndex);
        log.debug("diffToFetch: {}", JsonUtils.writeValueAsString(diffToFetch));

        Set<Index.Entry> changedEntries = new HashSet<>();
        changedEntries.addAll(diffToFetch.getAdded());
        changedEntries.addAll(diffToFetch.getUpdated());

        // fetch objects
        for (Index.Entry changedEntry : changedEntries) {
            remoteStorage.download(PathUtils.concat("objects",ObjectUtils.path(changedEntry.getObjectId())), ObjectUtils.getObjectFile(config.getObjectsDir(), changedEntry.getObjectId()));
        }

        // update logs
        try {
            remoteStorage.download(PathUtils.concat("logs","remotes","master.json"), new File(PathUtils.concat(config.getLogsRemotesDir(),"master.json.lock")));
        }catch (Exception e){
            log.error("download remote logs fail", e);
            remoteLogManager.rollback();
            throw e;
        }

        // update head
        remoteStorage.download(PathUtils.concat("refs","remotes","master"), remoteHeadLockFile);

        remoteLogManager.commit();
        Files.move(remoteHeadLockFile.toPath(), remoteHeadFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

    }




    public void merge() throws IOException {
        // todo hard
        // 找不同
        // 1. 创建目录结构列表
        // 2. 对比基础commit和新commit之间的文件路径差异(列出那些文件是添加， 那些是删除， 那些是更新)
        // 3. local和remote找到共同的提交历史， 对比变化
        // 4. 对比变化的差异（以文件路径为key)
        // fixme: 根据log查询local和remote共同经历的最后一个commit

        List<LogItem> committedLogs = localLogManager.getLogs();
        List<LogItem> remoteLogs = remoteLogManager.getLogs();

        if (committedLogs == null || remoteLogs == null) {
            return;
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
            log.warn("无相同的commitObjectId, 未合并");
            return;
        }

        String localCommitObjectId = findLocalCommitObjectId();
        String remoteCommitObjectId = findRemoteCommitObjectId();

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

        commit();

        push();
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

    private String findRemoteCommitObjectId() throws IOException {
        String headPath = config.getHeadPath();
        String ref = FileUtils.readFileToString(new File(headPath), StandardCharsets.UTF_8);
        String relativePath = StringUtils.trim(StringUtils.substringAfter(ref, "ref: "));
        relativePath = relativePath.replace("/heads/", "/remotes/");
        File file = new File(config.getGitDir(), relativePath);
        if (!file.exists()) {
            return null;
        }
        return StringUtils.trim(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
    }

    private String findRemoteLockCommitObjectId() throws IOException {
        String headPath = config.getHeadPath();
        String ref = FileUtils.readFileToString(new File(headPath), StandardCharsets.UTF_8);
        String relativePath = StringUtils.trim(StringUtils.substringAfter(ref, "ref: "));
        relativePath = relativePath.replace("/heads/", "/remotes/");
        File file = new File(config.getGitDir(), relativePath + ".lock");
        if (!file.exists()) {
            return null;
        }
        return StringUtils.trim(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
    }

    public void push() throws IOException {
        // fetch哪些就push哪些
        // 1. 根据最新commitObjectId获取更新了那些文件
        String localCommitObjectId = findLocalCommitObjectId();
        String remoteCommitObjectId = findRemoteCommitObjectId();

        Index committedHeadIndex = Index.generateFromCommit(localCommitObjectId, objectManager);
        Index remoteHeadIndex = Index.generateFromCommit(remoteCommitObjectId, objectManager);

        IndexDiffResult committedDiff = IndexDiffer.diff(committedHeadIndex, remoteHeadIndex);
        log.debug("committedDiff to push: {}", JsonUtils.writeValueAsString(committedDiff));

        initRemoteDirs();

        // 2. 上传objects
        Set<Index.Entry> changedEntries = new HashSet<>();
        changedEntries.addAll(committedDiff.getAdded());
        changedEntries.addAll(committedDiff.getUpdated());
        //  upload
        Set<String> dirs = changedEntries.stream().map(x -> PathUtils.parent(ObjectUtils.path(x.getObjectId()))).map(x->PathUtils.concat("objects",x)).collect(Collectors.toSet());
        remoteStorage.mkdir(dirs);
        for (Index.Entry changedEntry : changedEntries) {
            File objectFile = ObjectUtils.getObjectFile(config.getObjectsDir(), changedEntry.getObjectId());
            remoteStorage.upload(objectFile,  PathUtils.concat("objects", ObjectUtils.path(changedEntry.getObjectId())));
        }

        // 3. 写remote日志(异常回退)
        LogItem localCommitLogItem = localLogManager.getLogs().stream().filter(x -> Objects.equals(x.getCommitObjectId(), localCommitObjectId)).findFirst().orElse(null);
        if (localCommitLogItem == null) {
            throw new RuntimeException("log file error, maybe missing some commit");
        }
        remoteLogManager.lock();
        remoteLogManager.appendToLock(localCommitLogItem);

        // 4  上传日志
        try {
            //  upload log lock file to log file
            remoteStorage.upload(new File(PathUtils.concat(config.getLogsRemotesDir(),"master.json.lock")),
                    PathUtils.concat("logs", "remotes", "master.json"));
            remoteLogManager.commit();
        } catch (Exception e) {
            log.error("上传日志失败", e);
            remoteLogManager.rollback();
            throw e;
        }

        // 5. 修改本地remote的head(异常回退)
        File remoteHeadFile = new File(config.getRefsRemotesDir(), "master");
        File remoteHeadLockFile = new File(remoteHeadFile.getAbsolutePath()+".lock");
        FileUtils.copyFile(remoteHeadFile, remoteHeadLockFile);
        FileUtils.writeStringToFile(remoteHeadLockFile, localCommitObjectId, StandardCharsets.UTF_8);

        // 6. 上传remote的head
        try {
            //  upload remote head lock to remote head
            remoteStorage.upload(new File(PathUtils.concat(config.getRefsRemotesDir(),"master.lock")),
                    PathUtils.concat("refs", "remotes", "master"));
            Files.move(remoteHeadLockFile.toPath(), remoteHeadFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.error("上传日志失败", e);
            FileUtils.forceDelete(remoteHeadLockFile);
            throw e;
        }

    }

    private void initRemoteDirs() throws IOException {
        if (!remoteStorage.exists(PathUtils.concat("refs","remotes"))){
            remoteStorage.mkdir("");
            remoteStorage.mkdir("objects");
            remoteStorage.mkdir("logs");
            remoteStorage.mkdir(PathUtils.concat("logs","remotes"));
            remoteStorage.mkdir("refs");
            remoteStorage.mkdir(PathUtils.concat("refs","remotes"));
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


    public static void main(String[] args) throws IOException {
        GitLiteConfig config = new GitLiteConfig();
        config.setLocalDir("/home/beyond/Documents/GitHubProject/test-jgit");
        config.setGitDir(PathUtils.concat("/home/beyond/Documents/tmp-git-2/", ".git"));
        config.setHeadPath(PathUtils.concat(config.getGitDir(), "HEAD"));
        config.setIndexPath(PathUtils.concat(config.getGitDir(), "index.json"));
        config.setObjectsDir(PathUtils.concat(config.getGitDir(), "objects"));
        config.setRefsDir(PathUtils.concat(config.getGitDir(), "refs"));
        config.setRefsRemotesDir(PathUtils.concat(config.getGitDir(), "refs", "remotes"));
        config.setRefsHeadsDir(PathUtils.concat(config.getGitDir(), "refs", "heads"));
        config.setLogsDir(PathUtils.concat(config.getGitDir(), "logs"));
        config.setLogsHeadsDir(PathUtils.concat(config.getLogsDir(), "heads"));
        config.setLogsRemotesDir(PathUtils.concat(config.getLogsDir(), "remotes"));
        config.setCommitterName("beyondlov1");
        config.setCommitterEmail("beyondlov1@hotmail.com");
        config.setRemoteUrl("/home/beyond/Documents/tmp-git-2-remote");
        config.setRemoteUserName("");
        config.setRemotePassword("");

        GitLite gitLite = new GitLite(config);
        gitLite.init();
//        gitLite.add();
//        gitLite.commit();
//        gitLite.merge();
//        gitLite.push();
        gitLite.fetch();
    }
}
