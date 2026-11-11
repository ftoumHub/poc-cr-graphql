package fr.ftoumhub.poccr.domaine.document;

/** Signataires ou destinataires incohérents (règle métier violée). */
public class ActeursInvalides extends IllegalArgumentException {

    public ActeursInvalides(String message) {
        super(message);
    }
}
