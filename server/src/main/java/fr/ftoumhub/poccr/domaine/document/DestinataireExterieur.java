package fr.ftoumhub.poccr.domaine.document;

/** Destinataire saisi en texte libre (organisme, personne sans fiche de professionnel). */
public record DestinataireExterieur(String nom, String prenom, String email) {

    public DestinataireExterieur {
        if (nom == null || nom.isBlank()) {
            throw new ActeursInvalides("Un destinataire extérieur doit avoir un nom");
        }
        nom = nom.trim();
    }
}
