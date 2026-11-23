# Plan (optional): Endpunkt-Beschreibungen aus OpenAPI laden

## Context
Manni fragt, ob der `agentClient` die Endpunkt-Beschreibungen aus einer OpenAPI-Spezifikation lesen und für weitere Anfragen "merken" sollte, statt sie von Hand in `application.yml` zu pflegen.
Aktueller Stand: `EndpointCatalog` (aus `application.yml`) -> `ToolCatalogService#tools()` baut daraus bei jeder Anfrage die Claude-Tools; `execute(...)` prüft Aufrufe gegen die Liste.

## Antwort: Ja, sinnvoll, mit drei Einschränkungen
**Was "merken" heißt:** Claude selbst merkt sich nichts, jede API-Anfrage ist zustandslos und braucht die Tools erneut. Gemerkt wird also **im Server**: Spezifikation beim Start (und optional zyklisch) laden, in den Katalog umwandeln und im Speicher halten. Die Tools werden dann pro Anfrage aus diesem Cache gebaut.

**Vorteile:** eine einzige Quelle der Wahrheit, kein Auseinanderlaufen von Spezifikation und yml, Parameter, Typen und Pflichtfelder kommen automatisch aus der Spec.

**Einschränkungen:**
1. **Allowlist bleibt Pflicht.** Eine Spec enthält auch schreibende Operationen (buchen, stornieren). Nicht alles darf als Tool erscheinen. In der yml steht weiter eine explizite Liste erlaubter `operationId`s (zunächst nur GET); alles andere wird ignoriert. Das erhält die Schutzwirkung aus der aktuellen Lösung.
2. **Beschreibungen sind oft für Entwickler geschrieben,** nicht für die Auswahl durch ein Modell. In der yml bleibt deshalb ein optionaler `description`-Override pro Operation, damit die Auswahl zuverlässig bleibt.
3. **Größe:** Viele Tools verschlechtern die Auswahl und kosten Token pro Anfrage. Daher nur die freigegebenen Operationen laden. Erst bei sehr großen Katalogen lohnt Prompt-Caching der Tool-Definitionen (`cache_control`); bei Haiku 4.5 greift das erst ab einer Mindestlänge von einigen tausend Token und bringt bei 2-5 Tools nichts.

Zeitpunkt: sinnvoll, **sobald die echten Club-Endpunkte samt OpenAPI-Spec feststehen.** Für die jetzigen Mock-Endpunkte lohnt der Aufwand nicht.

## Umsetzung (nur falls gewünscht)
- Dependency `io.swagger.parser.v3:swagger-parser` (löst `$ref` auf).
- `conf/OpenApiCatalogLoader.java` (neu): lädt `application.agent.openapi.url`, filtert nach `include` (operationIds), wandelt jede Operation in einen vorhandenen `EndpointCatalog.Endpoint` um (`operationId` -> `id`, `summary`/`description` -> `description` bzw. yml-Override, Path- und Query-Parameter -> `Param` mit `type`/`required`, URL aus Server-URL + Pfad mit `{param}`-Platzhaltern).
- `EndpointCatalog` bleibt das gemeinsame Modell; `ToolCatalogService` und `AgentService` ändern sich nicht. Der Loader hält das Ergebnis in einem `volatile`-Feld, geladen beim Start und per `@Scheduled` aufgefrischt. Ist die Spec beim Start nicht erreichbar, greifen die in der yml von Hand gepflegten Endpunkte als Fallback.
- Nicht enthalten: Request-Bodies, schreibende Operationen, Authentifizierung aus `securitySchemes`.

## Verifikation
1. Test-Spec (JSON) mit zwei GET- und einer POST-Operation bereitstellen, `include` auf die beiden GETs setzen.
2. Start: Log zeigt zwei geladene Tools, die POST-Operation fehlt.
3. `curl -N /agent` mit einer passenden Frage: Tool-Aufruf mit richtigen Parametern; mit einer Frage, die nur die POST-Operation bräuchte: `unsupported`.
4. Spec nach dem Start ändern: nach dem Refresh-Intervall erscheint die Änderung, ohne Neustart.
