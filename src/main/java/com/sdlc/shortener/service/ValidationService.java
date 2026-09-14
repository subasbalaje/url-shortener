package com.sdlc.shortener.service;

import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * URL and alias validation — the most security-sensitive class in the service. A
 * shortener is an open redirector by design: it launders a malicious destination
 * behind a trusted domain, which is why shorteners are a standing phishing and
 * SSRF vector. Per OWASP SSRF guidance:
 *
 * <ul>
 *   <li>scheme allowlist: {@code http}/{@code https} only</li>
 *   <li>block loopback, RFC1918, link-local (which covers the cloud metadata
 *       endpoint {@code 169.254.169.254} — checked explicitly anyway, for a reader
 *       scanning this class to see it named, not just implied by a broader rule)</li>
 *   <li><b>resolve DNS and validate every resolved IP</b>, not just the hostname
 *       string — a public-looking hostname resolving to {@code 127.0.0.1}
 *       (attacker-controlled DNS, or DNS rebinding) otherwise walks straight
 *       through a hostname-string check</li>
 *   <li>no redirect-following during validation (this class never dereferences the
 *       URL with an HTTP client — only DNS resolution — so there is nothing here
 *       that could follow a redirect)</li>
 *   <li>reserved-word denylist for custom aliases</li>
 *   <li>self-shortening prevention</li>
 * </ul>
 *
 * <p><b>Residual risk, stated rather than claimed solved</b> (docs/architecture.md
 * §7 risk 6): DNS can change between validation and the first redirect (a rebind
 * window), and destination reputation can drift after acceptance. Neither is
 * fixed by this class; both are accepted, documented risks of the shortener
 * pattern itself.
 */
@Service
public class ValidationService {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> RESERVED_ALIASES = Set.of(
            "api", "admin", "login", "health", "static", "app", "www", "assets");

    private final Function<String, InetAddress[]> resolver;
    private final String ownHost;

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() { return new ValidationResult(true, ""); }
        static ValidationResult reject(String reason) { return new ValidationResult(false, reason); }
    }

    /**
     * @param resolver hostname -> resolved IPs. Production wiring passes
     *     {@code InetAddress::getAllByName}; tests inject a fixed map so no real
     *     DNS lookup happens.
     * @param ownHost this service's own hostname, for self-shortening prevention.
     */
    public ValidationService(Function<String, InetAddress[]> resolver, String ownHost) {
        this.resolver = resolver;
        this.ownHost = ownHost;
    }

    /** Production DNS resolution: {@link InetAddress#getAllByName} — resolves
     *  every A/AAAA record so all of them get validated, not just the first. */
    public static InetAddress[] resolveViaDns(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return new InetAddress[0];
        }
    }

    public ValidationResult validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return ValidationResult.reject("Malformed URL.");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return ValidationResult.reject("Scheme not allowed: " + scheme
                    + ". Only http/https are permitted.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ValidationResult.reject("URL has no host.");
        }

        if (host.equalsIgnoreCase(ownHost)) {
            return ValidationResult.reject("Self-shortening is not permitted.");
        }

        InetAddress[] resolved = resolver.apply(host);
        if (resolved == null || resolved.length == 0) {
            return ValidationResult.reject("Host does not resolve: " + host);
        }

        for (InetAddress address : resolved) {
            String blocked = blockedReason(address);
            if (blocked != null) {
                return ValidationResult.reject("Host '" + host + "' resolves to " + address.getHostAddress()
                        + ", which is " + blocked + ".");
            }
        }

        return ValidationResult.ok();
    }

    public ValidationResult validateAlias(String alias) {
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
            return ValidationResult.reject("'" + alias + "' is a reserved word and cannot be used as a custom alias.");
        }
        return ValidationResult.ok();
    }

    /** @return a human-readable reason the address is blocked, or {@code null} if
     *  it is allowed. */
    private String blockedReason(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return "a loopback address";
        }
        if (address.isSiteLocalAddress()) {
            return "an RFC1918 private address";
        }
        if (address.isLinkLocalAddress()) {
            // Covers 169.254.0.0/16 (RFC 3927), which includes the cloud metadata
            // endpoint 169.254.169.254 -- named explicitly below regardless, since
            // that specific address is the single most consequential one to block.
            return "a link-local address";
        }
        if ("169.254.169.254".equals(address.getHostAddress())) {
            return "the cloud metadata endpoint";
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4 && bytes[0] == 0) {
            return "in the 0.0.0.0/8 reserved range";
        }
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return "a non-unicast address";
        }
        return null;
    }
}
