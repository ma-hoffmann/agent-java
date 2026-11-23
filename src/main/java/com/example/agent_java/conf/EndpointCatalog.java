package com.example.agent_java.conf;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Die Liste der Endpunkte, die Claude aufrufen darf (application.yml, Präfix "application.agent").
 * Nur was hier steht, wird Claude als Tool angeboten und vom Server aufgerufen.
 */
@ConfigurationProperties("application.agent")
public record EndpointCatalog(String apiKey, List<Endpoint> endpoints) {

    public EndpointCatalog {
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    /**
     * @param id          eindeutiger Name, wird zum Tool-Namen (nur Buchstaben, Ziffern, "_" und "-")
     * @param description Beschreibung für Claude; anhand dieses Textes wird der Endpunkt ausgewählt
     * @param method      HTTP-Methode (GET)
     * @param url         URL mit Platzhaltern in geschweiften Klammern, z. B. http://host/courts?date={date}
     * @param params      erlaubte Parameter
     */
    public record Endpoint(String id, String description, String method, String url, List<Param> params) {

        public Endpoint {
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    /**
     * @param type "string", "date" (ISO yyyy-MM-dd) oder "integer"
     */
    public record Param(String name, String type, String description, boolean required) {
    }
}
