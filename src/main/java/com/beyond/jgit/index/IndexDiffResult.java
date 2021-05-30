package com.beyond.jgit.index;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class IndexDiffResult {
    private Set<Index.Entry> added = new HashSet<>();
    private Set<Index.Entry> removed = new HashSet<>();
    private Set<Index.Entry> updated = new HashSet<>();
}
