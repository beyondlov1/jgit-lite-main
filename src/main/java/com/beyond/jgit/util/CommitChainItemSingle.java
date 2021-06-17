package com.beyond.jgit.util;

import lombok.Data;

import java.io.IOException;

@Data
public class CommitChainItemSingle {
    private String commitObjectId;
    private CommitChainItemSingle parent;

    public void walk(Visitor visitor) throws IOException {
        visitor.visit(this);
        visitor.visit(parent);
    }

    public interface Visitor {
        void visit(CommitChainItemSingle item) throws IOException;
    }
}