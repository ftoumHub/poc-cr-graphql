package fr.ftoumhub.poccr.domaine.validation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Empreinte SHA-256 en hexadécimal minuscule. */
public final class Empreinte {

    private Empreinte() {
    }

    public static String sha256(byte[] contenu) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible dans la JVM", e);
        }
    }
}
