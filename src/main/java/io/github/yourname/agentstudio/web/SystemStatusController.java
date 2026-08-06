package io.github.yourname.agentstudio.web;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A small, safe status surface for the local UI and launchers. */
@RestController
@RequestMapping("/api/v1/system")
final class SystemStatusController {

    private final JdbcTemplate jdbcTemplate;

    SystemStatusController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/status")
    ResponseEntity<SystemStatusView> status() {
        try {
            jdbcTemplate.queryForObject("select count(*) from model_profile", Long.class);
            jdbcTemplate.queryForObject("select count(*) from agent_definition", Long.class);
            jdbcTemplate.queryForObject("select count(*) from run_execution_outbox", Long.class);
            return ResponseEntity.ok(new SystemStatusView("READY", null, "Control plane is ready."));
        } catch (DataAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new SystemStatusView(
                            "UNHEALTHY",
                            "PERSISTENCE_UNAVAILABLE",
                            "The control-plane data store is unavailable."));
        }
    }

    record SystemStatusView(String status, String code, String message) {}
}
