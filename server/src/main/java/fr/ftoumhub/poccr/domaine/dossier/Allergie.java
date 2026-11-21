package fr.ftoumhub.poccr.domaine.dossier;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Allergie ou intolérance déclarée.
 *
 * @param categorie   médicamenteuse, alimentaire, environnementale…
 * @param libelle     produit ou molécule
 * @param texte       réaction observée, texte libre
 * @param dateFin     renseignée quand l'allergie a été levée
 */
public record Allergie(UUID id, UUID patientId, String categorie, String libelle, String texte, String commentaire,
                       LocalDate dateDebut, LocalDate dateFin) {

    public Allergie {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(libelle, "libelle");
    }

    public boolean estActiveLe(LocalDate date) {
        return dateFin == null || date.isBefore(dateFin);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private String categorie;
        private String libelle;
        private String texte;
        private String commentaire;
        private LocalDate dateDebut;
        private LocalDate dateFin;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder categorie(String categorie) { this.categorie = categorie; return this; }
        public Builder libelle(String libelle) { this.libelle = libelle; return this; }
        public Builder texte(String texte) { this.texte = texte; return this; }
        public Builder commentaire(String commentaire) { this.commentaire = commentaire; return this; }
        public Builder dateDebut(LocalDate dateDebut) { this.dateDebut = dateDebut; return this; }
        public Builder dateFin(LocalDate dateFin) { this.dateFin = dateFin; return this; }

        public Allergie construire() {
            return new Allergie(id, patientId, categorie, libelle, texte, commentaire, dateDebut, dateFin);
        }
    }
}
