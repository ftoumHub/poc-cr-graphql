package fr.ftoumhub.poccr.domaine.traitement;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record Traitement(UUID id,
                         UUID patientId,
                         String molecule,
                         String posologie,
                         LocalDate dateDebut,
                         LocalDate dateFin) {

    public Traitement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(molecule, "molecule");
        Objects.requireNonNull(dateDebut, "dateDebut");
    }

    /** Un traitement sans date de fin est en cours. La date de fin est incluse. */
    public boolean estActifLe(LocalDate date) {
        return !date.isBefore(dateDebut) && (dateFin == null || !date.isAfter(dateFin));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private String molecule;
        private String posologie;
        private LocalDate dateDebut;
        private LocalDate dateFin;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder molecule(String molecule) { this.molecule = molecule; return this; }
        public Builder posologie(String posologie) { this.posologie = posologie; return this; }
        public Builder dateDebut(LocalDate dateDebut) { this.dateDebut = dateDebut; return this; }
        public Builder dateFin(LocalDate dateFin) { this.dateFin = dateFin; return this; }

        public Traitement construire() {
            return new Traitement(id, patientId, molecule, posologie, dateDebut, dateFin);
        }
    }
}
