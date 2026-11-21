package fr.ftoumhub.poccr.domaine.dossier;

import java.util.Objects;
import java.util.UUID;

/**
 * Personne à prévenir ou personne de confiance. Ses adresses sont des coordonnées comme celles des
 * professionnels (propriétaire = cette personne).
 *
 * @param lien lien avec le patient, texte libre
 */
public record PersonneEntourage(UUID id, UUID patientId, RoleEntourage role, String titre, String nom, String prenom,
                                String lien) {

    public PersonneEntourage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(nom, "nom");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private RoleEntourage role;
        private String titre;
        private String nom;
        private String prenom;
        private String lien;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder role(RoleEntourage role) { this.role = role; return this; }
        public Builder titre(String titre) { this.titre = titre; return this; }
        public Builder nom(String nom) { this.nom = nom; return this; }
        public Builder prenom(String prenom) { this.prenom = prenom; return this; }
        public Builder lien(String lien) { this.lien = lien; return this; }

        public PersonneEntourage construire() {
            return new PersonneEntourage(id, patientId, role, titre, nom, prenom, lien);
        }
    }
}
