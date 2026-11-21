package fr.ftoumhub.poccr.domaine.dossier;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Antécédent du patient.
 *
 * @param categorie médical, chirurgical, familial…
 * @param statut    actif, guéri, en rémission…
 */
public record Antecedent(UUID id, UUID patientId, String categorie, String libelle, String statut,
                         LocalDate dateDebut) {

    public Antecedent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(categorie, "categorie");
        Objects.requireNonNull(libelle, "libelle");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private String categorie;
        private String libelle;
        private String statut;
        private LocalDate dateDebut;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder categorie(String categorie) { this.categorie = categorie; return this; }
        public Builder libelle(String libelle) { this.libelle = libelle; return this; }
        public Builder statut(String statut) { this.statut = statut; return this; }
        public Builder dateDebut(LocalDate dateDebut) { this.dateDebut = dateDebut; return this; }

        public Antecedent construire() {
            return new Antecedent(id, patientId, categorie, libelle, statut, dateDebut);
        }
    }
}
