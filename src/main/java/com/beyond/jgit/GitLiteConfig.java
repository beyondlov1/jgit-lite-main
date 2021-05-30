package com.beyond.jgit;

import lombok.Data;


@Data
public class GitLiteConfig {
    private String localDir;
    private String headPath;
    private String indexPath;
    private String gitDir;
    private String objectsDir;
    private String refsDir;
    private String refsRemotesDir;
    private String refsHeadsDir;
    private String logsDir;
    private String logsRemotesDir;
    private String logsHeadsDir;

    private String committerName;
    private String committerEmail;

    private String remoteUrl;
    private String remoteUserName;
    private String remotePassword;

}
