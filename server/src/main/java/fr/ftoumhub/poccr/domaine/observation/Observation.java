package fr.ftoumhub.poccr.domaine.observation;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record Observation(UUID id,
                          UUID patientId,
                          CategorieObservation categorie,
                          String code,
                          String libelle,
                          String valeur,
                          String unite,
                          LocalDate dateObservation) {

    public Observation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(categorie, "categorie");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(dateObservation, "dateObservation");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private CategorieObservation categorie;
        private String code;
        private String libelle;
        private String valeur;
        private String unite;
        private LocalDate dateObservation;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder categorie(CategorieObservation categorie) { this.categorie = categorie; return this; }
        public Builder code(String code) { this.code = code; return this; }
        public Builder libelle(String libelle) { this.libelle = libelle; return this; }
        public Builder valeur(String valeur) { this.valeur = valeur; return this; }
        public Builder unite(String unite) { this.unite = unite; return this; }
        public Builder dateObservation(LocalDate dateObservation) { this.dateObservation = dateObservation; return this; }

        public Observation construire() {
            return new Observation(id, patientId, categorie, code, libelle, valeur, unite, dateObservation);
        }
    }
}
