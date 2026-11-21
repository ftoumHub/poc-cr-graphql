package fr.ftoumhub.poccr.domaine.dossier;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Observation écrite dans le dossier (fiche d'observation), rangée par chapitre : profession de l'auteur
 * (MEDECINS, IDE, KINESITHERAPEUTE…) ou rubrique libre (« Suivi social »).
 */
public record ObservationMedicale(UUID id, UUID patientId, String chapitre, Instant ecriteLe, String texte,
                                  UUID auteurId) {

    public ObservationMedicale {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(chapitre, "chapitre");
        Objects.requireNonNull(ecriteLe, "ecriteLe");
        Objects.requireNonNull(texte, "texte");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private String chapitre;
        private Instant ecriteLe;
        private String texte;
        private UUID auteurId;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder chapitre(String chapitre) { this.chapitre = chapitre; return this; }
        public Builder ecriteLe(Instant ecriteLe) { this.ecriteLe = ecriteLe; return this; }
        public Builder texte(String texte) { this.texte = texte; return this; }
        public Builder auteurId(UUID auteurId) { this.auteurId = auteurId; return this; }

        public ObservationMedicale construire() {
            return new ObservationMedicale(id, patientId, chapitre, ecriteLe, texte, auteurId);
        }
    }
}
