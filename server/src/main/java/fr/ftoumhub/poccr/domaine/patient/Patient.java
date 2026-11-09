package fr.ftoumhub.poccr.domaine.patient;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;
import java.util.UUID;

/**
 * Patient tel que vu par la rédaction d'un compte rendu.
 * Aucune annotation de framework : le domaine reste indépendant de Spring.
 */
public record Patient(UUID id, Ipp ipp, String nom, String prenom, LocalDate dateNaissance, Sexe sexe) {

    public Patient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ipp, "ipp");
        Objects.requireNonNull(nom, "nom");
        Objects.requireNonNull(dateNaissance, "dateNaissance");
    }

    public int ageLe(LocalDate date) {
        return Period.between(dateNaissance, date).getYears();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private Ipp ipp;
        private String nom;
        private String prenom;
        private LocalDate dateNaissance;
        private Sexe sexe = Sexe.INCONNU;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder ipp(Ipp ipp) { this.ipp = ipp; return this; }
        public Builder nom(String nom) { this.nom = nom; return this; }
        public Builder prenom(String prenom) { this.prenom = prenom; return this; }
        public Builder dateNaissance(LocalDate dateNaissance) { this.dateNaissance = dateNaissance; return this; }
        public Builder sexe(Sexe sexe) { this.sexe = sexe; return this; }

        public Patient construire() {
            return new Patient(id, ipp, nom, prenom, dateNaissance, sexe);
        }
    }
}
