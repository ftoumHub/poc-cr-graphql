package fr.ftoumhub.poccr.domaine.validation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Trace de la validation d'un compte rendu.
 *
 * Deux temps, car le docx final n'existe qu'après la sauvegarde OnlyOffice qui suit la validation :
 *  1. validation : on fige les données (réponse GraphQL canonique + empreinte) ;
 *  2. scellement : la première sauvegarde du docx après validation fixe son empreinte ;
 *     toute sauvegarde ultérieure est refusée.
 *
 * @param donneesJson      réponse GraphQL (bloc data) sérialisée de façon canonique (clés triées)
 * @param empreinteDonnees SHA-256 de donneesJson
 * @param empreinteDocx    SHA-256 du docx scellé, null tant que le scellement n'a pas eu lieu
 */
public record Validation(String document,
                         UUID patientId,
                         String requete,
                         String donneesJson,
                         String empreinteDonnees,
                         Instant valideLe,
                         String validePar,
                         String empreinteDocx) {

    public Validation {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(requete, "requete");
        Objects.requireNonNull(donneesJson, "donneesJson");
        Objects.requireNonNull(empreinteDonnees, "empreinteDonnees");
        Objects.requireNonNull(valideLe, "valideLe");
        Objects.requireNonNull(validePar, "validePar");
    }

    public boolean estScelle() {
        return empreinteDocx != null;
    }

    public Validation scellerDocx(byte[] docx) {
        if (estScelle()) {
            throw new DocumentDejaScelle(document);
        }
        return new Validation(document, patientId, requete, donneesJson, empreinteDonnees, valideLe, validePar,
                Empreinte.sha256(docx));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String document;
        private UUID patientId;
        private String requete;
        private String donneesJson;
        private String empreinteDonnees;
        private Instant valideLe;
        private String validePar;

        private Builder() {
        }

        public Builder document(String document) { this.document = document; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder requete(String requete) { this.requete = requete; return this; }
        public Builder valideLe(Instant valideLe) { this.valideLe = valideLe; return this; }
        public Builder validePar(String validePar) { this.validePar = validePar; return this; }

        /** Fixe les données et calcule leur empreinte : les deux ne peuvent pas diverger. */
        public Builder donneesJson(String donneesJson) {
            this.donneesJson = donneesJson;
            this.empreinteDonnees = Empreinte.sha256(donneesJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return this;
        }

        public Validation construire() {
            return new Validation(document, patientId, requete, donneesJson, empreinteDonnees, valideLe, validePar, null);
        }
    }

    public static final class DocumentDejaScelle extends RuntimeException {
        public DocumentDejaScelle(String document) {
            super("Le compte rendu " + document + " est déjà scellé");
        }
    }
}
