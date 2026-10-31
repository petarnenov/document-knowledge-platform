package org.documentknowledge.browserbff.application;

import java.util.List;

public record SessionView(String issuer, String subject, String displayName, List<String> tenants,
                          String selectedTenant, String csrfToken) { }
