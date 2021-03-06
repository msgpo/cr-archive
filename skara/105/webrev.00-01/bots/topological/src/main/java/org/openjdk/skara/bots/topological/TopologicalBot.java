/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.topological;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bot that automatically merges any changes from a dependency branch into a target branch
 */
class TopologicalBot implements Bot, WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");
    private final Path storage;
    private final HostedRepository hostedRepo;
    private final List<Branch> branches;
    private final String depsFileName;

    TopologicalBot(Path storage, HostedRepository repo, List<Branch> branches, String depsFileName) {
        this.storage = storage;
        this.hostedRepo = repo;
        this.branches = branches;
        this.depsFileName = depsFileName;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof TopologicalBot)) {
            return true;
        }
        var otherBot = (TopologicalBot) other;
        return !hostedRepo.getName().equals(otherBot.hostedRepo.getName());
    }

    @Override
    public void run(Path scratchPath) {
        log.info("Starting topobot run");
        try {
            var sanitizedUrl = URLEncoder.encode(hostedRepo.getWebUrl().toString(), StandardCharsets.UTF_8);
            var dir = storage.resolve(sanitizedUrl);
            Repository repo;
            if (!Files.exists(dir)) {
                log.info("Cloning " + hostedRepo.getName());
                Files.createDirectories(dir);
                repo = Repository.clone(hostedRepo.getUrl(), dir);
            } else {
                log.info("Found existing scratch directory for " + hostedRepo.getName());
                repo = Repository.get(dir)
                        .orElseThrow(() -> new RuntimeException("Repository in " + dir + " has vanished"));
            }

            repo.fetchAll();
            var depsFile = repo.root().resolve(depsFileName);

            var orderedBranches = orderedBranches(repo, depsFile);
            log.info("Merge order " + orderedBranches);
            for (var branch : orderedBranches) {
                log.info("Processing branch " + branch + "...");
                repo.checkout(branch);
                var parents = dependencies(depsFile).collect(Collectors.toSet());
                List<String> failedMerges = new ArrayList<>();
                boolean progress;
                boolean failed;
                do {
                    // We need to attempt merge parents in any order that works. Keep merging
                    // and pushing, until no further progress can be made.
                    progress = false;
                    failed = false;
                    for (var parentsIt = parents.iterator(); parentsIt.hasNext();) {
                        var parent = parentsIt.next();
                        try {
                            mergeIfAhead(repo, branch, parent);
                            progress = true;
                            parentsIt.remove(); // avoid doing pointless merges
                        } catch(IOException e) {
                            log.severe("Merge with " + parent + " failed. Reverting...");
                            repo.abortMerge();
                            failedMerges.add(branch + " <- " + parent);
                            failed = true;
                        }
                    }
                } while(progress && failed);

                if (!failedMerges.isEmpty()) {
                    throw new IOException("There were failed merges:\n" + failedMerges);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("Ending topobot run");
    }

    private static Stream<Branch> dependencies(Path depsFile) throws IOException {
        if (Files.exists(depsFile)) {
            var lines = Files.readAllLines(depsFile).stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (lines.size() > 1) {
                throw new IllegalStateException("Multiple non-empty lines in " + depsFile.toString() + ": "
                        + String.join("\n", lines));
            }
            return Stream.of(lines.get(0).split(" ")).map(Branch::new);
        } else {
            return Stream.of(new Branch("master"));
        }
    }

    private List<Branch> orderedBranches(Repository repo, Path depsFile) throws IOException {
        List<Edge> deps = new ArrayList<>();
        for (var branch : branches) {
            repo.checkout(branch);
            dependencies(depsFile).forEach(dep -> deps.add(new Edge(dep, branch)));
        }
        return TopologicalSort.tsort(deps).stream()
            .filter(branch -> !branch.name().equals("master"))
            .collect(Collectors.toList());
    }

    private void mergeIfAhead(Repository repo, Branch branch, Branch parent) throws IOException {
        var fromHash = repo.resolve(parent.name()).orElseThrow();
        if (!repo.contains(branch, fromHash)) {
            var isFastForward = repo.isAncestor(repo.head(), fromHash);
            repo.merge(fromHash);
            if (!isFastForward) {
                log.info("Merged " + parent + " into " + branch);
                repo.commit("Automatic merge with " + parent, "duke", "duke@openjdk.org");
            } else {
                log.info("Fast forwarded " + branch + " to " + parent);
            }
            log.info("merge with " + parent + " succeeded. The following commits will be pushed:\n"
                    + log(repo, "origin/" + branch.name(), branch.name()).collect(Collectors.joining("\n", "\n", "\n")));
            try {
                repo.push(repo.head(), hostedRepo.getUrl(), branch.name());
            } catch (IOException e) {
                log.severe("Pusing failed! Aborting...");
                repo.abortMerge();
                throw e;
            }
        }
    }

    private static Stream<String> log(Repository repo, String targetRef, String fromRef) throws IOException {
        var process = new ProcessBuilder()
                .command("git", "log", targetRef + ".." + fromRef, "--")
                .directory(repo.root().toFile())
                .start();
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("Unexpected exit code: " + exit + "\n\n"
                        + new BufferedReader(new InputStreamReader(process.getErrorStream()))
                            .lines()
                            .collect(Collectors.joining("\n")));
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        return new BufferedReader(new InputStreamReader(process.getInputStream())).lines();
    }

    @Override
    public String toString() {
        return "TopoBot@(" + hostedRepo + ")";
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }
}
