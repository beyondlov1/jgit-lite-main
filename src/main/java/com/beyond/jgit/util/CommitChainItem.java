package com.beyond.jgit.util;

import lombok.Data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Data
public class CommitChainItem {
    private String commitObjectId;
    private List<CommitChainItem> parents = new ArrayList<>();

    public void walk(Visitor visitor) throws IOException {
        visitor.visit(this);
        for (CommitChainItem parent : getParents()) {
            visitor.visit(parent);
        }
    }

    public interface Visitor {
        void visit(CommitChainItem item) throws IOException;
    }
}