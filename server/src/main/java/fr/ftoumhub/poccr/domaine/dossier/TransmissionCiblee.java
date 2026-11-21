package fr.ftoumhub.poccr.domaine.dossier;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Transmission ciblée infirmière : cible, puis données, actions et résultats (méthode DAR). */
public record TransmissionCiblee(UUID id, UUID patientId, String cible, Instant ecriteLe, String auteur,
                                 String donnees, String actions, String resultats, String etat) {

    public TransmissionCiblee {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(cible, "cible");
        Objects.requireNonNull(ecriteLe, "ecriteLe");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private String cible;
        private Instant ecriteLe;
        private String auteur;
        private String donnees;
        private String actions;
        private String resultats;
        private String etat;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder cible(String cible) { this.cible = cible; return this; }
        public Builder ecriteLe(Instant ecriteLe) { this.ecriteLe = ecriteLe; return this; }
        public Builder auteur(String auteur) { this.auteur = auteur; return this; }
        public Builder donnees(String donnees) { this.donnees = donnees; return this; }
        public Builder actions(String actions) { this.actions = actions; return this; }
        public Builder resultats(String resultats) { this.resultats = resultats; return this; }
        public Builder etat(String etat) { this.etat = etat; return this; }

        public TransmissionCiblee construire() {
            return new TransmissionCiblee(id, patientId, cible, ecriteLe, auteur, donnees, actions, resultats, etat);
        }
    }
}
