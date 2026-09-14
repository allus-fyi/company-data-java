package fyi.allme.allus.companydata;

/**
 * One page of a multi-page binary answer (an ID document's front, back, …).
 *
 * @param label the page's own label ({@code front} | {@code back} | {@code additional}), or null
 * @param name  the original filename the person uploaded it under, or null
 * @param mime  the server-derived media type, or null
 * @param bytes the decoded page bytes
 */
public record BinaryPage(String label, String name, String mime, byte[] bytes) {
}
