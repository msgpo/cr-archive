package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.*;
import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ArchiveMessages {
    private static final Pattern commentPattern = Pattern.compile("<!--.*?-->",
                                                                  Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern cutoffPattern = Pattern.compile("(.*?)<!-- Anything below this marker will be .*? -->",
                                                                 Pattern.DOTALL | Pattern.MULTILINE);
    private static String filterComments(String body) {
        var cutoffMatcher = cutoffPattern.matcher(body);
        if (cutoffMatcher.find()) {
            body = cutoffMatcher.group(1);
        }

        var commentMatcher = commentPattern.matcher(body);
        body = commentMatcher.replaceAll("");

        body = MarkdownToText.removeFormatting(body);
        return body.strip();
    }

    private static String formatCommitBrief(CommitMetadata commit) {
        var ret = new StringBuilder();
        var message = commit.message();
        var abbrev = commit.hash().abbreviate();
        if (message.size() == 0) {
            ret.append(" - ").append(abbrev).append(": <no commit message found>");
        } else {
            ret.append(" - ").append(message.get(0));
        }
        return ret.toString();
    }

    private static String formatSingleCommit(CommitMetadata commit) {
        var ret = new StringBuilder();
        var message = commit.message();
        if (message.size() == 0) {
            var abbrev = commit.hash().abbreviate();
            ret.append("  ").append(abbrev).append(": <no commit message found>");
        } else {
            ret.append("  ").append(String.join("\n  ", message));
        }
        return ret.toString();
    }

    private static String formatCommitInList(CommitMetadata commit) {
        var ret = new StringBuilder();
        var message = commit.message();
        if (message.size() == 0) {
            var abbrev = commit.hash().abbreviate();
            ret.append(" - ").append(abbrev).append(": <no commit message found>");
        } else {
            ret.append(" - ").append(String.join("\n   ", message));
        }
        return ret.toString();
    }

    private static Optional<String> formatCommitMessagesFull(Repository localRepo, Hash first, Hash last) {
        try  {
            var commits = localRepo.commitMetadata(first, last);
            if (commits.size() == 0) {
                return Optional.empty();
            } else if (commits.size() == 1) {
                return Optional.of(formatSingleCommit(commits.get(0)));
            } else {
                return Optional.of(commits.stream()
                                          .map(ArchiveMessages::formatCommitInList)
                                          .collect(Collectors.joining("\n")));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> formatCommitMessagesBrief(Repository localRepo, Hash first, Hash last) {
        try  {
            var commits = localRepo.commitMetadata(first, last);
            if (commits.size() == 0) {
                return Optional.empty();
            } else {
                return Optional.of(commits.stream()
                                          .map(ArchiveMessages::formatCommitBrief)
                                          .collect(Collectors.joining("\n")));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> issueUrl(PullRequest pr, URI issueTracker, String projectPrefix) {
        var issue = Issue.fromString(pr.title());
        return issue.map(value -> URIBuilder.base(issueTracker).appendPath(projectPrefix + "-" + value.id()).build().toString());
    }

    private static String stats(Repository localRepo, Hash base, Hash head) {
        try {
            var diff = localRepo.diff(base, head);
            var inserted = diff.added();
            var deleted = diff.removed();
            var modified = diff.modified();
            var linesChanged = inserted + deleted + modified;
            var filesChanged = diff.patches().size();
            return String.format("%d line%s in %d file%s changed: %d ins; %d del; %d mod",
                                 linesChanged,
                                 linesChanged == 1 ? "" : "s",
                                 filesChanged,
                                 filesChanged == 1 ? "" : "s",
                                 inserted,
                                 deleted,
                                 modified);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fetchCommand(PullRequest pr) {
        var repoUrl = pr.repository().webUrl();
        return "git fetch " + repoUrl + " " + pr.fetchRef() + ":pull/" + pr.id();
    }

    static String composeConversation(PullRequest pr, Repository localRepo, Hash base, Hash head) {
        var filteredBody = filterComments(pr.body());
        if (filteredBody.isEmpty()) {
            filteredBody = pr.title().strip();
        }

        return filteredBody;
    }

    static String composeIncrementalRevision(Repository localRepository, Hash head, Hash lastHead) {
        var ret = new StringBuilder();

        var incrementalUpdate = false;
        try {
            incrementalUpdate = localRepository.isAncestor(lastHead, head);
        } catch (IOException ignored) {
        }
        var newCommitMessages = formatCommitMessagesFull(localRepository, lastHead, head);
        if (incrementalUpdate) {
            ret.append("The pull request has been incrementally updated.");
            newCommitMessages.ifPresent(m -> ret.append(" The following content has been added to the pull request since the last revision:\n\n")
                                                .append(m));
        } else {
            ret.append("Previous commits in this pull request have been removed, probably due to a force push. ");
            ret.append("The incremental views will show differences compared to the previous content of the PR.");
            newCommitMessages.ifPresent(m -> ret.append(" The following content has been updated in the pull request since the last revision:\n\n")
                                                .append(m));
        }
        return ret.toString();
    }

    static String composeRebasedIncrementalRevision(Repository localRepository, Hash head, Hash lastHead) {
        var ret = new StringBuilder();

        ret.append("The pull request has been updated with a new target base due to a merge or a rebase. ");
        ret.append("The incremental webrev excludes the unrelated changes brought in by the merge/rebase.");

        var newCommitMessages = formatCommitMessagesFull(localRepository, lastHead, head);
        newCommitMessages.ifPresent(m -> ret.append(" The following content has been added to the pull request since the last revision:\n\n")
                                            .append(m));
        return ret.toString();
    }

    static String composeFullRevision(Repository localRepository, Hash base, Hash head) {
        var ret = new StringBuilder();

        ret.append("The pull request has been updated with a new target base due to a merge or a rebase.");

        var newCommitMessages = formatCommitMessagesFull(localRepository, base, head);
        newCommitMessages.ifPresent(m -> ret.append(" The current content of this pull request:\n\n").append(m));
        return ret.toString();
    }

    static String composeReplySubject(String parentSubject) {
        if (parentSubject.startsWith("Re: ")) {
            return parentSubject;
        } else {
            return "Re: " + parentSubject;
        }
    }

    static String composeReplyFooter(PullRequest pr) {
        return "PR: " + pr.webUrl();
    }

    // When changing this, ensure that the PR pattern in the notifier still matches
    static String composeConversationFooter(PullRequest pr, URI issueProject, String projectPrefix, Repository localRepo, URI webrev, Hash base, Hash head) {
        var issueString = issueUrl(pr, issueProject, projectPrefix).map(url -> "  Issue: " + url + "\n").orElse("");
        return "Commit messages:\n" +
                formatCommitMessagesBrief(localRepo, base, head).orElse("") + "\n\n" +
                "Changes: " + pr.changeUrl() + "\n" +
                " Webrev: " + webrev + "\n" +
                issueString +
                "  Stats: " + stats(localRepo, base, head) + "\n" +
                "  Patch: " + pr.diffUrl().toString() + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeRebasedFooter(PullRequest pr, Repository localRepo, URI fullWebrev, Hash base, Hash head) {
        return "Changes: " + pr.changeUrl() + "\n" +
                " Webrev: " + fullWebrev.toString() + "\n" +
                "  Stats: " + stats(localRepo, base, head) + "\n" +
                "  Patch: " + pr.diffUrl().toString() + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeIncrementalFooter(PullRequest pr, Repository localRepo, URI fullWebrev, URI incrementalWebrev, Hash head, Hash lastHead) {
        return "Changes:\n" +
                "  - all: " + pr.changeUrl() + "\n" +
                "  - new: " + pr.changeUrl(lastHead) + "\n\n" +
                "Webrevs:\n" +
                " - full: " + fullWebrev.toString() + "\n" +
                " - incr: " + incrementalWebrev.toString() + "\n\n" +
                "  Stats: " + stats(localRepo, lastHead, head) + "\n" +
                "  Patch: " + pr.diffUrl().toString() + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeComment(Comment comment) {
        return filterComments(comment.body());
    }

    static String composeReviewComment(PullRequest pr, ReviewComment reviewComment) {
        var body = new StringBuilder();

        // Add some context to the first post
        if (reviewComment.parent().isEmpty()) {
            body.append(reviewComment.path()).append(" line ").append(reviewComment.line()).append(":\n\n");
            try {
                var contents = pr.repository().fileContents(reviewComment.path(), reviewComment.hash().hex()).lines().collect(Collectors.toList());
                for (int i = Math.max(0, reviewComment.line() - 2); i < Math.min(contents.size(), reviewComment.line() + 1); ++i) {
                    body.append("> ").append(i + 1).append(": ").append(contents.get(i)).append("\n");
                }
                body.append("\n");
            } catch (RuntimeException e) {
                body.append("> (failed to retrieve contents of file, check the PR for context)\n");
            }
        }
        body.append(filterComments(reviewComment.body()));
        return body.toString();
    }

    private static String composeReviewVerdict(Review review, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole) {
        var result = new StringBuilder();
        if (review.verdict() != Review.Verdict.NONE) {
            if (review.verdict() == Review.Verdict.APPROVED) {
                result.append("Marked as reviewed");
            } else {
                result.append("Changes requested");
            }
            result.append(" by ");
            result.append(hostUserToUserName.userName(review.reviewer()));
            result.append(" (");
            result.append(hostUserToRole.role(review.reviewer()));
            result.append(").");
        }
        return result.toString();
    }

    static String composeReview(PullRequest pr, Review review, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole) {
        if (review.body().isPresent() && !review.body().get().isBlank()) {
            return filterComments(review.body().get());
        } else {
            return composeReviewVerdict(review, hostUserToUserName, hostUserToRole);
        }
    }

    static String composeReviewFooter(PullRequest pr, Review review, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole) {
        var result = new StringBuilder();
        if (review.body().isPresent() && !review.body().get().isBlank()) {
            result.append(composeReviewVerdict(review, hostUserToUserName, hostUserToRole));
            result.append("\n\n");
        }
        result.append(composeReplyFooter(pr));
        return result.toString();
    }

    static String composeReplyHeader(ZonedDateTime parentDate, EmailAddress parentAuthor) {
        return "On " + parentDate.format(DateTimeFormatter.RFC_1123_DATE_TIME) + ", " + parentAuthor.toString() + " wrote:";
    }
}
