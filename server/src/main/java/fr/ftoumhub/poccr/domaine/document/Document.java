package fr.ftoumhub.poccr.domaine.document;

import fr.ftoumhub.poccr.domaine.validation.NomDocument;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Fiche d'un document : identité stable (l'identifiant ne change pas au renommage), patient
 * concerné, établissement émetteur, auteur. Le contenu reste dans le fichier .docx de même nom.
 *
 * @param patientId  null tant que le document ne contient aucune liaison patient
 * @param auteurId   professionnel auteur (psCreateur) ; facultatif
 * @param dateValeur date clinique du document ; facultative
 */
public record Document(UUID id,
                       NomDocument nom,
                       String titre,
                       UUID patientId,
                       UUID etablissementId,
                       UUID auteurId,
                       Instant creeLe,
                       Instant dateValeur) {

    public Document {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nom, "nom");
        Objects.requireNonNull(etablissementId, "etablissementId");
        Objects.requireNonNull(creeLe, "creeLe");
    }

    public Document avecPatient(UUID patient) {
        return versBuilder().patientId(patient).construire();
    }

    public Document renomme(NomDocument nouveau) {
        return versBuilder().nom(nouveau).construire();
    }

    /** Copie sous un autre nom : nouvelle identité et nouvelle date de création, même patient et même émetteur. */
    public Document copie(UUID nouvelId, NomDocument nouveauNom, Instant maintenant) {
        return versBuilder().id(nouvelId).nom(nouveauNom).creeLe(maintenant).construire();
    }

    private Builder versBuilder() {
        return builder().id(id).nom(nom).titre(titre).patientId(patientId).etablissementId(etablissementId)
                .auteurId(auteurId).creeLe(creeLe).dateValeur(dateValeur);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private NomDocument nom;
        private String titre;
        private UUID patientId;
        private UUID etablissementId;
        private UUID auteurId;
        private Instant creeLe;
        private Instant dateValeur;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder nom(NomDocument nom) { this.nom = nom; return this; }
        public Builder titre(String titre) { this.titre = titre; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder etablissementId(UUID etablissementId) { this.etablissementId = etablissementId; return this; }
        public Builder auteurId(UUID auteurId) { this.auteurId = auteurId; return this; }
        public Builder creeLe(Instant creeLe) { this.creeLe = creeLe; return this; }
        public Builder dateValeur(Instant dateValeur) { this.dateValeur = dateValeur; return this; }

        public Document construire() {
            return new Document(id, nom, titre, patientId, etablissementId, auteurId, creeLe, dateValeur);
        }
    }
}
