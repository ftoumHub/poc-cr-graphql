package fr.ftoumhub.poccr.domaine.validation;

import java.util.regex.Pattern;

/** Identifiant de document du POC : sert aussi de nom de fichier, d'où la restriction de caractères. */
public record NomDocument(String valeur) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public NomDocument {
        if (valeur == null || !FORMAT.matcher(valeur).matches()) {
            throw new IllegalArgumentException("Nom de document invalide : " + valeur);
        }
    }

    @Override
    public String toString() {
        return valeur;
    }
}
