package com.example.agent_java.conf;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Setzt die Header, mit denen der Browser die Seite "cross-origin isolated" behandelt. Nur dann darf
 * WebAssembly mehrere Threads nutzen (SharedArrayBuffer). Die lokale Spracherkennung ist damit
 * auf einem 8-Thread-Rechner etwa dreimal schneller als mit einem Thread.
 *
 * <p>Gilt nur für die Chat-Seite und die Skripte, nicht für die API. Nebenwirkungen: Von außen geladene
 * Dateien brauchen CORS oder einen Cross-Origin-Resource-Policy-Header (jsDelivr und Hugging Face haben
 * beides), und Pop-up-Fenster mit window.opener funktionieren nicht mehr. Für eine spätere Anmeldung
 * per OIDC also Redirect- statt Pop-up-Ablauf verwenden.
 */
@Component
public class CrossOriginIsolationFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/chat") || path.startsWith("/js/")) {
            var headers = exchange.getResponse().getHeaders();
            headers.set("Cross-Origin-Opener-Policy", "same-origin");
            headers.set("Cross-Origin-Embedder-Policy", "require-corp");
        }
        return chain.filter(exchange);
    }
}
