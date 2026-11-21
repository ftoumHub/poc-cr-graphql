package fr.ftoumhub.poccr.domaine.dossier;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Ligne de prescription. Le contrat CDF imbrique prescriptions[].lignes[] ; le POC expose les lignes à plat,
 * filtrées par nature et statut.
 *
 * @param posologie posologie en clair (« libellé long de la posologie »)
 */
public record LignePrescription(UUID id, UUID patientId, NaturePrescription nature, StatutPrescription statut,
                                String libelle, String posologie, String commentaire, LocalDate dateDebut,
                                LocalDate dateFin) {

    public LignePrescription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(statut, "statut");
        Objects.requireNonNull(libelle, "libelle");
        Objects.requireNonNull(dateDebut, "dateDebut");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private NaturePrescription nature;
        private StatutPrescription statut;
        private String libelle;
        private String posologie;
        private String commentaire;
        private LocalDate dateDebut;
        private LocalDate dateFin;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder nature(NaturePrescription nature) { this.nature = nature; return this; }
        public Builder statut(StatutPrescription statut) { this.statut = statut; return this; }
        public Builder libelle(String libelle) { this.libelle = libelle; return this; }
        public Builder posologie(String posologie) { this.posologie = posologie; return this; }
        public Builder commentaire(String commentaire) { this.commentaire = commentaire; return this; }
        public Builder dateDebut(LocalDate dateDebut) { this.dateDebut = dateDebut; return this; }
        public Builder dateFin(LocalDate dateFin) { this.dateFin = dateFin; return this; }

        public LignePrescription construire() {
            return new LignePrescription(id, patientId, nature, statut, libelle, posologie, commentaire, dateDebut,
                    dateFin);
        }
    }
}
