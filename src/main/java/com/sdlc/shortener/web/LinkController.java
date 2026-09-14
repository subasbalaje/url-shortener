package com.sdlc.shortener.web;

import com.sdlc.shortener.model.Link;
import com.sdlc.shortener.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Create / metadata / disable (DEC-0008 core API). {@code POST} honours
 * {@code Idempotency-Key}: a replayed key returns the original result rather than
 * creating a second link.
 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    public record CreateLinkRequest(@NotBlank String url, String customAlias, String expiresAt) {}

    public record CreateLinkResponse(String code, String targetUrl, String createdAt, String expiresAt, String status) {
        static CreateLinkResponse from(Link link) {
            return new CreateLinkResponse(link.code(), link.targetUrl(), link.createdAt(), link.expiresAt(), link.status());
        }
    }

    @Operation(summary = "Create a short link")
    @PostMapping
    public ResponseEntity<CreateLinkResponse> create(
            @RequestBody CreateLinkRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Link created = linkService.create(request.url(), request.customAlias(), request.expiresAt(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateLinkResponse.from(created));
    }

    @Operation(summary = "Get link metadata")
    @GetMapping("/{code}")
    public ResponseEntity<CreateLinkResponse> get(@PathVariable String code) {
        return linkService.getMetadata(code)
                .map(link -> ResponseEntity.ok(CreateLinkResponse.from(link)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Disable a short link (soft delete)")
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> disable(@PathVariable String code) {
        if (linkService.getMetadata(code).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        linkService.disable(code);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(LinkService.ValidationFailedException.class)
    public ResponseEntity<Map<String, String>> handleValidationFailed(LinkService.ValidationFailedException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
