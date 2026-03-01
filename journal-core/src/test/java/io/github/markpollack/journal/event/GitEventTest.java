package io.github.markpollack.journal.event;

import io.github.markpollack.journal.event.GitBranchEvent.BranchAction;
import io.github.markpollack.journal.event.GitPatchEvent.FileChange;
import io.github.markpollack.journal.event.GitPatchEvent.FileChange.ChangeType;
import io.github.markpollack.journal.event.GitPullRequestEvent.PullRequestAction;
import io.github.markpollack.journal.test.BaseTrackingTest;
import io.github.markpollack.journal.test.TestEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for Git event types.
 */
@DisplayName("Git Events")
class GitEventTest extends BaseTrackingTest {

    @Nested
    @DisplayName("GitPatchEvent")
    class GitPatchEventTests {

        @Test
        @DisplayName("creates patch with computed line counts")
        void createsWithComputedLineCounts() {
            var patch = GitPatchEvent.of("main", List.of(
                    FileChange.modified("src/A.java", 10, 5),
                    FileChange.added("src/B.java", 20)
            ));

            assertThat(patch.linesAdded()).isEqualTo(30);
            assertThat(patch.linesRemoved()).isEqualTo(5);
            assertThat(patch.fileChanges()).hasSize(2);
            assertThat(patch.patchContent()).isNull();
        }

        @Test
        @DisplayName("creates patch with content")
        void createsWithContent() {
            var patch = TestEvents.gitPatchWithContent();

            assertThat(patch.patchContent()).isNotNull();
            assertThat(patch.patchContent()).contains("diff --git");
        }

        @Test
        @DisplayName("returns correct type")
        void returnsCorrectType() {
            var patch = TestEvents.gitPatch();
            assertThat(patch.type()).isEqualTo("git_patch");
        }

        @Test
        @DisplayName("toMap includes required fields")
        void toMapIncludesRequiredFields() {
            var patch = TestEvents.gitPatch();
            Map<String, Object> map = patch.toMap();

            assertThat(map).containsKey("base_branch");
            assertThat(map).containsKey("files_changed");
            assertThat(map).containsKey("lines_added");
            assertThat(map).containsKey("lines_removed");
        }

        @Test
        @DisplayName("toMap excludes null patch content")
        void toMapExcludesNullPatchContent() {
            var patch = TestEvents.gitPatch();
            Map<String, Object> map = patch.toMap();

            assertThat(map).doesNotContainKey("patch_content");
        }

        @Test
        @DisplayName("toMap includes patch content when present")
        void toMapIncludesPatchContent() {
            var patch = TestEvents.gitPatchWithContent();
            Map<String, Object> map = patch.toMap();

            assertThat(map).containsKey("patch_content");
        }

        @Test
        @DisplayName("fileChanges list is immutable")
        void fileChangesIsImmutable() {
            var patch = TestEvents.gitPatch();

            assertThatThrownBy(() -> patch.fileChanges().add(FileChange.added("x", 1)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("FileChange")
    class FileChangeTests {

        @Test
        @DisplayName("creates added file")
        void createsAddedFile() {
            var change = FileChange.added("src/New.java", 50);

            assertThat(change.path()).isEqualTo("src/New.java");
            assertThat(change.changeType()).isEqualTo(ChangeType.ADDED);
            assertThat(change.linesAdded()).isEqualTo(50);
            assertThat(change.linesRemoved()).isEqualTo(0);
        }

        @Test
        @DisplayName("creates modified file")
        void createsModifiedFile() {
            var change = FileChange.modified("src/Existing.java", 20, 10);

            assertThat(change.changeType()).isEqualTo(ChangeType.MODIFIED);
            assertThat(change.linesAdded()).isEqualTo(20);
            assertThat(change.linesRemoved()).isEqualTo(10);
        }

        @Test
        @DisplayName("creates deleted file")
        void createsDeletedFile() {
            var change = FileChange.deleted("src/Old.java", 100);

            assertThat(change.changeType()).isEqualTo(ChangeType.DELETED);
            assertThat(change.linesAdded()).isEqualTo(0);
            assertThat(change.linesRemoved()).isEqualTo(100);
        }

        @Test
        @DisplayName("creates renamed file")
        void createsRenamedFile() {
            var change = FileChange.renamed("src/NewName.java");

            assertThat(change.changeType()).isEqualTo(ChangeType.RENAMED);
            assertThat(change.linesAdded()).isEqualTo(0);
            assertThat(change.linesRemoved()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("GitCommitEvent")
    class GitCommitEventTests {

        @Test
        @DisplayName("creates simple commit with of()")
        void createsSimpleCommit() {
            var commit = GitCommitEvent.of("abc123def456", "Test message", "main");

            assertThat(commit.sha()).isEqualTo("abc123def456");
            assertThat(commit.shortSha()).isEqualTo("abc123d");
            assertThat(commit.message()).isEqualTo("Test message");
            assertThat(commit.branch()).isEqualTo("main");
            assertThat(commit.filesChanged()).isEmpty();
        }

        @Test
        @DisplayName("creates commit with builder")
        void createsCommitWithBuilder() {
            var commit = GitCommitEvent.builder()
                    .sha("abc123def456789")
                    .message("Feature implementation")
                    .branch("feature/test")
                    .filesChanged(List.of("a.java", "b.java"))
                    .linesAdded(100)
                    .linesRemoved(20)
                    .build();

            assertThat(commit.sha()).isEqualTo("abc123def456789");
            assertThat(commit.shortSha()).isEqualTo("abc123d");
            assertThat(commit.filesChanged()).hasSize(2);
            assertThat(commit.linesAdded()).isEqualTo(100);
            assertThat(commit.linesRemoved()).isEqualTo(20);
        }

        @Test
        @DisplayName("builder requires sha")
        void builderRequiresSha() {
            var builder = GitCommitEvent.builder()
                    .message("Test")
                    .branch("main");

            assertThatThrownBy(builder::build)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sha");
        }

        @Test
        @DisplayName("builder requires message")
        void builderRequiresMessage() {
            var builder = GitCommitEvent.builder()
                    .sha("abc123")
                    .branch("main");

            assertThatThrownBy(builder::build)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("message");
        }

        @Test
        @DisplayName("builder requires branch")
        void builderRequiresBranch() {
            var builder = GitCommitEvent.builder()
                    .sha("abc123")
                    .message("Test");

            assertThatThrownBy(builder::build)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("branch");
        }

        @Test
        @DisplayName("returns correct type")
        void returnsCorrectType() {
            var commit = TestEvents.gitCommit();
            assertThat(commit.type()).isEqualTo("git_commit");
        }

        @Test
        @DisplayName("toMap includes required fields")
        void toMapIncludesRequiredFields() {
            var commit = TestEvents.gitCommitComplete();
            Map<String, Object> map = commit.toMap();

            assertThat(map).containsKey("sha");
            assertThat(map).containsKey("short_sha");
            assertThat(map).containsKey("message");
            assertThat(map).containsKey("branch");
            assertThat(map).containsKey("files_changed");
            assertThat(map).containsKey("files_count");
            assertThat(map).containsKey("lines_added");
            assertThat(map).containsKey("lines_removed");
        }

        @Test
        @DisplayName("toMap excludes empty file list")
        void toMapExcludesEmptyFileList() {
            var commit = TestEvents.gitCommit();
            Map<String, Object> map = commit.toMap();

            assertThat(map).doesNotContainKey("files_changed");
            assertThat(map).doesNotContainKey("files_count");
        }

        @Test
        @DisplayName("filesChanged list is immutable")
        void filesChangedIsImmutable() {
            var commit = TestEvents.gitCommitComplete();

            assertThatThrownBy(() -> commit.filesChanged().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("GitBranchEvent")
    class GitBranchEventTests {

        @Test
        @DisplayName("creates branch created event")
        void createsBranchCreated() {
            var event = GitBranchEvent.created("feature/test", "main");

            assertThat(event.branchName()).isEqualTo("feature/test");
            assertThat(event.action()).isEqualTo(BranchAction.CREATED);
            assertThat(event.fromRef()).isEqualTo("main");
        }

        @Test
        @DisplayName("creates branch checked out event")
        void createsBranchCheckedOut() {
            var event = GitBranchEvent.checkedOut("develop");

            assertThat(event.branchName()).isEqualTo("develop");
            assertThat(event.action()).isEqualTo(BranchAction.CHECKED_OUT);
            assertThat(event.fromRef()).isNull();
        }

        @Test
        @DisplayName("creates branch deleted event")
        void createsBranchDeleted() {
            var event = GitBranchEvent.deleted("feature/old");

            assertThat(event.branchName()).isEqualTo("feature/old");
            assertThat(event.action()).isEqualTo(BranchAction.DELETED);
            assertThat(event.fromRef()).isNull();
        }

        @Test
        @DisplayName("returns correct type")
        void returnsCorrectType() {
            var event = TestEvents.gitBranchCreated();
            assertThat(event.type()).isEqualTo("git_branch");
        }

        @Test
        @DisplayName("toMap includes required fields")
        void toMapIncludesRequiredFields() {
            var event = TestEvents.gitBranchCreated();
            Map<String, Object> map = event.toMap();

            assertThat(map).containsKey("branch");
            assertThat(map).containsKey("action");
            assertThat(map).containsKey("from_ref");
        }

        @Test
        @DisplayName("toMap excludes null fromRef")
        void toMapExcludesNullFromRef() {
            var event = GitBranchEvent.checkedOut("develop");
            Map<String, Object> map = event.toMap();

            assertThat(map).doesNotContainKey("from_ref");
        }
    }

    @Nested
    @DisplayName("GitPullRequestEvent")
    class GitPullRequestEventTests {

        @Test
        @DisplayName("creates PR created event")
        void createsPrCreated() {
            var event = GitPullRequestEvent.created("123", "https://github.com/test/pr/123",
                    "Test PR", "feature/test", "main");

            assertThat(event.prNumber()).isEqualTo("123");
            assertThat(event.prUrl()).isEqualTo("https://github.com/test/pr/123");
            assertThat(event.title()).isEqualTo("Test PR");
            assertThat(event.sourceBranch()).isEqualTo("feature/test");
            assertThat(event.targetBranch()).isEqualTo("main");
            assertThat(event.action()).isEqualTo(PullRequestAction.CREATED);
        }

        @Test
        @DisplayName("creates PR updated event")
        void createsPrUpdated() {
            var event = GitPullRequestEvent.updated("123", "url", "Title", "src", "target");
            assertThat(event.action()).isEqualTo(PullRequestAction.UPDATED);
        }

        @Test
        @DisplayName("creates PR merged event")
        void createsPrMerged() {
            var event = GitPullRequestEvent.merged("123", "url", "Title", "src", "target");
            assertThat(event.action()).isEqualTo(PullRequestAction.MERGED);
        }

        @Test
        @DisplayName("creates PR closed event")
        void createsPrClosed() {
            var event = GitPullRequestEvent.closed("123", "url", "Title", "src", "target");
            assertThat(event.action()).isEqualTo(PullRequestAction.CLOSED);
        }

        @Test
        @DisplayName("returns correct type")
        void returnsCorrectType() {
            var event = TestEvents.gitPrCreated();
            assertThat(event.type()).isEqualTo("git_pull_request");
        }

        @Test
        @DisplayName("toMap includes all fields")
        void toMapIncludesAllFields() {
            var event = TestEvents.gitPrCreated();
            Map<String, Object> map = event.toMap();

            assertThat(map).containsKey("pr_number");
            assertThat(map).containsKey("pr_url");
            assertThat(map).containsKey("title");
            assertThat(map).containsKey("source_branch");
            assertThat(map).containsKey("target_branch");
            assertThat(map).containsKey("action");
            assertThat(map.get("action")).isEqualTo("created");
        }
    }

    @Nested
    @DisplayName("GitEvent hierarchy")
    class GitEventHierarchyTests {

        @Test
        @DisplayName("all git events implement GitEvent")
        void allGitEventsImplementGitEvent() {
            var events = TestEvents.allGitEventTypes();

            assertThat(events).hasSize(4);
            assertThat(events).allSatisfy(event -> assertThat(event).isInstanceOf(GitEvent.class));
        }

        @Test
        @DisplayName("all git events have timestamps")
        void allGitEventsHaveTimestamps() {
            var events = TestEvents.allGitEventTypes();

            assertThat(events).allSatisfy(event -> assertThat(event.timestamp()).isNotNull());
        }

        @Test
        @DisplayName("all git events have type")
        void allGitEventsHaveType() {
            var events = TestEvents.allGitEventTypes();

            assertThat(events).allSatisfy(event -> {
                assertThat(event.type()).isNotNull();
                assertThat(event.type()).startsWith("git_");
            });
        }

        @Test
        @DisplayName("all git events have toMap")
        void allGitEventsHaveToMap() {
            var events = TestEvents.allGitEventTypes();

            assertThat(events).allSatisfy(event -> assertThat(event.toMap()).isNotEmpty());
        }
    }
}
