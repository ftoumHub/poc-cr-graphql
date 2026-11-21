package fr.ftoumhub.poccr.domaine.document;

import java.util.UUID;

/**
 * Clé de contexte demandée pour un document : séjour du patient, unité fonctionnelle émettrice et auteur
 * (le professionnel connecté). Chaque élément est facultatif ; l'établissement se déduit de l'UF.
 */
public record ContexteDocument(UUID sejourId, String ufCode, UUID auteurId) {

    public static final ContexteDocument AUCUN = new ContexteDocument(null, null, null);

    public ContexteDocument {
        if (ufCode != null && ufCode.isBlank()) {
            ufCode = null;
        }
    }

    public boolean estVide() {
        return sejourId == null && ufCode == null && auteurId == null;
    }
}
