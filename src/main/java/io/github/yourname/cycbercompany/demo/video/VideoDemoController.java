package io.github.yourname.cycbercompany.demo.video;

import io.github.yourname.cycbercompany.media.MediaCatalogView;
import io.github.yourname.cycbercompany.media.MediaGatewayService;
import io.github.yourname.cycbercompany.media.MediaProgressCommand;
import io.github.yourname.cycbercompany.media.MediaProgressView;
import io.github.yourname.cycbercompany.media.MediaPlaybackView;
import io.github.yourname.cycbercompany.media.MediaResolveCommand;
import io.github.yourname.cycbercompany.media.MediaRuntimeStatusView;
import io.github.yourname.cycbercompany.media.MediaSearchView;
import io.github.yourname.cycbercompany.security.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** HTTP surface shipped by the Video Demo, not by the platform core. */
@RestController
@RequestMapping("/api/v1/media")
final class VideoDemoController {
    private final VideoDemoService demo;
    private final CurrentActorProvider actors;

    VideoDemoController(VideoDemoService demo, CurrentActorProvider actors) {
        this.demo = demo;
        this.actors = actors;
    }

    @GetMapping("/catalog")
    MediaCatalogView catalog(@RequestParam(required = false) String sourceUrl) {
        return demo.catalog(sourceUrl);
    }

    @GetMapping("/search")
    MediaSearchView search(@RequestParam String query,
                           @RequestParam(required = false) String sourceUrl,
                           @RequestParam(required = false) String sourceId) {
        return demo.search(query, sourceUrl, sourceId);
    }

    @PostMapping("/resolve")
    MediaPlaybackView resolve(@Valid @RequestBody MediaResolveCommand command,
                              @RequestParam(required = false) String sourceUrl) {
        return demo.resolve(command, sourceUrl);
    }

    @GetMapping("/stream/{token}")
    ResponseEntity<StreamingResponseBody> stream(@PathVariable String token,
                                                  @RequestHeader(value = "Range", required = false) String range) {
        MediaGatewayService.GatewayStream value = demo.stream(token, range);
        if (value == null) return ResponseEntity.notFound().build();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept-Ranges", value.responseHeaders().getOrDefault("Accept-Ranges", "bytes"));
        value.responseHeaders().forEach(headers::set);
        if (value.contentType() != null && !value.contentType().isBlank()) {
            headers.set(HttpHeaders.CONTENT_TYPE, value.contentType());
        } else {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        if (value.contentLength() >= 0) headers.setContentLength(value.contentLength());
        StreamingResponseBody body = output -> {
            if (value.body() != null) {
                output.write(value.body());
                return;
            }
            try (var input = value.stream()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.status(value.status()).headers(headers).body(body);
    }

    @GetMapping("/runtime")
    MediaRuntimeStatusView runtime() {
        return demo.runtime();
    }

    @GetMapping("/progress")
    MediaProgressView progress(@RequestParam String modId, @RequestParam String mediaId, HttpServletRequest request) {
        return demo.progress(modId, mediaId, actors.current(request));
    }

    @PutMapping("/progress")
    @ResponseStatus(HttpStatus.OK)
    MediaProgressView saveProgress(@Valid @RequestBody MediaProgressCommand command, HttpServletRequest request) {
        return demo.saveProgress(command, actors.current(request));
    }
}
