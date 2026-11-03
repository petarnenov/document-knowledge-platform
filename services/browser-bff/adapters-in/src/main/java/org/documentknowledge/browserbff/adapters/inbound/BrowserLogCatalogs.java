package org.documentknowledge.browserbff.adapters.inbound;

import java.util.Map;
import java.util.UUID;
import org.documentknowledge.browserbff.application.BffFailure;

/** Original operator-installed portal vocabulary, never populated from browser input. */
public final class BrowserLogCatalogs {
    private final Map<UUID, BrowserLogJson.Catalog> catalogs;
    public BrowserLogCatalogs(Map<UUID, BrowserLogJson.Catalog> catalogs) { this.catalogs = Map.copyOf(catalogs); }
    public BrowserLogJson.Catalog require(UUID build) {
        var catalog = catalogs.get(build); if (catalog == null) throw new BffFailure(403, "LOG_SCOPE_DENIED"); return catalog;
    }
}
