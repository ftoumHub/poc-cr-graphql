package fr.ftoumhub.poccr.domaine.patient;

/**
 * Filtre colonne par colonne pour la liste paginée des patients (page d'accueil).
 * Chaque champ vide ou null désactive le filtre de cette colonne.
 */
public record FiltrePatients(String ipp, String nom, String prenom, String sexe, String dateNaissance) {

    public static final FiltrePatients AUCUN = new FiltrePatients(null, null, null, null, null);
}
