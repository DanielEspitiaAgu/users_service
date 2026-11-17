package co.edu.uptc.users_service.domain.model;

import java.util.regex.Pattern;

public final class ProfileIdentityPolicy {
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private ProfileIdentityPolicy() { }

    public static void requireValid(String externalId) {
        if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            throw new IllegalArgumentException("X-User-Id inválido");
        }
    }
}
