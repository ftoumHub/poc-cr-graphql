package fr.ftoumhub.poccr.domaine.document;

import fr.ftoumhub.poccr.domaine.validation.NomDocument;

public class DocumentIntrouvable extends RuntimeException {

    public DocumentIntrouvable(NomDocument document) {
        super("Document introuvable : " + document);
    }
}
