package fyi.allme.allus.companydata;

/**
 * #491 gap 3: the calling client's own service identity, from {@code GET /api/company-data/whoami}.
 * {@code companyUserId} is the company user the client is bound to — the value a flow-run binding's
 * company party must use; {@code serviceId} is its service.
 */
public record Identity(String companyUserId, String serviceId) {}
