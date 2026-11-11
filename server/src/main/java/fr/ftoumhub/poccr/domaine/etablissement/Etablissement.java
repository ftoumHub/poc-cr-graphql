package fr.ftoumhub.poccr.domaine.etablissement;

import java.util.Objects;
import java.util.UUID;

/** Établissement émetteur des documents. */
public record Etablissement(UUID id, String finess, String siret, String libelle, String email) {

    public Etablissement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(finess, "finess");
        Objects.requireNonNull(libelle, "libelle");
        if (!finess.matches("\\d{9}")) {
            throw new IllegalArgumentException("FINESS invalide : 9 chiffres attendus");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private String finess;
        private String siret;
        private String libelle;
        private String email;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder finess(String finess) { this.finess = finess; return this; }
        public Builder siret(String siret) { this.siret = siret; return this; }
        public Builder libelle(String libelle) { this.libelle = libelle; return this; }
        public Builder email(String email) { this.email = email; return this; }

        public Etablissement construire() {
            return new Etablissement(id, finess, siret, libelle, email);
        }
    }
}
