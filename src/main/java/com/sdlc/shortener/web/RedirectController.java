package com.sdlc.shortener.web;

import com.sdlc.shortener.service.LinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The redirect hot path (DEC-0009): <b>302, not 301</b>, with
 * {@code Cache-Control: no-store}. A cached 301 means later clicks never reach
 * the origin — destroying click analytics <em>and</em> keeping a disabled link
 * redirecting. Expired/disabled links return <b>410 Gone</b> (the resource
 * existed and was deliberately retired); unknown codes return 404.
 */
@RestController
public class RedirectController {

    private final LinkService linkService;

    public RedirectController(LinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        LinkService.RedirectResult result = linkService.resolveForRedirect(code);
        return switch (result.outcome()) {
            case REDIRECT -> ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, result.targetUrl())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
            case GONE -> ResponseEntity.status(HttpStatus.GONE).build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }
}
