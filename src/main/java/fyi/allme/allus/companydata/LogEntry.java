package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Parse;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A service activity-log entry — ops events only (email / purge /
 * webhook), never person field data. {@code raw} is the underlying hardened object.
 */
public record LogEntry(
    String type,
    String message,
    Object metadata,
    OffsetDateTime at,
    Map<String, Object> raw
) {
    static LogEntry fromApi(Map<String, Object> obj) {
        Object atRaw = obj.get("at") != null ? obj.get("at") : obj.get("created_at");
        return new LogEntry(
            Parse.str(obj.get("type")),
            Parse.str(obj.get("message")),
            obj.get("metadata"),
            Parse.isoDateTime(atRaw),
            obj);
    }

    /** Parse the {@code /logs} response → a list of log entries. */
    @SuppressWarnings("unchecked")
    static List<LogEntry> listFromApi(Object body) {
        Object itemsObj;
        if (body instanceof Map<?, ?> m) {
            itemsObj = m.get("items");
        } else {
            itemsObj = body;
        }
        List<LogEntry> out = new ArrayList<>();
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> map) {
                    out.add(fromApi((Map<String, Object>) map));
                }
            }
        }
        return out;
    }
}
