package io.github.yourname.cycbercompany.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourname.cycbercompany.security.ActorContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionSettingsServiceTest {

    private static final ActorContext ACTOR = new ActorContext("tenant-a", "alice", Set.of(), Set.of());

    @Mock
    private ExecutionSettingsRepository settings;

    @Test
    void rejectsNodesOnlyModeWhenTheBackendHasNotExplicitlyOptedIn() {
        ExecutionSettingsService service = new ExecutionSettingsService(settings, false);

        assertThatThrownBy(() -> service.update(
                new UpdateExecutionSettingsCommand(ExecutionMode.NODES_ONLY), ACTOR))
                .isInstanceOf(ExecutionModeChangeNotAllowedException.class)
                .hasMessageContaining("APP_EXECUTION_ALLOW_NODES_ONLY=true");

        verify(settings, never()).save(any());
    }

    @Test
    void permitsExplicitlyEnabledDedicatedEvaluationBackend() {
        ExecutionSettingsService service = new ExecutionSettingsService(settings, true);
        when(settings.findById("tenant-a")).thenReturn(Optional.empty());

        ExecutionSettingsView updated = service.update(
                new UpdateExecutionSettingsCommand(ExecutionMode.NODES_ONLY), ACTOR);

        assertThat(updated.mode()).isEqualTo(ExecutionMode.NODES_ONLY);
        verify(settings).save(any(ExecutionSettingsEntity.class));
    }

    @Test
    void alwaysAllowsRestoringTheSafePersonalLocalMode() {
        ExecutionSettingsService service = new ExecutionSettingsService(settings, false);
        when(settings.findById("tenant-a")).thenReturn(Optional.empty());

        ExecutionSettingsView updated = service.update(
                new UpdateExecutionSettingsCommand(ExecutionMode.PERSONAL_LOCAL), ACTOR);

        assertThat(updated.mode()).isEqualTo(ExecutionMode.PERSONAL_LOCAL);
        verify(settings).save(any(ExecutionSettingsEntity.class));
    }
}
