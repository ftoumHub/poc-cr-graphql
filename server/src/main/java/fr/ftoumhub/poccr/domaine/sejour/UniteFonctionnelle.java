package fr.ftoumhub.poccr.domaine.sejour;

import java.util.Objects;

/** Unité fonctionnelle (UF) médicale ou d'hébergement d'un séjour. */
public record UniteFonctionnelle(String code, String libelle, String telephone, String fax) {

    public UniteFonctionnelle {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(libelle, "libelle");
    }
}
