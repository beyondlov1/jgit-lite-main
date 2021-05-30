package com.beyond.jgit.diff;

import com.beyond.jgit.index.Index;
import com.beyond.jgit.index.IndexDiffResult;
import com.beyond.jgit.index.IndexDiffer;
import com.beyond.jgit.object.ObjectManager;
import com.beyond.jgit.util.JsonUtils;
import org.junit.Test;

import java.io.IOException;

public class DefaultCommitDifferTest {

    @Test
    public void diff() throws IOException {
        DefaultCommitDiffer differ = new DefaultCommitDiffer("/media/beyond/70f23ead-fa6d-4628-acf7-c82133c03245/home/beyond/Documents/tmp-git",
                "/media/beyond/70f23ead-fa6d-4628-acf7-c82133c03245/home/beyond/Documents/tmp-git");
        ObjectDiffResult diff = differ.diff("e17f1f8cb806da2bdfbf46f126c022689bedf238", "e8fc4dbf18e4e9de9d3157ffece0c21b5b94e0f1");
        System.out.println(diff);


        Index index = Index.generateFromCommit("e17f1f8cb806da2bdfbf46f126c022689bedf238",
                new ObjectManager("/media/beyond/70f23ead-fa6d-4628-acf7-c82133c03245/home/beyond/Documents/tmp-git"));
        Index base = Index.generateFromCommit("e8fc4dbf18e4e9de9d3157ffece0c21b5b94e0f1",
                new ObjectManager("/media/beyond/70f23ead-fa6d-4628-acf7-c82133c03245/home/beyond/Documents/tmp-git"));
        IndexDiffResult indexDiffResult = IndexDiffer.diff(index, base);
        System.out.println(JsonUtils.writeValueAsString(indexDiffResult));
    }
}