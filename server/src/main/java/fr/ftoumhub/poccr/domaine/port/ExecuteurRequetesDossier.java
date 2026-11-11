package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.validation.ValidationEnCours;
import fr.ftoumhub.poccr.domaine.validation.VariablesDocument;

/**
 * Port secondaire : exécute côté serveur la requête compilée par l'éditeur.
 * C'est le serveur, pas le navigateur, qui produit les données figées à la validation.
 */
public interface ExecuteurRequetesDossier {

    /**
     * @param variables variables imposées par le serveur (document validé, patient rattaché)
     * @param enCours   date et valideur, visibles par les champs {@code document.validation.*}
     * @return le bloc data de la réponse, sérialisé en JSON canonique (clés triées) pour un hash stable
     * @throws RequeteRejetee si la requête est invalide, en erreur ou si une racine demandée est absente
     */
    String executer(String requete, VariablesDocument variables, ValidationEnCours enCours);

    final class RequeteRejetee extends RuntimeException {
        public RequeteRejetee(String message) {
            super(message);
        }
    }
}
