package fr.ftoumhub.poccr.domaine.sejour;

import java.util.Objects;

/**
 * Unité fonctionnelle (UF) : maille de l'organisation (établissement > service > UF), médicale ou
 * d'hébergement pour un séjour, émettrice pour un document.
 *
 * @param serviceCode    service de rattachement (null pour une UF lue sans son service)
 * @param serviceLibelle libellé de ce service
 */
public record UniteFonctionnelle(String code, String libelle, String telephone, String fax, String serviceCode,
                                 String serviceLibelle) {

    public UniteFonctionnelle {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(libelle, "libelle");
    }
}
