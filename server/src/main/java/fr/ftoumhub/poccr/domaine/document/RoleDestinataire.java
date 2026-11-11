package fr.ftoumhub.poccr.domaine.document;

/**
 * PRINCIPAL et COPIE désignent un professionnel de santé ; EXTERIEUR est saisi en texte libre ;
 * PATIENT désigne le patient du document (copie au patient), sans autre information.
 */
public enum RoleDestinataire {
    PRINCIPAL,
    COPIE,
    EXTERIEUR,
    PATIENT;

    boolean designeUnProfessionnel() {
        return this == PRINCIPAL || this == COPIE;
    }
}
