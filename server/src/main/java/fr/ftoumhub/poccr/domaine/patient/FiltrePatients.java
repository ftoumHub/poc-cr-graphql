package fr.ftoumhub.poccr.domaine.patient;

/**
 * Filtre colonne par colonne pour la liste paginée des patients (page d'accueil), restreint au contexte
 * organisationnel choisi : patients ayant un séjour dans l'établissement, le service ou l'unité fonctionnelle.
 * Chaque champ vide ou null désactive son filtre ; l'UF prime sur le service, le service sur l'établissement.
 */
public record FiltrePatients(String ipp, String nom, String prenom, String sexe, String dateNaissance,
                             String etablissement, String service, String uf) {

    public static final FiltrePatients AUCUN = new FiltrePatients(null, null, null, null, null);

    public FiltrePatients(String ipp, String nom, String prenom, String sexe, String dateNaissance) {
        this(ipp, nom, prenom, sexe, dateNaissance, null, null, null);
    }
}
