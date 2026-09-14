package com.sdlc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the URL shortener (the test subject — see CLAUDE.md §0). The
 *  orchestrator (com.sdlc.orchestrator) is a separate, plain-Java component; this
 *  class starts only the Spring Boot web service. */
@SpringBootApplication(scanBasePackages = "com.sdlc.shortener")
public class ShortenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShortenerApplication.class, args);
    }
}
