package fr.ftoumhub.poccr.domaine.port;

import java.util.UUID;

/**
 * Port secondaire : exécute côté serveur la requête compilée par l'éditeur.
 * C'est le serveur, pas le navigateur, qui produit les données figées à la validation.
 */
public interface ExecuteurRequetesDossier {

    /**
     * @return le bloc data de la réponse, sérialisé en JSON canonique (clés triées) pour un hash stable
     * @throws RequeteRejetee si la requête est invalide, en erreur ou ne renvoie pas le patient
     */
    String executer(String requete, UUID patientId);

    final class RequeteRejetee extends RuntimeException {
        public RequeteRejetee(String message) {
            super(message);
        }
    }
}
