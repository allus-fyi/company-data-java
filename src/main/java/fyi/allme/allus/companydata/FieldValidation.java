package fyi.allme.allus.companydata;

import java.util.Set;

/**
 * Country-data helpers.
 *
 * <p>What a value must satisfy for its field TYPE lives in {@link FieldTypeRegistry}: a type is a
 * row in the served registry and that class is the one interpreter of those rows. These two
 * helpers are about the bundled country dataset itself, which no registry row carries.
 */
public final class FieldValidation {

    private static final Set<String> COUNTRY_SET = Set.copyOf(CountryData.COUNTRY_CODES);

    private FieldValidation() {
    }

    /** True if {@code code} is an assigned ISO 3166-1 alpha-2 country code. */
    public static boolean isValidCountryCode(String code) {
        return code != null && COUNTRY_SET.contains(code);
    }

    /** The ITU E.164 dial code (digits only, no {@code +}) for a country code, or null. */
    public static String dialCodeFor(String code) {
        return code == null ? null : CountryData.DIAL_CODES.get(code);
    }
}
