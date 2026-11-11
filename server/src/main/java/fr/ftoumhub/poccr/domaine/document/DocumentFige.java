package fr.ftoumhub.poccr.domaine.document;

import fr.ftoumhub.poccr.domaine.validation.NomDocument;

/** Modification refusée : un compte rendu validé est figé (nom, patient, signataires, destinataires). */
public class DocumentFige extends IllegalStateException {

    public DocumentFige(NomDocument document, String quoi) {
        super("Le compte rendu " + document + " est validé : " + quoi + " ne peut plus changer");
    }
}
