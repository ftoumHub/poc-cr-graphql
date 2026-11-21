package fr.ftoumhub.poccr.domaine.patient;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Identité nationale de santé (INS) : matricule et traits d'identité de naissance.
 *
 * @param matricule        NIR (13 chiffres + clé) ou NIA
 * @param prenomsNaissance liste des prénoms de naissance, séparés par des espaces
 * @param lieuNaissance    code INSEE de la commune de naissance
 */
public record IdentiteNationaleSante(String matricule, String nomNaissance, String prenomsNaissance,
                                     String prenomUsuel, LocalDate dateNaissance, String lieuNaissance, Sexe sexe) {

    /** OID de l'autorité d'affectation : NIR (assurance maladie) ou NIA (identifiant d'attente). */
    static final String OID_NIR = "1.2.250.1.213.1.4.8";
    static final String OID_NIA = "1.2.250.1.213.1.4.9";

    public IdentiteNationaleSante {
        Objects.requireNonNull(matricule, "matricule");
    }

    /** Un NIA commence par 7 ou 8 : il est attribué en attente d'un NIR. */
    public String natureMatricule() {
        return estNia() ? "NIA" : "NIR";
    }

    public String oid() {
        return estNia() ? OID_NIA : OID_NIR;
    }

    /** Premier prénom de l'acte de naissance. */
    public String premierPrenom() {
        if (prenomsNaissance == null || prenomsNaissance.isBlank()) {
            return null;
        }
        return prenomsNaissance.trim().split("\\s+")[0];
    }

    private boolean estNia() {
        char premier = matricule.charAt(0);
        return premier == '7' || premier == '8';
    }
}
