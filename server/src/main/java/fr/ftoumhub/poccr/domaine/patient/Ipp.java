package fr.ftoumhub.poccr.domaine.patient;

import java.util.regex.Pattern;

/** Identifiant Permanent du Patient : identifiant métier, distinct de l'identifiant technique ({@code id}). */
public record Ipp(String valeur) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9-]{1,20}");

    public Ipp {
        if (valeur == null || !FORMAT.matcher(valeur).matches()) {
            throw new IllegalArgumentException("IPP invalide : " + valeur);
        }
    }

    @Override
    public String toString() {
        return valeur;
    }
}
