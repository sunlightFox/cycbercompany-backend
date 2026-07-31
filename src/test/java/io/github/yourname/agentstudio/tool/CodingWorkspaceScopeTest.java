package io.github.yourname.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CodingWorkspaceScopeTest {

    @Test
    void resolvesToolPathsInsideTheSelectedProject() {
        CodingWorkspaceScope scope = CodingWorkspaceScope.from("projects\\task-board");

        assertThat(scope.relativePath()).isEqualTo("projects/task-board");
        assertThat(scope.resolve(".")).isEqualTo("projects/task-board");
        assertThat(scope.resolve("src/main/App.java")).isEqualTo("projects/task-board/src/main/App.java");
    }

    @Test
    void rejectsAbsoluteAndParentEscapingPaths() {
        assertThatThrownBy(() -> CodingWorkspaceScope.from("D:\\outside"))
                .hasMessageContaining("workspace-relative");
        assertThatThrownBy(() -> CodingWorkspaceScope.from("projects/../outside"))
                .hasMessageContaining("must not leave");
        CodingWorkspaceScope scope = CodingWorkspaceScope.from("project");
        assertThatThrownBy(() -> scope.resolve("../outside.txt"))
                .hasMessageContaining("must not leave");
    }
}
